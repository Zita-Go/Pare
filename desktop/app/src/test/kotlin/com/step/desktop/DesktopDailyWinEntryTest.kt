package com.step.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

/** Real task rows and local transactions, with only fictional records in an isolated store. */
@OptIn(ExperimentalTestApi::class)
class DesktopDailyWinEntryTest {
    @Test fun unplannedTaskHasAStarThatIncludesItAndSetsTheDayFocus()=runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {r->
            val plan=ContentActions.planId(r.engine.state.value,Instant.now())
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("nav_四象限").performClick()
            onNodeWithTag("day_win_$A").assertIsDisplayed().assertIsEnabled()
                .assertContentDescriptionEquals("加入今天并设为重点").performClick()
            awaitFocus(r,plan,A)
            onNodeWithTag("day_win_$A").assertContentDescriptionEquals("取消今天重点")
            assertTrue(DayTaskSelection.contains(r.engine.state.value,A,plan))
            assertFalse(r.engine.state.value.items.getValue("task:$A").completed())
            assertNull("Choosing the day's focus must not start a timer",r.execution.state.value.focus)
            onNodeWithTag("desktop_dialog").assertDoesNotExist()
        }
    }

    @Test fun starSharesTheSameRightHandRowAsInclusionAndDetails()=runDesktopComposeUiTest(width=420,height=400) {
        fixture().use {r->
            val plan=ContentActions.planId(r.engine.state.value,Instant.now())
            r.edit {DayTaskActions.setIncluded(it,A,true,plan,Instant.now())}.getOrThrow()
            setContent {FixtureRows(r,plan,listOf(A))}
            val star=onNodeWithTag("day_win_$A").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val include=onNodeWithTag("day_include_$A").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val details=onNodeWithTag("task_detail_$A").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("The star must not occupy a second line",include.center.y,star.center.y,1f)
            assertEquals("Details and focus must share one action row",details.center.y,star.center.y,1f)
            assertTrue("The focus and inclusion hit areas must not overlap",star.right<=include.left || include.right<=star.left)
            assertTrue("The focus and details hit areas must not overlap",star.right<=details.left || details.right<=star.left)
        }
    }

    @Test fun switchingAndClearingTheFocusPreservesBothTasksDayMembership()=runDesktopComposeUiTest(width=600,height=500) {
        fixture().use {r->
            val plan=ContentActions.planId(r.engine.state.value,Instant.now())
            r.edit {DayTaskActions.setWin(it,A,true,plan,Instant.now())}.getOrThrow()
            setContent {FixtureRows(r,plan,listOf(A,B))}
            onNodeWithTag("day_win_$B").assertIsDisplayed().performClick()
            awaitFocus(r,plan,B)
            assertFalse(planWin(r.engine.state.value,plan,A))
            assertTrue(DayTaskSelection.contains(r.engine.state.value,A,plan))
            assertTrue(DayTaskSelection.contains(r.engine.state.value,B,plan))
            onNodeWithTag("day_win_$B").performClick()
            waitUntil(timeoutMillis=5_000){!planWin(r.engine.state.value,plan,B)}
            waitForIdle()
            assertTrue(DayTaskSelection.contains(r.engine.state.value,A,plan))
            assertTrue(DayTaskSelection.contains(r.engine.state.value,B,plan))
            assertEquals("",r.engine.state.value.items.getValue("day_plan:$plan").content().str("daily_win_task_id"))
            onNodeWithTag("day_win_$B").assertContentDescriptionEquals("设为今天重点")
        }
    }

    @Test fun starUsesTheSelectedDateWithoutChangingAnotherDaysFocus()=runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {r->
            val today=ContentActions.planId(r.engine.state.value,Instant.now())
            val day=TimelineDay.fromPlanId(today)
            val tomorrow=TimelineDay(day.date.plusDays(1),day.zone).planId
            r.edit {DayTaskActions.setWin(it,B,true,today,Instant.now())}.getOrThrow()
            val originalToday=r.engine.state.value.items.getValue("day_plan:$today")
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("nav_今天").performClick()
            onNodeWithTag("planning_next_day").performClick()
            onNodeWithTag("nav_四象限").performClick()
            onNodeWithTag("day_win_$A").assertIsDisplayed().performClick()
            awaitFocus(r,tomorrow,A)
            assertTrue(DayTaskSelection.contains(r.engine.state.value,A,tomorrow))
            assertFalse(DayTaskSelection.contains(r.engine.state.value,A,today))
            assertEquals(originalToday,r.engine.state.value.items.getValue("day_plan:$today"))
            assertNull(r.execution.state.value.focus)
        }
    }

    @Test fun completedAndArchivedTasksHaveNoClickableStar()=runDesktopComposeUiTest(width=600,height=500) {
        fixture().use {r->
            val plan=ContentActions.planId(r.engine.state.value,Instant.now())
            r.edit {state->
                var next=DayTaskActions.setIncluded(state,A,true,plan,Instant.now())
                next=DayTaskActions.setIncluded(next,B,true,plan,Instant.now())
                val task=next.items.getValue("task:$B")
                StateMachine.edit(next,"task",B,JsonObject(task.payload.orEmpty()+ ("lifecycle" to JsonPrimitive("archived"))))
            }.getOrThrow()
            r.setTaskCompleted(A,true).getOrThrow()
            setContent {FixtureRows(r,plan,listOf(A,B))}
            onNodeWithTag("day_win_$A").assertDoesNotExist()
            onNodeWithTag("day_win_$B").assertDoesNotExist()
        }
    }

    @Test fun taskConflictDisablesTheStar()=runDesktopComposeUiTest(width=600,height=400) {
        fixture().use {r->
            val plan=ContentActions.planId(r.engine.state.value,Instant.now())
            r.edit {state->
                val included=DayTaskActions.setIncluded(state,A,true,plan,Instant.now())
                val task=included.items.getValue("task:$A").copy(conflictGroup="fictional-task-conflict")
                included.copy(items=included.items+(task.key to task))
            }.getOrThrow()
            val before=r.engine.state.value.items
            setContent {FixtureRows(r,plan,listOf(A))}
            onNodeWithTag("day_win_$A").assertIsDisplayed().assertIsNotEnabled()
            assertEquals(before,r.engine.state.value.items)
        }
    }

    @Test fun conflictedPlanRejectsFocusWithoutPartiallyAddingTheTask()=runDesktopComposeUiTest(width=600,height=400) {
        fixture().use {r->
            val plan=ContentActions.planId(r.engine.state.value,Instant.now())
            r.edit {state->
                val included=DayTaskActions.setIncluded(state,B,true,plan,Instant.now())
                val day=included.items.getValue("day_plan:$plan").copy(conflictGroup="fictional-plan-conflict")
                included.copy(items=included.items+(day.key to day))
            }.getOrThrow()
            val before=r.engine.state.value.items
            setContent {FixtureRows(r,plan,listOf(A))}
            onNodeWithTag("day_win_$A").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis=5_000){r.error.value!=null}
            waitForIdle()
            assertEquals(before,r.engine.state.value.items)
            assertFalse(DayTaskSelection.contains(r.engine.state.value,A,plan))
        }
    }

    @Test fun conflictedPlanningProfileRejectsFocusWithoutCreatingAPlan()=runDesktopComposeUiTest(width=600,height=400) {
        fixture().use {r->
            val plan=ContentActions.planId(r.engine.state.value,Instant.now())
            r.edit {state->
                val profile=(state.items["profile:profile"]?:Entity("profile","profile")).copy(conflictGroup="fictional-profile-conflict")
                state.copy(items=state.items+(profile.key to profile))
            }.getOrThrow()
            val before=r.engine.state.value.items
            setContent {FixtureRows(r,plan,listOf(A))}
            onNodeWithTag("day_win_$A").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis=5_000){r.error.value!=null}
            waitForIdle()
            assertEquals(before,r.engine.state.value.items)
            assertNull(r.engine.state.value.items["day_plan:$plan"])
        }
    }

    @Composable private fun FixtureRows(runtime:DesktopRuntime,plan:String,ids:List<String>) {
        val state by runtime.engine.state.collectAsState()
        val work=rememberWork(runtime)
        DesktopTheme {Column {
            ids.forEach {id->TaskRow(state.items.getValue("task:$id"),state,plan,work,{},showDaySelection=true,dense=true)}
            WorkFeedback(work)
        }}
    }

    private fun DesktopComposeUiTest.awaitFocus(runtime:DesktopRuntime,plan:String,id:String) {
        waitUntil(timeoutMillis=5_000){planWin(runtime.engine.state.value,plan,id)}
        waitForIdle()
        onNodeWithTag("day_win_$id").assertIsEnabled()
    }

    private suspend fun fixture():DesktopRuntime {
        val runtime=DesktopRuntime(Files.createTempDirectory("step-daily-win-entry-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient {_,_,_->error("No AI in fictional fixture")})
        try {
            runtime.initialize()
            runtime.edit {state->
                val now=Instant.now()
                var next=StateMachine.edit(state,"task",A,ContentActions.save(null,"task","虚构｜整理阅读提纲","",1,A,now,nextAction="先写一个标题"))
                next=StateMachine.edit(next,"task",B,ContentActions.save(null,"task","虚构｜准备纸卡","",1,B,now))
                next
            }.getOrThrow()
            return runtime
        } catch(error:Throwable){runtime.close();throw error}
    }

    private companion object {
        const val A="00000000-0000-4000-8000-000000009701"
        const val B="00000000-0000-4000-8000-000000009702"
    }
}
