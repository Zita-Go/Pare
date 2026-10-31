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
    private fun scheduleEntry(taskId: String, start: String?, lead: Int = 60) = buildJsonObject {
        put("task_id", taskId)
        if (start != null) {
            put("scheduled_at", start); put("scheduled_minutes", 30); put("schedule_reminder_lead_minutes", lead)
        }
    }
    private fun plan(id: String, vararg entries: JsonObject) = Entity("day_plan", id, buildJsonObject {
        put("content", buildJsonObject {
            put("day_key", id.substringBefore('@')); put("time_zone", id.substringAfter('@'))
            put("ordered_task_ids", JsonArray(entries.map { it.getValue("task_id") })); put("task_plans", JsonArray(entries.toList()))
        })
    })

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

    @Test fun currentScheduleFallsBackToStartWhileLegacyFutureScheduleRemainsScheduled() {
        val plan = Entity("day_plan", "2026-10-30@UTC", buildJsonObject { put("content", buildJsonObject {
            put("time_zone", "UTC"); put("day_key", "2026-10-30")
            put("task_plans", buildJsonArray {
                add(buildJsonObject { put("task_id", "fictional"); put("scheduled_at", "2026-10-30T08:05:00Z"); put("schedule_reminder_lead_minutes", 10) })
                add(buildJsonObject { put("task_id", "tomorrow"); put("scheduled_at", "2026-10-31T09:00:00Z"); put("schedule_reminder_lead_minutes", 10) })
            })
        }) })
        val sources = ReminderPlanner.sources(state(task(), task("tomorrow"), plan), emptyList(), now).filter { it.kind == "schedule" }
        assertEquals(2, sources.size)
        assertEquals(Instant.parse("2026-10-30T08:05:00Z").toEpochMilli(), sources.single { it.taskId == "fictional" }.next(now)!!.atMillis)
        assertEquals(Instant.parse("2026-10-31T08:50:00Z").toEpochMilli(), sources.single { it.taskId == "tomorrow" }.next(now)!!.atMillis)
    }

    @Test fun futureBlockLeadIsScheduledBeforeMidnightInItsPlanningZone() {
        // Shanghai Nov 1 00:15, minus 60 minutes, is Oct 31 23:15 (15:15 UTC).
        val beforeLead = Instant.parse("2026-10-31T14:00:00Z")
        val schedule = plan("2026-11-01@Asia/Shanghai", scheduleEntry("fictional", "2026-10-31T16:15:00Z"))
        val shared = state(task(), schedule)
        val source = ReminderPlanner.sources(shared, emptyList(), beforeLead).single { it.kind == "schedule" }
        assertEquals("Asia/Shanghai", source.zone)
        val alarm = source.next(beforeLead)!!
        assertEquals(Instant.parse("2026-10-31T15:15:00Z").toEpochMilli(), alarm.atMillis)
        val pending = ReminderLedger(scheduled = mapOf(alarm.sourceKey to alarm))
        val afterMidnight = Instant.parse("2026-10-31T16:00:00Z")
        val refreshed = ReminderPlanner.sources(shared, emptyList(), afterMidnight).filter { it.kind == "schedule" }
        assertEquals(listOf(alarm), ReminderPlanner.reconcile(refreshed, pending, afterMidnight))
        assertTrue(ReminderPlanner.reconcile(refreshed, ReminderLedger(handled = setOf(alarm.token)), afterMidnight).isEmpty())
    }

    @Test fun authoritativeScheduleCancelsLegacyCopyWithoutDuplicateOrResurrectedReminder() {
        val canonicalId = "2026-11-01@UTC"
        val start = "2026-11-01T00:15:00Z"
        val canonical = plan(canonicalId, scheduleEntry("fictional", start))
        val legacy = plan("2026-10-30@UTC", scheduleEntry("fictional", start))
        val shared = state(task(), canonical, legacy)
        val sources = ReminderPlanner.sources(shared, emptyList(), now).filter { it.kind == "schedule" }
        assertEquals(1, sources.size)
        assertEquals("schedule:$canonicalId:fictional", sources.single().key)
        assertEquals(Instant.parse("2026-10-31T23:15:00Z").toEpochMilli(), sources.single().next(now)!!.atMillis)
        val alarm = sources.single().next(now)!!
        val snoozed = alarm.copy(atMillis = alarm.atMillis + 600_000, suffix = "snooze:selected")
        val cleared = state(task(), plan(canonicalId, scheduleEntry("fictional", null)), legacy)
        val remaining = ReminderPlanner.sources(cleared, emptyList(), now).filter { it.kind == "schedule" }
        assertTrue(remaining.isEmpty())
        assertTrue(ReminderPlanner.reconcile(remaining, ReminderLedger(scheduled = mapOf(alarm.sourceKey to snoozed)), now).isEmpty())
    }

    @Test fun unresolvedCanonicalPlanDoesNotFallBackToItsLegacyReminderCopy() {
        val canonical = plan("2026-11-01@UTC", scheduleEntry("fictional", "2026-11-01T00:15:00Z"))
            .copy(conflictGroup = "fictional-conflict")
        val legacy = plan("2026-10-30@UTC", scheduleEntry("fictional", "2026-11-01T00:15:00Z"))
        val sources = ReminderPlanner.sources(state(task(), canonical, legacy), emptyList(), now)
        assertTrue(sources.none { it.kind == "schedule" })
    }

    @Test fun movingLegacyScheduleToItsStartDayDoesNotReplayDeliveredLead() {
        val start = "2026-11-01T00:15:00Z"
        val legacyId = "2026-10-30@UTC"
        val legacy = plan(legacyId, scheduleEntry("fictional", start))
        val before = ReminderPlanner.sources(state(task(), legacy), emptyList(), now).single { it.kind == "schedule" }
        val delivered = before.next(now)!!
        val afterLead = Instant.parse("2026-11-01T00:00:00Z")
        val migrated = state(task(), plan(legacyId, scheduleEntry("fictional", null)),
            plan("2026-11-01@UTC", scheduleEntry("fictional", start)))
        val after = ReminderPlanner.sources(migrated, emptyList(), afterLead).filter { it.kind == "schedule" }
        assertEquals(before.key, after.single().key)
        assertTrue(ReminderPlanner.reconcile(after, ReminderLedger(handled = setOf(delivered.token)), afterLead).isEmpty())
    }

    @Test fun tasksInUnresolvedAtomicConflictProduceNoReminderSources() {
        val blocked = task().copy(conflictGroup = "fictional-conflict")
        val schedule = plan("2026-10-30@UTC", scheduleEntry("fictional", "2026-10-30T09:00:00Z"))
        val sources = ReminderPlanner.sources(state(blocked, schedule), emptyList(), now)
        assertTrue(sources.isEmpty())
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
