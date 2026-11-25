package com.step.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.pare.app.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO

/**
 * Real offscreen Compose rendering, text editing and input dispatch with fictional local data.
 * Text is committed through Compose's test input API: this does NOT exercise Microsoft Pinyin's
 * composition/candidate window, the Windows input service, physical monitors or WM_DPICHANGED.
 * Density changes below change the scene itself, not fontScale or just a LocalDensity override.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class DesktopInputScaleAcceptanceTest {
    @Test fun mixedChineseQuickCaptureCanSelectReplaceRetainAndSaveAtFractionalDensities() {
        for (scale in scales) {
            val home = Files.createTempDirectory("step-input-scale-capture-")
            val test = composeTest(460, 400, scale) // The shipping quick-capture window's logical size.
            test.runTest {
                isolatedDesktopRuntime(home).use { runtime ->
                    var visible by mutableStateOf(true)
                    var closeCount = 0
                    setContent { QuickCaptureUi(runtime, { visible = false; closeCount++ }, visible) }
                    val field = onNode(hasSetTextAction() and hasText("此刻想到什么？"))
                    field.performScrollTo().assertIsDisplayed().performClick()
                    field.performTextInput("虚构：整理中文提纲")
                    field.performTextInput(" for Project Alpha 2026")
                    val mixedText = "虚构：整理中文提纲 for Project Alpha 2026"
                    field.assertEditableText(mixedText)

                    // Dispatch the platform's select-all shortcut, then replace the selected text.
                    val selectModifier = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
                    field.performKeyInput { withKeyDown(selectModifier) { pressKey(Key.A) } }
                    val selection = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
                    assertEquals("Select all must include the Chinese and Latin text at $scale", 0, selection.min)
                    assertEquals(mixedText.length, selection.max)
                    field.performTextInput(CAPTURE)
                    field.assertEditableText(CAPTURE)

                    // Main keeps this composition alive while the native capture window is hidden.
                    onNodeWithText("取消").assertIsDisplayed().performMouseInput { click() }
                    runOnIdle { assertFalse(visible); visible = true }
                    field.assertEditableText(CAPTURE)
                    assertTrue(runtime.engine.state.value.items.isEmpty())
                    onNodeWithTag("quick_capture_save").assertIsDisplayed().assertIsEnabled()
                    capture("capture", scale, onRoot(), 460, 400)

                    field.performClick().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
                    waitUntil(timeoutMillis = 5_000) { runtime.engine.state.value.items.values.any { it.kind == "inbox" } }
                    assertEquals("Saving keeps the panel ready for another capture",1,closeCount)
                    assertEquals(listOf(CAPTURE), runtime.engine.state.value.items.values.filter { it.kind == "inbox" }.map { it.title() })
                    runOnIdle { visible = true }
                    field.assertEditableText("")
                    onNodeWithTag("quick_capture_save").assertIsNotEnabled()
                }
            }
            DesktopStore(home).use { store ->
                assertEquals("The shortcut must persist exactly one capture at $scale", listOf(CAPTURE),
                    store.read().items.values.filter { it.kind == "inbox" }.map { it.title() })
            }
        }
    }

    @Test fun taskDetailAndSmallStepRemainEditableAndPersistAtFractionalDensities() {
        for (scale in scales) {
            val home = Files.createTempDirectory("step-input-scale-detail-")
            val test = composeTest(960, 760, scale)
            test.runTest {
                isolatedDesktopRuntime(home, seedTask = true).use { runtime ->
                    setContent { DesktopUi(runtime, {}, {}, {}) }
                    onNodeWithTag("nav_四象限").performClick()
                    onNodeWithTag("task_detail_$TASK").assertIsDisplayed().performMouseInput { click() }
                    val next = onNode(hasSetTextAction() and hasText("下一步具体做什么？"))
                    next.performScrollTo().performTextReplacement(NEXT)
                    onNodeWithText("保存下一步").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { runtime.engine.state.value.items.getValue("task:$TASK").content().str("nextAction") == NEXT }
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty() }

                    onNodeWithText("任务详情").performScrollTo().performClick()
                    val title = onNode(hasSetTextAction() and hasText("任务名称"))
                    val note = onNode(hasSetTextAction() and hasText("背景与备注"))
                    title.performScrollTo().performTextInputSelection(TextRange(0, ORIGINAL_TITLE.length))
                    title.performTextInput(TITLE)
                    note.performScrollTo().performTextInput(NOTE)
                    onNodeWithText("关闭").assertIsDisplayed().performClick()
                    onNodeWithText("继续编辑").assertIsDisplayed().performClick()
                    title.assertEditableText(TITLE)
                    note.assertEditableText(NOTE)
                    assertEquals(ORIGINAL_TITLE, runtime.engine.state.value.items.getValue("task:$TASK").title())
                    onNodeWithText("保存任务详情").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { runtime.engine.state.value.items.getValue("task:$TASK").title() == TITLE }
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty() }
                    capture("task-detail", scale, onNodeWithTag("desktop_dialog"), 960, 760, fullWindow = false)
                    onNodeWithText("关闭").assertIsDisplayed().performClick()
                    onNodeWithTag("desktop_dialog").assertDoesNotExist()
                    onNodeWithTag("task_detail_$TASK").performClick()
                    onNodeWithText("任务详情").performClick()
                    title.assertEditableText(TITLE)
                    note.assertEditableText(NOTE)
                }
            }
            DesktopStore(home).use { store ->
                val task = store.read().items.getValue("task:$TASK")
                assertEquals("Saved Chinese title at $scale", TITLE, task.title())
                assertEquals(NOTE, task.content().str("note"))
                assertEquals(NEXT, task.content().str("nextAction"))
                assertFalse(task.completed())
            }
        }
    }

    @Test fun changingTheSameSceneDensityRetainsAnOpenChineseDraftAndItsSaveTarget() {
        val home = Files.createTempDirectory("step-input-density-transition-")
        // The test render surface is fixed in pixels; 200% still leaves 960 x 760 logical dp.
        val test = DesktopComposeUiTest(width = 1920, height = 1520, density = Density(1.25f))
        test.runTest {
            isolatedDesktopRuntime(home, seedTask = true).use { runtime ->
                setContent { DesktopUi(runtime, {}, {}, {}) }
                onNodeWithTag("nav_四象限").performClick()
                onNodeWithTag("task_detail_$TASK").performClick()
                onNodeWithText("任务详情").performClick()
                val note = onNode(hasSetTextAction() and hasText("背景与备注"))
                note.performScrollTo().performTextInput(NOTE)
                val originalScene = scene
                val originalWidth = onNodeWithTag("desktop_dialog").fetchSemanticsNode().boundsInRoot.width
                for (scale in listOf(2f, 1.5f, 1.75f, 1.25f)) {
                    runOnIdle { scene.density = Density(scale, fontScale = 1f) }
                    waitForIdle()
                    assertSame("A density change must retain the existing scene", originalScene, scene)
                    note.assertEditableText(NOTE)
                    assertEquals("", runtime.engine.state.value.items.getValue("task:$TASK").content().str("note"))
                    val dialogWidth = onNodeWithTag("desktop_dialog").fetchSemanticsNode().boundsInRoot.width
                    assertEquals("The dialog must relayout at scene density $scale", originalWidth * scale / 1.25f, dialogWidth, 2f)
                    onNodeWithText("保存任务详情").performScrollTo().assertIsDisplayed().assertIsEnabled()
                }
                onNodeWithText("保存任务详情").performMouseInput { click() }
                waitUntil(timeoutMillis = 5_000) { runtime.engine.state.value.items.getValue("task:$TASK").content().str("note") == NOTE }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty() }
                onNodeWithText("关闭").performClick()
                onNodeWithTag("desktop_dialog").assertDoesNotExist()
                assertEquals(1, runtime.engine.state.value.items.values.count { it.kind == "task" })
            }
        }
        DesktopStore(home).use { store -> assertEquals(NOTE, store.read().items.getValue("task:$TASK").content().str("note")) }
    }

    private fun composeTest(width: Int, height: Int, scale: Float): DesktopComposeUiTest {
        val density = Density(scale, fontScale = 1f)
        return DesktopComposeUiTest(width = with(density) { width.dp.roundToPx() },
            height = with(density) { height.dp.roundToPx() }, density = density)
    }

    private fun SemanticsNodeInteraction.assertEditableText(expected: String) {
        assertEquals(expected, fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    private fun SkikoComposeUiTest.capture(name: String, scale: Float, node: SemanticsNodeInteraction,
        width: Int, height: Int, fullWindow: Boolean = true) {
        waitForIdle()
        val bitmap = node.captureToImage()
        val physicalWidth = with(Density(scale)) { width.dp.roundToPx() }
        val physicalHeight = with(Density(scale)) { height.dp.roundToPx() }
        if (fullWindow) {
            assertEquals(physicalWidth, bitmap.width)
            assertEquals(physicalHeight, bitmap.height)
        } else {
            assertTrue("Dialog must fit the scene", bitmap.width in 1..physicalWidth && bitmap.height in 1..physicalHeight)
        }
        val output = Path.of("build", "test-screenshots")
        Files.createDirectories(output)
        val host = System.getProperty("os.name").lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val file = output.resolve("input-scale-$host-${(scale * 100).toInt()}pct-$name.png")
        assertTrue(ImageIO.write(bitmap.toAwtImage(), "png", file.toFile()))
        assertTrue("Rendered fixture evidence must be non-empty", Files.size(file) > 5_000)
    }

    private suspend fun isolatedDesktopRuntime(home: Path, seedTask: Boolean = false): DesktopRuntime {
        val runtime = DesktopRuntime(home, DesktopConfig(), { _, _ -> }, TestProtector(), NoNetwork,
            FixtureClock(), MobileAIClient { _, _, _ -> error("Input/scale fixture must never request AI") })
        try {
            runtime.initialize()
            if (seedTask) runtime.edit { state ->
                StateMachine.edit(state, "task", TASK, ContentActions.save(null, "task", ORIGINAL_TITLE, "", 0,
                    TASK, Instant.now(), nextAction = "打开虚构文档"))
            }.getOrThrow()
            return runtime
        } catch (error: Throwable) {
            runtime.close()
            throw error
        }
    }

    private companion object {
        val scales = listOf(1.25f, 1.5f, 1.75f, 2f)
        const val TASK = "00000000-0000-4000-8000-000000000841"
        const val ORIGINAL_TITLE = "虚构：整理研究提纲"
        const val TITLE = "虚构：中文与 English 混合提纲 2026"
        const val CAPTURE = "虚构：为 Project Alpha 写下第一步，先做 5 分钟。"
        const val NOTE = "虚构备注：先列出三个问题。\nReview v2：保留中文标点、数字 123 与 English。"
        const val NEXT = "虚构：打开 Draft v2，写一个中文小标题"
    }
}
