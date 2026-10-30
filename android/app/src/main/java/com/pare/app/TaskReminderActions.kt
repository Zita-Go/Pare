package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*

data class TaskReminderBaseline(val title:JsonElement?,val remindAt:JsonElement?,val recurrence:JsonElement?) {
    companion object {fun from(task:Entity)=TaskReminderBaseline(task.content()?.get("text"),task.content()?.get("remindAt"),task.content()?.get("recurrence"))}
}
object TaskReminderActions {
    fun save(state:SyncState,taskId:String,expected:TaskReminderBaseline,at:Instant?,recurrence:String?,now:Instant):SyncState {
        val task=state.items["task:$taskId"]
        require(task!=null && !task.deleted && !task.completed() && task.payload?.get("lifecycle")?.jsonPrimitive?.contentOrNull!="archived") {"任务已完成或不可用，提醒没有修改"}
        require(task.conflict==null) {"请先处理这个任务的同步冲突"}
        if(TaskReminderBaseline.from(task)!=expected) throw StaleContentEditException(StaleEditReason.UPDATED)
        require(at==null || at.isAfter(now)) {"提醒时间已过去，请重新选择"}
        require(recurrence==null || recurrence in setOf("daily","weekly","monthly"))
        val content=task.content()!!.toMutableMap()
        if(at==null) {content.remove("remindAt");content.remove("recurrence")}
        else {content["remindAt"]=JsonPrimitive(at.toString());if(recurrence==null)content.remove("recurrence") else content["recurrence"]=JsonPrimitive(recurrence)}
        return StateMachine.edit(state,"task",taskId,JsonObject(task.payload!!.toMutableMap().apply {put("content",JsonObject(content))}))
    }
}
