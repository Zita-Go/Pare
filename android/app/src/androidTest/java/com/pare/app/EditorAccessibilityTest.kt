package com.pare.app

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class EditorAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeFontSaveFailureLeavesDraftAndActionsVisible() {
        val original = EditorDraft(entityId = "00000000-0000-4000-8000-000000000008", kind = "task")
        compose.setContent {
            var draft by remember { mutableStateOf(original) }
            var error by remember { mutableStateOf<String?>(null) }
            val density = LocalDensity.current.density
            PareTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.6f)) {
                    TaskEditorDialog(draft = draft, original = original, saving = false, error = error,
                        onDraftChange = { draft = it },
                        onSave = { error = "保存失败，草稿已保留" },
                        onDismiss = {}, onDiscard = {})
                }
            }
        }
        compose.onNodeWithTag("editor_text").performTextReplacement("保存失败时也不丢失的虚构任务")
        compose.onNodeWithTag("editor_details").performClick()
        compose.onNodeWithTag("editor_next_action").performScrollTo().performTextReplacement("先打开笔记，写一行")
        compose.onNodeWithTag("editor_estimate").performScrollTo().performTextReplacement("15")
        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()
        compose.onNodeWithTag("editor_error").assertIsDisplayed()
        compose.onNodeWithTag("editor_text").performScrollTo().assertTextContains("保存失败时也不丢失的虚构任务")
        compose.onNodeWithTag("editor_cancel").assertIsDisplayed()
        compose.onNodeWithTag("editor_save").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.pare.app.qa")
        val folder = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { File(it) } ?: File(context.getExternalFilesDir(null), "ui-acceptance")
        folder.mkdirs()
        File(folder, "05-editor-large-font.png").outputStream().use {
            assertTrue(compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
