package com.pare.app

import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Writes only deterministic fictional payloads for the offline Mac SyncPayload decoder. */
class Workflow09CompatibilityTest {
    @Test fun exportSynthetic09WorkflowWireForMacDecoder() {
        val now = Instant.parse("2026-10-31T09:00:00Z")
        val taskId = "95000000-0000-0000-0000-000000000001"
        val stepId = "95000000-0000-0000-0000-000000000002"
        val goalId = "95000000-0000-0000-0000-000000000003"
        val ideaId = "95000000-0000-0000-0000-000000000004"
        val inboxId = "95000000-0000-0000-0000-000000000005"
        val preferenceId = "95000000-0000-0000-0000-000000000006"
        val scopedPreferenceId = "95000000-0000-0000-0000-000000000007"
        val source = "2026-10-31@UTC"; val target = "2026-11-01@UTC"
        var state = SyncState(device = "95000000-0000-0000-0000-000000000010")
        state = StateMachine.edit(state, "task", taskId, ContentActions.save(null, "task", "虚构：完成一页观察记录", "兼容性验收用虚构背景", 1, taskId, now,
            estimatedMinutes = 45, nextAction = "虚构：打开样例记录页"))
        state = StateMachine.edit(state, "idea", ideaId, ContentActions.save(null, "idea", "虚构：制作自然观察手册", "所有材料均为测试资料", 1, ideaId, now))
        state = StateMachine.edit(state, "inbox", inboxId, ContentActions.save(null, "inbox", "虚构：查找下一条观察路线", "", 1, inboxId, now))
        state = GoalsActions.save(state, GoalDraft(id = goalId, title = "虚构：形成一个月的观察记录", motivation = "把每日小行动连成可以回看的成果",
            targetDate = "2026-12-01T09:00:00Z", taskIds = listOf(taskId), newStep = "虚构：写下一个观察问题", newTaskId = stepId), GoalBaseline.from(null), now)
        state = PreferencesActions.saveProfile(state, ProfileDraft("虚构的周末观察者", 45, 5, "虚构偏好：每次只安排有限投入"), ProfileBaseline.from(null), ZoneId.of("UTC"))
        state = PreferencesActions.saveConfirmed(state, ConfirmedPreferenceDraft(preferenceId, "虚构偏好：先做五分钟"), PreferenceBaseline.from(state.items["profile:profile"], preferenceId), now)
        state = PreferencesActions.saveConfirmed(state, ConfirmedPreferenceDraft(scopedPreferenceId, "虚构偏好：观察手册只记录一种植物", ideaId, "2027-01-01T00:00:00Z"),
            PreferenceBaseline.from(state.items["profile:profile"], scopedPreferenceId), now)
        state = ImplementationPlanActions.save(state, taskId, ImplementationPlanBaseline.from(state, taskId), ImplementationPlanDraft(true, 9, 15, "虚构：先打开记录页"))
        state = TaskMetadataActions.save(state, taskId, TaskMetadataBaseline.from(state.items.getValue("task:$taskId")), Instant.parse("2026-11-01T17:00:00Z"), 8, now)
        state = TaskReminderActions.save(state, taskId, TaskReminderBaseline.from(state.items.getValue("task:$taskId")), Instant.parse("2026-11-01T10:05:00Z"), null, now)
        state = IdeaManagementActions.link(state, ideaId, taskId, true, IdeaManagementBaseline.from(state.items.getValue("idea:$ideaId")), now)
        state = IdeaManagementActions.review(state, ideaId, now.plusSeconds(7 * 86400L), IdeaManagementBaseline.from(state.items.getValue("idea:$ideaId")), now)
        state = IdeaActions.appendEntry(state, ideaId, "95000000-0000-0000-0000-000000000008", "虚构发现：先记录一种植物更容易开始", "finding", now)
        state = DayPlanActions.allocate(state, taskId, TodayAllocationDraft(source, "personal", 45, "95000000-0000-0000-0000-000000000011", DayAllocationBaseline.from(state, taskId, now)), now)
        state = DayPlanActions.allocate(state, stepId, TodayAllocationDraft(source, "check", 5, "95000000-0000-0000-0000-000000000012", DayAllocationBaseline.from(state, stepId, now)), now)
        state = TimelineActions.setSchedule(state, taskId, source, Instant.parse("2026-10-31T10:15:00Z"), 45, TimelineScheduleBaseline.from(state, source, taskId), 10)
        val move = TimelineMoveBaseline.capture(state, TimelineScheduleBaseline.from(state, source, taskId), target)
        state = TimelineActions.moveToDay(state, taskId, Instant.parse("2026-11-01T10:15:00Z"), 45, move, 10)

        assertEquals(setOf(taskId, stepId), state.items.getValue("goal:$goalId").content()!!.getValue("taskIDs").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(2, PreferencesActions.entries(state.items["profile:profile"]).size)
        assertEquals(1, TimelineProjection.schedules(state).size)
        assertEquals(target, TimelineProjection.schedules(state).single().sourcePlanId)
        assertEquals("check", (state.items.getValue("day_plan:$source").content()!!.getValue("task_plans").jsonArray.single().jsonObject["allocation"] as JsonObject)["kind"]?.jsonPrimitive?.content)
        val entities = state.items.values.filterNot { it.deleted }
        entities.forEach { PayloadValidation.validate(it.kind, it.id, requireNotNull(it.payload)) }
        val payloads = JsonObject(entities.associate { it.key to requireNotNull(it.payload) })
        assertEquals(8, payloads.size)
        assertFalse(payloads.toString().contains("localTrash")); assertFalse(payloads.toString().contains("accessToken"))
        assertFalse(payloads.toString().contains("refreshToken")); assertFalse(payloads.toString().contains("p_runs"))
        java.io.File(System.getProperty("pare.fixture.outputDir", "/private/tmp"), "pare-09-workflow-wire.json").writeText(payloads.toString(), Charsets.UTF_8)
    }
}
