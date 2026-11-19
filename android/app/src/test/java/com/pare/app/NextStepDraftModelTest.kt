package com.pare.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NextStepDraftModelTest {
    private fun task(next:String="原动作")=Entity("task","example",buildJsonObject {
        put("lifecycle","active");put("content",buildJsonObject {put("text","虚构任务");put("nextAction",next);put("definitionOfDone","")})
    })
    @Test fun incomingStateAndFailedCompletionNeverOverwriteDraft() {
        val model=NextStepDraftModel();model.sync(task())
        model.nextAction="未保存的草稿"
        model.sync(task("另一端动作"))
        assertEquals("未保存的草稿",model.nextAction);assertEquals("原动作",model.expected!!.nextAction)
        assertTrue(model.begin());assertFalse(model.begin())
        model.finish(Result.failure(IllegalStateException("保存失败")),null)
        assertEquals("未保存的草稿",model.nextAction);assertFalse(model.saving)
    }
    @Test fun successfulStepChangeUpdatesBaselineButNeverClearsAnInterveningDraft() {
        val model=NextStepDraftModel();model.sync(task());model.begin()
        model.nextAction="仍在编辑的草稿"
        model.finish(Result.success(Unit),task(""))
        assertEquals("仍在编辑的草稿",model.nextAction)
        assertEquals("",model.expected!!.nextAction)
    }
}
