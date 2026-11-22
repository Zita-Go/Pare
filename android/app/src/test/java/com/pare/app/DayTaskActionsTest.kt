package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DayTaskActionsTest {
    private val taskId = "92000000-0000-0000-0000-000000000001"
    private val otherId = "92000000-0000-0000-0000-000000000002"
    private val now = Instant.parse("2026-11-22T09:00:00Z")
    private val planId = "2026-11-22@UTC"
    private fun fixture(scheduledAt: String = "2026-11-22T10:00:00Z"): SyncState {
        val task = Entity("task", taskId, ContentActions.save(null, "task", "虚构：观察一片树叶", "", 1, taskId, now))
        val other = Entity("task", otherId, ContentActions.save(null, "task", "虚构：整理记录", "", 2, otherId, now))
        val plan = Entity("day_plan", planId, wire.parseToJsonElement("""{"futureEnvelope":true,"content":{"day_key":"2026-11-22","time_zone":"UTC","ordered_task_ids":["$taskId","$otherId"],"daily_win_task_id":"$taskId","futureField":"keep","task_plans":[{"task_id":"$taskId","scheduled_at":"$scheduledAt","scheduled_minutes":15,"schedule_reminder_lead_minutes":5,"allocation":{"id":"92000000-0000-0000-0000-000000000003","kind":"personal","planned_minutes":15},"futureEntry":true},{"task_id":"$otherId","futureEntry":"other"}]}}""").jsonObject)
        return SyncState(items = listOf(task, other, plan).associateBy { it.key })
    }

    @Test fun removalClearsOnlyThisDaysCommitmentAndKeepsTaskHistoryAndUnknownFields() {
        val before = fixture()
        val result = DayTaskActions.setIncluded(before, taskId, false, planId, now)
        val plan = result.items.getValue("day_plan:$planId").content()!!
        assertEquals(listOf(otherId), plan.getValue("ordered_task_ids").jsonArray.map { it.jsonPrimitive.content })
        assertNull(plan["daily_win_task_id"])
        assertEquals(JsonPrimitive("keep"), plan["futureField"])
        assertEquals(JsonPrimitive(true), result.items.getValue("day_plan:$planId").payload!!["futureEnvelope"])
        val entry = plan.getValue("task_plans").jsonArray.first().jsonObject
        assertEquals(setOf("task_id", "futureEntry"), entry.keys)
        assertEquals(before.items["task:$taskId"], result.items["task:$taskId"])
        assertEquals(before.items.getValue("day_plan:$planId").content()!!.getValue("task_plans").jsonArray[1], plan.getValue("task_plans").jsonArray[1])
        assertEquals(result, DayTaskActions.setIncluded(result, taskId, false, planId, now))
    }

    @Test fun removalPreservesLegacyScheduleAndAllocationForAnotherDate() {
        val before = fixture("2026-11-24T10:00:00Z")
        val result = DayTaskActions.setIncluded(before, taskId, false, planId, now)
        assertEquals(before.items.getValue("day_plan:$planId").content()!!["task_plans"], result.items.getValue("day_plan:$planId").content()!!["task_plans"])
    }

    @Test fun selectingDailyWinAddsMembershipOnceWithoutChangingOtherPlanFields() {
        val before = DayTaskActions.setIncluded(fixture(), taskId, false, planId, now)
        val result = DayTaskActions.setWin(before, taskId, true, planId, now)
        val plan = result.items.getValue("day_plan:$planId").content()!!
        assertEquals(listOf(otherId, taskId), plan.getValue("ordered_task_ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(taskId), plan["daily_win_task_id"])
        assertEquals(before.items.getValue("day_plan:$planId").content()!!["task_plans"], plan["task_plans"])
        assertEquals(result, DayTaskActions.setWin(result, taskId, true, planId, now))
        val cleared = DayTaskActions.setWin(result, taskId, false, planId, now)
        assertNull(cleared.items.getValue("day_plan:$planId").content()!!["daily_win_task_id"])
        assertEquals(plan["ordered_task_ids"], cleared.items.getValue("day_plan:$planId").content()!!["ordered_task_ids"])
    }

    @Test fun staleClosedOrConflictedTasksAndPlansCannotReceiveNewCommitments() {
        val before = fixture()
        val task = before.items.getValue("task:$taskId")
        val archived = before.copy(items = before.items + (task.key to task.copy(payload = JsonObject(task.payload!!.toMutableMap().apply { put("lifecycle", JsonPrimitive("archived")) }))))
        assertThrows(IllegalArgumentException::class.java) { DayTaskActions.setWin(archived, taskId, true, planId, now) }
        val plan = before.items.getValue("day_plan:$planId")
        val conflicted = before.copy(items = before.items + (plan.key to plan.copy(conflictGroup = "pending-choice")))
        assertThrows(IllegalArgumentException::class.java) { DayTaskActions.setIncluded(conflicted, taskId, false, planId, now) }
    }
}
