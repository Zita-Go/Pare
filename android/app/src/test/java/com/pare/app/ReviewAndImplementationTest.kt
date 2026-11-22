package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReviewAndImplementationTest {
    private val now=Instant.parse("2099-05-20T08:00:00Z")
    private val id="00000000-0000-4000-8000-000000000097"
    private val day="2099-05-20@UTC"
    private fun state():SyncState {
        val task=Entity("task",id,ContentActions.save(null,"task","Private fictional task title","Private note",1,id,now).let {p->JsonObject(p.toMutableMap().apply {put("content",JsonObject(p.getValue("content").jsonObject.toMutableMap().apply {
            put("dueAt",JsonPrimitive(now.plusSeconds(86400).toString()));put("remindAt",JsonPrimitive(now.plusSeconds(300).toString()))
        }))})})
        val plan=Entity("day_plan",day,buildJsonObject {put("content",buildJsonObject {put("day_key","2099-05-20");put("time_zone","UTC");put("ordered_task_ids",JsonArray(listOf(JsonPrimitive(id))));put("task_plans",JsonArray(emptyList()))})})
        return SyncState(device="fictional-review",items=listOf(task,plan).associateBy {it.key})
    }
    private fun edit(s:SyncState,vararg fields:Pair<String,JsonElement>):SyncState {
        val e=s.items.getValue("task:$id")
        return StateMachine.edit(s,"task",id,JsonObject(e.payload!!.toMutableMap().apply {put("content",JsonObject(e.content()!!.toMutableMap().apply {putAll(fields)}))}))
    }
    private fun entry(start:Instant,end:Instant,minutes:Int)=buildJsonObject {put("id",uid());put("startedAt",start.toString());put("endedAt",end.toString());put("minutes",minutes)}
    @Test fun eightWeekActualTrendDoesNotTurnEstimatesIntoRecordedTime() {
        val s=edit(state(),"estimatedMinutes" to JsonPrimitive(900),"progressSessions" to JsonArray(listOf(entry(now.minusSeconds(1200),now.minusSeconds(60),12))))
        val result=ReviewAnalytics.calculate(s,now)
        assertEquals(8,result.weeks.size);assertEquals(12L,result.weeks.sumOf {it.focusMinutes});assertNull(result.calibration)
        assertEquals(0,result.weeks.sumOf {it.completed});assertEquals(0L,result.weeks.sumOf {it.waitingMinutes})
    }
    @Test fun estimateCalibrationUsesOnlyCompletePairsAndActualZeroIsValid() {
        var s=edit(state(),"completed" to JsonPrimitive(true),"completedAt" to JsonPrimitive(now.toString()),"estimatedMinutes" to JsonPrimitive(20))
        assertNull(ReviewAnalytics.calculate(s,now).calibration)
        s=edit(s,"actualMinutes" to JsonPrimitive(30))
        val c=ReviewAnalytics.calculate(s,now).calibration!!
        assertEquals(1,c.pairs);assertEquals(1.5,c.ratio,0.001)
        assertEquals(1,ReviewAnalytics.calculate(s,now).quadrantCompletions[1])
        assertEquals(0.0,ReviewAnalytics.calculate(edit(s,"actualMinutes" to JsonPrimitive(0)),now).calibration!!.ratio,0.0)
    }
    @Test fun futureInvalidDeletedAndRepeatedRecordsAreNotFabricated() {
        val e=entry(now.minusSeconds(600),now.minusSeconds(1),7)
        val s=edit(state(),"completed" to JsonPrimitive(true),"completedAt" to JsonPrimitive(now.plusSeconds(60).toString()),
            "progressSessions" to JsonArray(listOf(e,e,entry(now,now.plusSeconds(60),99),entry(now,now.minusSeconds(1),20))))
        val result=ReviewAnalytics.calculate(s,now)
        assertEquals(7L,result.weeks.sumOf {it.focusMinutes});assertEquals(0,result.weeks.sumOf {it.completed})
        val deleted=s.copy(items=s.items+("task:$id" to s.items.getValue("task:$id").copy(deleted=true,payload=null)))
        assertFalse(ReviewAnalytics.calculate(deleted,now).hasRecords)
    }
    @Test fun clipboardExportIsAggregateOnlyAndMissingCalibrationIsExplicit() {
        val text=ReviewAnalytics.export(state(),now)
        assertTrue(text.contains("没有同时记录"));assertFalse(text.contains("Private fictional"));assertFalse(text.contains("Private note"));assertFalse(text.contains(id))
    }
    @Test fun timePlanPreservesDeadlineReminderAndUnknownPlanFields() {
        val s=edit(state(),"implementationPlan" to buildJsonObject {put("kind","time");put("hour",9);put("minute",0);put("action","Open paper");put("futureField","keep")})
        val result=ImplementationPlanActions.save(s,id,ImplementationPlanBaseline.from(s,id),ImplementationPlanDraft(true,10,15,"Draw one line"))
        val before=s.items["task:$id"]!!.content()!!;val after=result.items["task:$id"]!!.content()!!
        assertEquals(before["dueAt"],after["dueAt"]);assertEquals(before["remindAt"],after["remindAt"])
        assertEquals(JsonPrimitive("keep"),after.getValue("implementationPlan").jsonObject["futureField"])
        PayloadValidation.validate("task",id,result.items["task:$id"]!!.payload!!)
    }
    @Test fun unsupportedContextAndStaleEditsCannotBeOverwritten() {
        val s=edit(state(),"implementationPlan" to buildJsonObject {put("kind","returnToComputer");put("action","Open paper")})
        assertThrows(IllegalArgumentException::class.java) {ImplementationPlanActions.save(s,id,ImplementationPlanBaseline.from(s,id),ImplementationPlanDraft(false))}
        val source=state();val baseline=ImplementationPlanBaseline.from(source,id)
        assertThrows(StaleContentEditException::class.java) {ImplementationPlanActions.save(edit(source,"text" to JsonPrimitive("Changed")),id,baseline,ImplementationPlanDraft(true,9,0,"Start"))}
        assertThrows(IllegalStateException::class.java) {ImplementationPlanActions.save(source.copy(owner="different"),id,baseline,ImplementationPlanDraft(false))}
    }
    @Test fun onlyTodaySelectedTimePlansProduceIndependentRemindersAndNeverPastOrTomorrowCatchup() {
        val s=ImplementationPlanActions.save(state(),id,ImplementationPlanBaseline.from(state(),id),ImplementationPlanDraft(true,9,0,"Draw one line"))
        val sources=ReminderPlanner.sources(s,emptyList(),now)
        assertEquals(setOf("task","implementation"),sources.map {it.kind}.toSet())
        val reminder=sources.single {it.kind=="implementation"}
        assertEquals(Instant.parse("2099-05-20T09:00:00Z").toEpochMilli(),reminder.next(now)!!.atMillis)
        assertNull(reminder.next(now.plusSeconds(7200)))
        assertTrue(ImplementationPlanActions.sources(s,now.plusSeconds(86400)).isEmpty())
        val unselected=s.copy(items=s.items.minus("day_plan:$day"))
        assertTrue(ImplementationPlanActions.sources(unselected,now).isEmpty())
    }
    @Test fun changedActionInvalidatesPendingTriggerAndHandledEventIsNotReplayed() {
        val initial=state();val s=ImplementationPlanActions.save(initial,id,ImplementationPlanBaseline.from(initial,id),ImplementationPlanDraft(true,9,0,"Draw one line"))
        val first=ImplementationPlanActions.sources(s,now).single();val event=first.next(now)!!
        val changed=ImplementationPlanActions.save(s,id,ImplementationPlanBaseline.from(s,id),ImplementationPlanDraft(true,9,0,"Open a file"))
        val next=ImplementationPlanActions.sources(changed,now).single()
        assertFalse(next.accepts(event))
        assertTrue(ReminderPlanner.reconcile(listOf(first),ReminderLedger(handled=setOf(event.token)),now).isEmpty())
    }
    @Test fun explicitStartIntentIsRetainedUntilSuccessfulSaveThenClaimedOnce() {
        val model=TodayWorkflowModel()
        assertTrue(model.begin(startAfterSave=true));assertFalse(model.consumeStartAfterSave())
        model.finish(Result.failure(IllegalStateException("fictional disk failure")));assertFalse(model.consumeStartAfterSave())
        assertTrue(model.begin(startAfterSave=true));model.finish(Result.success(Unit))
        assertTrue(model.consumeStartAfterSave());assertFalse(model.consumeStartAfterSave())
        model.clear();assertTrue(model.begin());model.finish(Result.success(Unit));assertFalse(model.consumeStartAfterSave())
    }
    @Test fun unsupportedAndCompletedPlansDoNotScheduleMobileTriggers() {
        val unsupported=edit(state(),"implementationPlan" to buildJsonObject {put("kind","openApplication");put("applicationName","Fictional editor");put("action","Open paper")})
        assertTrue(ImplementationPlanActions.sources(unsupported,now).isEmpty())
        val valid=ImplementationPlanActions.save(state(),id,ImplementationPlanBaseline.from(state(),id),ImplementationPlanDraft(true,9,0,"Start"))
        assertTrue(ImplementationPlanActions.sources(edit(valid,"completed" to JsonPrimitive(true)),now).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {ImplementationPlanActions.save(state(),id,ImplementationPlanBaseline.from(state(),id),ImplementationPlanDraft(true,25,0,"Start"))}
    }
    @Test fun dstGapAndRepeatedClockDoNotSilentlyChangeTriggerTime() {
        fun sources(date:String,hour:Int,minute:Int):List<ReminderSource> {
            val at=Instant.parse("${date}T05:00:00Z");val planId="$date@America/New_York"
            val task=Entity("task",id,ContentActions.save(null,"task","Fictional DST task","",1,id,at))
            val plan=Entity("day_plan",planId,buildJsonObject {put("content",buildJsonObject {
                put("day_key",date);put("time_zone","America/New_York");put("ordered_task_ids",JsonArray(listOf(JsonPrimitive(id))));put("task_plans",JsonArray(emptyList()))
            })})
            val initial=SyncState(device="fictional-dst",items=mapOf(task.key to task,plan.key to plan))
            val changed=ImplementationPlanActions.save(initial,id,ImplementationPlanBaseline.from(initial,id),ImplementationPlanDraft(true,hour,minute,"Start"))
            return ImplementationPlanActions.sources(changed,at)
        }
        assertTrue(sources("2026-03-08",2,30).isEmpty())
        assertTrue(sources("2026-11-01",1,30).isEmpty())
    }

}
