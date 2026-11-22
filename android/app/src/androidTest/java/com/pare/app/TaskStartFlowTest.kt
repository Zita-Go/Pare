package com.pare.app

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Exercise the actual editor Dialog, including the font scale inherited by its window. */
class TaskStartFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var contentView: ComposeView? = null
    private val taskId = "98000000-0000-4000-8000-000000000001"
    private val original = EditorDraft(taskId, "task", text = "虚构：整理观察笔记",
        note = "保留观察背景", nextAction = "先写一句", estimate = "15", isNew = false)
    private val actions = mutableListOf<String>()
    private var submitted: EditorDraft? = null

    @After fun releaseContent() { compose.runOnUiThread { contentView?.disposeComposition() } }

    @Test fun largeFontKeepsSmallestStepBeforeStartAndSupportAdjacent() {
        show(fontScale = 1.6f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("开始专注", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(1.6f, layouts.single().layoutInput.density.fontScale, 0.001f)
        val priority = compose.onNodeWithTag("editor_priority").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("editor_next_action").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithTag("run_$taskId").assertIsDisplayed().assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        assertTrue("The next step must appear between priority and starting focus", priority.bottom <= next.top && next.bottom <= start.top)
        compose.onNodeWithTag("support_$taskId").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("schedule_$taskId").assertDoesNotExist()
        compose.onNodeWithTag("detail_complete_$taskId").assertDoesNotExist()
        compose.onNodeWithTag("editor_note").assertDoesNotExist()
        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Collapsing details must preserve their values", original, submitted) }
    }

    @Test fun changedDraftExposesSaveThenStartAndSaveThenSupportWithoutOpeningTools() {
        show()
        compose.onNodeWithTag("editor_next_action").performTextReplacement("只写一个词")
        compose.onNodeWithTag("run_$taskId").performScrollTo().assertTextContains("保存后开始专注").performClick()
        compose.onNodeWithTag("support_$taskId").performScrollTo().assertTextContains("保存后拆小一点").performClick()
        compose.onNodeWithTag("editor_save").performClick()
        compose.runOnIdle {
            assertEquals(listOf("run", "support"), actions)
            assertEquals(original.copy(nextAction = "只写一个词"), submitted)
        }
    }

    @Test fun secondaryToolsRemainAvailableWithoutChangingTheDraft() {
        show()
        compose.onNodeWithTag("task_tools_toggle").performScrollTo().performClick()
        listOf("schedule", "complete", "ai", "remind", "plan", "metadata", "implementation", "today", "win", "origin").forEach { action ->
            val tag = if (action == "complete") "detail_complete_$taskId" else "${action}_$taskId"
            compose.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        }
        compose.onNodeWithTag("editor_details").performScrollTo().performClick()
        compose.onNodeWithTag("editor_note").performScrollTo().assertTextContains("保留观察背景")
        compose.onNodeWithTag("editor_estimate").performScrollTo().assertTextContains("15")
        compose.onNodeWithTag("editor_save").performClick()
        compose.runOnIdle {
            assertEquals(listOf("schedule", "complete", "ai", "remind", "plan", "metadata", "implementation", "today", "win", "origin"), actions)
            assertEquals(original, submitted)
        }
    }

    private fun show(fontScale: Float = 1f) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.pare.app.qa")
        val task = Entity("task", taskId, ContentActions.save(null, "task", original.text, original.note,
            original.quadrant, taskId, Instant.parse("2026-01-01T00:00:00Z"), estimatedMinutes = 15, nextAction = original.nextAction))
        compose.activityRule.scenario.onActivity { activity ->
            val configuredContext = ContextThemeWrapper(activity, activity.theme).apply {
                applyOverrideConfiguration(Configuration().apply { this.fontScale = fontScale })
            }
            contentView = ComposeView(configuredContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    var draft by remember { mutableStateOf(original) }
                    PareTheme {
                        TaskEditorDialog(draft, original, false, null, { draft = it }, { submitted = draft }, {}, {},
                            taskActions = { TaskDetailActions(task, draft != original, false, false, false, false, true, actions::add) })
                    }
                }
            }
            activity.setContentView(requireNotNull(contentView))
        }
        compose.waitForIdle()
    }
}
