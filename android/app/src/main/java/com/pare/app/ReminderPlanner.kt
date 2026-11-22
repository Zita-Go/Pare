package com.pare.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

data class ReminderDeadline(val taskId: String, val kind: String, val identity: String, val atMillis: Long, val revision: String? = null)

@Serializable
data class ReminderOccurrence(val sourceKey: String, val taskId: String, val kind: String, val fingerprint: String, val atMillis: Long, val suffix: String = "") {
    val token: String get() = "$fingerprint:${if (kind == "task") atMillis else 0}:$suffix"
}

/** Contains identities and timestamps only. Task text remains in the task repository. */
@Serializable
data class ReminderLedger(
    val scheduled: Map<String, ReminderOccurrence> = emptyMap(),
    val visible: Map<String, ReminderOccurrence> = emptyMap(),
    val handled: Set<String> = emptySet(),
    val exact: Boolean = false,
    val nextRebuildAt: Long = 0
)

data class ReminderSource(val key: String, val taskId: String, val kind: String, val anchorMillis: Long,
    val recurrence: String? = null, val zone: String = ZoneId.systemDefault().id, val fallbackMillis: Long? = null, val identityStamp: String? = null) {
    val fingerprint: String get() = MessageDigest.getInstance("SHA-256")
        .digest("$key|${identityStamp ?: anchorMillis}|$recurrence|$zone|$fallbackMillis".toByteArray()).joinToString("") { "%02x".format(it) }

    fun accepts(event: ReminderOccurrence): Boolean = event.sourceKey == key && event.fingerprint == fingerprint

    fun next(after: Instant): ReminderOccurrence? {
        var date = Instant.ofEpochMilli(anchorMillis).atZone(ZoneId.of(zone))
        if (recurrence != null) {
            if (recurrence !in setOf("daily", "weekly", "monthly")) return null
            // Repeated calendar additions intentionally preserve Mac's month-end clamping.
            // Limit protects the receiver from a malformed ancient imported date.
            var iterations = 0
            while (!date.toInstant().isAfter(after)) {
                if (++iterations > 100_000) return null
                date = when (recurrence) { "daily" -> date.plusDays(1); "weekly" -> date.plusWeeks(1); else -> date.plusMonths(1) }
            }
        } else if (!date.toInstant().isAfter(after)) {
            if (fallbackMillis != null && fallbackMillis > after.toEpochMilli()) date = Instant.ofEpochMilli(fallbackMillis).atZone(ZoneId.of(zone))
            else if (kind !in setOf("focus", "check") || after.toEpochMilli() - anchorMillis > 86_400_000L) return null
        }
        return ReminderOccurrence(key, taskId, kind, fingerprint, date.toInstant().toEpochMilli())
    }
}

object ReminderPlanner {
    fun sources(state: SyncState, deadlines: List<ReminderDeadline>, now: Instant): List<ReminderSource> {
        val active = state.items.values.filter {
            it.kind == "task" && taskCanBePlanned(it) && it.conflict == null && it.conflictGroup.isEmpty()
        }.associateBy { it.id }
        val result = mutableListOf<ReminderSource>()
        for (task in active.values) {
            val content = task.content() ?: continue
            val at = content.instant("remindAt") ?: continue
            val rule = content["recurrence"]?.jsonPrimitive?.contentOrNull
            result += ReminderSource("task:${task.id}", task.id, "task", at.toEpochMilli(), rule)
        }
        // A future day's lead may be tonight. Use the calendar's authoritative occurrences
        // before filtering conflicts, so a stale legacy copy cannot replace a blocked source.
        for (schedule in TimelineProjection.schedules(state)) {
            val taskId = schedule.task.id
            if (taskId !in active) continue
            val source = state.items["day_plan:${schedule.sourcePlanId}"] ?: continue
            if (source.conflict != null || source.conflictGroup.isNotEmpty()) continue
            val start = schedule.start
            val zone = schedule.sourcePlanId.substringAfter('@')
            val lead = schedule.reminderLeadMinutes?.takeIf { it in 0..525600 } ?: continue
            // Normal plans retain their existing identity. Migrating a legacy future block to
            // its start-day plan must not replay an already delivered lead or discard its snooze.
            val planId = "${start.atZone(ZoneId.of(zone)).toLocalDate()}@$zone"
            result += ReminderSource("schedule:$planId:$taskId", taskId, "schedule", start.minusSeconds(lead * 60L).toEpochMilli(),
                zone = zone, fallbackMillis = start.toEpochMilli())
        }
        for (deadline in deadlines) {
            if (deadline.taskId !in active || deadline.kind !in setOf("focus", "check")) continue
            // Execution deadlines are absolute instants. Travel must not invalidate handled tokens or snoozes.
            result += ReminderSource("${deadline.kind}:${deadline.identity}", deadline.taskId, deadline.kind, deadline.atMillis,
                zone = "UTC", identityStamp = deadline.revision)
        }
        result += ImplementationPlanActions.sources(state,now)
        return result.distinctBy { it.key }
    }

    /** Existing valid pending alarms survive inexact delivery delay and unchanged sync. */
    fun reconcile(sources: List<ReminderSource>, ledger: ReminderLedger, now: Instant): List<ReminderOccurrence> = sources.mapNotNull { source ->
        val pending = ledger.scheduled[source.key]?.takeIf {
            source.accepts(it) && it.token !in ledger.handled && now.toEpochMilli() - it.atMillis < 86_400_000L &&
                (source.kind != "focus" || it.suffix.isNotEmpty() || kotlin.math.abs(it.atMillis - source.anchorMillis) < 2_000)
        }
        pending ?: source.next(now)?.takeUnless { it.token in ledger.handled }
    }

    private fun JsonObject.instant(key: String): Instant? = this[key]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
