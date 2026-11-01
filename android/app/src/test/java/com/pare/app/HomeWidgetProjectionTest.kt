package com.pare.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HomeWidgetProjectionTest {
    private val a="00000000-0000-4000-8000-000000000001"
    private val b="00000000-0000-4000-8000-000000000002"
    private val runId="00000000-0000-4000-8000-000000000003"
    private val now=ExecutionMoment(Instant.parse("2026-11-01T08:00:00Z").toEpochMilli(),10_000,"boot")
    private fun fixture():SyncState {
        var state=SyncState(device="fictional-widget-device")
        for(id in listOf(a,b))state=StateMachine.edit(state,"task",id,ContentActions.save(null,"task","示例任务 ${id.takeLast(1)}","",1,id,Instant.ofEpochMilli(now.wallMillis),nextAction="写下第一个句子"))
        state=StateMachine.edit(state,"profile","profile",buildJsonObject {put("content",buildJsonObject {put("planningTimeZone","Asia/Shanghai")})})
        val plan=ContentActions.planId(state,Instant.ofEpochMilli(now.wallMillis))
        return StateMachine.edit(state,"day_plan",plan,buildJsonObject {put("content",buildJsonObject {
            put("ordered_task_ids",JsonArray(listOf(JsonPrimitive(a),JsonPrimitive(b))));put("daily_win_task_id",b)
        })})
    }
    private fun focus(state:SyncState,paused:Boolean=false)=ExecutionSnapshot(device=state.device,focus=ExecutionRun(runId,a,now.wallMillis,anchor=if(paused)null else now,targetMinutes=5,paused=paused))

    @Test fun dailyPriorityAndMinimumNextStepComeFromTodayPlan() {
        val result=HomeWidgetProjection.project(fixture(),null,now)
        assertEquals("示例任务 2",result.title)
        assertEquals("下一步 · 写下第一个句子",result.nextStep)
        assertEquals(HomeWidgetRequest(HomeWidgetAction.START_FOCUS,b),result.request)
        assertTrue(result.heading.contains("11/1"))
    }
    @Test fun completedPriorityFallsBackToFirstOrderedActiveTask() {
        val state=fixture();val task=state.items.getValue("task:$b")
        val completed=StateMachine.edit(state,"task",b,JsonObject(task.payload!!+("content" to JsonObject(task.content()!!+("completed" to JsonPrimitive(true))))))
        assertEquals(a,HomeWidgetProjection.project(completed,null,now).request.taskId)
    }
    @Test fun activeFocusControlsItsOwnRunWithoutReplacingTodayPriority() {
        val state=fixture();val result=HomeWidgetProjection.project(state,focus(state),now)
        assertEquals("示例任务 2",result.title)
        assertTrue(result.status.contains("示例任务 1"))
        assertEquals(HomeWidgetRequest(HomeWidgetAction.PAUSE_FOCUS,a,runId),result.request)
    }
    @Test fun pausedAndDueRunsHaveDifferentActions() {
        val state=fixture()
        assertEquals(HomeWidgetAction.RESUME_FOCUS,HomeWidgetProjection.project(state,focus(state,true),now).request.action)
        val later=now.copy(wallMillis=now.wallMillis+300_000,elapsedMillis=now.elapsedMillis+300_000)
        assertEquals(HomeWidgetAction.REVIEW_FOCUS,HomeWidgetProjection.project(state,focus(state),later).request.action)
    }
    @Test fun anotherOwnerOrDeviceNeverPublishesTheirRun() {
        val state=fixture()
        for(snapshot in listOf(focus(state).copy(owner="other-owner"),focus(state).copy(device="other-device"))) {
            assertEquals(HomeWidgetAction.START_FOCUS,HomeWidgetProjection.project(state,snapshot,now).request.action)
        }
    }
    @Test fun rebootAndUnreadableExecutionRequireReviewInsteadOfPretendingToRun() {
        val state=fixture()
        val reboot=HomeWidgetProjection.project(state,focus(state),now.copy(bootId="new-boot"))
        assertEquals(HomeWidgetAction.REVIEW_FOCUS,reboot.request.action)
        assertTrue(reboot.status.contains("需检查"))
        assertEquals(HomeWidgetAction.TODAY,HomeWidgetProjection.project(state,null,now,executionUnavailable=true).request.action)
    }
    @Test fun tomorrowDoesNotReuseYesterdaysSelection() {
        val result=HomeWidgetProjection.project(fixture(),null,now.copy(wallMillis=now.wallMillis+86_400_000))
        assertEquals(HomeWidgetAction.TODAY,result.request.action)
        assertEquals("今天还没有选定行动",result.title)
    }
    @Test fun nextRefreshUsesPlanningZoneMidnightOrSoonerFocusDeadline() {
        val state=fixture()
        assertEquals(Instant.parse("2026-11-01T16:00:00Z").toEpochMilli(),HomeWidgetProjection.nextRefreshAt(state,null,now))
        assertEquals(now.wallMillis+300_000,HomeWidgetProjection.nextRefreshAt(state,focus(state),now))
    }
    @Test fun externalWaitIsNeverPresentedAsPersonalFocus() {
        val state=fixture()
        val execution=ExecutionSnapshot(device=state.device,external=listOf(ExecutionRun(runId,b,now.wallMillis,anchor=now)))
        val result=HomeWidgetProjection.project(state,execution,now)
        assertEquals("查看推进",result.actionLabel)
        assertTrue(result.status.contains("等待结果"))
        assertEquals(HomeWidgetRequest(HomeWidgetAction.REVIEW_FOCUS,b,runId),result.request)
    }
    private fun olderPlanSchedule(start:String,minutes:Int):SyncState {
        val state=fixture().let {it.copy(items=it.items.filterValues {entity->entity.kind!="day_plan"})}
        return StateMachine.edit(state,"day_plan","2026-10-31@Asia/Shanghai",buildJsonObject {put("content",buildJsonObject {
            put("day_key","2026-10-31");put("time_zone","Asia/Shanghai")
            put("ordered_task_ids",JsonArray(listOf(JsonPrimitive(a))))
            put("task_plans",JsonArray(listOf(buildJsonObject {
                put("task_id",a);put("scheduled_at",start);put("scheduled_minutes",minutes)
            })))
        })})
    }
    @Test fun visibleLegacyAndCrossMidnightBlocksAppearWithoutCurrentDayOrderedIds() {
        // Both a Mac legacy snapshot stored on yesterday and yesterday's canonical overnight block
        // are visible in Today's overview even though today's plan has never been created.
        for(start in listOf("2026-11-01T02:00:00Z","2026-10-31T15:30:00Z")) {
            val state=olderPlanSchedule(start,90)
            val day=TimelineDay.fromPlanId(ContentActions.planId(state,Instant.ofEpochMilli(now.wallMillis)))
            assertEquals(a,TodayOverviewProjection.project(state,day).scheduled.single().task.id)
            val result=HomeWidgetProjection.project(state,null,now)
            assertEquals("示例任务 1",result.title)
            assertEquals("下一步 · 写下第一个句子",result.nextStep)
            assertEquals(HomeWidgetRequest(HomeWidgetAction.START_FOCUS,a),result.request)
        }
    }
    @Test fun previousPlanSchedulesOutsideTodayNeverBecomeTodaySelection() {
        for(start in listOf("2026-10-31T02:00:00Z","2026-11-02T02:00:00Z")) {
            val state=olderPlanSchedule(start,30)
            val day=TimelineDay.fromPlanId(ContentActions.planId(state,Instant.ofEpochMilli(now.wallMillis)))
            val overview=TodayOverviewProjection.project(state,day)
            assertTrue(overview.scheduled.isEmpty() && overview.unscheduled.isEmpty())
            val result=HomeWidgetProjection.project(state,null,now)
            assertEquals("今天还没有选定行动",result.title)
            assertEquals(HomeWidgetRequest(HomeWidgetAction.TODAY),result.request)
        }
    }
}
