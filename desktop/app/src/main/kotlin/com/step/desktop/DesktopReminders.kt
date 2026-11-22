package com.step.desktop

import com.pare.app.*
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.time.Instant

/** Runs only while this process is alive. Handled tokens persist across tray/window restarts. */
@Serializable private data class DesktopReminderData(val version:Int,val ledger:ReminderLedger)
internal class DesktopReminders(home:Path,private val notify:(String,String)->Unit) {
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
        val next=old.copy(scheduled=occurrences.filter {it !in due}.associateBy {it.sourceKey},
            handled=(old.handled+due.map {it.token}).toList().takeLast(10_000).toSet())
        if(ledger==null || next!=old) {
            // At-most-once delivery: a crash after this commit may omit a toast, but never repeat it.
            file.write(wire.encodeToString(DesktopReminderData(1,next)).toByteArray(Charsets.UTF_8))
        }
        ledger=next
        due.forEach {event->
            val title=when(event.kind) {"focus"->"专注已到点";"check"->"到检查结果的时间了";else->"Step 提醒"}
            notify(title,state.items["task:${event.taskId}"]?.title() ?: "查看今天的安排")
        }
    }
}
