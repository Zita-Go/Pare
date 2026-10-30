package com.pare.app

import java.time.Instant
import java.time.ZoneId

/** Planned effort is separate from a task's overall estimate or external elapsed time. */
internal fun todayPlanLabel(state: SyncState, taskId: String, now: Instant): String? {
    val allocation = DayAllocationBaseline.from(state, taskId, now)
    val zone = ZoneId.of(allocation.planId.substringAfter('@'))
    val scheduledToday = allocation.scheduledAt?.let {
        runCatching { Instant.parse(it).atZone(zone).toLocalDate().toString() == allocation.planId.substringBefore('@') }.getOrDefault(false)
    } == true
    val minutes = (if (scheduledToday) allocation.scheduledMinutes else null) ?: allocation.plannedMinutes
    return minutes?.takeIf { it > 0 }?.let { "${if (allocation.kind == "check") "检查" else "计划投入"} $it 分钟" }
}
