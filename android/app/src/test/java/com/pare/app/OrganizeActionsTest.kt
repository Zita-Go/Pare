package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OrganizeActionsTest {
    private val now = Instant.parse("2026-10-30T00:00:00Z")
    private val inbox = Entity("inbox", "capture", wire.parseToJsonElement("""{"content":{"id":"capture","text":"Example captured task","suggestedRemindAt":"2026-10-31T00:00:00Z"}}""").jsonObject)
    private fun initial() = SyncState(items = mapOf(inbox.key to inbox))

    @Test fun explicitQuadrantsArePreservedAndDoNotCreateTodayPlan() {
        (0..3).forEach { quadrant ->
            val result = organizeInbox(initial(), inbox, quadrant, false, "task-$quadrant", now)
            val task = result.items.getValue("task:task-$quadrant")
            assertEquals(quadrant, task.content()!!.getValue("quadrant").jsonPrimitive.int)
            assertEquals(inbox.title(), task.title())
            assertEquals("2026-10-31T00:00:00Z", task.content()!!.getValue("remindAt").jsonPrimitive.content)
            assertTrue(result.items.getValue(inbox.key).deleted)
            assertTrue(result.items.values.none { it.kind == "day_plan" })
            assertEquals(task.group, result.items.getValue(inbox.key).group)
            assertFalse(task.group.isBlank())
            assertFalse(initial().items.getValue(inbox.key).deleted)
        }
    }

    @Test fun addingTodayPreservesPlanAndGroupsAllThreeMutations() {
        val planId = ContentActions.planId(initial(), now)
        val plan = Entity("day_plan", planId, wire.parseToJsonElement("""{"futureEnvelope":true,"content":{"day_key":"${planId.substringBefore('@')}","time_zone":"${planId.substringAfter('@')}","ordered_task_ids":["existing"],"daily_win_task_id":"existing","task_plans":[{"task_id":"existing","note":"keep"}],"futureContent":42}}""").jsonObject)
        val state = initial().copy(items = initial().items + (plan.key to plan))
        val result = organizeInbox(state, inbox, 2, true, "new-task", now)
        val changed = result.items.getValue(plan.key)
        assertEquals(listOf("existing", "new-task"), changed.content()!!.getValue("ordered_task_ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive("existing"), changed.content()!!["daily_win_task_id"])
        assertEquals(plan.content()!!["task_plans"], changed.content()!!["task_plans"])
        assertEquals(JsonPrimitive(42), changed.content()!!["futureContent"])
        assertEquals(JsonPrimitive(true), changed.payload!!["futureEnvelope"])
        assertEquals(1, result.items.values.filter { it.dirty }.map { it.group }.distinct().size)
        assertEquals(setOf(inbox.key, "task:new-task", plan.key), StateMachine.prepare(result).pending!!.mutations.map { it.key }.toSet())
    }

    @Test fun invalidChoiceCannotMutateSource() {
        assertThrows(IllegalArgumentException::class.java) { organizeInbox(initial(), inbox, -1, true, "new-task", now) }
        assertFalse(initial().items.getValue(inbox.key).deleted)
    }

    @Test fun sourceDeletedBeforeCommitCannotCreateTask() {
        val state = initial().copy(items = mapOf(inbox.key to inbox.copy(deleted = true, payload = null)))
        assertThrows(IllegalArgumentException::class.java) { organizeInbox(state, inbox, 0, true, "new-task", now) }
        assertEquals(1, state.items.size)
    }

    @Test fun sourceChangedWhileSavingRequiresReview() {
        val changed = inbox.copy(payload = wire.parseToJsonElement("""{"content":{"text":"Changed on another device"}}""").jsonObject)
        val state = initial().copy(items = mapOf(changed.key to changed))
        assertThrows(IllegalArgumentException::class.java) { organizeInbox(state, inbox, 1, false, "new-task", now) }
        assertFalse(state.items.getValue(inbox.key).deleted)
    }

    private fun task(id: String, quadrant: Int = 0, order: Long? = null, createdAt: String = "2026-10-30T00:00:00Z"): Entity =
        Entity("task", id, buildJsonObject {
            put("lifecycle", "active")
            put("futureEnvelope", buildJsonObject { put("keep", true) })
            put("content", buildJsonObject {
                put("id", id); put("text", "Example $id"); put("quadrant", quadrant)
                put("completed", false); put("createdAt", createdAt)
                order?.let { put("quadrantOrder", it) }
                put("lastProgressAt", "2026-10-29T00:00:00Z")
                put("nextAction", "Write one sentence")
                put("progressSessions", buildJsonArray { add(buildJsonObject { put("futureSession", true) }) })
                put("futureContent", buildJsonObject { put("keep", true) })
            })
        })

    private fun stateOf(vararg tasks: Entity) = SyncState(device = "organize-test-device", items = tasks.associateBy { it.key })
    private fun update(item: Entity, vararg values: Pair<String, JsonElement>) = item.copy(payload =
        JsonObject(item.payload!!.toMutableMap().apply {
            put("content", JsonObject(item.content()!!.toMutableMap().apply { putAll(values) }))
        }))
    private fun ids(state: SyncState, quadrant: Int = 0) = OrganizeActions.tasks(state, quadrant).map { it.id }
    private fun move(state: SyncState, id: String, quadrant: Int = 0, before: String? = null,
        expected: OrganizeMoveBaseline = OrganizeMoveBaseline.from(state, id)) =
        OrganizeActions.move(state, id, quadrant, before, expected, now)

    @Test fun manualOrderKeepsExistingNewestFirstFallbackAndExcludesInactiveTasks() {
        val older = task("older", createdAt = "2026-10-28T00:00:00Z")
        val newer = task("newer")
        val archived = task("archived").copy(payload = JsonObject(task("archived").payload!!.toMutableMap().apply { put("lifecycle", JsonPrimitive("archived")) }))
        val completed = update(task("completed"), "completed" to JsonPrimitive(true))
        val deleted = task("deleted").copy(deleted = true, payload = null)
        val state = stateOf(older, newer, archived, completed, deleted, task("elsewhere", 1))
        assertEquals(listOf("newer", "older"), ids(state))
        assertEquals(listOf("elsewhere"), ids(state, 1))
        assertTrue(ids(state, 2).isEmpty())
        assertEquals(listOf("older", "newer"), ids(move(state, "older", before = "newer")))
    }

    @Test fun movingAcrossEveryQuadrantOnlyEditsMovedTaskAndPreservesPlansAndHistory() {
        (0..3).forEach { quadrant ->
            val source = task("source", (quadrant + 1) % 4, 100)
            val peer = task("peer", quadrant, 200)
            val plan = Entity("day_plan", "2026-10-30@UTC", wire.parseToJsonElement("""{"content":{"ordered_task_ids":["source"],"daily_win_task_id":"source","task_plans":[{"task_id":"source","scheduled_at":"2026-10-30T08:00:00Z","allocation":{"kind":"check"}}]}}""").jsonObject)
            val state = stateOf(source, peer, plan)
            val result = move(state, "source", quadrant, "peer")
            val changed = result.items.getValue(source.key)
            assertEquals(listOf("source", "peer"), ids(result, quadrant))
            assertEquals(setOf(source.key), result.items.values.filter { it.dirty }.map { it.key }.toSet())
            assertEquals(plan, result.items[plan.key])
            assertEquals(peer, result.items[peer.key])
            assertEquals(source.payload!!.filterKeys { it != "content" }, changed.payload!!.filterKeys { it != "content" })
            assertEquals(source.content()!!.filterKeys { it !in setOf("quadrant", "quadrantOrder", "lastProgressAt") },
                changed.content()!!.filterKeys { it !in setOf("quadrant", "quadrantOrder", "lastProgressAt") })
            assertEquals(now.toString(), changed.content()!!.getValue("lastProgressAt").jsonPrimitive.content)
            val restored = wire.decodeFromString<SyncState>(wire.encodeToString(result))
            assertEquals(ids(result, quadrant), ids(restored, quadrant))
            assertEquals(setOf(source.key), StateMachine.prepare(restored).pending!!.mutations.map { it.key }.toSet())
        }
    }

    @Test fun reorderBeforeAndAppendUseSparseRanksAndDoNotCountAsTaskProgress() {
        val state = stateOf(task("a", order = 0), task("b", order = 1024), task("c", order = 2048))
        val reordered = move(state, "c", before = "b")
        assertEquals(listOf("a", "c", "b"), ids(reordered))
        assertEquals(setOf("task:c"), reordered.items.values.filter { it.dirty }.map { it.key }.toSet())
        assertEquals(state.items.getValue("task:c").content()!!["lastProgressAt"], reordered.items.getValue("task:c").content()!!["lastProgressAt"])
        val appended = move(state, "a")
        assertEquals(listOf("b", "c", "a"), ids(appended))
        assertEquals(state.items["task:b"], appended.items["task:b"])
        assertEquals(state.items["task:c"], appended.items["task:c"])
    }

    @Test fun emptyQuadrantAcceptsTaskAndUnchangedDropsHaveNoDirtyWrites() {
        val state = stateOf(task("a"), task("b", 1))
        val changed = move(state, "a", 3)
        assertEquals(listOf("a"), ids(changed, 3))
        assertTrue(ids(changed).isEmpty())
        assertSame(state, move(state, "a"))
        assertSame(state, move(state, "a", before = "a"))
        val pair = stateOf(task("a", order = 0), task("b", order = 1024))
        assertSame(pair, move(pair, "a", before = "b"))
        assertThrows(IllegalArgumentException::class.java) { move(state, "a", -1) }
        assertThrows(IllegalArgumentException::class.java) { move(state, "a", 2, "missing") }
        assertThrows(IllegalArgumentException::class.java) { move(state, "a", 2, "b") }
    }

    @Test fun staleSourceOrDestinationOrderingFailsBeforeWritingEvenForAnApparentNoOp() {
        val a = task("a", order = 0)
        val b = task("b", 1, 1024)
        val state = stateOf(a, b)
        val expected = OrganizeMoveBaseline.from(state, "a")
        val variants = listOf(
            stateOf(update(a, "quadrant" to JsonPrimitive(2)), b),
            stateOf(update(a, "quadrantOrder" to JsonPrimitive(9)), b),
            stateOf(a, update(b, "quadrantOrder" to JsonPrimitive(4096))),
            stateOf(a, b, task("new", 1)),
            stateOf(a),
            stateOf(a, update(b, "completed" to JsonPrimitive(true)))
        )
        variants.forEach { current ->
            assertThrows(IllegalStateException::class.java) { move(current, "a", 1, expected = expected) }
            assertTrue(current.items.values.none { it.dirty })
        }
        val current = stateOf(update(a, "quadrantOrder" to JsonPrimitive(9)), b)
        assertThrows(IllegalStateException::class.java) { move(current, "a", expected = expected) }
    }

    @Test fun unrelatedEditsAndSyncAcknowledgementsSurviveAnInProgressMove() {
        val a = task("a", order = 0)
        val b = task("b", 1, 1024)
        val state = stateOf(a, b, task("elsewhere", 2))
        val expected = OrganizeMoveBaseline.from(state, "a")
        val latest = update(a, "text" to JsonPrimitive("A newer title"), "progressSessions" to buildJsonArray { add("new history") })
            .copy(revision = 9)
        val changed = state.copy(items = state.items + (a.key to latest) + ("task:new" to task("new", 2)))
        val result = move(changed, "a", 1, expected = expected)
        val moved = result.items.getValue(a.key)
        assertEquals(latest.title(), moved.title())
        assertEquals(latest.content()!!["progressSessions"], moved.content()!!["progressSessions"])
        assertEquals(9L, moved.revision)
        assertEquals(changed.items["task:new"], result.items["task:new"])
        assertThrows(IllegalStateException::class.java) { move(changed.copy(owner = "different-owner"), "a", 1, expected = expected) }
        assertThrows(IllegalStateException::class.java) { move(changed.copy(device = "different-device"), "a", 1, expected = expected) }
    }

    @Test fun deletedArchivedCompletedAndConflictedTasksCannotBeMoved() {
        val a = task("a")
        val state = stateOf(a)
        val expected = OrganizeMoveBaseline.from(state, "a")
        val remote = Remote("task", "a", 1, payload = a.payload, device = "another-device", sequence = 1)
        val variants = listOf(a.copy(deleted = true, payload = null), update(a, "completed" to JsonPrimitive(true)),
            a.copy(payload = JsonObject(a.payload!!.toMutableMap().apply { put("lifecycle", JsonPrimitive("archived")) })),
            a.copy(conflict = remote), a.copy(conflictGroup = "conflict"))
        variants.forEach { current ->
            assertThrows(IllegalArgumentException::class.java) { move(state.copy(items = mapOf(current.key to current)), "a", 1, expected = expected) }
        }
        assertThrows(IllegalArgumentException::class.java) { move(state.copy(items = emptyMap()), "a", 1, expected = expected) }
    }

    @Test fun destinationAndInheritedGroupConflictsAreRejected() {
        val a = task("a", order = 0)
        val b = task("b", 1, 1024)
        val state = stateOf(a, b)
        val expected = OrganizeMoveBaseline.from(state, "a")
        val remote = Remote("task", "b", 1, payload = b.payload, device = "another-device", sequence = 1)
        val conflicted = state.copy(items = state.items + (b.key to b.copy(conflict = remote)))
        assertThrows(IllegalArgumentException::class.java) { move(conflicted, "a", 1, expected = expected) }
        val linked = state.copy(items = state.items + (a.key to a.copy(dirty = true, group = "linked")) +
            (b.key to b.copy(group = "linked", conflict = remote)))
        assertThrows(IllegalArgumentException::class.java) { move(linked, "a", 3, expected = expected) }
    }

    @Test fun denseRanksRenumberAsOneBoundedTransactionAndLeaveOtherQuadrantsUntouched() {
        val state = stateOf(task("a", order = 0), task("b", order = 1), task("c", order = 2), task("other", 2))
        val result = move(state, "c", before = "b")
        assertEquals(listOf("a", "c", "b"), ids(result))
        assertEquals(state.items["task:a"], result.items["task:a"])
        assertEquals(state.items["task:other"], result.items["task:other"])
        val dirty = result.items.values.filter { it.dirty }
        assertEquals(setOf("task:b", "task:c"), dirty.map { it.key }.toSet())
        assertEquals(1, dirty.map { it.group }.distinct().size)
        assertEquals(2, StateMachine.prepare(result).pending!!.mutations.size)
    }

    @Test fun largeQuadrantAllowsSparseMoveButRejectsOversizedRenumbering() {
        val spaced = (0..100).map { task("task-$it", 1, it * 1024L) }
        val state = stateOf(task("source"), *spaced.toTypedArray())
        val result = move(state, "source", 1, "task-50")
        assertEquals(1, result.items.values.count { it.dirty })
        assertEquals("source", ids(result, 1)[50])
        val tied = stateOf(task("source"), *(0..99).map { task("task-${it.toString().padStart(3, '0')}", 1, 0) }.toTypedArray())
        // The first rank is already zero: 101 destination tasks mean only 100
        // actual mutations, which must remain a valid atomic transaction.
        val atLimit = move(tied, "source", 1, "task-050")
        assertEquals(tied.items["task:task-000"], atLimit.items["task:task-000"])
        assertEquals(100, atLimit.items.values.count { it.dirty })
        assertEquals(100, StateMachine.prepare(atLimit).pending!!.mutations.size)
        assertEquals("source", ids(atLimit, 1)[50])
        val extra = task("task-100", 1, 0)
        val oversized = tied.copy(items = tied.items + (extra.key to extra))
        assertThrows(IllegalArgumentException::class.java) { move(oversized, "source", 1, "task-050") }
        assertTrue(oversized.items.values.none { it.dirty })
    }

    @Test fun inheritedDirtyGroupsCountTowardAtomicCountAndByteLimitsEvenWithPendingRequest() {
        val source = task("source").copy(dirty = true, group = "existing")
        val siblings = (0..99).map { task("other-$it", 2).copy(dirty = true, group = "existing") }
        val state = stateOf(source, *siblings.toTypedArray()).copy(pending = Push("pending", "device", emptyList()))
        assertThrows(IllegalArgumentException::class.java) { move(state, "source", 1) }
        val huge = update(task("huge", 2), "note" to JsonPrimitive("x".repeat(1_048_576))).copy(dirty = true, group = "existing")
        val oversized = stateOf(source, huge)
        assertThrows(IllegalArgumentException::class.java) { move(oversized, "source", 1) }
        assertEquals(0, source.content()!!.getValue("quadrant").jsonPrimitive.int)
    }

    @Test fun sparseRanksHandleLongExtremesWithoutOverflow() {
        val state = stateOf(task("a", order = Long.MIN_VALUE), task("b", order = 0), task("c", order = Long.MAX_VALUE))
        assertEquals(listOf("b", "a", "c"), ids(move(state, "b", before = "a")))
        assertEquals(listOf("b", "c", "a"), ids(move(state, "a")))
        assertEquals(listOf("a", "c", "b"), ids(move(state, "c", before = "b")))
    }
}
