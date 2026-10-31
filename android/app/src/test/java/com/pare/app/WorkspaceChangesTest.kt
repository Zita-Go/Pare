package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceChangesTest {
    private val now = Instant.parse("2026-10-31T09:00:00Z")
    private fun task(index: Int = 1, group: String = "", dirty: Boolean = false, archived: Boolean = false): Entity {
        val id = "93000000-0000-0000-0000-${index.toString().padStart(12, '0')}"
        return Entity("task", id, JsonObject(ContentActions.save(null, "task", "虚构任务 $index", "", 1, id, now).toMutableMap().apply {
            if (archived) put("lifecycle", JsonPrimitive("archived"))
        }), dirty = dirty, group = group)
    }
    private fun state(vararg items: Entity) = SyncState(items = items.associateBy { it.key })
    private fun note(item: Entity, text: String) = item.copy(payload = JsonObject(item.payload!!.toMutableMap().apply {
        put("content", JsonObject(item.content()!!.toMutableMap().apply { put("note", JsonPrimitive(text)) }))
    }))

    @Test fun activeFocusOrWaitingCannotBeArchivedDeletedOrRemoved() {
        val item = task(); val before = state(item)
        val changes = listOf(
            LibraryActions.archive(before, item.id, LibraryBaseline.from(item)),
            LibraryActions.delete(before, item.kind, item.id, LibraryBaseline.from(item), now),
            before.copy(items = emptyMap())
        )
        changes.forEach { after ->
            assertThrows(IllegalArgumentException::class.java) { WorkspaceChanges.checked(before, after, setOf(item.id), true) }
        }
        assertEquals(item, before.items[item.key]); assertTrue(before.localTrash.isEmpty())
    }

    @Test fun unknownExecutionLedgerBlocksDestructionButAllowsOtherTaskEdits() {
        val item = task(); val before = state(item)
        val deleted = LibraryActions.delete(before, item.kind, item.id, LibraryBaseline.from(item), now)
        assertThrows(IllegalArgumentException::class.java) { WorkspaceChanges.checked(before, deleted, emptySet(), false) }
        val after = StateMachine.edit(before, item.kind, item.id, note(item, "虚构的备注修改").payload)
        assertEquals(after, WorkspaceChanges.checked(before, after, emptySet(), false))
    }

    @Test fun alreadyArchivedMetadataAndUnrelatedTaskDeletionRemainAvailable() {
        val archived = task(1, archived = true)
        val active = task(2)
        val before = state(archived, active)
        val after = StateMachine.edit(before, archived.kind, archived.id, note(archived, "历史备注").payload)
        assertEquals(after, WorkspaceChanges.checked(before, after, setOf(active.id), false))
        val deleted = LibraryActions.delete(before, archived.kind, archived.id, LibraryBaseline.from(archived), now)
        assertEquals(deleted, WorkspaceChanges.checked(before, deleted, setOf(active.id), true))
    }

    @Test fun atomicGroupLimitIncludesUnchangedDirtySiblingsAndAllowsExactlyHundred() {
        val items = (1..101).map { task(it, group = "shared-fixture", dirty = true) }
        val before = state(*items.toTypedArray())
        val changed = note(items.first(), "改变其中一项")
        val after = before.copy(items = before.items + (changed.key to changed))
        assertThrows(IllegalArgumentException::class.java) { WorkspaceChanges.checked(before, after, emptySet(), true) }
        val hundred = state(*items.take(100).toTypedArray())
        val accepted = hundred.copy(items = hundred.items + (changed.key to changed))
        assertEquals(accepted, WorkspaceChanges.checked(hundred, accepted, emptySet(), true))
    }

    @Test fun groupByteLimitCountsUtf8AcrossTheWholeMutation() {
        val item = task(group = "utf8-fixture", dirty = true)
        val before = state(item)
        val changed = note(item, "界".repeat(400_000))
        val after = before.copy(items = mapOf(changed.key to changed))
        assertThrows(IllegalArgumentException::class.java) { WorkspaceChanges.checked(before, after, emptySet(), true) }
    }

    @Test fun legacyEmptyGroupsStayIndependentInsteadOfMergingAllDirtyEntities() {
        val items = (1..101).map { task(it, dirty = true) }
        val before = state(*items.toTypedArray())
        val changed = note(items.first(), "单条独立修改")
        val after = before.copy(items = before.items + (changed.key to changed))
        assertEquals(after, WorkspaceChanges.checked(before, after, emptySet(), true))
    }

    @Test fun localRecoveryCleanupDoesNotMutateOrRejectExistingOutboxGroups() {
        val item = task()
        val deleted = LibraryActions.delete(state(item), item.kind, item.id, LibraryBaseline.from(item), now)
        val cleaned = LocalTrashActions.clear(deleted, deleted.localTrash)
        assertEquals(cleaned, WorkspaceChanges.checked(deleted, cleaned, emptySet(), false))
        assertEquals(deleted.items, cleaned.items)
    }
}
