package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OrganizeActionsTest {
    private val now = Instant.parse("2026-10-30T00:00:00Z")
    private val inbox = Entity("inbox", "capture", wire.parseToJsonElement("""{"content":{"id":"capture","text":"Example captured task","suggestedRemindAt":"2026-10-31T00:00:00Z"}}""").jsonObject)
    private fun initial() = SyncState(items = mapOf(inbox.key to inbox))

    @Test fun explicitQuadrantsArePreservedAndDoNotCreateTodayPlan() {
        (0..3).forEach { quadrant ->
            val result = organizeInbox(initial(), inbox, quadrant, false, "task-$quadrant", now)
            val task = result.items.getValue("task:task-$quadrant")
            assertEquals(quadrant, task.content()!!.getValue("quadrant").jsonPrimitive.int)
            assertEquals(inbox.title(), task.title())
            assertEquals("2026-10-31T00:00:00Z", task.content()!!.getValue("remindAt").jsonPrimitive.content)
            assertTrue(result.items.getValue(inbox.key).deleted)
            assertTrue(result.items.values.none { it.kind == "day_plan" })
            assertEquals(task.group, result.items.getValue(inbox.key).group)
            assertFalse(task.group.isBlank())
            assertFalse(initial().items.getValue(inbox.key).deleted)
        }
    }

    @Test fun addingTodayPreservesPlanAndGroupsAllThreeMutations() {
        val planId = ContentActions.planId(initial(), now)
        val plan = Entity("day_plan", planId, wire.parseToJsonElement("""{"futureEnvelope":true,"content":{"day_key":"${planId.substringBefore('@')}","time_zone":"${planId.substringAfter('@')}","ordered_task_ids":["existing"],"daily_win_task_id":"existing","task_plans":[{"task_id":"existing","note":"keep"}],"futureContent":42}}""").jsonObject)
        val state = initial().copy(items = initial().items + (plan.key to plan))
        val result = organizeInbox(state, inbox, 2, true, "new-task", now)
        val changed = result.items.getValue(plan.key)
        assertEquals(listOf("existing", "new-task"), changed.content()!!.getValue("ordered_task_ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive("existing"), changed.content()!!["daily_win_task_id"])
        assertEquals(plan.content()!!["task_plans"], changed.content()!!["task_plans"])
        assertEquals(JsonPrimitive(42), changed.content()!!["futureContent"])
        assertEquals(JsonPrimitive(true), changed.payload!!["futureEnvelope"])
        assertEquals(1, result.items.values.filter { it.dirty }.map { it.group }.distinct().size)
        assertEquals(setOf(inbox.key, "task:new-task", plan.key), StateMachine.prepare(result).pending!!.mutations.map { it.key }.toSet())
    }

    @Test fun invalidChoiceCannotMutateSource() {
        assertThrows(IllegalArgumentException::class.java) { organizeInbox(initial(), inbox, -1, true, "new-task", now) }
        assertFalse(initial().items.getValue(inbox.key).deleted)
    }

    @Test fun sourceDeletedBeforeCommitCannotCreateTask() {
        val state = initial().copy(items = mapOf(inbox.key to inbox.copy(deleted = true, payload = null)))
        assertThrows(IllegalArgumentException::class.java) { organizeInbox(state, inbox, 0, true, "new-task", now) }
        assertEquals(1, state.items.size)
    }

    @Test fun sourceChangedWhileSavingRequiresReview() {
        val changed = inbox.copy(payload = wire.parseToJsonElement("""{"content":{"text":"Changed on another device"}}""").jsonObject)
        val state = initial().copy(items = mapOf(changed.key to changed))
        assertThrows(IllegalArgumentException::class.java) { organizeInbox(state, inbox, 1, false, "new-task", now) }
        assertFalse(state.items.getValue(inbox.key).deleted)
    }
}
