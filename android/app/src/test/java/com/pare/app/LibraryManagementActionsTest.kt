package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LibraryManagementActionsTest {
    private val now = Instant.parse("2026-10-31T09:00:00Z")
    private val taskID = "10000000-0000-0000-0000-000000000001"
    private val ideaID = "20000000-0000-0000-0000-000000000001"
    private val inboxID = "30000000-0000-0000-0000-000000000001"
    private val goalID = "40000000-0000-0000-0000-000000000001"
    private fun task(vararg fields: Pair<String, JsonElement>) = Entity("task", taskID, buildJsonObject {
        put("lifecycle", "active"); put("futureEnvelope", "preserved")
        put("content", buildJsonObject {
            put("id", taskID); put("text", "虚构的调研任务"); put("createdAt", now.toString()); put("quadrant", 1); put("completed", false)
            put("note", "收集树木资料"); put("nextAction", "打开观察笔记"); put("futureMetadata", buildJsonObject { put("keep", true) })
            fields.forEach { (key, value) -> put(key, value) }
        })
    }, revision = 3)
    private fun idea(vararg fields: Pair<String, JsonElement>) = Entity("idea", ideaID, buildJsonObject {
        put("content", buildJsonObject {
            put("id", ideaID); put("text", "虚构的植物观察想法"); put("notes", "慢慢积累"); put("createdAt", now.toString()); put("updatedAt", now.toString()); put("stage", "exploring")
            put("linkedTaskIDs", JsonArray(listOf(JsonPrimitive(taskID)))); put("entries", JsonArray(emptyList()))
            fields.forEach { (key, value) -> put(key, value) }
        })
    })
    private fun inbox() = Entity("inbox", inboxID, buildJsonObject { put("futureEnvelope", "kept"); put("content", buildJsonObject {
        put("id", inboxID); put("text", "虚构的观察路线"); put("createdAt", now.minusSeconds(60).toString()); put("futureMetadata", "capture")
    }) })
    private fun goal() = Entity("goal", goalID, buildJsonObject { put("content", buildJsonObject {
        put("id", goalID); put("title", "虚构的自然记录目标"); put("motivation", "认识家附近的植物"); put("createdAt", now.toString()); put("updatedAt", now.toString()); put("status", "active"); put("taskIDs", JsonArray(listOf(JsonPrimitive(taskID))))
    }) })
    private fun state(vararg entities: Entity) = SyncState(owner = "fictional-owner", device = "fixture-device", items = entities.associateBy { it.key })
    private fun updated(item: Entity, vararg fields: Pair<String, JsonElement>) = item.copy(payload = JsonObject(item.payload!!.toMutableMap().apply {
        put("content", JsonObject(item.content()!!.toMutableMap().apply { fields.forEach { (key, value) -> put(key, value) } }))
    }))
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected guarded edit failure") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    private fun session(minutes: Int) = JsonArray(listOf(buildJsonObject {
        put("id", "50000000-0000-0000-0000-000000000001"); put("startedAt", now.minusSeconds(minutes * 60L).toString()); put("endedAt", now.toString()); put("minutes", minutes)
    }))

    @Test fun searchFindsNotesNextActionsIdeaEntriesAndGoalMotivation() {
        val idea = idea("entries" to JsonArray(listOf(buildJsonObject { put("text", "发现榕树") })))
        val s = state(task(), idea, inbox(), goal())
        assertEquals(taskID, LibrarySearch.find(s, "树木 笔记").single().id)
        assertEquals(ideaID, LibrarySearch.find(s, "榕树").single().id)
        assertEquals(goalID, LibrarySearch.find(s, "家附近").single().id)
        assertTrue(LibrarySearch.find(s, "notes").isEmpty())
    }

    @Test fun scopesSeparateCompletedArchivedStagesAndOrderedDeadlines() {
        val active = task("dueAt" to JsonPrimitive(now.toString()))
        val completed = task("completed" to JsonPrimitive(true)).copy(id = "completed")
        val archived = task().copy(id = "archived", payload = JsonObject(task().payload!!.toMutableMap().apply { put("lifecycle", JsonPrimitive("archived")) }))
        val s = state(active, completed, archived, idea(), inbox(), goal())
        assertEquals(listOf(taskID), LibrarySearch.find(s, scope = LibraryScope.DEADLINES).map { it.id })
        assertEquals(listOf("completed"), LibrarySearch.find(s, scope = LibraryScope.COMPLETED).map { it.id })
        assertEquals(listOf("archived"), LibrarySearch.find(s, scope = LibraryScope.ARCHIVED).map { it.id })
        assertEquals(ideaID, LibrarySearch.find(s, scope = LibraryScope.IDEAS, ideaStage = "exploring").single().id)
        assertTrue(LibrarySearch.find(s, scope = LibraryScope.IDEAS, ideaStage = "seed").isEmpty())
        assertEquals(goalID, LibrarySearch.find(s, scope = LibraryScope.GOALS).single().id)
    }

    @Test fun deletingEachKindKeepsReferencesAndUsesProtocolTombstone() {
        val s = state(task(), idea(), inbox(), goal())
        for (item in s.items.values) {
            val next = LibraryActions.delete(s, item.kind, item.id, LibraryBaseline.from(item))
            val tombstone = next.items.getValue(item.key)
            assertTrue(tombstone.deleted); assertTrue(tombstone.dirty); assertNull(tombstone.payload)
            assertEquals(item.revision, tombstone.revision)
            s.items.filterKeys { it != item.key }.forEach { (key, value) -> assertEquals(value.payload, next.items[key]?.payload) }
            assertEquals(item.payload, LibraryActions.deletionUndo(s, next, item.key).restore(next).items[item.key]?.payload)
        }
    }

    @Test fun undoRejectsAnotherDeletionAConflictChangedOwnerAndAcknowledgedRevision() {
        val s = state(task())
        val removed = LibraryActions.delete(s, "task", taskID, LibraryBaseline.from(task()))
        val undo = LibraryActions.deletionUndo(s, removed, "task:$taskID")
        val restored = undo.restore(removed)
        val removedAgain = LibraryActions.delete(restored, "task", taskID, LibraryBaseline.from(restored.items.getValue("task:$taskID")))
        expectFailure { undo.restore(removedAgain) }
        expectFailure { undo.restore(removed.copy(owner = "another-fixture-owner")) }
        val tombstone = removed.items.getValue("task:$taskID")
        expectFailure { undo.restore(removed.copy(items = mapOf(tombstone.key to tombstone.copy(revision = tombstone.revision + 1, dirty = false, group = "")))) }
    }

    @Test fun destructiveOperationsRejectChangedUnknownFieldAndConflict() {
        val original = task()
        listOf(updated(original, "futureMetadata" to JsonPrimitive("changed")), original.copy(conflictGroup = "held-group")).forEach { changed ->
            expectFailure { LibraryActions.delete(state(changed), "task", taskID, LibraryBaseline.from(original)) }
            expectFailure { LibraryActions.archive(state(changed), taskID, LibraryBaseline.from(original)) }
        }
    }

    @Test fun unchangedPayloadAcknowledgementDoesNotInvalidateArchiveOrDeleteConfirmation() {
        val original = task().copy(dirty = true, group = "pending-fixture")
        val acknowledged = original.copy(revision = original.revision + 1, base = original.payload, dirty = false, group = "")
        val baseline = LibraryBaseline.from(original)
        val archived = LibraryActions.archive(state(acknowledged), taskID, baseline)
        assertEquals(JsonPrimitive("archived"), archived.items[original.key]?.payload?.get("lifecycle"))
        assertEquals(acknowledged.revision, archived.items[original.key]?.revision)
        val removed = LibraryActions.delete(state(acknowledged), "task", taskID, baseline, now)
        assertTrue(removed.items.getValue(original.key).deleted)
        assertEquals(acknowledged, removed.localTrash.single().before)
        expectFailure { LibraryActions.delete(state(acknowledged.copy(deleted = true, payload = null)), "task", taskID, baseline, now) }
        expectFailure { LibraryActions.archive(state(acknowledged.copy(conflictGroup = "held-fixture")), taskID, baseline) }
    }

    @Test fun archiveRestoreKeepsHistoryUnknownsAndPastPlansButClearsFutureCommitments() {
        val t = task("completed" to JsonPrimitive(true), "actualMinutes" to JsonPrimitive(12), "progressSessions" to session(12))
        fun plan(date: String) = Entity("day_plan", "$date@UTC", buildJsonObject { put("content", buildJsonObject {
            put("day_key", date); put("time_zone", "UTC"); put("futurePlan", "keep"); put("ordered_task_ids", JsonArray(listOf(JsonPrimitive(taskID))))
            put("daily_win_task_id", taskID); put("task_plans", JsonArray(listOf(buildJsonObject { put("task_id", taskID); put("scheduled_at", "${date}T09:00:00Z"); put("scheduled_minutes", 12) })))
        }) })
        val past = plan("2026-10-30"); val future = plan("2026-11-02")
        val s = state(t, past, future, idea(), goal())
        val archived = LibraryActions.archive(s, taskID, LibraryBaseline.from(t))
        val restored = LibraryActions.restoreArchive(archived, taskID, LibraryBaseline.from(archived.items.getValue(t.key)), now)
        val current = restored.items.getValue(t.key)
        assertFalse(current.completed()); assertEquals(JsonPrimitive("active"), current.payload?.get("lifecycle"))
        assertEquals(t.content()?.get("progressSessions"), current.content()?.get("progressSessions"))
        assertEquals(t.content()?.get("actualMinutes"), current.content()?.get("actualMinutes"))
        assertEquals(t.payload?.get("futureEnvelope"), current.payload?.get("futureEnvelope"))
        assertEquals(past, restored.items[past.key]); assertEquals(idea(), restored.items[idea().key])
        assertEquals(JsonArray(emptyList()), restored.items[future.key]?.content()?.get("ordered_task_ids"))
        assertEquals("keep", restored.items[future.key]?.content()?.get("futurePlan")?.jsonPrimitive?.content)
        assertEquals(current.group, restored.items[future.key]?.group)
        PayloadValidation.validate("task", taskID, current.payload!!)
        PayloadValidation.validate("day_plan", future.id, restored.items.getValue(future.key).payload!!)
    }

    @Test fun deletingAnEntityPreservesInheritedDirtyTransactionGroup() {
        val t = task().copy(dirty = true, group = "shared")
        val i = idea().copy(dirty = true, group = "shared")
        val changed = LibraryActions.delete(state(t, i), "task", taskID, LibraryBaseline.from(t))
        assertEquals(changed.items[t.key]?.group, changed.items[i.key]?.group)
        assertEquals(i.payload, changed.items[i.key]?.payload)
    }

    @Test fun metadataDueOnlyPreservesConcurrentRecordedFocusAndReminder() {
        val old = task("actualMinutes" to JsonPrimitive(10), "progressSessions" to session(10), "remindAt" to JsonPrimitive(now.plusSeconds(60).toString()), "recurrence" to JsonPrimitive("daily"))
        val fresh = updated(old, "actualMinutes" to JsonPrimitive(15), "progressSessions" to session(15))
        val next = TaskMetadataActions.save(state(fresh), taskID, TaskMetadataBaseline.from(old), now.plusSeconds(3600), 10, now).items.getValue(old.key)
        assertEquals(15, next.content()?.get("actualMinutes")?.jsonPrimitive?.int)
        assertEquals(fresh.content()?.get("progressSessions"), next.content()?.get("progressSessions"))
        assertEquals(old.content()?.get("remindAt"), next.content()?.get("remindAt"))
        assertEquals(old.content()?.get("recurrence"), next.content()?.get("recurrence"))
        assertEquals(old.content()?.get("futureMetadata"), next.content()?.get("futureMetadata"))
    }

    @Test fun manualActualEditRejectsConcurrentFocusEvenWhenRoundedTotalIsUnchanged() {
        val old = task("actualMinutes" to JsonPrimitive(10), "progressSessions" to session(10))
        val fresh = updated(old, "progressSessions" to session(0))
        expectFailure { TaskMetadataActions.save(state(fresh), taskID, TaskMetadataBaseline.from(old), null, 20, now) }
        expectFailure { TaskMetadataActions.save(state(old), taskID, TaskMetadataBaseline.from(old), null, -1, now) }
        val next = TaskMetadataActions.save(state(old), taskID, TaskMetadataBaseline.from(old), null, 20, now).items.getValue(old.key)
        assertEquals(20, next.content()?.get("actualMinutes")?.jsonPrimitive?.int)
        assertEquals(old.content()?.get("progressSessions"), next.content()?.get("progressSessions"))
        assertEquals(now.toString(), next.content()?.get("lastProgressAt")?.jsonPrimitive?.content)
    }

    @Test fun ideaUnlinkDoesNotDeleteTaskOrItsHistoryAndMissingLinkCanBeRemoved() {
        val i = idea(); val t = task("progressSessions" to session(12))
        val next = IdeaManagementActions.link(state(i, t), ideaID, taskID, false, IdeaManagementBaseline.from(i), now)
        assertEquals(t, next.items[t.key]); assertEquals(JsonArray(emptyList()), next.items[i.key]?.content()?.get("linkedTaskIDs"))
        val withoutTask = IdeaManagementActions.link(state(i), ideaID, taskID, false, IdeaManagementBaseline.from(i), now)
        assertEquals(JsonArray(emptyList()), withoutTask.items[i.key]?.content()?.get("linkedTaskIDs"))
    }

    @Test fun linkPreservesConcurrentExplorationEntriesButRejectsChangedLinks() {
        val i = idea("linkedTaskIDs" to JsonArray(emptyList()))
        val entries = JsonArray(listOf(buildJsonObject { put("text", "新发现") }))
        val fresh = updated(i, "entries" to entries)
        val next = IdeaManagementActions.link(state(fresh, task()), ideaID, taskID, true, IdeaManagementBaseline.from(i), now)
        assertEquals(entries, next.items[i.key]?.content()?.get("entries"))
        expectFailure { IdeaManagementActions.link(next, ideaID, taskID, false, IdeaManagementBaseline.from(i), now) }
    }

    @Test fun reviewEditDoesNotOverwriteOtherIdeaMetadataAndRejectsChangedDate() {
        val i = idea(); val fresh = updated(i, "nextQuestion" to JsonPrimitive("下一步观察什么？"))
        val next = IdeaManagementActions.review(state(fresh), ideaID, now.plusSeconds(3600), IdeaManagementBaseline.from(i), now)
        assertEquals(fresh.content()?.get("nextQuestion"), next.items[i.key]?.content()?.get("nextQuestion"))
        expectFailure { IdeaManagementActions.review(next, ideaID, null, IdeaManagementBaseline.from(i), now) }
    }

    @Test fun inboxToIdeaIsAtomicPreservesCaptureMetadataAndRejectsCollision() {
        val capture = inbox().copy(group = "capture-group", dirty = true)
        val sibling = task().copy(group = "capture-group", dirty = true)
        val s = state(capture, sibling)
        val next = IdeaManagementActions.keepInbox(s, inboxID, ideaID, LibraryBaseline.from(capture), now)
        val converted = next.items.getValue("idea:$ideaID")
        assertTrue(next.items.getValue(capture.key).deleted)
        assertEquals(capture.content()?.get("text"), converted.content()?.get("text"))
        assertEquals(capture.content()?.get("createdAt"), converted.content()?.get("createdAt"))
        assertEquals(capture.content()?.get("futureMetadata"), converted.content()?.get("futureMetadata"))
        assertEquals(capture.payload?.get("futureEnvelope"), converted.payload?.get("futureEnvelope"))
        assertEquals(converted.group, next.items[capture.key]?.group); assertEquals(converted.group, next.items[sibling.key]?.group)
        PayloadValidation.validate("idea", ideaID, converted.payload!!)
        expectFailure { IdeaManagementActions.keepInbox(state(capture, idea()), inboxID, ideaID, LibraryBaseline.from(capture), now) }
        val prepared = StateMachine.prepare(next)
        assertEquals(setOf(capture.key, sibling.key, converted.key), prepared.pending!!.mutations.map { it.key }.toSet())
    }
}
