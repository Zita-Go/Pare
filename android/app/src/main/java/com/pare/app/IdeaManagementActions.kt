package com.pare.app

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

data class IdeaManagementBaseline(val links: JsonElement?, val reviewAt: JsonElement?) {
    companion object { fun from(idea: Entity) = IdeaManagementBaseline(idea.content()?.get("linkedTaskIDs"), idea.content()?.get("reviewAt")) }
}

object IdeaManagementActions {
    fun link(state: SyncState, ideaId: String, taskId: String, linked: Boolean, expected: IdeaManagementBaseline, now: Instant): SyncState {
        val idea = libraryCurrent(state, "idea", ideaId)
        if (IdeaManagementBaseline.from(idea).links != expected.links) throw StaleContentEditException(StaleEditReason.UPDATED)
        // Unlinking a historical/missing action never deletes the task itself.
        if (linked) libraryCurrent(state, "task", taskId)
        val links = (idea.content()?.get("linkedTaskIDs") as? JsonArray).orEmpty()
        val hasLink = JsonPrimitive(taskId) in links
        if (hasLink == linked) return state
        val nextLinks = if (linked) links + JsonPrimitive(taskId) else links.filterNot { it == JsonPrimitive(taskId) }
        return libraryReplace(state, idea, idea.content()!!.toMutableMap().apply {
            put("linkedTaskIDs", JsonArray(nextLinks)); put("updatedAt", JsonPrimitive(now.toString()))
        })
    }

    fun review(state: SyncState, ideaId: String, at: Instant?, expected: IdeaManagementBaseline, now: Instant): SyncState {
        val idea = libraryCurrent(state, "idea", ideaId)
        if (IdeaManagementBaseline.from(idea).reviewAt != expected.reviewAt) throw StaleContentEditException(StaleEditReason.UPDATED)
        return libraryReplace(state, idea, idea.content()!!.toMutableMap().apply {
            if (at == null) remove("reviewAt") else put("reviewAt", JsonPrimitive(at.toString()))
            put("updatedAt", JsonPrimitive(now.toString()))
        })
    }

    fun keepInbox(state: SyncState, inboxId: String, ideaId: String, expected: LibraryBaseline, now: Instant): SyncState {
        require(UUID.fromString(ideaId).toString() == ideaId)
        val inbox = libraryCurrent(state, "inbox", inboxId)
        expected.check(inbox)
        require(state.items["idea:$ideaId"] == null) { "想法标识已经存在，未重复转换" }
        val content = inbox.content()!!.toMutableMap().apply {
            put("id", JsonPrimitive(ideaId)); put("updatedAt", JsonPrimitive(now.toString()))
            put("stage", JsonPrimitive("seed")); put("linkedTaskIDs", JsonArray(emptyList())); put("entries", JsonArray(emptyList()))
        }
        // Preserve original captured text, createdAt and future metadata atomically.
        var result = StateMachine.edit(state, "idea", ideaId, JsonObject(inbox.payload!!.toMutableMap().apply { put("content", JsonObject(content)) }))
        result = StateMachine.edit(result, "inbox", inboxId, null, deleted = true)
        return StateMachine.group(result, setOf(inbox.key, "idea:$ideaId"))
    }
}
