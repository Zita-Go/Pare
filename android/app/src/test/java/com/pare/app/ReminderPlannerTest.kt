package com.pare.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class ReminderPlannerTest {
    private val now = Instant.parse("2026-10-30T08:00:00Z")
    private fun task(id: String = "fictional", completed: Boolean = false, at: String = "2026-10-30T09:00:00Z", recurrence: String? = null) =
        Entity("task", id, buildJsonObject {
            put("lifecycle", "active")
            put("content", buildJsonObject { put("text", "虚构任务"); put("completed", completed); put("remindAt", at); recurrence?.let { put("recurrence", it) } })
        })
    private fun state(vararg entities: Entity) = SyncState(items = entities.associateBy { it.key })

    @Test fun completedDeletedAndArchivedTasksNeverProduceReminderSources() {
        val active = task()
        val completed = task("completed", true)
        val deleted = task("deleted").copy(deleted = true)
        val archived = task("archived").let { it.copy(payload = JsonObject(it.payload!! + ("lifecycle" to JsonPrimitive("archived")))) }
        assertEquals(listOf("fictional"), ReminderPlanner.sources(state(active, completed, deleted, archived), emptyList(), now).map { it.taskId })
    }

    @Test fun weeklyReminderHonorsFutureStartAndDoesNotChangeTask() {
        val original = state(task(at = "2026-11-03T09:00:00Z", recurrence = "weekly"))
        val reminders = ReminderPlanner.reconcile(ReminderPlanner.sources(original, emptyList(), now), ReminderLedger(), now)
        assertEquals(Instant.parse("2026-11-03T09:00:00Z").toEpochMilli(), reminders.single().atMillis)
        assertFalse(original.items.getValue("task:fictional").completed())
    }

    @Test fun monthlyRecurrenceClampsSuccessivelyLikeMacCalendar() {
        val source = ReminderSource("task:fictional", "fictional", "task", Instant.parse("2026-01-31T09:00:00Z").toEpochMilli(), "monthly", "UTC")
        val afterFebruary = Instant.parse("2026-03-01T00:00:00Z")
        assertEquals(Instant.parse("2026-03-28T09:00:00Z").toEpochMilli(), source.next(afterFebruary)!!.atMillis)
    }

    @Test fun dailyRecurrenceKeepsWallClockAcrossDaylightSaving() {
        val source = ReminderSource("task:fictional", "fictional", "task", Instant.parse("2026-03-07T14:00:00Z").toEpochMilli(), "daily", "America/New_York")
        assertEquals(Instant.parse("2026-03-08T13:00:00Z").toEpochMilli(), source.next(Instant.parse("2026-03-08T00:00:00Z"))!!.atMillis)
    }

    @Test fun currentDateScheduleUsesStartWhenLeadAlreadyPassedAndExcludesOtherDates() {
        val plan = Entity("day_plan", "2026-10-30@UTC", buildJsonObject { put("content", buildJsonObject {
            put("time_zone", "UTC"); put("day_key", "2026-10-30")
            put("task_plans", buildJsonArray {
                add(buildJsonObject { put("task_id", "fictional"); put("scheduled_at", "2026-10-30T08:05:00Z"); put("schedule_reminder_lead_minutes", 10) })
                add(buildJsonObject { put("task_id", "tomorrow"); put("scheduled_at", "2026-10-31T09:00:00Z"); put("schedule_reminder_lead_minutes", 10) })
            })
        }) })
        val sources = ReminderPlanner.sources(state(task(), task("tomorrow"), plan), emptyList(), now).filter { it.kind == "schedule" }
        assertEquals(1, sources.size)
        assertEquals(Instant.parse("2026-10-30T08:05:00Z").toEpochMilli(), sources.single().next(now)!!.atMillis)
    }

    @Test fun identicalSyncPreservesPendingAlarmAndFiredOneShotNeverRearms() {
        val sources = ReminderPlanner.sources(state(task()), emptyList(), now)
        val first = ReminderPlanner.reconcile(sources, ReminderLedger(), now).single()
        val scheduled = ReminderLedger(scheduled = mapOf(first.sourceKey to first))
        assertEquals(listOf(first), ReminderPlanner.reconcile(sources, scheduled, now.plusSeconds(60)))
        val delivered = scheduled.copy(scheduled = emptyMap(), handled = setOf(first.token))
        assertTrue(ReminderPlanner.reconcile(sources, delivered, now.plusSeconds(60)).isEmpty())
    }

    @Test fun editedDeadlineInvalidatesOldAlarmAndSnooze() {
        val original = ReminderPlanner.sources(state(task()), emptyList(), now).single()
        val event = original.next(now)!!
        val changed = ReminderPlanner.sources(state(task(at = "2026-10-30T10:00:00Z")), emptyList(), now)
        val next = ReminderPlanner.reconcile(changed, ReminderLedger(scheduled = mapOf(event.sourceKey to event.copy(atMillis = now.plusSeconds(600).toEpochMilli(), suffix = "snooze"))), now).single()
        assertEquals(Instant.parse("2026-10-30T10:00:00Z").toEpochMilli(), next.atMillis)
        assertNotEquals(event.fingerprint, next.fingerprint)
    }

    @Test fun alreadyScheduledDelayedAlarmSurvivesRefreshUntilDelivery() {
        val sources = ReminderPlanner.sources(state(task()), emptyList(), now)
        val event = sources.single().next(now)!!
        assertEquals(listOf(event), ReminderPlanner.reconcile(sources, ReminderLedger(scheduled = mapOf(event.sourceKey to event)), now.plusSeconds(3700)))
    }

    @Test fun removedTaskCancelsLocalFocusAndExternalCheck() {
        val deadlines = listOf(ReminderDeadline("fictional", "focus", "session", now.plusSeconds(60).toEpochMilli()), ReminderDeadline("fictional", "check", "run", now.plusSeconds(120).toEpochMilli()))
        assertEquals(setOf("task", "focus", "check"), ReminderPlanner.sources(state(task()), deadlines, now).map { it.kind }.toSet())
        assertTrue(ReminderPlanner.sources(state(task(completed = true)), deadlines, now).isEmpty())
    }

    @Test fun unresolvedRemoteDeletionStopsTaskAndExecutionReminder() {
        val task = task().copy(conflict = Remote("task", "fictional", 2, deletedAt = now.toString(), device = "fictional-other-device", sequence = 4))
        assertTrue(ReminderPlanner.sources(state(task), listOf(ReminderDeadline("fictional", "focus", "run", now.plusSeconds(60).toEpochMilli())), now).isEmpty())
    }

    @Test fun deliveredScheduleLeadDoesNotNotifyAgainAtBlockStart() {
        val source = ReminderSource("schedule:fictional", "fictional", "schedule", now.plusSeconds(300).toEpochMilli(), fallbackMillis = now.plusSeconds(600).toEpochMilli())
        val first = source.next(now)!!
        val handled = ReminderLedger(handled = setOf(first.token))
        assertTrue(ReminderPlanner.reconcile(listOf(source), handled, now.plusSeconds(301)).isEmpty())
    }

    @Test fun focusClockCorrectionReschedulesButKeepsDeliveredIdentity() {
        val source = ReminderSource("focus:run", "fictional", "focus", now.plusSeconds(300).toEpochMilli(), identityStamp = "run:target:0")
        val first = source.next(now)!!
        val moved = source.copy(anchorMillis = now.plusSeconds(900).toEpochMilli())
        val pending = ReminderPlanner.reconcile(listOf(moved), ReminderLedger(scheduled = mapOf(first.sourceKey to first)), now).single()
        assertEquals(now.plusSeconds(900).toEpochMilli(), pending.atMillis)
        assertEquals(first.token, pending.token)
        assertTrue(ReminderPlanner.reconcile(listOf(moved), ReminderLedger(handled = setOf(first.token)), now).isEmpty())
    }

    @Test fun timezoneChangeDoesNotReplayHandledFocusOrExternalCheck() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val shared = state(task())
            val deadlines = listOf(
                ReminderDeadline("fictional", "focus", "session", now.plusSeconds(60).toEpochMilli(), "target:0"),
                ReminderDeadline("fictional", "check", "external", now.plusSeconds(60).toEpochMilli())
            )
            val events = ReminderPlanner.sources(shared, deadlines, now).filter { it.kind in setOf("focus", "check") }.map { it.next(now)!! }
            val handled = ReminderLedger(handled = events.map { it.token }.toSet())
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val after = now.plusSeconds(120)
            val sources = ReminderPlanner.sources(shared, deadlines, after).filter { it.kind in setOf("focus", "check") }
            assertTrue("A timezone change must not replay delivered or dismissed execution reminders", ReminderPlanner.reconcile(sources, handled, after).isEmpty())
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun timezoneChangeKeepsFocusAndExternalCheckSnoozesAtTheirChosenInstants() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val shared = state(task())
            val deadlines = listOf(
                ReminderDeadline("fictional", "focus", "session", now.plusSeconds(60).toEpochMilli(), "target:0"),
                ReminderDeadline("fictional", "check", "external", now.plusSeconds(60).toEpochMilli())
            )
            val events = ReminderPlanner.sources(shared, deadlines, now).filter { it.kind in setOf("focus", "check") }.map { it.next(now)!! }
            val snoozes = events.map { it.copy(atMillis = now.plusSeconds(660).toEpochMilli(), suffix = "snooze:selected") }
            val ledger = ReminderLedger(scheduled = snoozes.associateBy { it.sourceKey }, handled = events.map { it.token }.toSet())
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val after = now.plusSeconds(120)
            val sources = ReminderPlanner.sources(shared, deadlines, after).filter { it.kind in setOf("focus", "check") }
            assertEquals(snoozes, ReminderPlanner.reconcile(sources, ledger, after))
        } finally { TimeZone.setDefault(previous) }
    }
}
