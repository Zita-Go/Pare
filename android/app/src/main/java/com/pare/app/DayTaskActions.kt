package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*

/** Explicit values make repeated save callbacks safe, while dated plans remain authoritative. */
object DayTaskActions {
    private fun plan(state: SyncState, taskId: String, planId: String, now: Instant, adding: Boolean): Pair<Entity?, MutableMap<String, JsonElement>> {
        val day = TimelineDay.fromPlanId(planId)
        require(day.zone.id == ContentActions.planId(state, now).substringAfter('@')) { "规划时区已改变，请重新打开这一天" }
        state.items["profile:profile"]?.let { require(it.conflict == null && it.conflictGroup.isEmpty()) { "请先处理规划时区的同步冲突" } }
        val task = state.items["task:$taskId"]
        require(task != null && !task.deleted && task.content() != null && task.conflict == null && task.conflictGroup.isEmpty()) { "任务已不可用或存在冲突，请查看最新内容" }
        // Existing Android task details can keep a completed task in the day's
        // history; adding membership must not silently change task completion.
        if (adding) require(task.payload?.get("lifecycle") != JsonPrimitive("archived")) { "任务已归档，请重新选择" }
        val old = state.items["day_plan:$planId"]
        require(old == null || (!old.deleted && old.content() != null && old.conflict == null && old.conflictGroup.isEmpty())) { "日计划已删除或有同步冲突，请先查看最新内容" }
        val content = old?.content()?.toMutableMap() ?: mutableMapOf(
            "day_key" to JsonPrimitive(day.date.toString()), "time_zone" to JsonPrimitive(day.zone.id),
            "ordered_task_ids" to JsonArray(emptyList()), "task_plans" to JsonArray(emptyList()))
        return old to content
    }

    private fun save(state: SyncState, old: Entity?, planId: String, content: Map<String, JsonElement>): SyncState {
        if (old?.content() == JsonObject(content)) return state
        return StateMachine.edit(state, "day_plan", planId,
            JsonObject(old?.payload.orEmpty().toMutableMap().apply { put("content", JsonObject(content)) }))
    }

    fun setIncluded(state: SyncState, taskId: String, included: Boolean, planId: String, now: Instant): SyncState {
        val (old, content) = plan(state, taskId, planId, now, adding = included)
        val ids = (content["ordered_task_ids"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        if (included) {
            content["ordered_task_ids"] = JsonArray((ids + taskId).distinct().map(::JsonPrimitive))
        } else {
            if (old == null) return state
            content["ordered_task_ids"] = JsonArray(ids.filterNot { it == taskId }.map(::JsonPrimitive))
            if ((content["daily_win_task_id"] as? JsonPrimitive)?.contentOrNull == taskId) content.remove("daily_win_task_id")
            val day = TimelineDay.fromPlanId(planId)
            (content["task_plans"] as? JsonArray)?.let { plans ->
                content["task_plans"] = JsonArray(plans.map { value ->
                    val entry = value.jsonObject
                    if (entry["task_id"]?.jsonPrimitive?.contentOrNull != taskId) value else {
                        val at = entry["scheduled_at"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
                        // Older Mac plans may carry another date's schedule; removing this day's
                        // commitment must not erase that other date's arrangement or allocation.
                        if (at != null && at.atZone(day.zone).toLocalDate() != day.date) value
                        else JsonObject(entry.filterKeys { it !in setOf("allocation", "scheduled_at", "scheduled_minutes", "schedule_reminder_lead_minutes") })
                    }
                })
            }
        }
        return save(state, old, planId, content)
    }

    fun setWin(state: SyncState, taskId: String, win: Boolean, planId: String, now: Instant): SyncState {
        val (old, content) = plan(state, taskId, planId, now, adding = win)
        if (win) {
            val ids = (content["ordered_task_ids"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            content["ordered_task_ids"] = JsonArray((ids + taskId).distinct().map(::JsonPrimitive))
            content["daily_win_task_id"] = JsonPrimitive(taskId)
        } else {
            if ((content["daily_win_task_id"] as? JsonPrimitive)?.contentOrNull != taskId) return state
            content.remove("daily_win_task_id")
        }
        return save(state, old, planId, content)
    }
}
