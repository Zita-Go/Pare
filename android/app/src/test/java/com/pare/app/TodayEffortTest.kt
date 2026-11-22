package com.pare.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TodayEffortTest {
    private val day = TimelineDay.fromPlanId("2026-11-01@UTC")
    private fun task(id: String, done: Boolean = false) = Entity("task", id, wire.parseToJsonElement("""
        {"lifecycle":"active","content":{"text":"虚构 $id","completed":$done,"estimatedMinutes":600,"expectedWaitMinutes":720}}
    """).jsonObject)
    private fun plan(id: String = day.planId, entries: String) = Entity("day_plan", id, wire.parseToJsonElement("""
        {"content":{"day_key":"${id.substringBefore('@')}","time_zone":"UTC","ordered_task_ids":["a","b","c","d","done"],"task_plans":[$entries]}}
    """).jsonObject)
    private fun state(vararg entities: Entity) = SyncState(items = entities.associateBy { it.key })

    @Test fun summaryCountsSavedAllocationOnceAndSeparatesCheckFromPersonalAndUnknown() {
        val s = state(task("a"), task("b"), task("c"), task("d"), task("done", true), plan(entries = """
            {"task_id":"a","scheduled_at":"2026-11-01T09:00:00Z","scheduled_minutes":120,"allocation":{"kind":"personal","planned_minutes":25}},
            {"task_id":"b","allocation":{"kind":"check","planned_minutes":8}},
            {"task_id":"c","scheduled_at":"2026-11-01T12:00:00Z","scheduled_minutes":240},
            {"task_id":"d","allocation":{"kind":"waiting","planned_minutes":720}},
            {"task_id":"done","allocation":{"kind":"personal","planned_minutes":50}}
        """))
        val projection = TodayOverviewProjection.project(s, day)
        val load = TodayEffort.summary(s, day, projection)
        assertEquals(25L, load.personalMinutes)
        assertEquals(8L, load.checkMinutes)
        assertEquals(2, load.unknownCount)
        assertEquals("个人投入 25 分钟", TodayEffort.forTask(s, day, "a").label)
        assertEquals("检查 8 分钟", TodayEffort.forTask(s, day, "b").label)
        assertEquals("未设投入", TodayEffort.forTask(s, day, "c").label)
    }

    @Test fun legacyFutureAndCrossMidnightSchedulesNeverBorrowAnotherDaysAllocation() {
        val previous = plan("2026-10-31@UTC", """
            {"task_id":"a","scheduled_at":"2026-10-31T23:30:00Z","scheduled_minutes":90,"allocation":{"kind":"personal","planned_minutes":45}},
            {"task_id":"b","scheduled_at":"2026-11-01T10:00:00Z","scheduled_minutes":30,"allocation":{"kind":"check","planned_minutes":6}}
        """)
        val s = state(task("a"), task("b"), previous)
        val projection = TodayOverviewProjection.project(s, day)
        assertEquals(listOf("a", "b"), projection.scheduled.map { it.task.id })
        val load = TodayEffort.summary(s, day, projection)
        assertEquals(0L, load.personalMinutes)
        assertEquals(0L, load.checkMinutes)
        assertEquals(2, load.unknownCount)
        val selected = plan(entries = """{"task_id":"a","allocation":{"kind":"personal","planned_minutes":10}}""")
        val withAllocation = s.copy(items = s.items + (selected.key to selected))
        assertEquals(10L, TodayEffort.summary(withAllocation, day, projection).personalMinutes)
    }

    @Test fun invalidOrConflictedAllocationsStayUnknown() {
        val invalid = listOf("null", "{}", """{"kind":"personal","planned_minutes":0}""", """{"kind":"check","planned_minutes":1441}""", """{"planned_minutes":5}""")
        invalid.forEach { allocation ->
            val s = state(task("a"), plan(entries = """{"task_id":"a","allocation":$allocation}"""))
            assertNull(TodayEffort.forTask(s, day, "a").minutes)
        }
        val p = plan(entries = """{"task_id":"a","allocation":{"kind":"personal","planned_minutes":10}}""").copy(conflictGroup = "blocked")
        assertNull(TodayEffort.forTask(state(task("a"), p), day, "a").minutes)
    }
}
