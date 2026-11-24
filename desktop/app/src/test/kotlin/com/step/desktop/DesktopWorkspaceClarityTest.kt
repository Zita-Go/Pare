package com.step.desktop

import androidx.compose.ui.test.*
import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

@OptIn(ExperimentalTestApi::class)
class DesktopWorkspaceClarityTest {
    @Test fun todayCreatesDirectlyIntoTheVisibleSelectedDate()=runDesktopComposeUiTest(width=1280,height=900) {
        val r=fixture()
        try {
            val today=ContentActions.planId(r.engine.state.value,Instant.now())
            val tomorrow=TimelineDay.fromPlanId(today).let {TimelineDay(it.date.plusDays(1),it.zone).planId}
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("planning_next_day").performClick()
            onNodeWithTag("create_day_task").performClick()
            onNode(hasSetTextAction() and hasText("任务名称")).performTextReplacement("虚构｜明天整理纸卡")
            onNodeWithTag("task_create").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000){r.engine.state.value.items.values.any {it.title()=="虚构｜明天整理纸卡"}}
            val state=r.engine.state.value;val task=state.items.values.single {it.title()=="虚构｜明天整理纸卡"}
            assertTrue(DayTaskSelection.contains(state,task.id,tomorrow))
            assertFalse(DayTaskSelection.contains(state,task.id,today))
        } finally {r.close()}
    }

    @Test fun quadrantDateAndVisibleInclusionPrecedeSettingTheFocus()=runDesktopComposeUiTest(width=1280,height=900) {
        val r=fixture()
        try {
            val day=TimelineDay.fromPlanId(ContentActions.planId(r.engine.state.value,Instant.now()))
            val tomorrow=TimelineDay(day.date.plusDays(1),day.zone).planId
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("planning_next_day").performClick()
            onNodeWithTag("nav_四象限").performClick()
            onNodeWithTag("planning_date").assertTextContains("${day.date.plusDays(1).monthValue}月${day.date.plusDays(1).dayOfMonth}日",substring=true)
            onNodeWithTag("day_win_$FIRST").assertDoesNotExist()
            onNodeWithTag("day_include_$FIRST").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis=5_000){DayTaskSelection.contains(r.engine.state.value,FIRST,tomorrow)}
            onNodeWithTag("day_win_$FIRST").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis=5_000){planWin(r.engine.state.value,tomorrow,FIRST)}
            assertNull(r.execution.state.value.focus)
        } finally {r.close()}
    }

    @Test fun scheduleOverviewOpensScheduleFieldsDirectly()=runDesktopComposeUiTest(width=1280,height=900) {
        val r=fixture()
        try {
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("schedule_task_$FIRST").assertIsDisplayed().performClick()
            onNode(hasSetTextAction() and hasText("开始时间 HH:mm")).assertIsDisplayed()
            onNode(hasSetTextAction() and hasText("下一步具体做什么？")).assertDoesNotExist()
        } finally {r.close()}
    }

    @Test fun featuredActionStartsFiveMinutesWithoutFinishingItsTask()=runDesktopComposeUiTest(width=1280,height=900) {
        val r=fixture()
        try {
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("featured_start_$FIRST").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis=5_000){r.execution.state.value.focus?.taskId==FIRST}
            assertEquals(5,r.execution.state.value.focus?.targetMinutes)
            assertFalse(r.engine.state.value.items.getValue("task:$FIRST").completed())
            assertEquals("写三个标题",r.engine.state.value.items.getValue("task:$FIRST").content().str("nextAction"))
        } finally {r.close()}
    }

    @Test fun overnightTimeBlockOpensItsOriginalPlanningDate()=runDesktopComposeUiTest(width=1280,height=900) {
        val r=fixture()
        try {
            val today=TimelineDay.fromPlanId(ContentActions.planId(r.engine.state.value,Instant.now()))
            val source=TimelineDay(today.date.minusDays(1),today.zone)
            r.edit {TimelineActions.setSchedule(it,SECOND,source.planId,source.date.atTime(23,45).atZone(source.zone).toInstant(),45,
                TimelineScheduleBaseline.from(it,source.planId,SECOND))}.getOrThrow()
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("schedule_task_$SECOND").assertIsDisplayed().performClick()
            onNode(hasSetTextAction() and hasText("开始时间 HH:mm")).assertTextContains("23:45")
            onNodeWithText(source.date.toString(),substring=true).assertExists()
        } finally {r.close()}
    }

    @Test fun compactTodayPrioritizesActionsAndOffersTheTimeOverviewOnDemand()=runDesktopComposeUiTest(width=960,height=760) {
        val r=fixture()
        try {
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("time_overview").assertDoesNotExist()
            onNodeWithTag("task_$THIRD").assertIsDisplayed()
            onNodeWithTag("schedule_overview_toggle").assertIsDisplayed().performClick()
            onNodeWithTag("time_overview").assertIsDisplayed()
            onNodeWithTag("schedule_task_$FIRST").assertIsDisplayed().performClick()
            onNode(hasSetTextAction() and hasText("开始时间 HH:mm")).assertIsDisplayed()
        } finally {r.close()}
    }

    private suspend fun fixture():DesktopRuntime {
        val r=DesktopRuntime(Files.createTempDirectory("step-workspace-clarity-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient())
        r.initialize()
        r.edit {state->
            val now=Instant.now();val plan=ContentActions.planId(state,now)
            var next=state
            for((id,name)in listOf(FIRST to "虚构｜报告提纲",SECOND to "虚构｜准备画笔",THIRD to "虚构｜复盘纸卡")) {
                next=StateMachine.edit(next,"task",id,ContentActions.save(null,"task",name,"",1,id,now,
                    nextAction=if(id==FIRST)"写三个标题"else""))
                next=DayTaskActions.setIncluded(next,id,true,plan,now)
            }
            next=DayTaskActions.setWin(next,FIRST,true,plan,now)
            val day=TimelineDay.fromPlanId(plan)
            TimelineActions.setSchedule(next,FIRST,plan,day.date.atTime(9,0).atZone(day.zone).toInstant(),30,
                TimelineScheduleBaseline.from(next,plan,FIRST))
        }.getOrThrow()
        return r
    }

    private companion object {
        const val FIRST="00000000-0000-4000-8000-000000007101"
        const val SECOND="00000000-0000-4000-8000-000000007102"
        const val THIRD="00000000-0000-4000-8000-000000007103"
    }
}
