package com.step.desktop

import androidx.compose.ui.test.*
import com.pare.app.*
import org.junit.Assert.*
import org.junit.Test
import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle
import java.nio.file.Files
import java.time.Instant

@OptIn(ExperimentalTestApi::class)
class DesktopWindowBoundsTest {
    @Test fun mainWindowFitsA1024DesktopAfterTheTaskbarIsRemoved() {
        val area = DesktopWindowBounds.workArea(Rectangle(0, 0, 1024, 768), Insets(0, 0, 40, 0))
        assertEquals(Rectangle(0, 0, 1024, 728), area)
        assertEquals(Dimension(1024, 728), DesktopWindowBounds.initialSize(Dimension(1160, 780), area))
        assertEquals(Rectangle(0, 0, 1024, 728), DesktopWindowBounds.fit(Rectangle(12, 12, 1160, 780), area))
    }

    @Test fun highDpiWorkAreasAreAlreadyInAwtUserSpaceAndAreNotDividedAgain() {
        // 1920x1080 at 200% gives AWT bounds 960x540 and a 20-unit (40-pixel) taskbar.
        // These are explicit synthetic display inputs; the test never queries the host desktop.
        val area200 = DesktopWindowBounds.workArea(Rectangle(0, 0, 960, 540), Insets(0, 0, 20, 0))
        assertEquals(Dimension(960, 520), DesktopWindowBounds.initialSize(Dimension(1160, 780), area200))
        assertEquals(Dimension(960, 520), DesktopWindowBounds.minimumSize(Dimension(960, 680), area200))
        // A 1920x1080 desktop at 125% exposes 1536x864 AWT units, leaving the preferred size intact.
        val area125 = DesktopWindowBounds.workArea(Rectangle(0, 0, 1536, 864), Insets(0, 0, 32, 0))
        assertEquals(Dimension(1160, 780), DesktopWindowBounds.initialSize(Dimension(1160, 780), area125))
    }

    @Test fun captureAndWidgetMinimumsCannotForceThemOutsideASmallWorkArea() {
        val area = Rectangle(100, 50, 410, 300)
        assertEquals(Dimension(410, 300), DesktopWindowBounds.initialSize(Dimension(460, 400), area))
        assertEquals(Dimension(390, 300), DesktopWindowBounds.initialSize(Dimension(390, 560), area))
        assertEquals(Dimension(340, 300), DesktopWindowBounds.minimumSize(Dimension(340, 420), area))
        assertEquals(Rectangle(100, 50, 410, 300), DesktopWindowBounds.fit(Rectangle(220, 190, 460, 400), area))
    }

    @Test fun secondaryMonitorOriginsAndTaskbarInsetsArePreservedWhenClamping() {
        val area = DesktopWindowBounds.workArea(Rectangle(-1280, 0, 1280, 1024), Insets(0, 40, 32, 0))
        assertEquals(Rectangle(-1240, 0, 1240, 992), area)
        assertEquals(Rectangle(-1160, 212, 1160, 780), DesktopWindowBounds.fit(Rectangle(-1100, 850, 1160, 780), area))
    }

    @Test fun aWindowAlreadyInsideTheNewWorkAreaKeepsItsUserChosenSizeAndPosition() {
        val desired = Rectangle(-1100, 60, 900, 650)
        assertEquals(desired, DesktopWindowBounds.fit(desired, Rectangle(-1280, 0, 1280, 984)))
        assertEquals("Setting AWT minimumSize must not silently enlarge a valid window",
            Dimension(900, 650), DesktopWindowBounds.minimumSize(Dimension(960, 680), Rectangle(-1280, 0, 1280, 984), desired.size))
    }

    @Test fun shortDesktopTaskDialogKeepsCloseAndScrolledSaveReachable() = runDesktopComposeUiTest(width = 960, height = 480) {
        val home = Files.createTempDirectory("step-short-dialog-")
        DesktopRuntime(home, DesktopConfig(), { _, _ -> }, TestProtector(), NoNetwork, FixtureClock(),
            MobileAIClient { _, _, _ -> error("No model access in window fixture") }).use { runtime ->
            runtime.initialize()
            val id = "00000000-0000-4000-8000-000000000851"
            runtime.edit { StateMachine.edit(it, "task", id,
                ContentActions.save(null, "task", "虚构：小屏研究任务", "", 0, id, Instant.now())) }.getOrThrow()
            setContent { DesktopUi(runtime, {}, {}, {}) }
            onNodeWithTag("nav_四象限").performClick()
            onNodeWithTag("task_$id").performClick()
            onNodeWithText("任务详情").performClick()
            onNode(hasSetTextAction() and hasText("背景与备注")).performScrollTo().performTextInput("虚构：小屏仍能保存中文备注")
            onNodeWithText("保存任务详情").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { runtime.engine.state.value.items.getValue("task:$id").content().str("note") == "虚构：小屏仍能保存中文备注" }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty() }
            val dialog = onNodeWithTag("desktop_dialog").fetchSemanticsNode().boundsInRoot
            assertTrue("Dialog must fit the 480-pixel content surface", dialog.height <= 480f)
            onNodeWithText("关闭").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("desktop_dialog").assertDoesNotExist()
        }
    }
}
