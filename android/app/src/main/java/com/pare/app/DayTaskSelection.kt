package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*

/** Selecting existing work changes membership only; it creates neither effort nor a calendar block. */
object DayTaskSelection {
    fun available(state: SyncState, planId: String): List<Entity> {
        val selected = selectedIds(state, planId)
        return (0..3).flatMap { quadrant ->
            OrganizeActions.tasks(state, quadrant).filter {
                it.conflict == null && it.conflictGroup.isEmpty() && it.id !in selected
            }
        }
    }

    fun contains(state: SyncState, taskId: String, planId: String): Boolean = taskId in selectedIds(state, planId)

    fun add(state: SyncState, taskId: String, planId: String, now: Instant): SyncState {
        val day = TimelineDay.fromPlanId(planId)
        if (day.zone.id != ContentActions.planId(state, now).substringAfter('@')) {
            throw StaleContentEditException(StaleEditReason.UPDATED)
        }
        state.items["profile:profile"]?.let {
            check(it.conflict == null && it.conflictGroup.isEmpty()) { "请先处理规划时区的同步冲突" }
        }
        val task = state.items["task:$taskId"] ?: throw StaleContentEditException(StaleEditReason.DELETED)
        guardEntity(task)
        check(timelineActiveTask(task)) { "任务已完成或归档，请重新选择" }
        val old = state.items["day_plan:$planId"]
        old?.let {
            guardEntity(it)
            if (it.kind != "day_plan" || it.content()?.timelineString("day_key") != day.date.toString() ||
                it.content()?.timelineString("time_zone") != day.zone.id) {
                throw StaleContentEditException(StaleEditReason.UPDATED)
            }
        }
        if (contains(state, taskId, planId)) return state
        val content = old?.content()?.toMutableMap() ?: mutableMapOf(
            "day_key" to JsonPrimitive(day.date.toString()), "time_zone" to JsonPrimitive(day.zone.id),
            "task_plans" to JsonArray(emptyList()))
        // Preserve order and every existing field, including unknown fields and existing allocations.
        content["ordered_task_ids"] = JsonArray((content["ordered_task_ids"] as? JsonArray).orEmpty() + JsonPrimitive(taskId))
        val payload = old?.payload.orEmpty().toMutableMap().apply { put("content", JsonObject(content)) }
        return StateMachine.edit(state, "day_plan", planId, JsonObject(payload))
    }

    private fun selectedIds(state: SyncState, planId: String): Set<String> {
        val day = TimelineDay.fromPlanId(planId)
        val ordered = (state.items["day_plan:$planId"]?.takeUnless { it.deleted }?.content()?.get("ordered_task_ids") as? JsonArray)
            .orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return ordered.toSet() + TimelineProjection.project(state, day).blocks.map { it.task.id }
    }

    private fun guardEntity(entity: Entity) {
        if (entity.deleted || entity.content() == null) throw StaleContentEditException(StaleEditReason.DELETED)
        check(entity.conflict == null && entity.conflictGroup.isEmpty()) { "请先处理这条内容的同步冲突，再选择任务" }
    }
}
