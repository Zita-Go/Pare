package com.pare.app

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Completing a step advances a task without changing its completion, plan or execution. */
object NextStepActions {
    private fun active(state:SyncState,taskId:String):Entity {
        val task=state.items["task:$taskId"]
        require(task!=null && !task.deleted && !task.completed() && task.content()!=null && task.conflict==null && task.conflictGroup.isEmpty() &&
            task.payload?.get("lifecycle")?.jsonPrimitive?.contentOrNull=="active") {"任务已完成、归档或存在冲突，请查看最新内容"}
        return task
    }
    private fun store(state:SyncState,task:Entity,content:MutableMap<String,JsonElement>,now:Instant):SyncState {
        val previous=content["lastProgressAt"]?.jsonPrimitive?.contentOrNull?.let {runCatching {Instant.parse(it)}.getOrNull()}
        if(previous==null || !previous.isAfter(now))content["lastProgressAt"]=JsonPrimitive(now.toString())
        val payload=JsonObject(task.payload!!.toMutableMap().apply {put("content",JsonObject(content))})
        PayloadValidation.validate("task",task.id,payload)
        return StateMachine.edit(state,"task",task.id,payload)
    }
    fun complete(state:SyncState,taskId:String,completionId:String,expected:SmallStepBaseline,now:Instant):SyncState {
        require(UUID.fromString(completionId).toString()==completionId) {"记录标识无效，请重新打开任务"}
        val task=active(state,taskId);val content=task.content()!!.toMutableMap()
        val history=(content["completedSteps"] as? JsonArray).orEmpty()
        // A delayed retry must not consume a newly written next step.
        if(history.any {it.jsonObject["id"]?.jsonPrimitive?.contentOrNull==completionId})return state
        if(SmallStepBaseline.from(task)!=expected)throw StaleContentEditException(StaleEditReason.UPDATED)
        require(expected.nextAction.isNotBlank()) {"先写下一个最小下一步"}
        val entry=buildJsonObject {
            put("id",completionId);put("text",expected.nextAction.trim());put("completedAt",now.toString())
            if(expected.definitionOfDone.isNotBlank())put("definitionOfDone",expected.definitionOfDone.trim())
        }
        content["completedSteps"]=JsonArray(history+entry)
        content.remove("nextAction");content.remove("definitionOfDone")
        return store(state,task,content,now)
    }
    fun save(state:SyncState,taskId:String,nextAction:String,definitionOfDone:String,expected:SmallStepBaseline,now:Instant):SyncState {
        require(nextAction.isNotBlank()) {"请写下一个具体的小动作"}
        val task=active(state,taskId)
        if(SmallStepBaseline.from(task)!=expected)throw StaleContentEditException(StaleEditReason.UPDATED)
        val content=task.content()!!.toMutableMap()
        content["nextAction"]=JsonPrimitive(nextAction.trim())
        if(definitionOfDone.isBlank())content.remove("definitionOfDone") else content["definitionOfDone"]=JsonPrimitive(definitionOfDone.trim())
        return store(state,task,content,now)
    }
    fun undo(state:SyncState,taskId:String,completionId:String,now:Instant):SyncState {
        val task=active(state,taskId);val baseline=SmallStepBaseline.from(task)
        require(baseline.nextAction.isBlank() && baseline.definitionOfDone.isBlank()) {"已设置新的下一步，未覆盖后续内容"}
        val content=task.content()!!.toMutableMap();val history=(content["completedSteps"] as? JsonArray).orEmpty()
        val last=history.lastOrNull()?.jsonObject
        require(last!=null && last["id"]?.jsonPrimitive?.contentOrNull==completionId) {"只能撤销最近完成的一步"}
        content["nextAction"]=last.getValue("text")
        last["definitionOfDone"]?.takeUnless {it==JsonNull}?.let {content["definitionOfDone"]=it} ?: content.remove("definitionOfDone")
        content["completedSteps"]=JsonArray(history.dropLast(1))
        return store(state,task,content,now)
    }
}
