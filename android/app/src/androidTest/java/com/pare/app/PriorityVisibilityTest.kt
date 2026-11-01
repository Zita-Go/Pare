package com.pare.app

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.WindowInsets
import android.view.inspector.WindowInspector
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
import androidx.test.filters.SdkSuppress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real editor input with fictional drafts; persistence is covered by the capture flow tests. */
class PriorityVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var contentView: ComposeView? = null
    private var submitted: EditorDraft? = null
    private val taskId = "97000000-0000-4000-8000-000000000001"

    @After fun releaseContent() { compose.runOnUiThread { contentView?.disposeComposition() } }

    @Test @SdkSuppress(minSdkVersion = 30)
    fun newTaskShowsPriorityWithoutOpeningDetailsAndSubmitsSelection() {
        show(EditorDraft(entityId = taskId, kind = "task"))

        compose.onNodeWithTag("editor_text").assertIsFocused()
        assertKeyboardVisible()
        compose.onNodeWithTag("editor_next_action").assertDoesNotExist()
        compose.onNodeWithTag("editor_note").assertDoesNotExist()
        compose.onNodeWithTag("editor_priority").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .assertTextContains("重要 · 不紧急")
        compose.onNodeWithTag("editor_text").performTextReplacement("虚构：整理观察笔记")
        choosePriority(0)
        compose.onNodeWithTag("editor_priority").assertTextContains("重要 · 紧急")
        compose.onNodeWithTag("editor_note").assertDoesNotExist()
        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertEquals("虚构：整理观察笔记", requireNotNull(submitted).text)
            assertEquals(0, requireNotNull(submitted).quadrant)
        }
    }

    @Test fun existingTaskShowsPriorityBeforeOtherFieldsAndSubmitsSelection() {
        val original = EditorDraft(entityId = taskId, kind = "task", isNew = false,
            text = "虚构：观察笔记", note = "保留这段虚构背景", nextAction = "先写一句", estimate = "15", quadrant = 2)
        show(original)

        val priority = compose.onNodeWithTag("editor_priority").assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).assertTextContains("紧急 · 不重要").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("editor_text").fetchSemanticsNode().boundsInRoot
        val nextAction = compose.onNodeWithTag("editor_next_action").fetchSemanticsNode().boundsInRoot
        assertTrue("Priority should follow the title before the optional fields", priority.top >= title.bottom && priority.bottom <= nextAction.top)
        choosePriority(3)
        compose.onNodeWithTag("editor_priority").assertTextContains("留待以后")
        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(original.copy(quadrant = 3), submitted) }
    }

    @Test fun expandingDetailsKeepsOnePriorityControlAndItsSelection() {
        show(EditorDraft(entityId = taskId, kind = "task", text = "虚构：写一行记录"))
        choosePriority(2)
        compose.onNodeWithTag("editor_details").performScrollTo().performClick()
        compose.onNodeWithTag("editor_note").assertExists()
        compose.onAllNodesWithTag("editor_priority").assertCountEquals(1)
        compose.onNodeWithTag("editor_priority").performScrollTo().assertTextContains("紧急 · 不重要")
        compose.onNodeWithTag("editor_details").performScrollTo().performClick()
        compose.onNodeWithTag("editor_priority").assertIsDisplayed().assertTextContains("紧急 · 不重要")
        compose.onNodeWithTag("editor_note").assertDoesNotExist()
    }

    @Test @SdkSuppress(minSdkVersion = 30)
    fun largeFontNewTaskKeepsPriorityAndSaveReachableWithoutDetails() {
        show(EditorDraft(entityId = taskId, kind = "task"), fontScale = 1.6f)
        compose.onNodeWithTag("editor_text").assertIsFocused()
        assertKeyboardVisible()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("重要 · 不紧急", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals("Measure the actual Dialog text scale", 1.6f, layouts.single().layoutInput.density.fontScale, 0.001f)
        compose.onNodeWithTag("editor_priority").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("editor_text").performTextReplacement("虚构：写一句")
        choosePriority(0)
        compose.onNodeWithTag("editor_priority").assertIsDisplayed().assertTextContains("重要 · 紧急")
        compose.onNodeWithTag("editor_save").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(0, requireNotNull(submitted).quadrant) }
    }

    @Test fun inboxAndIdeaCaptureKeepTheirExistingFieldsWithoutTaskPriority() {
        val original = mutableStateOf(EditorDraft(entityId = taskId, kind = "inbox"))
        setContentWithFontScale(1f) {
            val initial = original.value
            var draft by remember(initial) { mutableStateOf(initial) }
            PareTheme {
                TaskEditorDialog(draft, initial, false, null, { draft = it }, { submitted = draft }, {}, {})
            }
        }
        compose.onNodeWithTag("editor_priority").assertDoesNotExist()
        compose.onNodeWithTag("editor_details").assertDoesNotExist()
        compose.onNodeWithTag("editor_text").performTextReplacement("虚构随手记")
        compose.onNodeWithTag("editor_save").performClick()
        compose.runOnIdle {
            assertEquals("inbox", requireNotNull(submitted).kind)
            assertEquals("虚构随手记", requireNotNull(submitted).text)
            original.value = EditorDraft(entityId = "97000000-0000-4000-8000-000000000002", kind = "idea")
        }
        compose.onNodeWithTag("editor_priority").assertDoesNotExist()
        compose.onNodeWithTag("editor_text").performTextReplacement("虚构想法")
        compose.onNodeWithTag("editor_details").performClick()
        compose.onNodeWithTag("editor_note").performScrollTo().performTextReplacement("先观察一个小问题")
        compose.onNodeWithTag("editor_priority").assertDoesNotExist()
        compose.onNodeWithTag("editor_save").performClick()
        compose.runOnIdle {
            assertEquals("idea", requireNotNull(submitted).kind)
            assertEquals("先观察一个小问题", requireNotNull(submitted).note)
        }
    }

    private fun choosePriority(index: Int) {
        compose.onNodeWithTag("editor_priority").assertIsDisplayed().performClick()
        compose.onNodeWithTag("priority_$index").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
    }

    /** Focus alone does not prove that the visible keyboard left enough room for priority. */
    @android.annotation.TargetApi(30)
    private fun assertKeyboardVisible() {
        compose.waitUntil(5_000) {
            var visible = false
            compose.runOnUiThread {
                visible = WindowInspector.getGlobalWindowViews().any {
                    it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                }
            }
            visible
        }
    }

    private fun show(original: EditorDraft, fontScale: Float = 1f) {
        setContentWithFontScale(fontScale) {
            var draft by remember { mutableStateOf(original) }
            PareTheme {
                TaskEditorDialog(draft, original, false, null, { draft = it }, { submitted = draft }, {}, {})
            }
        }
    }

    /** A configured Context lets the actual Dialog window inherit the requested font scale. */
    private fun setContentWithFontScale(fontScale: Float, content: @Composable () -> Unit) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.pare.app.qa")
        compose.activityRule.scenario.onActivity { activity ->
            val configuredContext = ContextThemeWrapper(activity, activity.theme).apply {
                applyOverrideConfiguration(Configuration().apply { this.fontScale = fontScale })
            }
            contentView = ComposeView(configuredContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent(content)
            }
            activity.setContentView(requireNotNull(contentView))
        }
        compose.waitForIdle()
    }
}
