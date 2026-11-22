package com.pare.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import java.time.Instant

/** Commands run on IO. One process owner serializes local changes; there is no ticking service. */
class ExecutionController(
    private val engine:SyncEngine,
    private val persistence:ExecutionPersistence,
    private val clock:ExecutionClock
) {
    private val mutable=MutableStateFlow(ExecutionSnapshot())
    val state:StateFlow<ExecutionSnapshot> = mutable
    private var initialized=false

    @Synchronized fun initialize() {
        if(initialized) return
        check(engine.initialized.value) {"请等待本机内容读取完成"}
        val saved=persistence.load()
        saved?.validate()
        mutable.value=saved ?: ExecutionSnapshot(owner=engine.state.value.owner,device=engine.state.value.device)
        initialized=true
        reconcile()
    }
    private fun save(next:ExecutionSnapshot) {
        if(next==mutable.value) return
        persistence.save(next)
        mutable.value=next
    }
    private fun current() :ExecutionSnapshot {check(initialized) {"执行状态尚未读取完成"};return mutable.value}
    private fun activeTask(s:SyncState,taskId:String):Entity {
        val entity=s.items["task:$taskId"]
        check(usable(entity)) {"任务已完成、删除或归档，请查看最新内容"}
        check(entity!!.conflict==null) {"任务有同步冲突，请先选择保留的版本"}
        return entity
    }
    private fun usable(entity:Entity?):Boolean {
        if(entity==null || entity.deleted || entity.content()==null || entity.completed() || entity.payload?.get("lifecycle")?.jsonPrimitive?.content=="archived") return false
        val remote=entity.conflict ?: return true
        return remote.deletedAt==null && remote.payload!=null &&
            remote.payload["lifecycle"]?.jsonPrimitive?.content!="archived" &&
            (remote.payload["content"] as? JsonObject)?.get("completed")?.jsonPrimitive?.booleanOrNull!=true
    }
    private fun settle(run:ExecutionRun,now:ExecutionMoment):ExecutionRun {
        if(run.paused || run.pendingFinish!=null) return run
        val anchor=run.anchor
        if(anchor==null || anchor.bootId!=now.bootId || now.elapsedMillis<anchor.elapsedMillis) {
            return run.copy(paused=true,anchor=null,deadlineAtMillis=null,recoveryNotice="设备重启或时钟状态变化，已暂停；无法确认的时长未计入。")
        }
        val elapsed=run.elapsedMillis(now)
        val due=run.targetMinutes?.let {elapsed>=it*60_000L}==true
        return run.copy(accumulatedMillis=elapsed,anchor=if(due) null else now,paused=due,due=due,
            deadlineAtMillis=run.currentFocusDeadlineMillis(now))
    }
    private fun replace(snapshot:ExecutionSnapshot,run:ExecutionRun):ExecutionSnapshot =
        if(snapshot.focus?.taskId==run.taskId) snapshot.copy(focus=run)
        else snapshot.copy(external=snapshot.external.map {if(it.taskId==run.taskId) run else it})
    private fun remove(snapshot:ExecutionSnapshot,taskId:String)=snapshot.copy(
        focus=snapshot.focus?.takeUnless {it.taskId==taskId},external=snapshot.external.filterNot {it.taskId==taskId})

    /** Call after shared content changes and when a selected deadline fires or the app foregrounds. */
    @Synchronized fun reconcile() {
        val old=current();val shared=engine.state.value
        if(old.device!=shared.device || (old.owner!=null && old.owner!=shared.owner)) {
            save(ExecutionSnapshot(owner=shared.owner,device=shared.device));return
        }
        val now=clock.now()
        fun reconcileRun(run:ExecutionRun?):ExecutionRun? {
            run ?: return null
            val entity=shared.items["task:${run.taskId}"]
            if(!usable(entity)) return null
            val settled=settle(run,now)
            return if(entity!!.conflict!=null && !settled.paused) settled.copy(paused=true,anchor=null,deadlineAtMillis=null,
                recoveryNotice="任务有同步冲突，已暂停；处理后可继续。") else settled
        }
        save(old.copy(owner=shared.owner,focus=reconcileRun(old.focus),external=old.external.mapNotNull(::reconcileRun)))
        val pending=listOfNotNull(mutable.value.focus)+mutable.value.external
        pending.filter {it.pendingFinish!=null && shared.items["task:${it.taskId}"]?.conflict==null}.forEach(::commitFinish)
    }
    @Synchronized fun startFocus(taskId:String,targetMinutes:Int=5) {
        require(targetMinutes in 1..1440) {"专注时长需为 1–1440 分钟"}
        reconcile();activeTask(engine.state.value,taskId)
        val old=current()
        check(old.external.none {it.taskId==taskId}) {"这项任务正在等结果，请先结束或取消这段等待记录"}
        check(old.focus==null) {"本机已有个人专注，请先暂停并结束或取消上一段"}
        val now=clock.now()
        save(old.copy(focus=ExecutionRun(uid(),taskId,now.wallMillis,anchor=now,targetMinutes=targetMinutes,
            deadlineAtMillis=now.wallMillis+targetMinutes*60_000L)))
    }
    @Synchronized fun startExternal(taskId:String,checkAfterMinutes:Int?=null) {
        checkAfterMinutes?.let {require(it in 1..10080) {"检查时间需为 1–10080 分钟"}}
        reconcile();activeTask(engine.state.value,taskId)
        val old=current()
        check(old.focus?.taskId!=taskId) {"这项任务已有个人专注，请先结束或取消专注"}
        check(old.external.none {it.taskId==taskId}) {"这项任务已经有一段等待记录"}
        val now=clock.now()
        save(old.copy(external=old.external+ExecutionRun(uid(),taskId,now.wallMillis,anchor=now,
            nextCheckAtMillis=checkAfterMinutes?.let {now.wallMillis+it*60_000L})))
    }
    @Synchronized fun pause(taskId:String) {
        reconcile()
        val old=current();val run=old.runFor(taskId) ?: return
        check(run.pendingFinish==null) {"上一段正在保存，请重试结束"}
        if(run.paused) return
        save(replace(old,settle(run,clock.now()).copy(paused=true,anchor=null,deadlineAtMillis=null)))
    }
    @Synchronized fun resume(taskId:String) {
        reconcile();activeTask(engine.state.value,taskId)
        val old=current();val run=old.runFor(taskId) ?: error("执行记录已结束")
        check(run.pendingFinish==null) {"上一段正在保存，请重试结束"}
        if(!run.paused) return
        check(!run.due) {"专注已到点，请确认记录后再开始下一段"}
        val now=clock.now()
        save(replace(old,run.copy(paused=false,anchor=now,recoveryNotice=null,deadlineGeneration=run.deadlineGeneration+1,
            deadlineAtMillis=run.targetMinutes?.let {now.wallMillis+it*60_000L-run.accumulatedMillis})))
    }
    @Synchronized fun setCheck(taskId:String,minutes:Int?) {
        minutes?.let {require(it in 1..10080) {"检查时间需为 1–10080 分钟"}}
        reconcile();val old=current()
        val run=old.external.firstOrNull {it.taskId==taskId} ?: error("请先记录一次等待")
        check(run.pendingFinish==null) {"上一段正在保存，请重试结束"}
        save(replace(old,run.copy(nextCheckAtMillis=minutes?.let {clock.now().wallMillis+it*60_000L})))
    }
    @Synchronized fun cancel(taskId:String) {
        // A committed-but-uncleared finish is retried first; cancellation cannot erase its history.
        reconcile();save(remove(current(),taskId))
    }
    @Synchronized fun finish(taskId:String) {
        reconcile();val old=current();val existing=old.runFor(taskId) ?: return
        activeTask(engine.state.value,taskId)
        val now=clock.now();val run=settle(existing,now)
        val minutes=((run.accumulatedMillis+59_999)/60_000).coerceIn(1,Int.MAX_VALUE.toLong()).toInt()
        val pending=run.copy(paused=true,anchor=null,deadlineAtMillis=null,
            pendingFinish=ExecutionFinish(maxOf(now.wallMillis,run.startedAtMillis),minutes))
        save(replace(old,pending))
        commitFinish(pending)
    }
    private fun commitFinish(run:ExecutionRun) {
        val end=run.pendingFinish ?: return
        val owner=current().owner;val device=current().device
        val isFocus=current().focus?.id==run.id
        engine.edit {shared ->
            check(shared.owner==owner && shared.device==device) {"本机账号发生变化，请重新读取"}
            val entity=shared.items["task:${run.taskId}"]
            // Completion/deletion may race with this transaction. Never recreate or reopen a task.
            if(!usable(entity)) return@edit shared
            check(entity!!.conflict==null) {"任务有同步冲突，结束记录已留在本机，请处理后重试"}
            val content=entity.content()!!.toMutableMap()
            val field=if(isFocus) "progressSessions" else "parallelRunSessions"
            val history=content[field]?.takeUnless {it==JsonNull}?.jsonArray ?: JsonArray(emptyList())
            if(history.any {it.jsonObject["id"]?.jsonPrimitive?.content==run.id}) return@edit shared
            val endedAt=Instant.ofEpochMilli(end.endedAtMillis).toString()
            val entry=buildJsonObject {
                put("id",run.id);put("startedAt",Instant.ofEpochMilli(run.startedAtMillis).toString());put("endedAt",endedAt)
                if(isFocus) {put("minutes",end.minutes);run.targetMinutes?.let {put("targetMinutes",it)}}
                else {put("elapsedMinutes",end.minutes);put("reason","completed")}
            }
            content[field]=JsonArray(history+entry)
            if(isFocus) {
                val actual=content["actualMinutes"]?.jsonPrimitive?.intOrNull ?: 0
                content["actualMinutes"]=JsonPrimitive((actual.toLong()+end.minutes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            val previous=content["lastProgressAt"]?.jsonPrimitive?.contentOrNull?.let {runCatching {Instant.parse(it)}.getOrNull()}
            if(previous==null || !previous.isAfter(Instant.ofEpochMilli(end.endedAtMillis))) content["lastProgressAt"]=JsonPrimitive(endedAt)
            StateMachine.edit(shared,"task",run.taskId,JsonObject(entity.payload!!.toMutableMap().apply {put("content",JsonObject(content))}))
        }
        save(remove(current(),run.taskId))
    }
    fun execute(command:ExecutionCommand) = when(command.action) {
        ExecutionAction.START_FOCUS -> startFocus(command.taskId,command.minutes ?: 5)
        ExecutionAction.START_EXTERNAL -> startExternal(command.taskId,command.minutes)
        ExecutionAction.PAUSE -> pause(command.taskId)
        ExecutionAction.RESUME -> resume(command.taskId)
        ExecutionAction.FINISH -> finish(command.taskId)
        ExecutionAction.CANCEL -> cancel(command.taskId)
        ExecutionAction.SET_CHECK -> setCheck(command.taskId,command.minutes)
    }
}
