package com.pare.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Fictional in-memory data; no account, storage or network runtime. */
class ManagementPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun isolated() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.pare.app.qa")}

    @Test fun repeatedGoalSavesExpireWithoutMovingContentAndFailureRetainsDraft() {
        isolated()
        var state by mutableStateOf(SyncState())
        var fail=false
        compose.setContent { PareTheme { GoalsWorkspace(state,onEdit={change,done ->
            if(fail) done(Result.failure(IllegalStateException("虚构：磁盘不可写")))
            else done(runCatching {state=change(state)})
        },onDismiss={}) } }
        compose.onNodeWithTag("goal_add").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃这份草稿？").assertDoesNotExist()
        compose.onNodeWithTag("goal_add").assertIsDisplayed()
        repeat(2) { index ->
            compose.onNodeWithTag("goal_add").performClick()
            compose.onNodeWithTag("goal_title").performTextReplacement("虚构：观察第${index+1}片叶子")
            compose.onNodeWithTag("goal_save").performClick()
            compose.onNodeWithTag("goal_saved").assertIsDisplayed()
            val before=compose.onNodeWithTag("goal_add").fetchSemanticsNode().boundsInRoot
            compose.mainClock.advanceTimeBy(6_000)
            compose.waitForIdle()
            compose.onNodeWithTag("goal_saved").assertDoesNotExist()
            assertEquals(before,compose.onNodeWithTag("goal_add").fetchSemanticsNode().boundsInRoot)
        }
        compose.runOnIdle {fail=true}
        compose.onNodeWithTag("goal_add").performClick()
        compose.onNodeWithTag("goal_title").performTextReplacement("虚构：暂存观察草稿")
        compose.onNodeWithTag("goal_save").performClick()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("goals_workspace_error").assertTextContains("虚构：磁盘不可写",substring=true)
        compose.onNodeWithTag("goal_title").assertTextContains("虚构：暂存观察草稿")
        compose.runOnIdle {assertEquals(2,state.items.values.count {it.kind=="goal"})}
    }

    @Test fun metadataBackOffersDiscardAndKeepsDraftUntilExplicitChoice() {
        isolated()
        val id="00000000-0000-4000-8000-000000000099"
        val now=Instant.parse("2026-10-31T08:00:00Z")
        val task=Entity("task",id,ContentActions.save(null,"task","虚构：画一片叶子","",1,id,now))
        val state=SyncState(items=mapOf(task.key to task))
        var open by mutableStateOf(true)
        var writes=0
        compose.setContent { PareTheme { if(open) TaskMetadataDialog(id,state,onEdit={_,done ->
            writes++;done(Result.success(Unit))
        },onDismiss={open=false}) } }
        compose.onNodeWithTag("task_actual_minutes").performTextReplacement("17")
        compose.onNodeWithTag("task_metadata_close").performClick()
        compose.onNodeWithText("放弃这份草稿？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("task_actual_minutes").assertTextContains("17")
        compose.onNodeWithTag("task_metadata_save").assertIsDisplayed()
        compose.onNodeWithTag("task_metadata_close").performClick()
        compose.onNodeWithText("放弃草稿").performClick()
        compose.onNodeWithTag("task_metadata").assertDoesNotExist()
        compose.runOnIdle {assertEquals(0,writes);assertFalse(open)}
    }
}
