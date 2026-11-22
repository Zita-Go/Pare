package com.pare.app

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.*

private fun JsonObject.string(key:String)=this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.integer(key:String)=this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.array(key:String)=(this[key] as? JsonArray).orEmpty()
private fun stableUUID(value:String):String {require(UUID.fromString(value).toString()==value) {"标识无效，请重新打开表单"};return value}
private fun currentEntity(state:SyncState,kind:String,id:String):Entity {
    val entity=state.items["$kind:$id"]
    require(entity!=null && !entity.deleted && entity.content()!=null) {"内容已删除，草稿已保留"}
    return entity
}
private fun activeTask(state:SyncState,id:String):Entity = currentEntity(state,"task",id).also {
    require(!it.completed() && it.payload?.string("lifecycle")!="archived") {"任务已完成或归档，草稿已保留"}
}
private fun replaceContent(state:SyncState,entity:Entity,content:Map<String,JsonElement>):SyncState =
    StateMachine.edit(state,entity.kind,entity.id,JsonObject(entity.payload!!.toMutableMap().apply {put("content",JsonObject(content))}))
private fun staleIf(condition:Boolean) {if(condition) throw StaleContentEditException(StaleEditReason.UPDATED)}

data class IdeaContextBaseline(val stage:String,val nextQuestion:String,val nextQuestionUpdatedAt:String?) {
    companion object {fun from(entity:Entity)=entity.content().let {IdeaContextBaseline(it?.string("stage") ?: "seed",it?.string("nextQuestion").orEmpty(),it?.string("nextQuestionUpdatedAt"))}}
}
data class SmallStepBaseline(val title:String,val nextAction:String,val definitionOfDone:String,val lastCompletedStepId:String?=null) {
    companion object {fun from(entity:Entity)=SmallStepBaseline(entity.title(),entity.content()?.string("nextAction").orEmpty(),entity.content()?.string("definitionOfDone").orEmpty(),
        entity.content()?.array("completedSteps")?.lastOrNull()?.jsonObject?.string("id"))}
}

/** Only the current task's fields that this operation consumes; other plan entries can advance freely. */
data class DayAllocationBaseline(val planId:String,val kind:String?,val plannedMinutes:Int?,val scheduledAt:String?,val scheduledMinutes:Int?,
    val allocation:JsonObject?,val included:Boolean,val planDeleted:Boolean=false) {
    /** Calendar blocks can be copied into this plan even when their start belongs to another day. */
    val hasCurrentDaySchedule:Boolean get() = scheduledAt?.let {start ->
        runCatching {Instant.parse(start).atZone(ZoneId.of(planId.substringAfter('@'))).toLocalDate().toString()==planId.substringBefore('@')}.getOrDefault(false)
    } ?: false
    companion object {
        fun from(state:SyncState,taskId:String,now:Instant):DayAllocationBaseline {
            val id=ContentActions.planId(state,now);val entity=state.items["day_plan:$id"]
            val c=entity?.takeUnless {it.deleted}?.content()
            val p=c?.array("task_plans")?.firstOrNull {it.jsonObject.string("task_id")==taskId}?.jsonObject
            val a=p?.get("allocation") as? JsonObject
            return DayAllocationBaseline(id,a?.string("kind"),a?.integer("planned_minutes"),p?.string("scheduled_at"),p?.integer("scheduled_minutes"),a,
                c?.array("ordered_task_ids")?.any {it.jsonPrimitive.content==taskId}==true,entity?.deleted==true)
        }
    }
}
data class TodayAllocationDraft(val planId:String,val kind:String,val minutes:Int,val allocationId:String,val expected:DayAllocationBaseline)

object DayPlanActions {
    fun allocate(state:SyncState,taskId:String,draft:TodayAllocationDraft,now:Instant):SyncState {
        activeTask(state,taskId)
        require(draft.kind in setOf("personal","check") && draft.minutes in 1..1440) {"当天投入请填写 1–1440 分钟"}
        stableUUID(draft.allocationId)
        staleIf(draft.planId!=ContentActions.planId(state,now) || draft.expected.planId!=draft.planId)
        val current=DayAllocationBaseline.from(state,taskId,now)
        staleIf(current.planDeleted)
        val sameValue=current.included && current.kind==draft.kind && current.plannedMinutes==draft.minutes &&
            (!current.hasCurrentDaySchedule || current.scheduledMinutes==draft.minutes)
        if(sameValue) return state
        // Unknown allocation fields and reminder settings are deliberately not guarded: they are preserved below.
        staleIf(current.kind!=draft.expected.kind || current.plannedMinutes!=draft.expected.plannedMinutes ||
            current.scheduledAt!=draft.expected.scheduledAt || current.scheduledMinutes!=draft.expected.scheduledMinutes ||
            current.included!=draft.expected.included || current.allocation?.string("id")!=draft.expected.allocation?.string("id"))
        val old=state.items["day_plan:${draft.planId}"]
        val content:MutableMap<String,JsonElement> = old?.content()?.toMutableMap() ?: mutableMapOf("day_key" to JsonPrimitive(draft.planId.substringBefore('@')),"time_zone" to JsonPrimitive(draft.planId.substringAfter('@')))
        val ids=(content["ordered_task_ids"] as? JsonArray).orEmpty().map {it.jsonPrimitive.content}
        content["ordered_task_ids"]=JsonArray((ids+taskId).distinct().map {JsonPrimitive(it)})
        val plans=(content["task_plans"] as? JsonArray).orEmpty().toMutableList()
        val index=plans.indexOfFirst {it.jsonObject.string("task_id")==taskId}
        val plan:MutableMap<String,JsonElement> = if(index<0) mutableMapOf("task_id" to JsonPrimitive(taskId)) else plans[index].jsonObject.toMutableMap()
        val allocation:MutableMap<String,JsonElement> = (plan["allocation"] as? JsonObject)?.toMutableMap() ?: mutableMapOf("id" to JsonPrimitive(draft.allocationId))
        allocation["kind"]=JsonPrimitive(draft.kind);allocation["planned_minutes"]=JsonPrimitive(draft.minutes)
        plan["allocation"]=JsonObject(allocation)
        if(current.hasCurrentDaySchedule) plan["scheduled_minutes"]=JsonPrimitive(draft.minutes)
        if(index<0) plans.add(JsonObject(plan)) else plans[index]=JsonObject(plan)
        content["task_plans"]=JsonArray(plans)
        return StateMachine.edit(state,"day_plan",draft.planId,JsonObject((old?.payload ?: JsonObject(emptyMap())).toMutableMap().apply {put("content",JsonObject(content))}))
    }
}

/** A retry must already contain the requested result; it must never silently drop edited draft fields. */
private fun matchesSubmittedAllocation(state:SyncState,taskId:String,draft:TodayAllocationDraft?,now:Instant):Boolean {
    if(draft==null) return true
    if(draft.planId!=ContentActions.planId(state,now)) return false
    val current=DayAllocationBaseline.from(state,taskId,now)
    return !current.planDeleted && current.included && current.kind==draft.kind && current.plannedMinutes==draft.minutes &&
        (!current.hasCurrentDaySchedule || current.scheduledMinutes==draft.minutes)
}

object IdeaActions {
    fun saveContext(state:SyncState,ideaId:String,stage:String,nextQuestion:String,expected:IdeaContextBaseline,now:Instant):SyncState {
        require(stage in setOf("seed","exploring","building","parked","archived"))
        val idea=currentEntity(state,"idea",ideaId);val current=IdeaContextBaseline.from(idea)
        val question=nextQuestion.trim()
        if(current.stage==stage && current.nextQuestion==question) return state
        staleIf(current!=expected)
        val content=idea.content()!!.toMutableMap()
        content["stage"]=JsonPrimitive(stage)
        if(question.isEmpty()) content.remove("nextQuestion") else content["nextQuestion"]=JsonPrimitive(question)
        if(current.nextQuestion!=question) content["nextQuestionUpdatedAt"]=JsonPrimitive(now.toString())
        content["updatedAt"]=JsonPrimitive(now.toString())
        return replaceContent(state,idea,content)
    }
    fun appendEntry(state:SyncState,ideaId:String,entryId:String,text:String,kind:String,now:Instant):SyncState {
        stableUUID(entryId);require(text.isNotBlank() && kind in setOf("note","finding","decision"))
        val idea=currentEntity(state,"idea",ideaId);val entries=idea.content()!!.array("entries")
        val existing=entries.firstOrNull {it.jsonObject.string("id")==entryId}?.jsonObject
        if(existing!=null) {require(existing.string("text")==text.trim() && existing.string("kind")==kind) {"记录标识已存在，草稿已保留"};return state}
        require(idea.content()!!.string("stage")!="archived") {"想法已归档，请恢复阶段后再添加记录"}
        val content=idea.content()!!.toMutableMap()
        content["entries"]=JsonArray(entries+buildJsonObject {put("id",entryId);put("text",text.trim());put("kind",kind);put("createdAt",now.toString())})
        content["updatedAt"]=JsonPrimitive(now.toString())
        return replaceContent(state,idea,content)
    }
    fun createLinkedTask(state:SyncState,ideaId:String,taskId:String,title:String,nextAction:String,definitionOfDone:String,quadrant:Int,allocation:TodayAllocationDraft?,now:Instant):SyncState {
        stableUUID(taskId);require(title.isNotBlank() && quadrant in 0..3) {"请填写具体行动并选择象限"}
        val idea=currentEntity(state,"idea",ideaId);val links=idea.content()!!.array("linkedTaskIDs").map {it.jsonPrimitive.content}
        if(state.items["task:$taskId"]!=null) {
            val existing=activeTask(state,taskId)
            val content=existing.content()!!
            require(taskId in links && existing.title()==title.trim() && content.string("nextAction").orEmpty()==nextAction.trim() &&
                content.string("definitionOfDone").orEmpty()==definitionOfDone.trim() && content.integer("quadrant")==quadrant &&
                matchesSubmittedAllocation(state,taskId,allocation,now)) {"这个行动已创建或更新，请查看关联行动后再处理草稿"}
            return state
        }
        require(idea.content()!!.string("stage")!="archived") {"想法已归档，请恢复阶段后再拆出行动"}
        val payload=ContentActions.save(null,"task",title.trim(),"",quadrant,taskId,now,nextAction=nextAction).toMutableMap()
        payload["content"]=JsonObject(payload.getValue("content").jsonObject.toMutableMap().apply {if(definitionOfDone.isNotBlank()) put("definitionOfDone",JsonPrimitive(definitionOfDone.trim()))})
        var result=StateMachine.edit(state,"task",taskId,JsonObject(payload))
        result=replaceContent(result,idea,idea.content()!!.toMutableMap().apply {put("linkedTaskIDs",JsonArray((links+taskId).distinct().map {JsonPrimitive(it)}));put("updatedAt",JsonPrimitive(now.toString()))})
        val keys=mutableSetOf(idea.key,"task:$taskId")
        if(allocation!=null) {result=DayPlanActions.allocate(result,taskId,allocation,now);keys+="day_plan:${allocation.planId}"}
        return StateMachine.group(result,keys)
    }
}

object SmallStepActions {
    fun save(state:SyncState,taskId:String,nextAction:String,definitionOfDone:String,expected:SmallStepBaseline,allocation:TodayAllocationDraft?,blockerReason:String?,blockerEntryId:String?,now:Instant):SyncState {
        require(nextAction.isNotBlank() && definitionOfDone.isNotBlank()) {"请填写具体动作和停止标准"}
        require(blockerReason==null || blockerReason in setOf("unclear","tooLarge","lowEnergy","resistance"))
        if(blockerReason!=null) stableUUID(requireNotNull(blockerEntryId))
        val task=activeTask(state,taskId);val content=task.content()!!.toMutableMap()
        val entries=task.content()!!.array("blockerEntries")
        val previous=entries.firstOrNull {it.jsonObject.string("id")==blockerEntryId}?.jsonObject
        val current=SmallStepBaseline.from(task)
        staleIf(current.title!=expected.title || current.lastCompletedStepId!=expected.lastCompletedStepId)
        val same=current.nextAction==nextAction.trim() && current.definitionOfDone==definitionOfDone.trim()
        if(previous!=null) {
            require(previous.string("reason")==blockerReason && same && matchesSubmittedAllocation(state,taskId,allocation,now)) {
                "这条卡点与下一步已保存或更新，请查看最新任务后再处理草稿"
            }
            return state
        }
        staleIf(!same && current!=expected)
        content["nextAction"]=JsonPrimitive(nextAction.trim());content["definitionOfDone"]=JsonPrimitive(definitionOfDone.trim())
        content["lastProgressAt"]=JsonPrimitive(now.toString())
        if(blockerReason!=null) content["blockerEntries"]=JsonArray(entries+buildJsonObject {put("id",blockerEntryId!!);put("reason",blockerReason);put("at",now.toString())})
        var result=replaceContent(state,task,content)
        if(allocation!=null) {result=DayPlanActions.allocate(result,taskId,allocation,now);result=StateMachine.group(result,setOf(task.key,"day_plan:${allocation.planId}"))}
        return result
    }
}

data class TodayLoad(val personalMinutes:Long,val checkMinutes:Long,val unplannedCount:Int) {
    val unknownCount get()=unplannedCount
    companion object {
        fun calculate(state:SyncState,now:Instant):TodayLoad {
            val id=ContentActions.planId(state,now);val plan=state.items["day_plan:$id"]?.takeUnless {it.deleted}?.content()
            val date=id.substringBefore('@');val zone=ZoneId.of(id.substringAfter('@'))
            var personal=0L;var check=0L;var unknown=0
            for(taskId in plan?.array("ordered_task_ids").orEmpty().map {it.jsonPrimitive.content}.distinct()) {
                val task=state.items["task:$taskId"]?.takeUnless {it.deleted || it.completed() || it.payload?.string("lifecycle")=="archived"} ?: continue
                if(task.content()==null) continue
                val p=plan?.array("task_plans")?.firstOrNull {it.jsonObject.string("task_id")==taskId}?.jsonObject
                val a=p?.get("allocation") as? JsonObject
                val scheduledToday=p?.string("scheduled_at")?.let {runCatching {Instant.parse(it).atZone(zone).toLocalDate().toString()==date}.getOrDefault(false)}==true
                val minutes=(if(scheduledToday) p?.integer("scheduled_minutes") else null) ?: a?.integer("planned_minutes")
                if(minutes==null || minutes<=0) unknown++ else if(a?.string("kind")=="check") check+=minutes.toLong() else personal+=minutes.toLong()
            }
            return TodayLoad(personal,check,unknown)
        }
    }
}

data class LinkedIdeaTask(val id:String,val title:String,val completed:Boolean,val archived:Boolean,val missing:Boolean)
object IdeaLinkedTasks {
    fun project(state:SyncState,idea:Entity):List<LinkedIdeaTask> = idea.content()?.array("linkedTaskIDs").orEmpty().map {it.jsonPrimitive.content}.distinct().map {id ->
        val task=state.items["task:$id"]?.takeUnless {it.deleted || it.content()==null}
        LinkedIdeaTask(id,task?.title() ?: "行动已不可用",task?.completed()==true,task?.payload?.string("lifecycle")=="archived",task==null)
    }
}
fun relatedIdeas(state:SyncState,taskId:String):List<Entity> = state.items.values.filter {it.kind=="idea" && !it.deleted && it.content()?.array("linkedTaskIDs")?.any {id ->id.jsonPrimitive.content==taskId}==true}
