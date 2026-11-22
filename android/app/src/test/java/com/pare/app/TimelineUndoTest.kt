package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimelineUndoTest {
    private val id="00000000-0000-4000-8000-000000000081"
    private val now=Instant.parse("2026-10-31T01:00:00Z")
    private fun initial():SyncState=StateMachine.edit(SyncState(),"task",id,ContentActions.save(null,"task","虚构事项","",1,id,now))
    @Test fun undoRestoresPlanButRejectsLaterEdits() {
        val state=initial();val day=TimelineDay.today(state,now)
        val once=TimelineActions.setSchedule(state,id,day.planId,day.start.plusSeconds(9*3600),30,TimelineScheduleBaseline.from(state,day.planId,id))
        val after=TimelineActions.setSchedule(once,id,day.planId,day.start.plusSeconds(10*3600),60,TimelineScheduleBaseline.from(once,day.planId,id))
        val undo=TimelineUndo.capture(once,after)!!
        assertEquals(once.items["day_plan:${day.planId}"]!!.payload,undo.apply(after).items["day_plan:${day.planId}"]!!.payload)
        val third=TimelineActions.setSchedule(after,id,day.planId,day.start.plusSeconds(11*3600),60,TimelineScheduleBaseline.from(after,day.planId,id))
        assertThrows(IllegalArgumentException::class.java){undo.apply(third)}
    }
    @Test fun undoNewPlanKeepsTaskAndRejectsOtherOwner() {
        val before=initial();val day=TimelineDay.today(before,now)
        val after=TimelineActions.setSchedule(before,id,day.planId,day.start.plusSeconds(9*3600),30,TimelineScheduleBaseline.from(before,day.planId,id))
        val undo=TimelineUndo.capture(before,after)!!;val result=undo.apply(after)
        assertFalse(result.items["day_plan:${day.planId}"]!!.deleted)
        assertTrue(result.items["day_plan:${day.planId}"]!!.content()!!["task_plans"]!!.jsonArray.isEmpty())
        assertEquals(before.items["task:$id"]!!.payload,result.items["task:$id"]!!.payload)
        assertThrows(IllegalArgumentException::class.java){undo.apply(after.copy(owner=uid()))}
    }
    @Test fun legacyMigrationUndoRestoresVisibleOccurrenceAndCanScheduleAgain() {
        val state=initial();val source=TimelineDay.today(state,now);val target=TimelineDay.from(state,source.date.plusDays(1))
        val start=target.start.plusSeconds(9*3600)
        val legacy=StateMachine.edit(state,"day_plan",source.planId,buildJsonObject {put("content",buildJsonObject {
            put("day_key",source.date.toString());put("time_zone",source.zone.id);put("ordered_task_ids",JsonArray(listOf(JsonPrimitive(id))))
            put("task_plans",buildJsonArray {add(buildJsonObject {put("task_id",id);put("scheduled_at",start.toString());put("scheduled_minutes",30)})})
        })})
        val moved=TimelineActions.setSchedule(legacy,id,source.planId,start.plusSeconds(1800),45,TimelineScheduleBaseline.from(legacy,source.planId,id))
        val restored=TimelineUndo.capture(legacy,moved)!!.apply(moved)
        assertEquals(start,TimelineProjection.project(restored,target).blocks.single().start)
        val again=TimelineActions.setSchedule(restored,id,source.planId,start.plusSeconds(3600),60,TimelineScheduleBaseline.from(restored,source.planId,id))
        assertEquals(60,TimelineProjection.project(again,target).blocks.single().durationMinutes)
    }
}
