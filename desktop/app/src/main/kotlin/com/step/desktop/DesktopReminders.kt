package com.step.desktop

import com.pare.app.*
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.time.Instant

/** Runs only while this process is alive. Handled tokens persist across tray/window restarts. */
@Serializable private data class DesktopReminderData(val version:Int,val ledger:ReminderLedger)
internal class DesktopReminders(home:Path,private val actionable:((DesktopNotice)->Unit)?=null,private val notify:(String,String)->Unit) {
    private val file=DesktopAtomicFile(home.resolve("reminders.json"),4*1024*1024)
    private var ledger:ReminderLedger?=null
    @Synchronized fun refresh(state:SyncState,execution:ExecutionSnapshot,moment:ExecutionMoment) {
        val old=ledger ?: file.read()?.let {
            wire.decodeFromString<DesktopReminderData>(it.toString(Charsets.UTF_8)).also {data->check(data.version==1)}.ledger
        } ?: ReminderLedger()
        val deadlines=buildList {
            execution.focus?.let {run->run.currentFocusDeadlineMillis(moment)?.let {at->
                add(ReminderDeadline(run.taskId,"focus",run.id,at,run.deadlineGeneration.toString()))
            }}
            execution.external.filter {it.pendingFinish==null}.forEach {run->run.nextCheckAtMillis?.let {at->
                add(ReminderDeadline(run.taskId,"check",run.id,at,at.toString()))
            }}
        }
        val now=Instant.ofEpochMilli(moment.wallMillis)
        val sources=ReminderPlanner.sources(state,deadlines,now)
        val occurrences=ReminderPlanner.reconcile(sources,old,now)
        val due=occurrences.filter {it.atMillis<=moment.wallMillis && it.token !in old.handled}
        val visible=old.visible.filter {(_,event)->moment.wallMillis-event.atMillis<86_400_000 && sources.any {it.accepts(event)}}+due.associateBy {it.sourceKey}
        val next=old.copy(scheduled=occurrences.filter {it !in due}.associateBy {it.sourceKey},visible=visible,
            handled=(old.handled+due.map {it.token}).toList().takeLast(10_000).toSet())
        if(ledger==null || next!=old) {
            // At-most-once delivery: a crash after this commit may omit a toast, but never repeat it.
            file.write(wire.encodeToString(DesktopReminderData(1,next)).toByteArray(Charsets.UTF_8))
        }
        ledger=next
        due.forEach {event->
            val title=when(event.kind) {"focus"->"专注已到点";"check"->"到检查结果的时间了";else->"Step 提醒"}
            val body=state.items["task:${event.taskId}"]?.title() ?: "查看今天的安排"
            if(actionable!=null)actionable.invoke(DesktopNotice(title,body,event.token,event.kind)) else notify(title,body)
        }
    }
    @Synchronized fun scheduled():List<DesktopScheduledNotice> = ledger?.scheduled?.values.orEmpty().map {DesktopScheduledNotice(it.token,it.atMillis)}
    @Synchronized fun cancelPausedFocus() {
        val old=ledger ?: return
        val next=old.copy(scheduled=old.scheduled.filterValues {it.kind!="focus"})
        if(next!=old){file.write(wire.encodeToString(DesktopReminderData(1,next)).toByteArray());ledger=next}
    }
    @Synchronized fun action(token:String,state:SyncState,moment:ExecutionMoment):ReminderOccurrence {
        val event=ledger?.visible?.values?.firstOrNull {it.token==token} ?: error("这条提醒已处理或过期")
        check(moment.wallMillis-event.atMillis in 0..86_400_000) {"提醒已过期，请打开任务查看最新内容"}
        val item=state.items["task:${event.taskId}"]
        check(item!=null && !item.deleted && !item.completed() && item.conflict==null && item.payload?.get("lifecycle")!=kotlinx.serialization.json.JsonPrimitive("archived")) {"任务已改变，提醒未执行"}
        return event
    }
    @Synchronized fun consume(event:ReminderOccurrence,snoozeUntil:Long?=null) {
        val old=requireNotNull(ledger)
        check(old.visible[event.sourceKey]?.token==event.token) {"提醒已被更新"}
        val next=old.copy(visible=old.visible-event.sourceKey,scheduled=if(snoozeUntil==null)old.scheduled else old.scheduled+(event.sourceKey to event.copy(atMillis=snoozeUntil,suffix=uid())))
        file.write(wire.encodeToString(DesktopReminderData(1,next)).toByteArray(Charsets.UTF_8));ledger=next
    }
}
