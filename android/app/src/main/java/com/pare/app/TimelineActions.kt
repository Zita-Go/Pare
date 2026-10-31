package com.pare.app

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

data class TimelineCanonicalBaseline(val planId: String, val planExists: Boolean, val entry: JsonObject?, val included: Boolean)
data class TimelineLegacyPeerBaseline(val planId: String, val scheduledAt: String, val scheduledMinutes: Int?, val reminderLeadMinutes: Int?)

/** Baselines compare only fields consumed by this action; unrelated task/plan data can merge. */
data class TimelineScheduleBaseline(
    val planId: String,
    val taskId: String,
    val planningZone: String,
    val scheduledAt: String?,
    val scheduledMinutes: Int?,
    val reminderLeadMinutes: Int?,
    val included: Boolean,
    val taskTitle: String?,
    val taskExists: Boolean,
    val planDeleted: Boolean,
    val planExists: Boolean,
    val canonical: TimelineCanonicalBaseline? = null,
    val legacyPeers: List<TimelineLegacyPeerBaseline> = emptyList()
) {
    companion object {
        fun from(state: SyncState, planId: String, taskId: String): TimelineScheduleBaseline {
            val plan = state.items["day_plan:$planId"]
            val content = plan?.takeUnless {it.deleted}?.content()
            val entry = (content?.get("task_plans") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .firstOrNull {it.timelineString("task_id") == taskId}
            val task = state.items["task:$taskId"]
            val canonicalId = entry?.timelineString("scheduled_at")?.let {value -> runCatching {
                val sourceDay = TimelineDay.fromPlanId(planId)
                "${Instant.parse(value).atZone(sourceDay.zone).toLocalDate()}@${sourceDay.zone.id}"
            }.getOrNull()}?.takeUnless {it == planId}
            val canonical = canonicalId?.let {id ->
                val target = state.items["day_plan:$id"]
                TimelineCanonicalBaseline(id, target != null, timelineEntry(state, id, taskId),
                    taskId in target?.takeUnless {it.deleted}?.content().timelineIds())
            }
            val peers = if (canonical == null) emptyList() else timelineLegacyPeers(state, taskId, planId.substringAfter('@'))
                .filter {it.planId.substringBefore('@') < planId.substringBefore('@')}
            return TimelineScheduleBaseline(planId, taskId, timelinePlanningZone(state), entry?.timelineString("scheduled_at"),
                entry?.timelineInt("scheduled_minutes"), entry?.timelineInt("schedule_reminder_lead_minutes"),
                taskId in content.timelineIds(), task?.title(), task != null, plan?.deleted == true, plan != null, canonical, peers)
        }
    }
}

data class TimelineOrderBaseline(val planId: String, val planningZone: String, val orderedTaskIds: List<String>, val planExists: Boolean) {
    companion object {
        fun from(state: SyncState, planId: String) = TimelineOrderBaseline(planId, timelinePlanningZone(state),
            state.items["day_plan:$planId"]?.takeUnless {it.deleted}?.content().timelineIds(), state.items["day_plan:$planId"] != null)
    }
}

object TimelineActions {
    fun setSchedule(state: SyncState, taskId: String, planId: String, start: Instant, durationMinutes: Int,
        expected: TimelineScheduleBaseline, reminderLeadMinutes: Int? = expected.reminderLeadMinutes): SyncState {
        val day = TimelineDay.fromPlanId(planId)
        val targetId = "${start.atZone(day.zone).toLocalDate()}@${day.zone.id}"
        require(targetId == (expected.canonical?.planId ?: planId)) { "开始日期已改变，请重新打开对应日期的安排；跨午夜的结束时间会保留" }
        require(durationMinutes in 1..1440) { "时间块请填写 1–1440 分钟" }
        require(reminderLeadMinutes == null || reminderLeadMinutes in 0..525600) { "提醒提前量无效" }
        val current = guardSchedule(state, taskId, planId, expected)
        if (expected.canonical != null) return migrateLegacy(state, taskId, expected, current, start, durationMinutes, reminderLeadMinutes)
        if (current.included && current.scheduledAt == start.toString() && current.scheduledMinutes == durationMinutes &&
            current.reminderLeadMinutes == reminderLeadMinutes) return state
        requireScheduleUnchanged(current, expected)
        return updateEntry(state, taskId, planId, include = true) {entry ->
            entry["scheduled_at"] = JsonPrimitive(start.toString())
            entry["scheduled_minutes"] = JsonPrimitive(durationMinutes)
            if (reminderLeadMinutes == null) entry.remove("schedule_reminder_lead_minutes")
            else entry["schedule_reminder_lead_minutes"] = JsonPrimitive(reminderLeadMinutes)
        }
    }

    /** Keeps membership, ordering, daily win, allocation and every unknown field. */
    fun clearSchedule(state: SyncState, taskId: String, planId: String, expected: TimelineScheduleBaseline): SyncState {
        val current = guardSchedule(state, taskId, planId, expected)
        if (expected.canonical != null) return migrateLegacy(state, taskId, expected, current, null, null, null)
        if (current.scheduledAt == null && current.scheduledMinutes == null && current.reminderLeadMinutes == null) return state
        requireScheduleUnchanged(current, expected)
        return updateEntry(state, taskId, planId, include = false) {
            it.remove("scheduled_at"); it.remove("scheduled_minutes"); it.remove("schedule_reminder_lead_minutes")
        }
    }

    /** One immutable transition, then the repository persists the task and plan in one atomic group. */
    fun createScheduledTask(state: SyncState, taskId: String, title: String, quadrant: Int, planId: String,
        start: Instant, durationMinutes: Int, expected: TimelineScheduleBaseline, now: Instant,
        nextAction: String = "", reminderLeadMinutes: Int? = null): SyncState {
        require(UUID.fromString(taskId).toString() == taskId) { "任务标识无效" }
        require(title.isNotBlank() && quadrant in 0..3) { "请填写任务并选择象限" }
        check(!expected.taskExists && expected.taskId == taskId && expected.planId == planId) { "创建草稿不匹配，请重新打开" }
        guardPlan(state, planId, expected.planningZone, expected.planExists)
        val old = state.items["task:$taskId"]
        if (old != null) {
            guardEntity(old)
            val current = TimelineScheduleBaseline.from(state, planId, taskId)
            if (!timelineActiveTask(old) || old.title() != title.trim() || old.content()?.timelineInt("quadrant") != quadrant ||
                old.content()?.timelineString("nextAction").orEmpty() != nextAction.trim() || !current.included ||
                current.scheduledAt != start.toString() || current.scheduledMinutes != durationMinutes || current.reminderLeadMinutes != reminderLeadMinutes) {
                throw StaleContentEditException(StaleEditReason.ALREADY_EXISTS)
            }
            return state
        }
        val payload = ContentActions.save(null, "task", title.trim(), "", quadrant, taskId, now, nextAction = nextAction)
        val created = StateMachine.edit(state, "task", taskId, payload)
        val scheduled = setSchedule(created, taskId, planId, start, durationMinutes,
            expected.copy(taskExists = true, taskTitle = title.trim()), reminderLeadMinutes)
        return StateMachine.group(scheduled, setOf("task:$taskId", "day_plan:$planId"))
    }

    /** A subset permutation changes only its slots. New remote suffix appends remain in place. */
    fun reorder(state: SyncState, planId: String, orderedTaskIds: List<String>, expected: TimelineOrderBaseline): SyncState {
        staleIf(expected.planId != planId)
        guardPlan(state, planId, expected.planningZone, expected.planExists)
        require(orderedTaskIds.distinct().size == orderedTaskIds.size && expected.orderedTaskIds.containsAll(orderedTaskIds)) { "任务顺序无效" }
        val selected = orderedTaskIds.toSet()
        val iterator = orderedTaskIds.iterator()
        val desired = expected.orderedTaskIds.map {if (it in selected) iterator.next() else it}
        val old = state.items["day_plan:$planId"] ?: return state
        val current = old.content().timelineIds()
        // Only suffix appends are safe: an insertion, deletion or reorder must be reviewed first.
        val suffix = current.drop(expected.orderedTaskIds.size)
        staleIf(current.size < expected.orderedTaskIds.size || suffix.any {it in expected.orderedTaskIds})
        val prefix = current.take(expected.orderedTaskIds.size)
        if (prefix == desired) return state
        staleIf(prefix != expected.orderedTaskIds)
        val content = old.content()!!.toMutableMap()
        content["ordered_task_ids"] = JsonArray((desired + suffix).map(::JsonPrimitive))
        return writePlan(state, planId, content)
    }

    private fun guardSchedule(state: SyncState, taskId: String, planId: String, expected: TimelineScheduleBaseline): TimelineScheduleBaseline {
        staleIf(expected.planId != planId || expected.taskId != taskId)
        guardPlan(state, planId, expected.planningZone, expected.planExists)
        val task = state.items["task:$taskId"] ?: throw StaleContentEditException(StaleEditReason.DELETED)
        guardEntity(task)
        check(timelineActiveTask(task)) { "任务已完成或归档，时间安排未修改" }
        staleIf(!expected.taskExists || task.title() != expected.taskTitle)
        return TimelineScheduleBaseline.from(state, planId, taskId)
    }

    private fun requireScheduleUnchanged(current: TimelineScheduleBaseline, expected: TimelineScheduleBaseline) {
        staleIf(current.scheduledAt != expected.scheduledAt || current.scheduledMinutes != expected.scheduledMinutes ||
            current.reminderLeadMinutes != expected.reminderLeadMinutes || current.included != expected.included)
    }

    /** Older Mac plans can hold a future schedule. Normalize only on an explicit edit, atomically. */
    private fun migrateLegacy(state: SyncState, taskId: String, expected: TimelineScheduleBaseline,
        current: TimelineScheduleBaseline, start: Instant?, durationMinutes: Int?, reminderLeadMinutes: Int?): SyncState {
        val target = requireNotNull(expected.canonical)
        guardPlan(state, target.planId, expected.planningZone, target.planExists)
        val targetEntry = timelineEntry(state, target.planId, taskId)
        val targetIncluded = taskId in state.items["day_plan:${target.planId}"]?.content().timelineIds()
        val capturedPeers = expected.legacyPeers.associateBy {it.planId}
        // A newly discovered snapshot could be newer than the dialog or add history we have
        // not guarded. Do not normalize an outdated source or leave an uncaptured ghost behind.
        val discoveredPeers = timelineLegacyPeers(state, taskId, expected.planId.substringAfter('@'))
            .filter {it.planId != expected.planId}
        staleIf(discoveredPeers.any {it.planId !in capturedPeers})
        val peerEntries = expected.legacyPeers.associate {peer ->
            guardPlan(state, peer.planId, expected.planningZone, expectedExists = true)
            peer.planId to timelineEntry(state, peer.planId, taskId)
        }
        val sourceCleared = current.scheduledAt == null && current.scheduledMinutes == null && current.reminderLeadMinutes == null
        val targetMatches = targetEntry != null && targetIncluded && targetEntry.timelineString("scheduled_at") == start?.toString() &&
            targetEntry.timelineInt("scheduled_minutes") == durationMinutes && targetEntry.timelineInt("schedule_reminder_lead_minutes") == reminderLeadMinutes
        // A persisted success may be retried after rotation/reload. Never write either plan again.
        val peersCleared = peerEntries.values.all {entry -> entry != null && scheduleIsCleared(entry)}
        if (sourceCleared && current.included == expected.included && targetMatches && peersCleared) return state
        requireScheduleUnchanged(current, expected)
        staleIf(targetEntry != target.entry || targetIncluded != target.included)
        expected.legacyPeers.forEach {peer ->
            val entry = peerEntries[peer.planId]
            staleIf(entry?.timelineString("scheduled_at") != peer.scheduledAt || entry.timelineInt("scheduled_minutes") != peer.scheduledMinutes ||
                entry.timelineInt("schedule_reminder_lead_minutes") != peer.reminderLeadMinutes)
        }
        val sourceEntry = requireNotNull(timelineEntry(state, expected.planId, taskId))
        // Target-specific allocation/unknown values take precedence; missing fields are copied.
        val merged = sourceEntry.toMutableMap().apply {targetEntry?.let {putAll(it)}}
        if (start == null) {
            merged.remove("scheduled_at"); merged.remove("scheduled_minutes"); merged.remove("schedule_reminder_lead_minutes")
        } else {
            merged["scheduled_at"] = JsonPrimitive(start.toString())
            merged["scheduled_minutes"] = JsonPrimitive(requireNotNull(durationMinutes))
            if (reminderLeadMinutes == null) merged.remove("schedule_reminder_lead_minutes")
            else merged["schedule_reminder_lead_minutes"] = JsonPrimitive(reminderLeadMinutes)
        }
        var result = updateEntry(state, taskId, target.planId, include = true) {entry -> entry.clear(); entry.putAll(merged)}
        result = updateEntry(result, taskId, expected.planId, include = false) {
            it.remove("scheduled_at"); it.remove("scheduled_minutes"); it.remove("schedule_reminder_lead_minutes")
        }
        expected.legacyPeers.forEach {peer ->
            result = updateEntry(result, taskId, peer.planId, include = false) {
                it.remove("scheduled_at"); it.remove("scheduled_minutes"); it.remove("schedule_reminder_lead_minutes")
            }
        }
        val keys = setOf("day_plan:${expected.planId}", "day_plan:${target.planId}") + expected.legacyPeers.map {"day_plan:${it.planId}"}
        val grouped = StateMachine.group(result, keys)
        val groupId = grouped.items.getValue("day_plan:${expected.planId}").group
        // Include inherited dirty groups, not just the plans edited here. The repository only
        // commits a returned state; throwing now leaves all source, target and peer data intact.
        require(grouped.items.values.count {it.dirty && it.group == groupId} <= 100) { "这次迁移关联的同步内容超过100条，尚未修改任何安排" }
        return grouped
    }

    private fun scheduleIsCleared(entry: JsonObject): Boolean = entry.timelineString("scheduled_at") == null &&
        entry.timelineInt("scheduled_minutes") == null && entry.timelineInt("schedule_reminder_lead_minutes") == null

    private fun guardPlan(state: SyncState, planId: String, planningZone: String, expectedExists: Boolean) {
        val day = TimelineDay.fromPlanId(planId)
        staleIf(timelinePlanningZone(state) != planningZone)
        state.items["profile:profile"]?.let {check(it.conflict == null && it.conflictGroup.isEmpty()) { "请先处理规划时区的同步冲突" }}
        val plan = state.items["day_plan:$planId"]
        if (plan == null) {
            if (expectedExists) throw StaleContentEditException(StaleEditReason.DELETED)
            return
        }
        guardEntity(plan)
        staleIf(plan.content()?.timelineString("day_key") != day.date.toString() || plan.content()?.timelineString("time_zone") != day.zone.id)
    }

    private fun guardEntity(entity: Entity) {
        if (entity.deleted || entity.content() == null) throw StaleContentEditException(StaleEditReason.DELETED)
        check(entity.conflict == null && entity.conflictGroup.isEmpty()) { "请先处理这条内容的同步冲突，时间安排未修改" }
    }

    private fun updateEntry(state: SyncState, taskId: String, planId: String, include: Boolean,
        change: (MutableMap<String, JsonElement>) -> Unit): SyncState {
        val content = state.items["day_plan:$planId"]?.content()?.toMutableMap() ?: mutableMapOf(
            "day_key" to JsonPrimitive(planId.substringBefore('@')), "time_zone" to JsonPrimitive(planId.substringAfter('@')),
            "ordered_task_ids" to JsonArray(emptyList()), "task_plans" to JsonArray(emptyList()))
        if (include) content["ordered_task_ids"] = JsonArray((JsonObject(content).timelineIds() + taskId).distinct().map(::JsonPrimitive))
        val entries = (content["task_plans"] as? JsonArray).orEmpty().toMutableList()
        val index = entries.indexOfFirst {(it as? JsonObject)?.timelineString("task_id") == taskId}
        val entry = if (index < 0) mutableMapOf<String, JsonElement>("task_id" to JsonPrimitive(taskId)) else entries[index].jsonObject.toMutableMap()
        change(entry)
        if (index < 0) entries.add(JsonObject(entry)) else entries[index] = JsonObject(entry)
        content["task_plans"] = JsonArray(entries)
        return writePlan(state, planId, content)
    }

    private fun writePlan(state: SyncState, planId: String, content: Map<String, JsonElement>): SyncState {
        val payload = state.items["day_plan:$planId"]?.payload.orEmpty().toMutableMap().apply {put("content", JsonObject(content))}
        return StateMachine.edit(state, "day_plan", planId, JsonObject(payload))
    }

    private fun staleIf(condition: Boolean) {if (condition) throw StaleContentEditException(StaleEditReason.UPDATED)}
}

private fun JsonObject?.timelineIds(): List<String> = (this?.get("ordered_task_ids") as? JsonArray).orEmpty()
    .mapNotNull {(it as? JsonPrimitive)?.contentOrNull}

private fun timelineEntry(state: SyncState, planId: String, taskId: String): JsonObject? =
    (state.items["day_plan:$planId"]?.takeUnless {it.deleted}?.content()?.get("task_plans") as? JsonArray).orEmpty()
        .filterIsInstance<JsonObject>().firstOrNull {it.timelineString("task_id") == taskId}

private fun timelineLegacyPeers(state: SyncState, taskId: String, zone: String): List<TimelineLegacyPeerBaseline> =
    state.items.values.filter {it.kind == "day_plan" && !it.deleted && it.id.substringAfter('@') == zone}.mapNotNull {plan ->
        val entry = timelineEntry(state, plan.id, taskId) ?: return@mapNotNull null
        val value = entry.timelineString("scheduled_at") ?: return@mapNotNull null
        val sourceDay = runCatching {TimelineDay.fromPlanId(plan.id)}.getOrNull() ?: return@mapNotNull null
        val startDay = runCatching {Instant.parse(value).atZone(sourceDay.zone).toLocalDate()}.getOrNull() ?: return@mapNotNull null
        if (startDay == sourceDay.date) return@mapNotNull null
        TimelineLegacyPeerBaseline(plan.id, value, entry.timelineInt("scheduled_minutes"), entry.timelineInt("schedule_reminder_lead_minutes"))
    }.sortedBy {it.planId}
