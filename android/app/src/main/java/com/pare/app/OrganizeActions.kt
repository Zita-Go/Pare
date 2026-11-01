package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*

/** Pure state transition; the engine persists this complete group in a single local edit. */
internal fun organizeInbox(state: SyncState, expected: Entity, quadrant: Int, addToToday: Boolean,
    taskId: String, now: Instant): SyncState {
    require(quadrant in 0..3) { "请选择象限" }
    val current = state.items[expected.key]
    require(current != null && current.kind == "inbox" && !current.deleted) { "这条内容已不可用，请关闭后重试" }
    require(current.payload == expected.payload) { "内容已更新，请关闭后重新整理" }
    require(state.items["task:$taskId"] == null) { "任务已存在，请重新整理" }
    val payload = ContentActions.save(null, "task", current.title(), "", quadrant, taskId, now).toMutableMap()
    val content = payload.getValue("content").jsonObject.toMutableMap()
    current.content()?.get("suggestedRemindAt")?.let { content["remindAt"] = it }
    payload["content"] = JsonObject(content)
    var next = StateMachine.edit(state, "task", taskId, JsonObject(payload))
    next = StateMachine.edit(next, "inbox", current.id, null, true)
    val keys = mutableSetOf("task:$taskId", current.key)
    if (addToToday) {
        val planId = ContentActions.planId(next, now)
        val old = next.items["day_plan:$planId"]?.takeUnless { it.deleted }
        val plan = old?.content()?.toMutableMap() ?: mutableMapOf(
            "day_key" to JsonPrimitive(planId.substringBefore('@')),
            "time_zone" to JsonPrimitive(planId.substringAfter('@')),
            "ordered_task_ids" to JsonArray(emptyList()), "task_plans" to JsonArray(emptyList()))
        val ids = (plan["ordered_task_ids"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        plan["ordered_task_ids"] = JsonArray((ids + taskId).distinct().map { JsonPrimitive(it) })
        next = StateMachine.edit(next, "day_plan", planId,
            JsonObject((old?.payload ?: JsonObject(emptyMap())).toMutableMap().apply { put("content", JsonObject(plan)) }))
        keys += "day_plan:$planId"
    }
    return StateMachine.group(next, keys)
}

/** Only ordering inputs belong in the drag baseline; new notes and history can merge. */
data class OrganizeTaskPosition(val taskId: String, val quadrant: Int, val order: JsonElement?, val createdAt: JsonElement?)

data class OrganizeMoveBaseline(
    val owner: String?, val device: String, val taskId: String, val sourceQuadrant: Int,
    val quadrants: Map<Int, List<OrganizeTaskPosition>>
) {
    companion object {
        fun from(state: SyncState, taskId: String): OrganizeMoveBaseline {
            val source = organizeCurrent(state, taskId)
            return OrganizeMoveBaseline(state.owner, state.device, taskId, organizeQuadrant(source),
                (0..3).associateWith { quadrant -> OrganizeActions.tasks(state, quadrant).map(::organizePosition) })
        }
    }
}

object OrganizeActions {
    /**
     * quadrantOrder is an additive v1 content field. Mac preserves it as an unknown
     * field but currently renders its own quadrant order; todayOrder belongs to the
     * day plan and must never be reused here. Existing tasks stay newest first.
     */
    fun tasks(state: SyncState, quadrant: Int): List<Entity> {
        require(quadrant in 0..3) { "请选择象限" }
        return state.items.values.filter { organizeActive(it) && organizeQuadrant(it) == quadrant }
            .sortedWith(compareBy<Entity>(::organizeRank).thenByDescending(::organizeCreatedAt).thenBy { it.id })
    }

    /** Move before an active task in the destination; null appends (also into an empty quadrant). */
    fun move(state: SyncState, taskId: String, targetQuadrant: Int, beforeTaskId: String?,
        expected: OrganizeMoveBaseline, now: Instant): SyncState {
        require(targetQuadrant in 0..3) { "请选择象限" }
        check(state.owner == expected.owner && state.device == expected.device && taskId == expected.taskId) {
            "资料库已改变，请重新拖动任务"
        }
        val source = organizeCurrent(state, taskId)
        val sourceQuadrant = organizeQuadrant(source)
        check(sourceQuadrant == expected.sourceQuadrant) { "任务象限已更新，请重新拖动" }
        // Validate at commit time, before even an apparent no-op. Changes elsewhere
        // and sync receipts do not invalidate a drag; membership/order changes do.
        for (quadrant in setOf(sourceQuadrant, targetQuadrant)) {
            val current = tasks(state, quadrant)
            check(current.map(::organizePosition) == expected.quadrants[quadrant]) { "任务顺序已更新，请重新拖动" }
            current.forEach { organizeCurrent(state, it.id) }
        }
        if (beforeTaskId == taskId) {
            require(sourceQuadrant == targetQuadrant) { "目标任务不在这个象限" }
            return state
        }
        val oldDestination = tasks(state, targetQuadrant)
        val destination = oldDestination.filterNot { it.id == taskId }.toMutableList()
        val index = if (beforeTaskId == null) destination.size else destination.indexOfFirst { it.id == beforeTaskId }
        require(index >= 0) { "目标任务已不可用，请重新拖动" }
        destination.add(index, source)
        if (sourceQuadrant == targetQuadrant && destination.map { it.id } == oldDestination.map { it.id }) return state

        val lower = destination.getOrNull(index - 1)?.let(::organizeRank)
        val upper = destination.getOrNull(index + 1)?.let(::organizeRank)
        val previousRank = organizeRank(source)
        val rank = if ((lower == null || previousRank > lower) && (upper == null || previousRank < upper)) previousRank
            else organizeRankBetween(lower, upper)
        // Sparse ranks keep ordinary moves to one task. If adjacent ranks have no
        // gap, compact this destination only; never reorder the source or day plan.
        val ranks = if (rank != null) mapOf(taskId to rank)
            else destination.mapIndexed { position, task -> task.id to position * 1024L }.toMap()
        var result = state
        val changed = mutableSetOf<String>()
        for (task in destination) {
            val content = task.content()!!.toMutableMap()
            ranks[task.id]?.let { value ->
                if (value != organizeRank(task)) content["quadrantOrder"] = JsonPrimitive(value)
            }
            if (task.id == taskId && sourceQuadrant != targetQuadrant) {
                content["quadrant"] = JsonPrimitive(targetQuadrant)
                content["lastProgressAt"] = JsonPrimitive(now.toString())
            }
            if (JsonObject(content) != task.content()) {
                result = libraryReplace(result, task, content)
                changed += task.key
            }
        }
        if (changed.isEmpty()) return state
        result = StateMachine.group(result, changed)
        // Include inherited dirty siblings, even when a previous request is still
        // pending. Otherwise a successful local drop could leave an unsendable group.
        val group = result.items.getValue(changed.first()).group
        val members = result.items.values.filter { it.dirty && it.group == group }
        require(members.none { it.conflict != null || it.conflictGroup.isNotEmpty() }) { "请先处理相关内容的同步冲突" }
        require(members.size <= 100) { "这次调整涉及的任务超过同步上限，请先同步后重试" }
        val mutations = members.map { Mutation(it.kind, it.id, it.revision, deleted = it.deleted, payload = it.payload) }
        require(wire.encodeToString(mutations).toByteArray(Charsets.UTF_8).size <= 1_048_576) { "这次调整内容超过同步上限，请先同步后重试" }
        return result
    }
}

private fun organizeActive(task: Entity) = task.kind == "task" && !task.deleted && task.content() != null &&
    !task.completed() && !LibrarySearch.archived(task)

private fun organizeCurrent(state: SyncState, id: String): Entity = libraryCurrent(state, "task", id).also {
    require(organizeActive(it)) { "任务已完成或归档，未调整顺序" }
}

private fun organizeQuadrant(task: Entity) = (task.content()?.get("quadrant") as? JsonPrimitive)?.intOrNull ?: 1

private fun organizePosition(task: Entity) = OrganizeTaskPosition(task.id, organizeQuadrant(task),
    task.content()?.get("quadrantOrder"), task.content()?.get("createdAt"))

private fun organizeCreatedAt(task: Entity): Instant = (task.content()?.get("createdAt") as? JsonPrimitive)?.contentOrNull
    ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH

private fun organizeRank(task: Entity): Long = (task.content()?.get("quadrantOrder") as? JsonPrimitive)
    ?.takeUnless { it.isString }?.longOrNull
    ?: runCatching { Math.negateExact(organizeCreatedAt(task).toEpochMilli()) }.getOrDefault(0L)

private fun organizeRankBetween(lower: Long?, upper: Long?): Long? = when {
    lower == null && upper == null -> 0L
    lower == null -> upper!!.takeIf { it >= Long.MIN_VALUE + 1024 }?.minus(1024)
    upper == null -> lower.takeIf { it <= Long.MAX_VALUE - 1024 }?.plus(1024)
    lower >= upper -> null
    // Overflow-safe floor average, including ranks on opposite sides of zero.
    else -> ((lower and upper) + ((lower xor upper) shr 1)).takeIf { it > lower && it < upper }
}
