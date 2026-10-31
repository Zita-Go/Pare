package com.pare.app

import kotlinx.serialization.json.*
import java.time.Instant

internal fun JsonObject.stringValue(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun managedFields(content: JsonObject?, fields: Set<String>) = fields.associateWith { content?.get(it)?.takeUnless { value -> value == JsonNull } }
internal fun requireEditableEntity(entity: Entity?): Entity {
    require(entity != null && !entity.deleted && entity.payload != null) { "内容已删除或不可用，草稿已保留。" }
    require(entity.conflict == null && entity.conflictGroup.isEmpty()) { "内容存在同步冲突，请先处理冲突。草稿已保留。" }
    return entity
}
internal fun requireNoExistingEntity(entity: Entity?) {
    require(entity == null) { "内容已存在或已被删除，草稿已保留，请查看最新版本。" }
}

data class GoalDraft(
    val id: String = uid(), val title: String = "", val motivation: String = "",
    val targetDate: String? = null, val status: String = "active", val taskIds: List<String> = emptyList(),
    val newStep: String = "", val newTaskId: String = uid()
) {
    fun validationError(): String? = when {
        title.trim().isEmpty() || title.length > 150 -> "目标需填写 1–150 字"
        motivation.length > 600 -> "目标说明最多 600 字"
        status !in setOf("active", "paused", "achieved") -> "请选择有效的目标状态"
        targetDate != null && runCatching { Instant.parse(targetDate) }.isFailure -> "请选择有效日期"
        taskIds.distinct().size + (if (newStep.isBlank()) 0 else 1) > 100 -> "最多关联 100 个任务"
        newStep.length > 200 -> "下一步任务最多 200 字"
        else -> null
    }
    companion object {
        fun from(entity: Entity): GoalDraft {
            val c = requireNotNull(entity.content())
            return GoalDraft(entity.id,c.stringValue("title").orEmpty(),c.stringValue("motivation").orEmpty(),
                c.stringValue("targetDate"),c.stringValue("status") ?: "active",
                (c["taskIDs"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content })
        }
    }
}

data class GoalBaseline(val isNew: Boolean, val fields: Map<String, JsonElement?>) {
    companion object {
        private val keys = setOf("title","motivation","targetDate","status","taskIDs")
        fun from(entity: Entity?) = GoalBaseline(entity == null,managedFields(entity?.content(),keys))
    }
}

object GoalsActions {
    fun save(state: SyncState, draft: GoalDraft, expected: GoalBaseline, now: Instant): SyncState {
        draft.validationError()?.let { throw IllegalArgumentException(it) }
        val old = state.items["goal:${draft.id}"]
        // A retained create ID may be retried after local persistence succeeded but its acknowledgement
        // was interrupted. Recognize that exact intent without creating another goal or next step.
        if (expected.isNew && old != null) {
            requireEditableEntity(old)
            val current=GoalDraft.from(old)
            val wantedIds=draft.taskIds.distinct() + if(draft.newStep.isBlank()) emptyList() else listOf(draft.newTaskId)
            val same=current.title==draft.title.trim() && current.motivation==draft.motivation && current.targetDate==draft.targetDate &&
                current.status==draft.status && current.taskIds==wantedIds
            require(same) { "内容已存在，草稿已保留，请查看最新版本。" }
            if(draft.newStep.isNotBlank()) {
                val step=requireEditableEntity(state.items["task:${draft.newTaskId}"])
                require(step.title()==draft.newStep.trim()) { "关联行动已更新，请查看最新版本。" }
            }
            return state
        }
        if (expected.isNew) requireNoExistingEntity(old) else {
            requireEditableEntity(old)
            require(GoalBaseline.from(old) == expected) { "目标已在另一端更新，草稿已保留。请查看最新版本。" }
        }
        val linked = draft.taskIds.distinct().toMutableList()
        linked.forEach { id -> requireEditableEntity(state.items["task:$id"]) }
        val c: MutableMap<String,JsonElement> = old?.content()?.toMutableMap() ?: mutableMapOf(
            "id" to JsonPrimitive(draft.id.lowercase()), "createdAt" to JsonPrimitive(now.toString()))
        c["title"] = JsonPrimitive(draft.title.trim())
        c["motivation"] = JsonPrimitive(draft.motivation)
        c["status"] = JsonPrimitive(draft.status)
        if (draft.targetDate == null) c.remove("targetDate") else c["targetDate"] = JsonPrimitive(draft.targetDate)
        val createStep = draft.newStep.isNotBlank()
        if (createStep) {
            requireNoExistingEntity(state.items["task:${draft.newTaskId}"])
            require(draft.newTaskId !in linked) { "下一步任务标识重复" }
            linked += draft.newTaskId
        }
        c["taskIDs"] = JsonArray(linked.map(::JsonPrimitive))
        require(old == null || c != old.content()) { "没有需要保存的修改" }
        c["updatedAt"] = JsonPrimitive(now.toString())
        val payload = JsonObject(old?.payload.orEmpty() + ("content" to JsonObject(c)))
        PayloadValidation.validate("goal", draft.id, payload)
        var next = StateMachine.edit(state,"goal",draft.id,payload)
        if (createStep) {
            val task = ContentActions.save(null,"task",draft.newStep.trim(),"",1,draft.newTaskId,now)
            PayloadValidation.validate("task",draft.newTaskId,task)
            next = StateMachine.edit(next,"task",draft.newTaskId,task)
            next = StateMachine.group(next,setOf("goal:${draft.id}","task:${draft.newTaskId}"))
            val group=next.items.getValue("goal:${draft.id}").group
            require(next.items.values.count {it.dirty && it.group==group}<=100) {
                "未同步的关联行动已达到单次同步上限，请先同步后再新增。草稿已保留。"
            }
        }
        return next
    }

    /** Deleting a goal never deletes its linked actions. The confirmation targets exactly the shown version. */
    fun delete(state: SyncState, expected: Entity): SyncState {
        val current = requireEditableEntity(state.items[expected.key])
        require(current.kind == "goal" && current.payload == expected.payload) { "目标已更新，请重新查看后再删除。" }
        return LibraryActions.delete(state,"goal",expected.id,LibraryBaseline.from(expected))
    }
}
