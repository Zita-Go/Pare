package com.pare.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PresentationTest {
    private fun entity(id: String, created: String) = Entity("task", id,
        buildJsonObject { putJsonObject("content") { put("id", id); put("text", id); put("createdAt", created) } })

    @Test fun orderingIsStableAcrossDatabaseAndSyncInsertionOrder() {
        val a = entity("a", "2026-10-29T01:00:00Z")
        val b = entity("b", "2026-10-29T01:00:00Z")
        val later = entity("later", "2026-10-30T01:00:00Z")
        assertEquals(listOf("a", "b", "later"), stableEntities(listOf(later, b, a)).map { it.id })
        assertEquals(stableEntities(listOf(a, b, later)), stableEntities(listOf(b, later, a)))
    }

    @Test fun translatesEverySharedIdeaStageWithSafeFutureFallback() {
        assertEquals(listOf("萌芽", "探索", "推进", "暂放", "归档"),
            listOf("seed", "exploring", "building", "parked", "archived").map(::ideaStageLabel))
        assertEquals("萌芽", ideaStageLabel(null))
        assertEquals("其他阶段", ideaStageLabel("future_stage"))
    }

    @Test fun localSaveFailureKeepsEditorOpenAndAllowsRetry() {
        val draft = EditorDraft("stable-id", "task", text = "保留草稿", nextAction = "打开笔记", estimate = "15")
        val saving = EditorSaveState().begin(draft)
        assertTrue(saving.saving)
        assertFalse(saving.saved)
        val failed = saving.finish(Result.failure(java.io.IOException("disk full")))
        assertFalse(failed.saving)
        assertFalse(failed.saved)
        assertTrue(failed.error!!.contains("草稿已保留"))
        assertTrue(failed.begin(draft).saving)
        assertTrue(failed.begin(draft).finish(Result.success(Unit)).saved)
        assertEquals("stable-id", draft.entityId)
    }

    @Test fun invalidEstimatesNeverBeginSave() {
        listOf("0", "-1", "1.5", "abc", "2147483648").forEach { value ->
            val result = EditorSaveState().begin(EditorDraft("id", "task", text = "task", estimate = value))
            assertFalse(result.saving)
            assertNotNull(result.error)
        }
        assertTrue(EditorSaveState().begin(EditorDraft("id", "task", text = "task")).saving)
    }

    @Test fun conflictComparisonIncludesNotesNextActionEstimateAndStatus() {
        val payload = wire.parseToJsonElement("""{"lifecycle":"active","content":{"text":"同一个标题","note":"不同备注","nextAction":"第一步","estimatedMinutes":30,"completed":true,"quadrant":2}}""").jsonObject
        val fields = conflictFields("task", payload, false).associate { it.label to it.value }
        assertEquals("不同备注", fields["备注"])
        assertEquals("第一步", fields["下一步"])
        assertEquals("30 分钟", fields["预计时长"])
        assertEquals("已完成", fields["状态"])
        assertEquals("紧急 · 不重要", fields["优先级"])
        assertEquals(listOf(ConflictField("状态", "已删除")), conflictFields("task", null, true))
    }

    @Test fun dayPlanConflictUsesWireDayKeyAndReadableTaskNames() {
        val payload = wire.parseToJsonElement("""{"content":{"day_key":"2026-10-30","time_zone":"Asia/Shanghai","ordered_task_ids":["task-id"],"daily_win_task_id":"task-id"}}""").jsonObject
        val fields = conflictFields("day_plan", payload, false, mapOf("task-id" to "准备会议")).associate { it.label to it.value }
        assertEquals("2026-10-30", fields["日期"])
        assertEquals("准备会议", fields["今日任务"])
        assertEquals("准备会议", fields["今日重点"])
    }

    @Test fun openingAnotherItemRequiresExplicitDraftSwitchDecision() {
        val retained = EditorDraft("task-a", "task", text = "还没有保存的编辑")
        val requested = EditorDraft("task-b", "task", text = "另一件事", isNew = false)
        assertEquals(DraftOpenAction.CONFIRM_SWITCH, draftOpenAction(retained, requested))
        assertEquals(DraftOpenAction.RESUME_EXISTING, draftOpenAction(retained, retained.copy(text = "已持久化的旧内容")))
        assertEquals(DraftOpenAction.OPEN_REQUESTED, draftOpenAction(EditorDraft("", "inbox"), requested))
        assertEquals("还没有保存的编辑", retained.text)
    }
}
