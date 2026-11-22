package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DayTaskSelectionTest {
    private val now = Instant.parse("2026-11-01T08:00:00Z")
    private val day = "2026-11-01@UTC"
    private fun task(id: String, quadrant: Int = 1) = Entity("task", id,
        ContentActions.save(null, "task", "虚构行动 $id", "", quadrant, id, now))
    private fun plan(id: String = day) = Entity("day_plan", id, wire.parseToJsonElement("""
        {"futureEnvelope":"preserve","content":{"day_key":"${id.substringBefore('@')}","time_zone":"UTC",
        "ordered_task_ids":["a"],"daily_win_task_id":"a","futurePlanField":{"value":2},
        "task_plans":[{"task_id":"a","scheduled_at":"2026-11-01T09:00:00Z","scheduled_minutes":60,
        "schedule_reminder_lead_minutes":10,"allocation":{"id":"allocation-a","kind":"check","planned_minutes":8},
        "futureEntryField":"keep"}]}}
    """).jsonObject)
    private fun state(vararg entities: Entity): SyncState {
        val profile = Entity("profile", "profile", buildJsonObject { put("content", buildJsonObject { put("planningTimeZone", "UTC") }) })
        return SyncState(items = (entities.toList() + profile).associateBy { it.key })
    }

    @Test fun addingExistingTaskPreservesOrderPlanFieldsAndReminderIdentity() {
        val before = state(task("a"), task("b"), plan(), plan("2026-11-02@UTC"))
        val reminders = ReminderPlanner.sources(before, emptyList(), now)
        val result = DayTaskSelection.add(before, "b", day, now)
        val original = before.items.getValue("day_plan:$day")
        val updated = result.items.getValue("day_plan:$day")
        assertEquals(listOf("a", "b"), updated.content()!!["ordered_task_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(original.content()!!.filterKeys { it != "ordered_task_ids" }, updated.content()!!.filterKeys { it != "ordered_task_ids" })
        assertEquals(original.payload!!["futureEnvelope"], updated.payload!!["futureEnvelope"])
        assertEquals(before.items.filterKeys { it != updated.key }, result.items.filterKeys { it != updated.key })
        assertEquals(reminders, ReminderPlanner.sources(result, emptyList(), now))
        assertTrue(updated.dirty)
        assertSame(result, DayTaskSelection.add(result, "b", day, now))
    }

    @Test fun futureDateSelectionOnlyCreatesTheSelectedPlanWithoutAllocationOrSchedule() {
        val before = state(task("b"))
        val result = DayTaskSelection.add(before, "b", "2026-11-03@UTC", now)
        assertNull(result.items["day_plan:$day"])
        val content = result.items.getValue("day_plan:2026-11-03@UTC").content()!!
        assertEquals("2026-11-03", content.timelineString("day_key"))
        assertEquals("UTC", content.timelineString("time_zone"))
        assertEquals(listOf("b"), content["ordered_task_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue((content["task_plans"] as? JsonArray).orEmpty().isEmpty())
        assertTrue(ReminderPlanner.sources(result, emptyList(), now).isEmpty())
    }

    @Test fun chooserExcludesUnavailableAndAlreadyVisibleTasksAndKeepsQuadrantOrdering() {
        val done = task("done").let { it.copy(payload = JsonObject(it.payload!!.toMutableMap().apply {
            put("content", JsonObject(it.content()!!.toMutableMap().apply { put("completed", JsonPrimitive(true)) }))
        })) }
        val archived = task("archived").let { it.copy(payload = JsonObject(it.payload!!.toMutableMap().apply { put("lifecycle", JsonPrimitive("archived")) })) }
        val s = state(task("a"), task("later", 3), task("urgent", 0), task("normal", 1), done, archived,
            task("deleted").copy(deleted = true), task("conflict").copy(conflictGroup = "blocked"), plan())
        assertEquals(listOf("urgent", "normal", "later"), DayTaskSelection.available(s, day).map { it.id })
        assertTrue(DayTaskSelection.contains(s, "a", day))
    }

    @Test fun authoritativeLegacyAndCrossMidnightRowsAreAlreadySelected() {
        val previous = plan("2026-10-31@UTC").let { it.copy(payload = wire.parseToJsonElement("""
            {"content":{"day_key":"2026-10-31","time_zone":"UTC","ordered_task_ids":[],"task_plans":[
            {"task_id":"legacy","scheduled_at":"2026-11-01T10:00:00Z","scheduled_minutes":30},
            {"task_id":"overnight","scheduled_at":"2026-10-31T23:30:00Z","scheduled_minutes":90}]}}
        """).jsonObject) }
        val s = state(task("legacy"), task("overnight"), task("available"), previous)
        assertEquals(listOf("available"), DayTaskSelection.available(s, day).map { it.id })
        assertSame(s, DayTaskSelection.add(s, "legacy", day, now))
        assertSame(s, DayTaskSelection.add(s, "overnight", day, now))
    }

    @Test fun chooserKeepsManualQuadrantOrderInsteadOfCreationChronology() {
        fun positioned(id: String, createdAt: String, rank: Long): Entity {
            val original = task(id)
            return original.copy(payload = JsonObject(original.payload!!.toMutableMap().apply {
                put("content", JsonObject(original.content()!!.toMutableMap().apply {
                    put("createdAt", JsonPrimitive(createdAt)); put("quadrantOrder", JsonPrimitive(rank))
                }))
            }))
        }
        val s = state(positioned("older", "2026-10-01T08:00:00Z", 20),
            positioned("middle", "2026-10-02T08:00:00Z", 10),
            positioned("newer", "2026-10-03T08:00:00Z", 30))
        assertEquals(listOf("middle", "older", "newer"), DayTaskSelection.available(s, day).map { it.id })
    }

    @Test fun latestDeletedCompletedArchivedOrConflictedTaskCannotBeAdded() {
        val s = state(task("b"))
        val current = s.items.getValue("task:b")
        val alternatives = listOf(current.copy(deleted = true), current.copy(payload = null), current.copy(conflictGroup = "blocked"),
            current.copy(payload = wire.parseToJsonElement("""{"lifecycle":"archived","content":{"text":"虚构","completed":false}}""").jsonObject),
            current.copy(payload = wire.parseToJsonElement("""{"lifecycle":"active","content":{"text":"虚构","completed":true}}""").jsonObject))
        alternatives.forEach { changed ->
            assertThrows(IllegalStateException::class.java) { DayTaskSelection.add(s.copy(items = s.items + (changed.key to changed)), "b", day, now) }
        }
        assertThrows(IllegalStateException::class.java) { DayTaskSelection.add(s, "missing", day, now) }
    }

    @Test fun staleZoneDeletedPlanAndConflictsCannotResurrectOrOverwriteContent() {
        val s = state(task("b"), plan())
        assertThrows(IllegalStateException::class.java) { DayTaskSelection.add(s, "b", "2026-11-01@Asia/Shanghai", now) }
        assertThrows(java.time.DateTimeException::class.java) { DayTaskSelection.add(s, "b", "invalid", now) }
        val original = s.items.getValue("day_plan:$day")
        listOf(original.copy(deleted = true), original.copy(conflictGroup = "blocked"), original.copy(payload = null),
            original.copy(payload = wire.parseToJsonElement("""{"content":{"day_key":"2026-11-02","time_zone":"UTC"}}""").jsonObject)).forEach { changed ->
            assertThrows(IllegalStateException::class.java) { DayTaskSelection.add(s.copy(items = s.items + (changed.key to changed)), "b", day, now) }
        }
        val profile = s.items.getValue("profile:profile").copy(conflictGroup = "blocked")
        assertThrows(IllegalStateException::class.java) { DayTaskSelection.add(s.copy(items = s.items + (profile.key to profile)), "b", day, now) }
    }
}
