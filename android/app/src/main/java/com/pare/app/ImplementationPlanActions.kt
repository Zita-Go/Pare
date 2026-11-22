package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*

data class ImplementationPlanBaseline(val owner:String?,val device:String,val title:JsonElement?,val plan:JsonElement?) {
    companion object {fun from(state:SyncState,taskId:String)=ImplementationPlanBaseline(state.owner,state.device,state.items["task:$taskId"]?.content()?.get("text"),state.items["task:$taskId"]?.content()?.get("implementationPlan"))}
}
data class ImplementationPlanDraft(val enabled:Boolean,val hour:Int=9,val minute:Int=0,val action:String="")
object ImplementationPlanActions {
    fun save(state:SyncState,taskId:String,expected:ImplementationPlanBaseline,draft:ImplementationPlanDraft):SyncState {
        check(state.owner==expected.owner && state.device==expected.device) {"资料库已改变，行动触发未修改"}
        val task=state.items["task:$taskId"]
        check(taskCanBePlanned(task) && task?.conflict==null && task?.conflictGroup.orEmpty().isEmpty()) {"任务已完成、删除或存在冲突，草稿已保留"}
        if(ImplementationPlanBaseline.from(state,taskId)!=expected) throw StaleContentEditException(StaleEditReason.UPDATED)
        val old=task!!.content()?.get("implementationPlan") as? JsonObject
        require(old==null || old.timelineString("kind")=="time") {"这个触发方式需要在支持它的设备编辑；手机保留原计划"}
        val content=task.content()!!.toMutableMap()
        if(!draft.enabled) content.remove("implementationPlan") else {
            require(draft.hour in 0..23 && draft.minute in 0..59 && draft.action.trim().length in 1..500 && draft.action.none {it.isISOControl() && it!='\n'}) {"请填写有效时刻和一个具体行动（最多500字）"}
            val plan=old?.toMutableMap() ?: mutableMapOf()
            plan["kind"]=JsonPrimitive("time");plan["hour"]=JsonPrimitive(draft.hour);plan["minute"]=JsonPrimitive(draft.minute);plan["action"]=JsonPrimitive(draft.action.trim())
            content["implementationPlan"]=JsonObject(plan)
        }
        val payload=JsonObject(task.payload!!.toMutableMap().apply {put("content",JsonObject(content))})
        PayloadValidation.validate("task",taskId,payload)
        return if(payload==task.payload) state else StateMachine.edit(state,"task",taskId,payload)
    }
    fun sources(state:SyncState,now:Instant):List<ReminderSource> {
        val day=TimelineDay.fromPlanId(ContentActions.planId(state,now))
        val selected=state.items["day_plan:${day.planId}"] ?: return emptyList()
        if(selected.deleted || selected.conflict!=null || selected.conflictGroup.isNotEmpty()) return emptyList()
        if(state.items["profile:profile"]?.let {it.conflict!=null || it.conflictGroup.isNotEmpty()}==true) return emptyList()
        val ids=(selected.content()?.get("ordered_task_ids") as? JsonArray).orEmpty().mapNotNull {(it as? JsonPrimitive)?.contentOrNull}.distinct()
        return ids.mapNotNull {id->
            val task=state.items["task:$id"]
            if(!taskCanBePlanned(task) || task?.conflict!=null || !task?.conflictGroup.orEmpty().isEmpty()) return@mapNotNull null
            val plan=task?.content()?.get("implementationPlan") as? JsonObject ?: return@mapNotNull null
            if(plan.timelineString("kind")!="time" || plan.timelineString("action").isNullOrBlank())return@mapNotNull null
            val hour=plan.timelineInt("hour")?.takeIf {it in 0..23} ?: return@mapNotNull null
            val minute=plan.timelineInt("minute")?.takeIf {it in 0..59} ?: return@mapNotNull null
            val at=runCatching {TimelineTime.resolveLocal(day,hour*60+minute)}.getOrNull() ?: return@mapNotNull null
            ReminderSource("implementation:${day.planId}:$id",id,"implementation",at.toEpochMilli(),zone=day.zone.id,identityStamp="${day.planId}|$hour:$minute|${plan.timelineString("action")}")
        }
    }
}
