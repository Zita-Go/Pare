package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*

data class TaskMetadataBaseline(val dueAt: JsonElement?, val actualMinutes: JsonElement?, val progressSessions: JsonElement?) {
    companion object { fun from(task: Entity) = TaskMetadataBaseline(task.content()?.get("dueAt"), task.content()?.get("actualMinutes"), task.content()?.get("progressSessions")) }
}

object TaskMetadataActions {
    fun save(state: SyncState, taskId: String, expected: TaskMetadataBaseline, dueAt: Instant?, actualMinutes: Int?, now: Instant): SyncState {
        require(actualMinutes == null || actualMinutes in 0..1_000_000) { "实际投入请填写 0–1000000 分钟，留空可清除" }
        val task = libraryCurrent(state, "task", taskId)
        val current = TaskMetadataBaseline.from(task)
        val dueValue = dueAt?.let { JsonPrimitive(it.toString()) }
        val actualValue = actualMinutes?.takeIf { it > 0 }?.let { JsonPrimitive(it) }
        fun normalized(value: JsonElement?) = value?.takeUnless { it == JsonNull }
        val dueChanged = dueValue != normalized(expected.dueAt)
        val actualChanged = actualValue != normalized(expected.actualMinutes)
        if ((dueChanged && normalized(current.dueAt) != normalized(expected.dueAt)) ||
            (actualChanged && (normalized(current.actualMinutes) != normalized(expected.actualMinutes) || current.progressSessions != expected.progressSessions))) {
            throw StaleContentEditException(StaleEditReason.UPDATED)
        }
        if (!dueChanged && !actualChanged) return state
        return libraryReplace(state, task, task.content()!!.toMutableMap().apply {
            if (dueChanged) { if (dueValue == null) remove("dueAt") else put("dueAt", dueValue) }
            if (actualChanged) {
                if (actualValue == null) remove("actualMinutes") else put("actualMinutes", actualValue)
                put("lastProgressAt", JsonPrimitive(now.toString()))
            }
            // remindAt, recurrence, recorded focus sessions and future fields are untouched.
        })
    }
}
