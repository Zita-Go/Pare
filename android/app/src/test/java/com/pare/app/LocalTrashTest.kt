package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalTrashTest {
    private val now = Instant.parse("2026-10-31T09:00:00Z")
    private val id = "90000000-0000-0000-0000-000000000001"
    private val owner = "fictional-owner"
    private fun task(index: Int = 1): Entity {
        val taskId = "90000000-0000-0000-0000-${index.toString().padStart(12, '0')}"
        return Entity("task", taskId, buildJsonObject {
            put("lifecycle", "active"); put("futureEnvelope", "fixture preserved")
            put("content", buildJsonObject {
                put("id", taskId); put("text", "虚构恢复任务 $index"); put("createdAt", now.toString()); put("quadrant", 1); put("completed", false)
                put("note", "虚构的可恢复背景"); put("actualMinutes", 5)
                put("progressSessions", JsonArray(listOf(buildJsonObject {
                    put("id", "90000000-0000-0000-0001-000000000001"); put("startedAt", now.minusSeconds(300).toString()); put("endedAt", now.toString()); put("minutes", 5)
                })))
            })
        }, revision = 3)
    }
    private fun state(vararg items: Entity) = SyncState(owner = owner, device = "fixture-device", items = items.associateBy { it.key })
    private fun deleted(s: SyncState, item: Entity, at: Instant = now) = LibraryActions.delete(s, item.kind, item.id, LibraryBaseline.from(item), at)
    private fun acknowledged(s: SyncState): SyncState {
        val entry = s.localTrash.last()
        val item = s.items.getValue(entry.before.key)
        return s.copy(items = s.items + (item.key to item.copy(revision = item.revision + 1, base = null, dirty = false, group = "")))
    }

    @Test fun oldSnapshotsDecodeWithEmptyTrashAndRecoverySurvivesSerialization() {
        assertTrue(wire.decodeFromString<SyncState>("{}").localTrash.isEmpty())
        val removed = deleted(state(task()), task())
        val restoredState = wire.decodeFromString<SyncState>(wire.encodeToString(removed))
        assertEquals(removed.localTrash, restoredState.localTrash)
        val recovered = LocalTrashActions.restoreOriginal(restoredState, restoredState.localTrash.single().id, now)
        assertEquals(task().payload, recovered.items["task:$id"]?.payload)
        assertEquals("task:$id", recovered.localTrash.single().restoredKey)
    }

    @Test fun recoveryPayloadNeverEntersPushAndDeletionRemainsProtocolTombstone() {
        val removed = deleted(state(task()), task())
        val prepared = StateMachine.prepare(removed)
        val push = wire.encodeToString(prepared.pending!!)
        assertTrue(prepared.pending!!.mutations.single().deleted)
        assertNull(prepared.pending!!.mutations.single().payload)
        assertFalse(push.contains("虚构的可恢复背景")); assertFalse(push.contains("localTrash")); assertFalse(push.contains("futureEnvelope"))
        assertEquals(removed.localTrash, prepared.localTrash)
    }

    @Test fun acknowledgedDeletionRestoresExactlyOneStableCopyWithoutTouchingOriginal() {
        val removed = acknowledged(deleted(state(task()), task()))
        val entry = removed.localTrash.single()
        assertThrows(IllegalArgumentException::class.java) { LocalTrashActions.restoreOriginal(removed, entry.id, now) }
        val serialized = wire.decodeFromString<SyncState>(wire.encodeToString(removed))
        val copied = LocalTrashActions.restoreCopy(serialized, entry.id, now)
        val copy = copied.items.getValue("task:${entry.copyId}")
        assertEquals(removed.items["task:$id"], copied.items["task:$id"])
        assertEquals(entry.copyId, copy.content()?.get("id")?.jsonPrimitive?.content)
        assertEquals(task().content()?.get("progressSessions"), copy.content()?.get("progressSessions"))
        assertEquals(task().content()?.get("actualMinutes"), copy.content()?.get("actualMinutes"))
        assertEquals(task().payload?.get("futureEnvelope"), copy.payload?.get("futureEnvelope"))
        assertEquals(copied, LocalTrashActions.restoreCopy(copied, entry.id, now.plusSeconds(1)))
        assertThrows(IllegalArgumentException::class.java) { LocalTrashActions.restoreOriginal(copied, entry.id, now.plusSeconds(1)) }
        PayloadValidation.validate("task", entry.copyId, copy.payload!!)
    }

    @Test fun restoringCopyDoesNotRedirectExistingIdeaGoalOrPlanReferences() {
        val references = listOf("idea" to "linkedTaskIDs", "goal" to "taskIDs", "day_plan" to "ordered_task_ids").map { (kind, field) ->
            Entity(kind, "fixture-reference", buildJsonObject { put("content", buildJsonObject { put(field, JsonArray(listOf(JsonPrimitive(id)))) }) })
        }
        val initial = state(task(), *references.toTypedArray())
        val removed = acknowledged(deleted(initial, task()))
        val restored = LocalTrashActions.restoreCopy(removed, removed.localTrash.single().id, now)
        references.forEach { assertEquals(it, restored.items[it.key]) }
    }

    @Test fun originalRestoreCannotOverwriteLaterEditOrASecondDeletion() {
        val removed = deleted(state(task()), task())
        val entry = removed.localTrash.single()
        val fresh = StateMachine.edit(removed, "task", id, task().payload)
        assertFalse(entry.canRestoreOriginal(fresh))
        assertThrows(IllegalArgumentException::class.java) { LocalTrashActions.restoreOriginal(fresh, entry.id, now) }
        val original = LocalTrashActions.restoreOriginal(removed, entry.id, now)
        val removedAgain = deleted(original, original.items.getValue("task:$id"))
        assertEquals(2, removedAgain.localTrash.size)
        // Retrying the earlier already-completed recovery cannot resurrect the newer deletion.
        assertThrows(IllegalArgumentException::class.java) { LocalTrashActions.restoreOriginal(removedAgain, entry.id, now) }
        assertTrue(removedAgain.items.getValue("task:$id").deleted)
    }

    @Test fun copyCollisionAndOtherAccountNeverOverwriteAnyEntity() {
        val removed = acknowledged(deleted(state(task()), task()))
        val entry = removed.localTrash.single()
        val occupied = task().copy(id = entry.copyId)
        val collision = removed.copy(items = removed.items + (occupied.key to occupied))
        assertThrows(IllegalArgumentException::class.java) { LocalTrashActions.restoreCopy(collision, entry.id, now) }
        assertThrows(IllegalArgumentException::class.java) { LocalTrashActions.restoreCopy(removed.copy(owner = "other-fixture-owner"), entry.id, now) }
        assertThrows(IllegalArgumentException::class.java) { LocalTrashActions.restoreOriginal(removed.copy(device = "other-device"), entry.id, now) }
    }

    @Test fun hundredEntryLimitRefusesNextDeletionWithoutRemovingItsSource() {
        var current = state(task())
        repeat(LocalTrashActions.MAX_ENTRIES) { index ->
            current = deleted(current, current.items.getValue("task:$id"), now.plusSeconds(index.toLong()))
            current = LocalTrashActions.restoreOriginal(current, current.localTrash.last().id, now)
        }
        val full = current
        assertEquals(100, full.localTrash.size)
        assertThrows(IllegalArgumentException::class.java) { deleted(full, full.items.getValue("task:$id")) }
        assertFalse(full.items.getValue("task:$id").deleted)
        assertEquals(task().payload, full.items.getValue("task:$id").payload)
    }

    @Test fun twoMegabyteLimitRefusesDeletionAndKeepsRecoverableSource() {
        val original = task()
        val large = original.copy(payload = JsonObject(original.payload!!.toMutableMap().apply {
            put("content", JsonObject(original.content()!!.toMutableMap().apply { put("note", JsonPrimitive("x".repeat(LocalTrashActions.MAX_BYTES))) }))
        }))
        val s = state(large)
        assertThrows(IllegalArgumentException::class.java) { deleted(s, large) }
        assertFalse(s.items.getValue(large.key).deleted); assertTrue(s.localTrash.isEmpty())
    }

    @Test fun thirtyDaysOnlySuggestsCleanupAndNeverSilentlyDropsOlderRecovery() {
        val first = task(); val second = task(2)
        val old = deleted(state(first, second), first, now.minusSeconds(31 * 86400L))
        val next = deleted(old, second)
        assertEquals(2, next.localTrash.size)
        assertTrue(next.localTrash.first().canReviewForCleanup(now))
        assertEquals(first.payload, next.localTrash.first().before.payload)
    }

    @Test fun confirmedCleanupCannotClearNewerRecordsOrChangeRemoteItems() {
        val first = task(); val second = task(2)
        val initial = deleted(state(first, second), first)
        val selected = initial.localTrash
        val latest = deleted(initial, second)
        val cleaned = LocalTrashActions.clear(latest, selected)
        assertEquals(latest.items, cleaned.items)
        assertEquals(listOf(second.key), cleaned.localTrash.map { it.before.key })
        val restored = LocalTrashActions.restoreOriginal(initial, selected.single().id, now)
        assertThrows(IllegalArgumentException::class.java) { LocalTrashActions.clear(restored, selected) }
        assertTrue(LocalTrashActions.clear(restored, restored.localTrash).localTrash.isEmpty())
        assertFalse(restored.items.getValue(first.key).deleted)
    }

    @Test fun goalEditorDeletionUsesDurableRecoveryAndKeepsLinkedTaskAfterOwnAcknowledgement() {
        val draft = GoalDraft(id = "92000000-0000-0000-0000-000000000001", title = "虚构的长期观察目标", motivation = "虚构背景", taskIds = listOf(id))
        val saved = GoalsActions.save(state(task()), draft, GoalBaseline.from(null), now)
        val original = saved.items.getValue("goal:${draft.id}")
        val acknowledged = original.copy(revision = 1, base = original.payload, dirty = false, group = "")
        val before = saved.copy(items = saved.items + (original.key to acknowledged))
        val removed = GoalsActions.delete(before, original)
        assertTrue(removed.items.getValue(original.key).deleted)
        assertEquals(task(), removed.items["task:$id"])
        assertEquals(acknowledged, removed.localTrash.single().before)
        val reopened = wire.decodeFromString<SyncState>(wire.encodeToString(removed))
        val restored = LocalTrashActions.restoreOriginal(reopened, reopened.localTrash.single().id, now)
        assertEquals(original.payload, restored.items[original.key]?.payload)
        assertEquals(task(), restored.items["task:$id"])
        val changed = acknowledged.copy(payload = JsonObject(acknowledged.payload!!.toMutableMap().apply { put("newEnvelope", JsonPrimitive(true)) }))
        assertThrows(IllegalArgumentException::class.java) { GoalsActions.delete(before.copy(items = before.items + (changed.key to changed)), original) }
    }
}
