package com.pare.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant

@Serializable data class Session(val owner:String,val email:String,val accessToken:String,val refreshToken:String,val expiresAt:Long)

class PareViewModel(app:Application):AndroidViewModel(app) {
    private val runtime=SyncRuntime.get(app)
    private val engine=runtime.engine
    private val connectivity=ForegroundConnectivity(app) {runtime.requestSync(networkReturned=true)}
    private var foregroundPolling:Job?=null
    val state=engine.state
    val initialized=engine.initialized
    val planningInstant=MutableStateFlow(Instant.now())
    val status=engine.status
    val session=engine.session
    val busy=engine.busy
    val previewReady=engine.previewReady
    val execution=runtime.execution.state
    val executionReady=runtime.executionReady
    val deviceError=runtime.localError
    val reminderError=runtime.reminderError
    val presence=runtime.presence.state
    val presenceSharingEnabled=runtime.presenceSharingEnabled
    val presenceSharingError=runtime.presenceSharingError
    fun setPresenceSharing(enabled:Boolean)=runtime.setPresenceSharing(enabled)
    fun refreshPresence()=runtime.refreshPresenceManually()
    fun peerFocusLabels():String?=runtime.presence.freshPeerFocus().takeIf {it.isNotEmpty()}?.joinToString("、") {it.label}.orEmpty().ifBlank {null}
    val notificationTask=MutableStateFlow<String?>(null)
    fun openNotificationTask(id:String?) {notificationTask.value=id?.takeIf {runCatching {java.util.UUID.fromString(it)}.isSuccess}}
    fun consumeNotificationTask() {notificationTask.value=null}
    fun runCommand(command:ExecutionCommand,onResult:(Result<Unit>)->Unit={}) {
        viewModelScope.launch(Dispatchers.IO) {
            val result=runCatching {runtime.execute(command)}
            if(result.isFailure)deviceError.value=result.exceptionOrNull()?.message ?: "推进操作未完成"
            withContext(Dispatchers.Main.immediate) {onResult(result)}
        }
    }
    fun editMobileAI(change:(SyncState)->SyncState,onResult:(Result<Unit>)->Unit)=edit(onResult,change)
    fun editWorkspace(change:(SyncState)->SyncState,onResult:(Result<Unit>)->Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result=runCatching {WorkspaceChanges.edit(engine,runtime.execution,executionReady.value,change);Unit}
            withContext(Dispatchers.Main.immediate) {onResult(result)}
            if(result.isSuccess)runtime.requestSync()
        }
    }
    fun editTimeline(change:(SyncState)->SyncState,onResult:(Result<Unit>)->Unit)=edit(onResult,change)
    fun saveReminder(taskId:String,expected:TaskReminderBaseline,at:Instant?,recurrence:String?,onResult:(Result<Unit>)->Unit)=
        edit(onResult) {TaskReminderActions.save(it,taskId,expected,at,recurrence,Instant.now())}
    private fun edit(onResult:(Result<Unit>)->Unit={},block:(SyncState)->SyncState) {
        viewModelScope.launch(Dispatchers.IO) {
            val result=try {engine.edit(block);Result.success(Unit)}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {status.value=e.message ?: "保存失败，尚未确认保存";Result.failure(e)}
            withContext(Dispatchers.Main.immediate) {onResult(result)}
            if(result.isSuccess) runtime.requestSync()
        }
    }
    fun save(entity:Entity?,kind:String,text:String,note:String,q:Int,
             estimatedMinutes:Int?=entity?.content()?.get("estimatedMinutes")?.jsonPrimitive?.intOrNull,
             nextAction:String?=entity?.content()?.get("nextAction")?.jsonPrimitive?.contentOrNull,
             addToToday:Boolean=false,expected:ContentEditBaseline=ContentEditBaseline.from(entity),targetPlanId:String?=null,onResult:(Result<Unit>)->Unit={}) {
        val id=entity?.id ?: uid()
        edit(onResult) {s ->
            require(text.isNotBlank()) {"请填写内容"}
            var next=StateMachine.edit(s,kind,id,ContentActions.save(s.items["$kind:$id"],kind,text,note,q,id,Instant.now(),estimatedMinutes=estimatedMinutes,nextAction=nextAction,expected=expected))
            if(addToToday && kind=="task") {
                val planId=targetPlanId ?: ContentActions.planId(next,Instant.now())
                require(TimelineDay.fromPlanId(planId).zone==TimelineDay.today(next,Instant.now()).zone) {"规划时区已改变，请重新选择日期；草稿已保留"}
                next=editPlan(next,planId) {c ->
                    val ids=c.getValue("ordered_task_ids").jsonArray.map {it.jsonPrimitive.content}.toMutableList()
                    if(id !in ids) ids.add(id)
                    c["ordered_task_ids"]=JsonArray(ids.map {JsonPrimitive(it)})
                }
                next=StateMachine.group(next,setOf("task:$id","day_plan:$planId"))
            }
            next
        }
    }
    fun saveIdeaContext(ideaId:String,stage:String,nextQuestion:String,expected:IdeaContextBaseline,onResult:(Result<Unit>)->Unit={}) =
        edit(onResult) {IdeaActions.saveContext(it,ideaId,stage,nextQuestion,expected,Instant.now())}
    fun appendIdeaEntry(ideaId:String,entryId:String,text:String,kind:String,onResult:(Result<Unit>)->Unit={}) =
        edit(onResult) {IdeaActions.appendEntry(it,ideaId,entryId,text,kind,Instant.now())}
    fun createIdeaAction(ideaId:String,taskId:String,title:String,nextAction:String,definitionOfDone:String,quadrant:Int,allocation:TodayAllocationDraft?=null,onResult:(Result<Unit>)->Unit={}) =
        edit(onResult) {IdeaActions.createLinkedTask(it,ideaId,taskId,title,nextAction,definitionOfDone,quadrant,allocation,Instant.now())}
    fun allocateToday(taskId:String,allocation:TodayAllocationDraft,onResult:(Result<Unit>)->Unit={}) =
        edit(onResult) {DayPlanActions.allocate(it,taskId,allocation,Instant.now())}
    fun saveSmallStep(taskId:String,nextAction:String,definitionOfDone:String,expected:SmallStepBaseline,allocation:TodayAllocationDraft?=null,blockerReason:String?=null,blockerEntryId:String?=null,onResult:(Result<Unit>)->Unit={}) =
        edit(onResult) {SmallStepActions.save(it,taskId,nextAction,definitionOfDone,expected,allocation,blockerReason,blockerEntryId,Instant.now())}
    fun complete(entity:Entity) {
      val desired=!entity.completed()
      viewModelScope.launch(Dispatchers.IO) {
       try {
        if(desired && executionReady.value && execution.value.runFor(entity.id)!=null)runtime.execute(ExecutionCommand(entity.id,ExecutionAction.FINISH))
        engine.edit {CompletionActions.set(it,entity.id,desired,Instant.now())}
        runtime.requestSync()
       } catch(e:Exception) {deviceError.value=e.message ?: "完成操作未保存，请重试"}
      }
    }
    fun organize(entity:Entity,quadrant:Int,addToToday:Boolean,onResult:(Result<Unit>)->Unit={}) {
        val taskId=uid()
        edit(onResult) {s -> organizeInbox(s,entity,quadrant,addToToday,taskId,Instant.now()) }
    }
    fun planningZone(s:SyncState,now:Instant=planningInstant.value)=ContentActions.planId(s,now).substringAfter('@')
    fun planningDate(s:SyncState,now:Instant=planningInstant.value)=ContentActions.planId(s,now).substringBefore('@')
    fun todayPlan(s:SyncState,now:Instant=planningInstant.value)=s.items["day_plan:${ContentActions.planId(s,now)}"]?.takeUnless {it.deleted}
    private fun editPlan(s:SyncState,id:String=ContentActions.planId(s,Instant.now()),change:(MutableMap<String,JsonElement>)->Unit):SyncState {
        val old=s.items["day_plan:$id"]
        require(old?.deleted!=true && old?.conflict==null) {"日计划已删除或有同步冲突，请先查看最新内容"}
        val content=old?.content()?.toMutableMap() ?: mutableMapOf("day_key" to JsonPrimitive(id.substringBefore('@')),"time_zone" to JsonPrimitive(id.substringAfter('@')),"ordered_task_ids" to JsonArray(emptyList()),"task_plans" to JsonArray(emptyList()))
        change(content)
        return StateMachine.edit(s,"day_plan",id,JsonObject((old?.payload ?: JsonObject(emptyMap())).toMutableMap().apply {put("content",JsonObject(content))}))
    }
    fun toggleToday(e:Entity) = edit {s -> editPlan(s) {content ->
        val ids=content.getValue("ordered_task_ids").jsonArray.map {it.jsonPrimitive.content}.toMutableList()
        if(e.id in ids) {ids.remove(e.id);if(content["daily_win_task_id"]?.jsonPrimitive?.contentOrNull==e.id)content.remove("daily_win_task_id");content["task_plans"]?.jsonArray?.let {plans -> content["task_plans"]=JsonArray(plans.map {entry ->
            if(entry.jsonObject["task_id"]?.jsonPrimitive?.content==e.id) {
                val planned=entry.jsonObject
                val at=planned["scheduled_at"]?.jsonPrimitive?.contentOrNull?.let {runCatching {Instant.parse(it)}.getOrNull()}
                val day=TimelineDay.today(s,Instant.now())
                if(at!=null && at.atZone(day.zone).toLocalDate()!=day.date)entry
                else JsonObject(planned.filterKeys {it !in setOf("allocation","scheduled_at","scheduled_minutes","schedule_reminder_lead_minutes")})
            } else entry
        })}}
        else ids.add(e.id)
        content["ordered_task_ids"]=JsonArray(ids.map {JsonPrimitive(it)})
    }}
    fun setWin(e:Entity) = edit {s -> editPlan(s) {c ->
        val ids=c.getValue("ordered_task_ids").jsonArray.map {it.jsonPrimitive.content}.toMutableList()
        if(e.id !in ids) ids.add(e.id)
        c["ordered_task_ids"]=JsonArray(ids.map {JsonPrimitive(it)})
        if(c["daily_win_task_id"]?.jsonPrimitive?.contentOrNull==e.id)c.remove("daily_win_task_id") else c["daily_win_task_id"]=JsonPrimitive(e.id)
    }}
    private fun action(block:suspend ()->Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {block()}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {status.value=e.message ?: "操作未完成，本机变更已保留"}
        }
    }
    fun authenticate(email:String,password:String,signup:Boolean)=action {engine.authenticate(email,password,signup)}
    fun enableSync()=action {engine.enable();runtime.requestSync()}
    fun pauseSync() {engine.revoke();runtime.presence.clear();runtime.persistControl {engine.pause()}}
    fun onForeground() {
        planningInstant.value=Instant.now()
        runtime.refreshDeviceState()
        runtime.refreshPresence()
        connectivity.start()
        runtime.requestSync()
        if(foregroundPolling?.isActive==true) return
        foregroundPolling=viewModelScope.launch {
            while(isActive) {delay(15_000);planningInstant.value=Instant.now();runtime.refreshDeviceState();runtime.requestSync();runtime.refreshPresence()}
        }
    }
    fun onBackground() {foregroundPolling?.cancel();foregroundPolling=null;connectivity.stop();runtime.refreshDeviceState()}
    fun sync(manual:Boolean=false)=runtime.requestSync(manual)
    fun resolve(key:String,local:Boolean)=edit {StateMachine.resolve(it,key,local)}
    fun signOut() {engine.revoke();runtime.presence.clear();runtime.persistControl {engine.signOut()}}
    override fun onCleared() {onBackground();super.onCleared()}
}
