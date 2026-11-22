package com.pare.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimelineMoveTest {
    private val taskId="00000000-0000-4000-8000-000000000091"
    private val peerId="00000000-0000-4000-8000-000000000092"
    private val day="2099-05-20@UTC"
    private val target="2099-05-21@UTC"
    private val now=Instant.parse("2099-05-20T09:00:00Z")
    private fun plan(id:String,task:String=taskId,start:Instant=now):Entity=Entity("day_plan",id,buildJsonObject {put("futureEnvelope","keep");put("content",buildJsonObject {
        put("day_key",id.substringBefore('@'));put("time_zone",id.substringAfter('@'));put("ordered_task_ids",JsonArray(listOf(JsonPrimitive(task))));put("daily_win_task_id",task)
        put("futurePlan","keep");put("task_plans",buildJsonArray {add(buildJsonObject {put("task_id",task);put("scheduled_at",start.toString());put("scheduled_minutes",20);put("schedule_reminder_lead_minutes",5)
            put("unknownEntry","preserved");put("allocation",buildJsonObject {put("id","00000000-0000-4000-8000-000000000093");put("kind","check");put("planned_minutes",7)})
        })})
    })})
    private fun state():SyncState {
        val task=Entity("task",taskId,ContentActions.save(null,"task","Fictional move","",1,taskId,now))
        val peer=Entity("task",peerId,ContentActions.save(null,"task","Fictional other appointment","",1,peerId,now))
        return SyncState(device="fictional-timeline-move",items=listOf(task,peer,plan(day),plan(target,peerId,now.plusSeconds(86400))).associateBy {it.key})
    }
    private fun baseline(s:SyncState)=TimelineMoveBaseline.capture(s,TimelineScheduleBaseline.from(s,day,taskId),target)
    private fun entry(s:SyncState,id:String,task:String=taskId)=(s.items["day_plan:$id"]?.content()?.get("task_plans") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull {it.timelineString("task_id")==task}
    @Test fun movingAcrossDatesIsAtomicPreservesAllocationAndOtherAppointmentsAndUndo() {
        val s=state();val expected=baseline(s);val moved=TimelineActions.moveToDay(s,taskId,now.plusSeconds(86400),20,expected)
        assertFalse(moved.items.getValue("day_plan:$day").deleted)
        assertFalse(taskId in moved.items["day_plan:$day"]?.content().planningIds("ordered_task_ids"))
        assertNull(moved.items["day_plan:$day"]?.content()?.get("daily_win_task_id"))
        assertEquals(peerId,moved.items["day_plan:$target"]?.content()?.timelineString("daily_win_task_id"))
        assertEquals(entry(s,day)?.get("allocation"),entry(moved,target)?.get("allocation"))
        assertEquals(entry(s,target,peerId),entry(moved,target,peerId))
        assertEquals(JsonPrimitive("preserved"),entry(moved,target)?.get("unknownEntry"))
        assertEquals(5,entry(moved,target)?.timelineInt("schedule_reminder_lead_minutes"))
        assertEquals(moved.items["day_plan:$day"]?.group,moved.items["day_plan:$target"]?.group)
        assertEquals(moved,TimelineActions.moveToDay(moved,taskId,now.plusSeconds(86400),20,expected))
        val restored=TimelineUndo.capture(s,moved)!!.apply(moved)
        assertEquals(s.items["day_plan:$day"]?.payload,restored.items["day_plan:$day"]?.payload)
        assertEquals(s.items["day_plan:$target"]?.payload,restored.items["day_plan:$target"]?.payload)
        moved.items.values.filter {it.kind=="day_plan"}.forEach {PayloadValidation.validate(it.kind,it.id,it.payload!!)}
    }
    @Test fun targetSameTaskSlotCannotBeOverwrittenEvenWhenUnscheduled() {
        val s=state().let {it.copy(items=it.items+("day_plan:$target" to plan(target,taskId,now.plusSeconds(90000))))}
        assertThrows(IllegalArgumentException::class.java) {TimelineActions.moveToDay(s,taskId,now.plusSeconds(86400),20,baseline(s))}
    }
    @Test fun changedTargetOwnerTaskOrZoneRejectsWholeMove() {
        val s=state();val expected=baseline(s)
        assertThrows(IllegalStateException::class.java) {TimelineActions.moveToDay(s.copy(owner="different"),taskId,now.plusSeconds(86400),20,expected)}
        val changed=s.copy(items=s.items+("day_plan:$target" to plan(target,peerId,now.plusSeconds(90000))))
        assertThrows(IllegalStateException::class.java) {TimelineActions.moveToDay(changed,taskId,now.plusSeconds(86400),20,expected)}
        val deleted=s.copy(items=s.items+("day_plan:$target" to s.items.getValue("day_plan:$target").copy(deleted=true,payload=null)))
        assertThrows(IllegalStateException::class.java) {TimelineActions.moveToDay(deleted,taskId,now.plusSeconds(86400),20,expected)}
    }
    @Test fun movingLegacySnapshotClearsOlderSnapshotsWithoutErasingHistoricalAllocations() {
        val older="2099-05-18@UTC";val source="2099-05-19@UTC"
        val s=state().let {it.copy(items=it.items.minus("day_plan:$day")+("day_plan:$source" to plan(source))+("day_plan:$older" to plan(older,taskId,now.minusSeconds(3600))))}
        val expected=TimelineMoveBaseline.capture(s,TimelineScheduleBaseline.from(s,source,taskId),target)
        val moved=TimelineActions.moveToDay(s,taskId,now.plusSeconds(86400),20,expected)
        assertNull(entry(moved,older)?.get("scheduled_at"));assertNotNull(entry(moved,older)?.get("allocation"))
        assertEquals(listOf(target),TimelineProjection.schedules(moved).filter {it.task.id==taskId}.map {it.sourcePlanId})
    }
    @Test fun shiftKeepsLocalClockAndRejectsDstGapAndAmbiguity() {
        val zone=ZoneId.of("America/New_York")
        val start=Instant.parse("2026-03-07T07:30:00Z")
        assertThrows(IllegalArgumentException::class.java) {TimelineTime.onDate(start,TimelineDay(LocalDate.parse("2026-03-08"),zone))}
        assertThrows(IllegalArgumentException::class.java) {TimelineTime.onDate(Instant.parse("2026-10-31T05:30:00Z"),TimelineDay(LocalDate.parse("2026-11-01"),zone))}
        val normal=TimelineTime.onDate(Instant.parse("2026-03-07T14:00:00Z"),TimelineDay(LocalDate.parse("2026-03-08"),zone))
        assertEquals(Instant.parse("2026-03-08T13:00:00Z"),normal)
    }
    @Test fun removingSourceDoesNotAffectIndependentCanonicalOccurrence() {
        val s=state();val independent="2099-05-24@UTC";val independentPlan=plan(independent,taskId,now.plusSeconds(86400L*4))
        val multiple=s.copy(items=s.items+(independentPlan.key to independentPlan))
        val moved=TimelineActions.moveToDay(multiple,taskId,now.plusSeconds(86400),20,baseline(multiple))
        assertEquals(independentPlan,moved.items[independentPlan.key])
        assertEquals(setOf(target,independent),TimelineProjection.schedules(moved).filter {it.task.id==taskId}.map {it.sourcePlanId}.toSet())
    }

}
