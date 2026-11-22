package com.pare.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class IdeaWorkspacePresentationTest {
    private fun idea(stage: String = "exploring") = Entity("idea", "fictional-idea", buildJsonObject {
        put("lifecycle", "active")
        putJsonObject("content") { put("stage", stage); put("text", "设计读书角") }
    })

    @Test fun archiveAndDeletionPreventNewWork() {
        assertTrue(ideaCanGrow(idea()))
        assertFalse(ideaCanGrow(idea("archived")))
        assertFalse(ideaCanGrow(idea().copy(deleted = true)))
        assertFalse(ideaCanGrow(null))
    }

    @Test fun timelineKeepsEveryEntryAndUsesStableReverseChronology() {
        val source = idea()
        val payload = buildJsonObject { putJsonObject("content") {
            put("entries", wire.parseToJsonElement("""[
              {"id":"b","text":"第二条","kind":"decision","createdAt":"2026-10-30T10:00:00Z"},
              {"id":"a","text":"同一刻的发现","kind":"finding","createdAt":"2026-10-30T10:00:00Z"},
              {"id":"older","text":"最初的想法","createdAt":"2026-10-29T10:00:00Z"}, null]
            """))
        } }
        val entries = ideaTimeline(source.copy(payload = payload))
        assertEquals(listOf("a", "b", "older"), entries.map { it.id })
        assertEquals("note", entries.last().kind)
        assertEquals("发现", ideaEntryKindLabel(entries.first().kind))
    }

    @Test fun failedSaveRetainsDraftAndStableIdentifierAndAllowsRetry() {
        val holder = IdeaWorkspaceDraftViewModel()
        holder.entry = IdeaEntryDraft(text = "角落采光很好", kind = "finding")
        val draft = holder.entry
        holder.open("entry")
        assertTrue(holder.begin())
        assertFalse(holder.begin())
        holder.back()
        assertEquals("entry", holder.form)
        holder.finish(Result.failure(java.io.IOException("磁盘忙")))
        assertFalse(holder.saving)
        assertEquals(draft, holder.entry)
        assertTrue(holder.error!!.contains("草稿已保留"))
        assertTrue(holder.begin())
        holder.finish(Result.success(Unit))
        assertEquals("", holder.form)
        assertNull(holder.entry)
    }

    @Test fun validationNeverWritesAndReturningPreservesDraft() {
        val holder = IdeaWorkspaceDraftViewModel()
        holder.entry = IdeaEntryDraft(text = "未保存的记录")
        holder.open("entry")
        assertFalse(holder.begin("请填写记录"))
        assertFalse(holder.saving)
        holder.back()
        holder.open("entry")
        assertEquals("未保存的记录", holder.entry!!.text)
    }

    @Test fun derivedActionDefaultsAndValidationKeepStableRetryIds() {
        val id = uid()
        val baseline = DayAllocationBaseline.from(SyncState(), id, java.time.Instant.parse("2026-10-30T10:00:00Z"))
        val draft = IdeaActionDraft(id = id, baseline = baseline)
        assertEquals(1, draft.quadrant)
        assertFalse(draft.today)
        assertNotNull(draft.validationError())
        val ready = draft.copy(title = "量一下书架的尺寸", next = "拿出卷尺", done = "记下宽高")
        assertNull(ready.validationError())
        listOf("0", "-1", "1.5", "1441", "abc").forEach {
            assertNotNull(ready.copy(today = true, minutes = it).validationError())
        }
        assertNull(ready.copy(today = true, minutes = "5").validationError())
        val holder = IdeaWorkspaceDraftViewModel()
        holder.action = ready
        holder.open("action")
        assertTrue(holder.begin())
        holder.finish(Result.failure(java.io.IOException("保存失败")))
        assertEquals(id, holder.action!!.id)
        assertEquals(draft.allocationId, holder.action!!.allocationId)
        assertEquals(baseline, holder.action!!.baseline)
    }


    @Test fun timelineUsesActualInstantsAcrossFractionPrecisionAndOffsets() {
        val payload = buildJsonObject { putJsonObject("content") {
            put("entries", wire.parseToJsonElement("""[
              {"id":"whole","text":"整秒","createdAt":"2026-10-30T10:00:00Z"},
              {"id":"fraction","text":"稍后","createdAt":"2026-10-30T10:00:00.100Z"},
              {"id":"a-equivalent","text":"同一刻","createdAt":"2026-10-30T18:00:00+08:00"},
              {"id":"invalid","text":"旧记录","createdAt":"unknown"}]
            """))
        } }
        assertEquals(listOf("fraction", "a-equivalent", "whole", "invalid"),
            ideaTimeline(idea().copy(payload = payload)).map { it.id })
    }

    @Test fun midnightRecoveryRequiresChoiceAndKeepsActionTextAndIdentifiers() {
        val state = SyncState()
        val before = java.time.Instant.parse("2026-10-30T10:00:00Z")
        val after = before.plusSeconds(86_400)
        val id = uid()
        val original = IdeaActionDraft(id = id, title = "量书架", next = "找卷尺", done = "记下宽高", quadrant = 2,
            today = true, minutes = "10", baseline = DayAllocationBaseline.from(state, id, before))
        val holder = IdeaWorkspaceDraftViewModel()
        holder.action = original
        holder.open("action")
        assertTrue(holder.begin())
        holder.finish(Result.failure(IllegalStateException("安排日期已变化")))
        assertTrue(holder.requestActionReload(state, after))
        assertEquals(original, holder.action)
        assertNotEquals(original.baseline.planId, holder.pendingActionReload!!.baseline.planId)
        holder.cancelActionReload()
        assertEquals(original, holder.action)
        assertNull(holder.pendingActionReload)
        assertTrue(holder.requestActionReload(state, after))
        assertTrue(holder.confirmActionReload(state))
        val refreshed = holder.action!!
        assertEquals(DayAllocationBaseline.from(state, id, after), refreshed.baseline)
        assertEquals(original, refreshed.copy(baseline = original.baseline))
        assertNull(holder.error)
    }

    @Test fun actionReloadCannotAdoptAnExistingOrDeletedTaskIdentifier() {
        val state = SyncState()
        val now = java.time.Instant.parse("2026-10-30T10:00:00Z")
        val id = uid()
        val draft = IdeaActionDraft(id = id, title = "量书架", baseline = DayAllocationBaseline.from(state, id, now))
        val holder = IdeaWorkspaceDraftViewModel()
        holder.action = draft
        val collision = state.copy(items = mapOf("task:$id" to Entity("task", id, deleted = true)))
        assertFalse(holder.requestActionReload(collision, now))
        assertEquals(draft, holder.action)
        assertNull(holder.pendingActionReload)
        assertTrue(holder.requestActionReload(state, now))
        assertFalse(holder.confirmActionReload(collision))
        assertEquals(draft, holder.action)
        assertNotNull(holder.error)
    }

}
