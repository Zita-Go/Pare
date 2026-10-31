package com.pare.app

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*

typealias LibraryEdit = ((SyncState) -> SyncState, (Result<Unit>) -> Unit) -> Unit

internal fun libraryCurrent(state: SyncState, kind: String, id: String): Entity {
    val item = state.items["$kind:$id"]
    require(item != null && !item.deleted && item.content() != null) { "内容已不可用，未保存修改" }
    require(item.conflict == null && item.conflictGroup.isEmpty() && state.items.values.none {
        item.group.isNotEmpty() && it.group == item.group && it.conflict != null
    }) { "请先处理相关内容的同步冲突" }
    return item
}

internal fun libraryReplace(state: SyncState, item: Entity, content: Map<String, JsonElement>): SyncState =
    StateMachine.edit(state, item.kind, item.id, JsonObject(item.payload!!.toMutableMap().apply { put("content", JsonObject(content)) }))

data class LibraryBaseline(val revision: Long, val payload: JsonObject?) {
    companion object { fun from(item: Entity) = LibraryBaseline(item.revision, item.payload) }
    fun check(item: Entity) {
        // Acknowledging this device's unchanged payload may advance revision while a
        // confirmation is open. Lifecycle/deletion/conflict guards live at the action boundary.
        if (item.payload != payload) throw StaleContentEditException(StaleEditReason.UPDATED)
    }
}

/** A local tombstone is restorable only while it is still this exact deletion.
 * Each deletion gets a fresh mutation group, preventing delete/restore/delete ABA.
 * A sync acknowledgement invalidates this short-lived undo, rather than guessing
 * whether a later tombstone represents this device's deletion or another edit. */
data class LibraryDeletionUndo(val owner: String?, val device: String, val before: Entity, val tombstone: Entity) {
    fun restore(state: SyncState): SyncState {
        require(state.owner == owner && state.device == device) { "资料库已改变，无法恢复这次删除" }
        require(state.items[before.key] == tombstone && tombstone.deleted && tombstone.conflict == null) {
            "删除状态已同步或再次改变，未覆盖新版本"
        }
        val retained = state.localTrash.firstOrNull { it.before.key == before.key && it.tombstone == tombstone }
        require(retained != null) { "回收记录已清理，不能再撤销" }
        return LocalTrashActions.restoreOriginal(state, retained.id)
    }
}

object LibraryActions {
    fun delete(state: SyncState, kind: String, id: String, expected: LibraryBaseline, now: Instant = Instant.now()): SyncState {
        require(kind in setOf("task", "inbox", "idea", "goal"))
        val item = libraryCurrent(state, kind, id)
        expected.check(item)
        // Links and past day plans remain as historical references, just as on Mac.
        val deleted = StateMachine.group(StateMachine.edit(state, kind, id, null, deleted = true), setOf(item.key))
        return LocalTrashActions.retain(state, deleted, item.key, now)
    }

    fun deletionUndo(before: SyncState, after: SyncState, key: String): LibraryDeletionUndo {
        val old = requireNotNull(before.items[key])
        val deleted = requireNotNull(after.items[key])
        require(!old.deleted && deleted.deleted && deleted.payload == null)
        return LibraryDeletionUndo(before.owner, before.device, old, deleted)
    }

    fun archive(state: SyncState, id: String, expected: LibraryBaseline): SyncState {
        val task = libraryCurrent(state, "task", id)
        expected.check(task)
        require(task.payload?.get("lifecycle") != JsonPrimitive("archived")) { "任务已经归档" }
        return StateMachine.edit(state, "task", id, JsonObject(task.payload!!.toMutableMap().apply { put("lifecycle", JsonPrimitive("archived")) }))
    }

    fun restoreArchive(state: SyncState, id: String, expected: LibraryBaseline, now: Instant): SyncState {
        val task = libraryCurrent(state, "task", id)
        expected.check(task)
        require(task.payload?.get("lifecycle") == JsonPrimitive("archived")) { "任务已恢复或发生变化" }
        val content = task.content()!!.toMutableMap().apply {
            put("completed", JsonPrimitive(false)); remove("completedAt")
            put("lastProgressAt", JsonPrimitive(now.toString()))
            listOf("isToday", "todayOrder", "isDailyWin", "scheduledAt", "scheduledMinutes", "scheduleReminderLeadMinutes").forEach(::remove)
        }
        var result = StateMachine.edit(state, "task", id, JsonObject(task.payload!!.toMutableMap().apply {
            put("lifecycle", JsonPrimitive("active")); put("content", JsonObject(content))
        }))
        val keys = mutableSetOf(task.key)
        // Restoring from history does not silently re-add the task to today's/future
        // commitments. Past plans, focus sessions, links, and unknown fields survive.
        for (plan in state.items.values.filter { it.kind == "day_plan" && !it.deleted }) {
            val today = now.atZone(ZoneId.of(plan.id.substringAfter('@'))).toLocalDate().toString()
            if (plan.id.substringBefore('@') < today) continue
            val c = plan.content() ?: continue
            val ids = (c["ordered_task_ids"] as? JsonArray).orEmpty()
            val plans = (c["task_plans"] as? JsonArray).orEmpty()
            if (ids.none { it == JsonPrimitive(id) } && plans.none { (it as? JsonObject)?.get("task_id") == JsonPrimitive(id) } && c["daily_win_task_id"] != JsonPrimitive(id)) continue
            libraryCurrent(state, "day_plan", plan.id)
            result = libraryReplace(result, plan, c.toMutableMap().apply {
                put("ordered_task_ids", JsonArray(ids.filterNot { it == JsonPrimitive(id) }))
                put("task_plans", JsonArray(plans.filterNot { (it as? JsonObject)?.get("task_id") == JsonPrimitive(id) }))
                if (get("daily_win_task_id") == JsonPrimitive(id)) remove("daily_win_task_id")
            })
            keys += plan.key
        }
        return StateMachine.group(result, keys)
    }
}

enum class LibraryScope(val label: String) { ALL("全部"), TASKS("任务"), INBOX("随手记"), IDEAS("想法"), GOALS("目标"), DEADLINES("截止日期"), COMPLETED("已完成"), ARCHIVED("已归档"), TRASH("本机回收站") }

object LibrarySearch {
    fun title(item: Entity): String = (item.content()?.get(if (item.kind == "goal") "title" else "text") as? JsonPrimitive)?.contentOrNull ?: item.id
    fun archived(item: Entity): Boolean = item.payload?.get("lifecycle") == JsonPrimitive("archived") || (item.kind == "idea" && item.content()?.get("stage") == JsonPrimitive("archived"))
    fun deadline(item: Entity): Instant? = (item.content()?.get("dueAt") as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
    fun find(state: SyncState, query: String = "", scope: LibraryScope = LibraryScope.ALL, ideaStage: String? = null): List<Entity> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return state.items.values.filter { item ->
            if (item.deleted || item.content() == null || item.kind !in setOf("task", "inbox", "idea", "goal")) return@filter false
            val c = item.content()!!
            val matchesScope = when (scope) {
                LibraryScope.ALL -> true
                LibraryScope.TASKS -> item.kind == "task" && !archived(item) && !item.completed()
                LibraryScope.INBOX -> item.kind == "inbox"
                LibraryScope.IDEAS -> item.kind == "idea"
                LibraryScope.GOALS -> item.kind == "goal"
                LibraryScope.DEADLINES -> item.kind == "task" && !archived(item) && !item.completed() && deadline(item) != null
                LibraryScope.COMPLETED -> item.kind == "task" && item.completed()
                LibraryScope.ARCHIVED -> archived(item)
                LibraryScope.TRASH -> false
            }
            val stageMatches = scope != LibraryScope.IDEAS || ideaStage == null || (c["stage"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifEmpty { "seed" } == ideaStage
            val searchable = (listOf(title(item)) + listOf("note", "notes", "nextAction", "nextQuestion", "motivation").map { (c[it] as? JsonPrimitive)?.contentOrNull.orEmpty() }).joinToString(" ") + " " +
                (c["entries"] as? JsonArray).orEmpty().joinToString(" ") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
            matchesScope && stageMatches && terms.all { searchable.contains(it, ignoreCase = true) }
        }.let { items -> if (scope == LibraryScope.DEADLINES) items.sortedBy(::deadline) else items.sortedWith(compareBy<Entity> { archived(it) }.thenBy { title(it).lowercase() }.thenBy { it.id }) }
    }
}
