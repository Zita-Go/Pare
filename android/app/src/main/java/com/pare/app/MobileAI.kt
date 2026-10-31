package com.pare.app

import java.net.URI
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class MobileAIException(message:String):IllegalArgumentException(message)
private fun requireAI(ok:Boolean,message:String) {if(!ok) throw MobileAIException(message)}
private fun JsonObject.pick(keys:Set<String>)=JsonObject(filterKeys {it in keys})
private fun JsonObject.text(key:String)=(this[key] as? JsonPrimitive)?.contentOrNull

@Serializable data class MobileAIConfig(val endpoint:String="",val model:String="") {
    fun validated():MobileAIConfig {
        val uri=try {URI(endpoint.trim())} catch(_:Exception) {throw MobileAIException("请填写完整的 HTTPS Chat Completions 地址")}
        requireAI(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.port in -1..65535 && uri.port!=0,
            "地址须为 HTTPS，不能包含账号、查询参数或片段")
        requireAI(model.trim().isNotEmpty() && model.length<=200 && model.none {it.isISOControl()},"请填写有效模型名称")
        return copy(endpoint=uri.toASCIIString(),model=model.trim())
    }
}
data class MobileAIOptions(val ideas:Boolean=false,val goals:Boolean=false,val preferences:Boolean=false,val history:Boolean=false)
data class MobileAIRequest(val id:String,val taskId:String,val owner:String?,val device:String,val options:MobileAIOptions,
    val evidence:JsonObject,val taskContent:JsonObject,val preview:String,val preparedAt:Instant)

object MobileAIContext {
    private fun task(state:SyncState,id:String):Entity {
        val t=state.items["task:$id"]
        requireAI(t!=null && !t.deleted && !t.completed() && t.content()!=null && t.payload?.text("lifecycle")!="archived" && t.conflict==null,
            "任务已完成、删除或存在冲突，请先处理任务；建议草稿已保留")
        return t!!
    }
    fun evidence(state:SyncState,taskId:String,options:MobileAIOptions,now:Instant):JsonObject {
        val task=task(state,taskId)
        val historyFields=if(options.history) setOf("blockerEntries","progressSessions","parallelRunSessions") else emptySet()
        val linked=relatedIdeas(state,taskId).sortedBy {it.id}
        return buildJsonObject {
            put("task",task.content()!!.pick(setOf("id","text","note","quadrant","nextAction","definitionOfDone","estimatedMinutes")+historyFields))
            if(options.ideas) put("relatedIdeas",JsonArray(linked.map {it.content()!!.pick(setOf("id","text","notes","stage","nextQuestion")+(if(options.history) setOf("entries") else emptySet()))}))
            if(options.goals) put("relatedGoals",JsonArray(state.items.values.filter {e -> e.kind=="goal" && !e.deleted && e.content()?.text("status")=="active" &&
                (e.content()?.get("taskIDs") as? JsonArray)?.any {(it as? JsonPrimitive)?.content==taskId}==true}.sortedBy {it.id}.map {it.content()!!.pick(setOf("id","title","motivation","targetDate","status"))}))
            if(options.preferences) {
                val profile=state.items["profile:profile"]?.takeUnless {it.deleted}?.content()
                val ids=linked.map {it.id}.toSet()
                val confirmed=(profile?.get("confirmedPreferences") as? JsonArray).orEmpty().filter {e ->
                    val p=e as? JsonObject ?: return@filter false
                    val scope=p.text("ideaID");val expires=p.text("expiresAt")
                    (scope==null || scope in ids) && (expires==null || runCatching {Instant.parse(expires)>now}.getOrDefault(false))
                }.map {(it as JsonObject).pick(setOf("id","text","source","ideaID"))}
                put("confirmedPreferences",JsonArray(confirmed))
                (profile?.get("preferences") as? JsonObject)?.let {put("declaredPreferences",it.pick(setOf("notes","startingMinutes","planningMinutes")))}
            }
        }
    }
    fun prepare(state:SyncState,taskId:String,options:MobileAIOptions,history:List<MobileAITrace>,now:Instant=Instant.now()):MobileAIRequest {
        val id=uid();val evidence=evidence(state,taskId,options,now)
        val localHistory=if(options.history) history.filter {it.kind=="NEXT_STEP" && it.taskId==taskId}.takeLast(3) else emptyList()
        val preview=buildJsonObject {
            put("version",1);put("requestId",id);put("taskId",taskId);put("evidence",evidence)
            // Raw prior requests include older consent scopes. Only earlier model output from this task is eligible.
            if(options.history) put("previousModelOutput",JsonArray(localHistory.map {buildJsonObject {put("at",it.at);put("output",it.output)}}))
        }.toString()
        requireAI(preview.toByteArray(Charsets.UTF_8).size<=32_000,"所选上下文超过 32 KB，请减少参考范围或缩短任务内容")
        return MobileAIRequest(id,taskId,state.owner,state.device,options,evidence,task(state,taskId).content()!!,preview,now)
    }
    fun requireFresh(state:SyncState,input:MobileAIRequest) {
        requireAI(state.owner==input.owner && state.device==input.device,"资料库已改变，建议草稿已保留")
        requireAI(evidence(state,input.taskId,input.options,input.preparedAt)==input.evidence,"参考内容已更新，请重新预览后生成；建议草稿已保留")
        // Adoption writes this progress marker too; undo must never restore a value older than an intervening session.
        requireAI(state.items["task:${input.taskId}"]?.content()?.get("lastProgressAt")==input.taskContent["lastProgressAt"],
            "任务进展已更新，请重新预览后生成；建议草稿已保留")
    }
}

@Serializable data class MobileAIProposal(val requestId:String,val taskId:String,val nextAction:String,val stopCriterion:String,val minutes:Int,val reason:String) {
    fun validate(input:MobileAIRequest):MobileAIProposal {
        requireAI(requestId==input.id && taskId==input.taskId,"AI 建议引用了未授权的任务")
        requireAI(nextAction.trim().length in 1..500 && stopCriterion.trim().length in 1..500 && reason.trim().length in 1..600 && minutes in 2..5,
            "AI 建议缺少具体动作、停止标准或 2–5 分钟的起点")
        requireAI(listOf(nextAction,stopCriterion,reason).none {text -> text.any {it.isISOControl() && it!='\n' && it!='\t'}},"AI 建议格式无效")
        return this
    }
}
data class MobileAIUndo(val taskId:String,val owner:String?,val device:String,val before:Map<String,JsonElement?>,val after:Map<String,JsonElement>) {
    companion object {
        fun from(input:MobileAIRequest,proposal:MobileAIProposal,at:Instant):MobileAIUndo {
            val after=mapOf("nextAction" to JsonPrimitive(proposal.nextAction.trim()),"definitionOfDone" to JsonPrimitive(proposal.stopCriterion.trim()),"lastProgressAt" to JsonPrimitive(at.toString()))
            return MobileAIUndo(input.taskId,input.owner,input.device,after.keys.associateWith {input.taskContent[it]},after)
        }
    }
}
object MobileAIActions {
    fun adopt(state:SyncState,input:MobileAIRequest,proposal:MobileAIProposal,at:Instant):SyncState {
        proposal.validate(input);MobileAIContext.requireFresh(state,input)
        return SmallStepActions.save(state,input.taskId,proposal.nextAction,proposal.stopCriterion,
            SmallStepBaseline.from(state.items.getValue("task:${input.taskId}")),null,null,null,at)
    }
    fun undo(state:SyncState,receipt:MobileAIUndo):SyncState {
        val task=state.items["task:${receipt.taskId}"];val content=task?.content()
        requireAI(state.owner==receipt.owner && state.device==receipt.device && task!=null && !task.deleted && !task.completed() && task.conflict==null &&
            task.payload?.text("lifecycle")!="archived" && content!=null,"任务状态已改变，无法直接撤销")
        requireAI(receipt.after.all {(k,v)->content!![k]==v},"下一步或进展已更新，无法撤销；后续编辑已保留")
        val restored=content!!.toMutableMap()
        receipt.before.forEach {(k,v)->if(v==null) restored.remove(k) else restored[k]=v}
        return StateMachine.edit(state,"task",receipt.taskId,JsonObject(task!!.payload!!.toMutableMap().apply {put("content",JsonObject(restored))}))
    }
}

@Serializable data class MobileAITrace(val id:String,val taskId:String,val at:String,val input:String,val output:String,val success:Boolean,val kind:String="NEXT_STEP",val taskIds:List<String> = listOf(taskId)) {
    fun redacted(key:String):MobileAITrace {
        fun redact(value:String):String {
            val escaped=JsonPrimitive(key).toString().removeSurrounding("\"")
            val text=if(key.isNotEmpty()) value.replace(escaped,"[已隐藏密钥]").replace(key,"[已隐藏密钥]") else value
            return text.replace(Regex("(?i)authorization[^\\r\\n]*"),"[已隐藏认证信息]")
        }
        return copy(input=redact(input),output=redact(output))
    }
}
object MobileAIHistory {
    fun append(history:List<MobileAITrace>,trace:MobileAITrace):List<MobileAITrace> {
        val result=(history.filterNot {it.id==trace.id}+trace).takeLast(12).toMutableList()
        while(result.isNotEmpty() && wire.encodeToString(result).toByteArray(Charsets.UTF_8).size>384_000) result.removeAt(0)
        return result
    }
    fun remove(history:List<MobileAITrace>,id:String)=history.filterNot {it.id==id}
}
