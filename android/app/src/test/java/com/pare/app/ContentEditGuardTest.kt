package com.pare.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ContentEditGuardTest {
    private val now = Instant.parse("2026-10-30T10:00:00Z")
    private val original = Entity("task", "task-id", wire.parseToJsonElement("""{"lifecycle":"active","content":{"id":"task-id","text":"原始任务","note":"原始备注","quadrant":1,"nextAction":"第一步","estimatedMinutes":20,"completed":false,"createdAt":"2026-10-30T00:00:00Z"}}""").jsonObject, revision = 3)
    private fun updated(field: String, value: JsonElement) = original.copy(payload = JsonObject(original.payload!!.toMutableMap().apply {
        put("content", JsonObject(original.content()!!.toMutableMap().apply { put(field, value) }))
    }), revision = 4)
    private fun save(current: Entity?, expected: ContentEditBaseline = ContentEditBaseline.from(original)) =
        ContentActions.save(current, "task", "草稿中的任务", "原始备注", 1, "task-id", now,
            estimatedMinutes = 20, nextAction = "第一步", expected = expected)

    @Test fun remotelyChangedEditorFieldsRejectStaleDraftWithoutOverwriting() {
        mapOf<String, JsonElement>("text" to JsonPrimitive("Mac 修改"), "note" to JsonPrimitive("新备注"),
            "quadrant" to JsonPrimitive(3), "nextAction" to JsonPrimitive("新步骤"), "estimatedMinutes" to JsonPrimitive(35)).forEach { (field, value) ->
            val current = updated(field, value)
            val error = assertThrows(StaleContentEditException::class.java) { save(current) }
            assertEquals(StaleEditReason.UPDATED, error.reason)
            assertEquals(value, current.content()!![field])
        }
    }

    @Test fun deletionOrMissingRecordCannotBeResurrectedByAnOpenEditor() {
        listOf(null, original.copy(payload = null, deleted = true, revision = 4)).forEach { current ->
            val error = assertThrows(StaleContentEditException::class.java) { save(current) }
            assertEquals(StaleEditReason.DELETED, error.reason)
        }
    }

    @Test fun newDraftCannotOverwriteAnExistingOrTombstonedStableId() {
        listOf(original, original.copy(payload = null, deleted = true)).forEach { current ->
            val error = assertThrows(StaleContentEditException::class.java) { save(current, ContentEditBaseline(isNew = true)) }
            assertEquals(StaleEditReason.ALREADY_EXISTS, error.reason)
        }
        assertEquals("草稿中的任务", save(null, ContentEditBaseline(isNew = true))["content"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun acknowledgementRevisionAndUnknownHistoryChangesDoNotRejectValidEdit() {
        val sessions = JsonArray(listOf(buildJsonObject { put("id", "new-session") }))
        val current = updated("progressSessions", sessions).copy(revision = 99)
        val saved = save(current)["content"]!!.jsonObject
        assertEquals(sessions, saved["progressSessions"])
        assertEquals("草稿中的任务", saved["text"]!!.jsonPrimitive.content)
        assertEquals(false, saved["completed"]!!.jsonPrimitive.boolean)
    }

    @Test fun editorOriginalSnapshotProducesSameBaselineAfterDraftChanges() {
        val opened = EditorDraft.from(original)
        val draft = opened.copy(text = "手机的草稿", note = "新的草稿备注")
        assertEquals(ContentEditBaseline.from(original), opened.editBaseline())
        assertNotEquals(opened.editBaseline(), draft.editBaseline())
        val error = assertThrows(StaleContentEditException::class.java) { save(updated("text", JsonPrimitive("Mac 修改")), opened.editBaseline()) }
        val result = EditorSaveState(saving = true).finish(Result.failure(error))
        assertFalse(result.saved)
        assertTrue(result.error!!.contains("另一端更新"))
        assertTrue(result.error!!.contains("草稿已保留"))
    }

    @Test fun remoteCompletionIsPreservedBecauseTheTextEditorDoesNotWriteIt() {
        val saved = save(updated("completed", JsonPrimitive(true)))["content"]!!.jsonObject
        assertTrue(saved["completed"]!!.jsonPrimitive.boolean)
    }

    @Test fun ideaNotesUseTheirWireFieldForTheBaselineGuard() {
        val idea = Entity("idea", "idea-id", wire.parseToJsonElement("""{"content":{"id":"idea-id","text":"想法","notes":"原始想法备注","stage":"seed"}}""").jsonObject)
        val current = idea.copy(payload = wire.parseToJsonElement("""{"content":{"id":"idea-id","text":"想法","notes":"Mac 想法备注","stage":"seed"}}""").jsonObject)
        assertThrows(StaleContentEditException::class.java) {
            ContentActions.save(current, "idea", "手机想法草稿", "原始想法备注", 1, "idea-id", now, expected = ContentEditBaseline.from(idea))
        }
    }
}
