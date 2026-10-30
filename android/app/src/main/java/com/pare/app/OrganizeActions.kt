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
