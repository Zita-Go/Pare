package com.pare.app

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Device-local recovery data. Push is constructed exclusively from items, never this ledger. */
@Serializable
data class LocalTrashEntry(
    val id: String,
    val owner: String?,
    val device: String,
    val deletedAt: String,
    val before: Entity,
    val tombstone: Entity,
    val copyId: String,
    val restoredKey: String? = null,
    val restoredAt: String? = null
) {
    fun canReviewForCleanup(now: Instant): Boolean = runCatching { Duration.between(Instant.parse(deletedAt), now).toDays() >= 30 }.getOrDefault(false)
    fun belongsTo(state: SyncState): Boolean = device == state.device && (owner == state.owner || owner == null)
    fun canRestoreOriginal(state: SyncState): Boolean = belongsTo(state) && restoredKey == null && state.items[before.key] == tombstone && tombstone.deleted && tombstone.conflict == null
}

object LocalTrashActions {
    const val MAX_ENTRIES = 100
    const val MAX_BYTES = 2 * 1024 * 1024

    fun visible(state: SyncState): List<LocalTrashEntry> = state.localTrash.filter { it.belongsTo(state) }.sortedByDescending { it.deletedAt }

    /** Called within the same repository transform as the tombstone: either both commit or neither does. */
    fun retain(before: SyncState, deleted: SyncState, key: String, now: Instant): SyncState {
        require(before.owner == deleted.owner && before.device == deleted.device) { "资料库已改变，尚未删除" }
        val original = requireNotNull(before.items[key])
        val tombstone = requireNotNull(deleted.items[key])
        require(!original.deleted && original.payload != null && tombstone.deleted && tombstone.payload == null)
        val entry = LocalTrashEntry(uid(), before.owner, before.device, now.toString(), original, tombstone, uid())
        val entries = before.localTrash + entry
        // Reserve room for each entry's one-time restoration receipt as well.
        require(entries.size <= MAX_ENTRIES && wire.encodeToString(entries).toByteArray(Charsets.UTF_8).size + entries.size * 128 <= MAX_BYTES) {
            "本机回收站已达到 100 项或 2 MB 上限，请先管理回收站；原内容尚未删除"
        }
        return deleted.copy(localTrash = entries)
    }

    private fun entry(state: SyncState, id: String): LocalTrashEntry {
        val entry = state.localTrash.firstOrNull { it.id == id }
        require(entry != null && entry.belongsTo(state)) { "回收记录已清理或不属于当前资料库" }
        return entry
    }

    private fun recordRestore(state: SyncState, entry: LocalTrashEntry, key: String, now: Instant): SyncState = state.copy(
        localTrash = state.localTrash.map { if (it.id == entry.id) it.copy(restoredKey = key, restoredAt = now.toString()) else it }
    )

    fun restoreOriginal(state: SyncState, id: String, now: Instant = Instant.now()): SyncState {
        val entry = entry(state, id)
        if (entry.restoredKey != null) {
            require(entry.restoredKey == entry.before.key) { "这项已恢复为副本，请打开恢复内容；未覆盖原记录" }
            require(state.items[entry.restoredKey]?.let { !it.deleted && it.payload != null } == true) { "这项曾经恢复，内容随后已删除或不可用；不会重复恢复" }
            return state
        }
        require(entry.canRestoreOriginal(state)) { "原删除状态已同步或再次改变，未覆盖新版本；可以恢复为副本" }
        val restored = StateMachine.group(StateMachine.edit(state, entry.before.kind, entry.before.id, entry.before.payload), setOf(entry.before.key))
        return recordRestore(restored, entry, entry.before.key, now)
    }

    fun restoreCopy(state: SyncState, id: String, now: Instant = Instant.now()): SyncState {
        val entry = entry(state, id)
        if (entry.restoredKey != null) {
            require(entry.restoredKey == "${entry.before.kind}:${entry.copyId}") { "这项已恢复原内容，未重复创建副本" }
            require(state.items[entry.restoredKey]?.let { !it.deleted && it.payload != null } == true) { "这项副本随后已删除或不可用；不会重复创建" }
            return state
        }
        require(UUID.fromString(entry.copyId).toString() == entry.copyId)
        val key = "${entry.before.kind}:${entry.copyId}"
        require(state.items[key] == null) { "副本标识已存在，未覆盖已有内容" }
        val payload = requireNotNull(entry.before.payload)
        val content = requireNotNull(entry.before.content()).toMutableMap().apply {
            put("id", JsonPrimitive(entry.copyId))
            // Copies are not automatically assigned the original task's day-plan slots.
            if (entry.before.kind == "task") listOf("isToday", "todayOrder", "isDailyWin", "scheduledAt", "scheduledMinutes", "scheduleReminderLeadMinutes").forEach(::remove)
        }
        val copied = JsonObject(payload.toMutableMap().apply { put("content", JsonObject(content)) })
        PayloadValidation.validate(entry.before.kind, entry.copyId, copied)
        val restored = StateMachine.edit(state, entry.before.kind, entry.copyId, copied)
        return recordRestore(restored, entry, key, now)
    }

    fun forget(state: SyncState, id: String): SyncState {
        entry(state, id)
        return state.copy(localTrash = state.localTrash.filterNot { it.id == id })
    }

    /** The confirmed IDs freeze the user's selection; newer deletions are never cleared by a stale dialog. */
    fun clear(state: SyncState, expected: List<LocalTrashEntry>): SyncState {
        expected.forEach { previous -> require(entry(state, previous.id) == previous) { "回收站已发生变化，请查看最新记录后再清理" } }
        val ids = expected.map { it.id }.toSet()
        return state.copy(localTrash = state.localTrash.filterNot { it.id in ids })
    }
}
