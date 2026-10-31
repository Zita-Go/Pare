package com.pare.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Fictional in-memory form: exercising dismissal never writes a task or requests a network. */
class TimelineDraftDismissTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val model = TimelineScheduleModel()
    private var dismissals = 0

    private fun show() {
        val now = Instant.parse("2099-05-20T09:00:00Z")
        val id = "96000000-0000-4000-8000-000000000033"
        val task = Entity("task", id, ContentActions.save(null, "task", "虚构：安排观察笔记", "", 1, id, now))
        val state = SyncState(device = "fictional-dismiss-test", items = mapOf(task.key to task))
        model.initialize(state, TimelineDay.fromPlanId("2099-05-20@UTC"), task, now)
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            PareTheme {
                if (open) TimelineScheduleDialog(model, available = true,
                    onSave = { if (model.begin()) model.finish(Result.failure(IllegalStateException("虚构：保存失败"))) },
                    onUnschedule = null, onDismiss = { dismissals++; model.clear(); open = false })
            }
        }
    }

    @Test fun unmodifiedBackClosesDirectly() {
        show()
        compose.onNodeWithTag("timeline_schedule_cancel").performClick()
        compose.onNodeWithTag("timeline_schedule_dialog").assertDoesNotExist()
        compose.onNodeWithTag("timeline_schedule_discard").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun modifiedSystemBackCanContinueThenExplicitlyDiscardFromHeader() {
        show()
        compose.onNodeWithTag("timeline_schedule_duration").performScrollTo().performTextReplacement("45")
        closeSoftKeyboard()
        pressBack()
        compose.onNodeWithTag("timeline_schedule_continue").assertIsDisplayed().performClick()
        compose.onNodeWithTag("timeline_schedule_duration").assertTextContains("45")
        compose.runOnIdle { assertEquals(0, dismissals); assertTrue(model.hasChanges()) }
        compose.onNodeWithTag("timeline_schedule_cancel").performClick()
        compose.onNodeWithTag("timeline_schedule_discard").assertIsDisplayed().performClick()
        compose.onNodeWithTag("timeline_schedule_dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun failedSaveSystemBackKeepsDraftAndFailureUntilExplicitDiscard() {
        show()
        compose.onNodeWithTag("timeline_schedule_time").performScrollTo().performTextReplacement("10:30")
        closeSoftKeyboard()
        compose.onNodeWithTag("timeline_schedule_save").performClick()
        compose.onNodeWithTag("timeline_schedule_error").assertIsDisplayed().assertTextContains("虚构：保存失败", substring = true)
        pressBack()
        compose.onNodeWithTag("timeline_schedule_continue").performClick()
        compose.onNodeWithTag("timeline_schedule_time").performScrollTo().assertTextContains("10:30")
        compose.onNodeWithTag("timeline_schedule_error").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismissals); assertEquals("10:30", model.time) }
        pressBack()
        compose.onNodeWithTag("timeline_schedule_discard").performClick()
        compose.onNodeWithTag("timeline_schedule_dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }
}
