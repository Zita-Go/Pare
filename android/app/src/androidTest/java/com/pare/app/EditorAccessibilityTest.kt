package com.pare.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class EditorAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var contentView: ComposeView? = null

    @After fun releaseContent() { compose.runOnUiThread { contentView?.disposeComposition() } }

    @Test fun largeFontSaveFailureLeavesDraftAndActionsVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.pare.app.qa")
        val original = EditorDraft(entityId = "00000000-0000-4000-8000-000000000008", kind = "task")
        setContentWithFontScale(1.6f) {
            var draft by remember { mutableStateOf(original) }
            var error by remember { mutableStateOf<String?>(null) }
            PareTheme(darkTheme = true) {
                TaskEditorDialog(draft = draft, original = original, saving = false, error = error,
                    onDraftChange = { draft = it },
                    onSave = { error = "保存失败，草稿已保留" },
                    onDismiss = {}, onDiscard = {})
            }
        }
        val textLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("添加任务").assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResults ->
            assertTrue("Editor title must expose its actual text layout", getResults(textLayouts))
        }
        assertEquals(1, textLayouts.size)
        val titleLayout = textLayouts.single()
        assertEquals("Editor Dialog must actually render at 1.6 font scale", 1.6f, titleLayout.layoutInput.density.fontScale, 0.001f)
        compose.onNodeWithTag("editor_text").performTextReplacement("保存失败时也不丢失的虚构任务")
        compose.onNodeWithTag("editor_details").performClick()
        compose.onNodeWithTag("editor_next_action").performScrollTo().performTextReplacement("先打开笔记，写一行")
        compose.onNodeWithTag("editor_estimate").performScrollTo().performTextReplacement("15")
        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()
        compose.onNodeWithTag("editor_error").assertIsDisplayed()
        compose.onNodeWithTag("editor_text").performScrollTo().assertTextContains("保存失败时也不丢失的虚构任务")
        compose.onNodeWithTag("editor_cancel").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("editor_save").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val folder = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { File(it) } ?: File(context.getExternalFilesDir(null), "ui-acceptance")
        folder.mkdirs()
        File(folder, "visual-font-scale-editor.json").writeText(buildJsonObject {
            put("requested_font_scale", 1.6f)
            put("rendered_font_scale", titleLayout.layoutInput.density.fontScale)
            put("density", titleLayout.layoutInput.density.density)
            put("title_font_size_sp", titleLayout.layoutInput.style.fontSize.value)
            put("title_height_px", titleLayout.size.height)
        }.toString())
        File(folder, "05-editor-large-font.png").outputStream().use {
            assertTrue(compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    /** Configure only this QA View's resources so newly created Dialog windows inherit the scale. */
    private fun setContentWithFontScale(requestedFontScale: Float, content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity { activity ->
            val configuredContext = ContextThemeWrapper(activity, activity.theme).apply {
                applyOverrideConfiguration(Configuration().apply { fontScale = requestedFontScale })
            }
            assertEquals(requestedFontScale, configuredContext.resources.configuration.fontScale, 0.001f)
            contentView = ComposeView(configuredContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent(content)
            }
            activity.setContentView(requireNotNull(contentView))
        }
        compose.waitForIdle()
    }
}
