package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TodayOverviewTest {
    private val today="2026-10-31@UTC"
    private val tomorrow="2026-11-01@UTC"
    private fun task(id:String,done:Boolean=false,archived:Boolean=false)=Entity("task",id,buildJsonObject {
        put("lifecycle",if(archived)"archived" else "active")
        put("content",buildJsonObject {put("id",id);put("text","Fictional $id");put("completed",done)})
    })
    private fun plan(id:String,ids:List<String>,blocks:List<Triple<String,String,Int>>)=Entity("day_plan",id,buildJsonObject {put("content",buildJsonObject {
        put("day_key",id.substringBefore('@'));put("time_zone",id.substringAfter('@'));put("ordered_task_ids",JsonArray(ids.map(::JsonPrimitive)))
        put("task_plans",JsonArray(blocks.map {(task,at,length)->buildJsonObject {put("task_id",task);put("scheduled_at",at);put("scheduled_minutes",length)}}))
    })})
    private fun state(vararg entities:Entity)=SyncState(items=entities.associateBy {it.key})

    @Test fun selectedDateIncludesCompletedUnscheduledAndExcludesOtherDaysAndDeletedContent() {
        val s=state(task("a"),task("b"),task("c",done=true),task("d",archived=true),task("e").copy(deleted=true),
            plan(today,listOf("a"),emptyList()),plan(tomorrow,listOf("b","c","d","e","missing","b"),listOf(Triple("b","2026-11-01T20:00:00Z",30))))
        val result=TodayOverviewProjection.project(s,TimelineDay.fromPlanId(tomorrow))
        assertEquals(listOf("b"),result.scheduled.map {it.task.id})
        assertTrue(result.unscheduled.isEmpty())
        assertEquals(listOf("c"),result.completed.map {it.task.id})
    }

    @Test fun keepsCrossMidnightAndMultipleOccurrencesWithoutDuplicatingTaskRows() {
        val s=state(task("a"),task("b"),plan(today,listOf("a"),listOf(Triple("a","2026-10-31T23:30:00Z",90))),
            plan(tomorrow,listOf("a","b"),listOf(Triple("a","2026-11-01T20:00:00Z",45))))
        val result=TodayOverviewProjection.project(s,TimelineDay.fromPlanId(tomorrow))
        assertEquals(1,result.scheduled.size)
        assertEquals(listOf(today,tomorrow),result.scheduled.single().blocks.map {it.sourcePlanId})
        assertEquals(listOf("b"),result.unscheduled.map {it.task.id})
        assertTrue(result.completed.isEmpty())
    }

    @Test fun oldMacFutureScheduleAndCompletedScheduledTaskKeepTheirActualDate() {
        val s=state(task("a"),task("b",done=true),plan(today,emptyList(),listOf(Triple("a","2026-11-01T09:00:00Z",30))),
            plan(tomorrow,listOf("b"),listOf(Triple("b","2026-11-01T10:00:00Z",30))))
        val result=TodayOverviewProjection.project(s,TimelineDay.fromPlanId(tomorrow))
        assertEquals(today,result.scheduled.single().blocks.single().sourcePlanId)
        assertEquals("a",result.scheduled.single().task.id)
        assertEquals(listOf("b"),result.completed.map {it.task.id})
        assertEquals(1,result.completed.single().blocks.size)
        assertEquals(Instant.parse("2026-11-01T09:00:00Z"),result.scheduled.single().blocks.single().start)
    }
}
