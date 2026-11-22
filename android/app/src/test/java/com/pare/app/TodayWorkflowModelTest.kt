package com.pare.app

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TodayWorkflowModelTest {
    private val now = Instant.parse("2026-10-30T08:00:00Z")
    private fun task(completed: Boolean = false) = Entity("task", "example", wire.parseToJsonElement("""
        {"lifecycle":"active","content":{"id":"example","text":"虚构行动","completed":$completed,
        "quadrant":1,"createdAt":"2026-10-30T00:00:00Z","nextAction":"打开虚构材料",
        "definitionOfDone":"列出一个问题","estimatedMinutes":45}}
    """).jsonObject)
    private fun state() = SyncState(items = mapOf("task:example" to task()))

    @Test fun minuteValidationAllowsOneDayAndRejectsMissingOrOverflow() {
        assertEquals(1, validTodayMinutes("1"))
        assertEquals(1440, validTodayMinutes("1440"))
        listOf("", "0", "-1", "1441", "1.5", "999999999999").forEach { assertNull(validTodayMinutes(it)) }
    }

    @Test fun initialSupportPreservesExistingActionAndDoneWithoutUsingTotalEstimate() {
        val model = TodayWorkflowModel()
        model.initialize(state(), "example", now, support = true)
        assertEquals("打开虚构材料", model.nextAction)
        assertEquals("列出一个问题", model.definitionOfDone)
        assertEquals("5", model.minutes)
        assertEquals("personal", model.kind)
        assertEquals(5, model.allocation()!!.minutes)
    }

    @Test fun allocationUsesExistingCheckAndCalendarDurationBeforePlannedMinutes() {
        val original = state()
        val planId = ContentActions.planId(original, now)
        val plan = Entity("day_plan", planId, wire.parseToJsonElement("""
            {"content":{"ordered_task_ids":["example"],"task_plans":[{"task_id":"example",
            "scheduled_at":"2026-10-30T09:00:00Z","scheduled_minutes":12,
            "allocation":{"kind":"check","planned_minutes":8}}]}}
        """).jsonObject)
        val model = TodayWorkflowModel()
        model.initialize(original.copy(items = original.items + (plan.key to plan)), "example", now, support = false)
        assertEquals("check", model.kind)
        assertEquals("12", model.minutes)
        assertEquals(planId, model.allocation()!!.planId)
        assertNotNull(model.allocationBaseline!!.scheduledAt)
        assertEquals(8, model.allocationBaseline!!.plannedMinutes)
    }

    @Test fun allocationDoesNotUseFutureDateCalendarMinutesAsTodaysDefault() {
        val original = state()
        val planId = ContentActions.planId(original, now)
        val plan = Entity("day_plan", planId, wire.parseToJsonElement("""
            {"content":{"ordered_task_ids":["example"],"task_plans":[{"task_id":"example",
            "scheduled_at":"2026-11-02T09:00:00Z","scheduled_minutes":120,
            "allocation":{"kind":"check","planned_minutes":8}}]}}
        """).jsonObject)
        val model = TodayWorkflowModel()
        model.initialize(original.copy(items = original.items + (plan.key to plan)), "example", now, support = false)
        assertEquals("8", model.minutes)
        assertFalse(model.allocationBaseline!!.hasCurrentDaySchedule)
    }

    @Test fun smallStepPreservesCheckModeInsteadOfSilentlyReclassifyingEffort() {
        val original = state()
        val id = ContentActions.planId(original, now)
        val plan = Entity("day_plan", id, wire.parseToJsonElement("""
            {"content":{"ordered_task_ids":["example"],"task_plans":[{"task_id":"example",
            "allocation":{"kind":"check","planned_minutes":8}}]}}
        """).jsonObject)
        val model = TodayWorkflowModel()
        model.initialize(original.copy(items = original.items + (plan.key to plan)), "example", now, support = true)
        assertEquals("check", model.kind)
        assertEquals("5", model.minutes)
        assertEquals("check", model.allocation()!!.kind)
    }

    @Test fun failureRetainsDraftAndStableEventIdsAndCanRetry() {
        val model = TodayWorkflowModel()
        model.initialize(state(), "example", now, support = true)
        model.nextAction = "只读一页"
        model.definitionOfDone = "记下一点"
        model.reason = "lowEnergy"
        model.minutes = "10"
        val allocation = model.allocation()
        val blockerId = model.blockerEntryId
        val baseline = model.smallStepBaseline
        assertTrue(model.begin())
        assertFalse(model.begin())
        model.finish(Result.failure(IllegalStateException("另一端已修改")))
        // Recomposition or recreation initializes the same retained model again.
        model.initialize(SyncState(), "example", now.plusSeconds(86400), support = true)
        assertFalse(model.saving)
        assertFalse(model.saved)
        assertEquals("另一端已修改", model.error)
        assertEquals("只读一页", model.nextAction)
        assertEquals("记下一点", model.definitionOfDone)
        assertEquals("lowEnergy", model.reason)
        assertEquals(allocation, model.allocation())
        assertEquals(blockerId, model.blockerEntryId)
        assertEquals(baseline, model.smallStepBaseline)
        assertTrue(model.begin())
        assertNull(model.error)
    }

    @Test fun callbackAcknowledgementPreventsDuplicateSubmissionUntilConsumed() {
        val model = TodayWorkflowModel()
        assertTrue(model.begin())
        model.finish(Result.success(Unit))
        assertFalse(model.saving)
        assertTrue(model.saved)
        assertFalse(model.begin())
        model.clear()
        assertFalse(model.saved)
        assertTrue(model.begin())
    }

    @Test fun unavailableTasksCannotBePlanned() {
        assertTrue(taskCanBePlanned(task()))
        assertFalse(taskCanBePlanned(null))
        assertFalse(taskCanBePlanned(task().copy(deleted = true)))
        assertFalse(taskCanBePlanned(task(completed = true)))
    }

    @Test fun reasonCodesHaveLocalConcreteGuidance() {
        assertEquals(setOf("unclear", "tooLarge", "lowEnergy", "resistance"), supportReasons.map { it.code }.toSet())
        assertTrue(supportReasons.all { it.label.isNotBlank() && it.guidance.isNotBlank() })
    }

    @Test fun cancellingReloadKeepsDraftBaselinesAndRetryIds() {
        val model = TodayWorkflowModel()
        model.initialize(state(), "example", now, support = true)
        model.nextAction = "草稿动作"
        model.definitionOfDone = "草稿停止标准"
        model.reason = "resistance"
        model.minutes = "15"
        val allocation = model.allocation()
        val baseline = model.smallStepBaseline
        val blockerId = model.blockerEntryId
        model.begin()
        model.finish(Result.failure(IllegalStateException("内容已改变")))
        model.requestReload()
        assertTrue(model.reloadRequested)
        model.cancelReload()
        // Calling the confirm action without an active confirmation must also preserve the draft.
        model.confirmReload(state(), "example", now.plusSeconds(86400), support = true)
        assertFalse(model.reloadRequested)
        assertEquals("草稿动作", model.nextAction)
        assertEquals("草稿停止标准", model.definitionOfDone)
        assertEquals("resistance", model.reason)
        assertEquals("15", model.minutes)
        assertEquals(allocation, model.allocation())
        assertEquals(baseline, model.smallStepBaseline)
        assertEquals(blockerId, model.blockerEntryId)
        assertEquals("内容已改变", model.error)
    }

    @Test fun confirmedReloadReadsCurrentFieldsAndNewPlanningDay() {
        val model = TodayWorkflowModel()
        model.initialize(state(), "example", now, support = true)
        val oldAllocationId = model.allocationId
        val oldBlockerId = model.blockerEntryId
        val oldPlanId = model.allocationBaseline!!.planId
        model.nextAction = "未保存草稿"
        model.minutes = "15"
        model.begin()
        model.finish(Result.failure(IllegalStateException("日期或内容已改变")))
        val updatedTask = task().copy(payload = wire.parseToJsonElement("""
            {"lifecycle":"active","content":{"id":"example","text":"更新后的虚构行动","completed":false,
            "nextAction":"最新动作","definitionOfDone":"最新停止标准"}}
        """).jsonObject)
        val updatedState = SyncState(items = mapOf(updatedTask.key to updatedTask))
        val tomorrow = now.plusSeconds(86400)
        model.requestReload()
        model.confirmReload(updatedState, "example", tomorrow, support = true)
        assertFalse(model.reloadRequested)
        assertNull(model.error)
        assertEquals("最新动作", model.nextAction)
        assertEquals("最新停止标准", model.definitionOfDone)
        assertEquals("5", model.minutes)
        assertEquals(SmallStepBaseline.from(updatedTask), model.smallStepBaseline)
        assertEquals(ContentActions.planId(updatedState, tomorrow), model.allocationBaseline!!.planId)
        assertNotEquals(oldPlanId, model.allocationBaseline!!.planId)
        assertNotEquals(oldAllocationId, model.allocationId)
        assertNotEquals(oldBlockerId, model.blockerEntryId)
    }
}
