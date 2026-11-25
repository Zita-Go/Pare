package com.step.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.pare.app.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO

/** Inspect the actual pointer preview and its transaction, using an isolated fictional library. */
@OptIn(ExperimentalTestApi::class)
class DesktopQuadrantDragFeedbackTest {
    @Test fun handleIsEasyToGrabAndHoverExplainsCrossQuadrantDragging() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {runtime->
            setContent {DesktopUi(runtime,{}, {}, {})}
            onNodeWithTag("nav_四象限").performClick()
            val handle=onNodeWithTag("quadrant_drag_$A",useUnmergedTree=true)
            handle.assertIsDisplayed().assertWidthIsAtLeast(androidx.compose.ui.unit.Dp(32f))
            val bounds=handle.fetchSemanticsNode().boundsInRoot
            val before=handle.captureToImage().toAwtImage().pixelHash()
            onNodeWithTag("desktop_root").performMouseInput {moveTo(bounds.center)}
            mainClock.advanceTimeBy(700);waitForIdle()
            assertNotEquals("The handle should visibly respond before the user starts dragging",before,handle.captureToImage().toAwtImage().pixelHash())
            onNodeWithText("拖动到其他象限或调整顺序").assertIsDisplayed()
            assertEquals(bounds,handle.fetchSemanticsNode().boundsInRoot)
            assertEquals(0,runtime.engine.state.value.items.getValue("task:$A").content().number("quadrant"))
        }
    }

    @Test fun dragShowsSourceAndDestinationUntilDropThenSavesAndCanBeUndone() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {runtime->
            setContent {DesktopUi(runtime,{}, {}, {})}
            onNodeWithTag("nav_四象限").performClick()
            beginDragTo(B)
            onNodeWithTag("task_$A").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"正在拖动"))
            onNodeWithTag("quadrant_drop_1",useUnmergedTree=true).assertIsDisplayed().assertTextContains("松开移入",substring=true)
            onNodeWithTag("task_drag_feedback",useUnmergedTree=true).assertIsDisplayed().assertTextContains("虚构拖动来源",substring=true)
            // A preview must not change saved content before release.
            assertEquals(0,runtime.engine.state.value.items.getValue("task:$A").content().number("quadrant"))
            savePreview("active")
            onNodeWithTag("desktop_root").performMouseInput {release()}
            waitUntil(timeoutMillis=5_000){runtime.engine.state.value.items.getValue("task:$A").content().number("quadrant")==1}
            assertEquals(listOf(A,B),OrganizeActions.tasks(runtime.engine.state.value,1).map {it.id})
            onNodeWithTag("quadrant_drop_1",useUnmergedTree=true).assertDoesNotExist()
            onNodeWithTag("organize_undo").performClick()
            waitUntil(timeoutMillis=5_000){runtime.engine.state.value.items.getValue("task:$A").content().number("quadrant")==0}
        }
    }

    @Test fun escapeRemovesPreviewAndReleaseDoesNotSaveCancelledMove() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {runtime->
            setContent {DesktopUi(runtime,{}, {}, {})}
            onNodeWithTag("nav_四象限").performClick()
            val before=runtime.engine.state.value
            beginDragTo(B)
            onNodeWithTag("quadrant_drop_1",useUnmergedTree=true).assertIsDisplayed()
            onNodeWithTag("desktop_root").performKeyInput {pressKey(Key.Escape)}
            waitForIdle()
            onNodeWithTag("quadrant_drop_1",useUnmergedTree=true).assertDoesNotExist()
            onNodeWithTag("task_drag_feedback",useUnmergedTree=true).assertDoesNotExist()
            onNodeWithTag("desktop_root").performMouseInput {release()}
            waitForIdle()
            assertEquals(before,runtime.engine.state.value)
        }
    }

    @Test fun droppingOverVisibleCompletedTaskUsesOnlyActiveOrderAnchors() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture(includeCompleted=true).use {runtime->
            setContent {DesktopUi(runtime,{}, {}, {})}
            onNodeWithTag("nav_四象限").performClick()
            onNodeWithTag("task_$C").assertIsDisplayed()
            onNodeWithTag("quadrant_drag_$C",useUnmergedTree=true).assertDoesNotExist()
            beginDragTo(C)
            onNodeWithTag("desktop_root").performMouseInput {release()}
            waitUntil(timeoutMillis=5_000){runtime.engine.state.value.items.getValue("task:$A").content().number("quadrant")==1}
            assertEquals(listOf(B,A),OrganizeActions.tasks(runtime.engine.state.value,1).map {it.id})
            assertTrue(runtime.engine.state.value.items.getValue("task:$C").completed())
        }
    }

    @Test fun shortWindowEdgeDragScrollsToLowerQuadrantAndSavesOnlyOnRelease() = runDesktopComposeUiTest(width=960,height=480) {
        fixture().use {runtime->
            setContent {DesktopUi(runtime,{}, {}, {})}
            onNodeWithTag("nav_四象限").performClick()
            val before=runtime.engine.state.value
            val viewport=onNodeWithTag("quadrant_board_viewport",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val lowerBefore=onNodeWithTag("quadrant_2",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val source=onNodeWithTag("quadrant_drag_$A",useUnmergedTree=true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
            assertTrue("The lower quadrant should initially be mostly below the short viewport",lowerBefore.height<viewport.height*.65f)
            onNodeWithTag("desktop_root").performMouseInput {
                moveTo(source);press();moveTo(source+Offset(0f,12f),delayMillis=80)
                moveTo(Offset(viewport.left+viewport.width*.25f,viewport.bottom-8f),delayMillis=250)
            }
            // Keep the mouse held at the visible board edge; only the real drag auto-scroll may reveal it.
            waitUntil(timeoutMillis=5_000) {
                onNodeWithTag("quadrant_2",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.height>=viewport.height*.65f
            }
            val lower=onNodeWithTag("quadrant_2",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertTrue("Dragging at the edge must reveal more of the lower quadrant",lower.height>lowerBefore.height)
            // boundsInRoot is clipped to the viewport, so its center is a visible drop position.
            onNodeWithTag("desktop_root").performMouseInput {moveTo(lower.center,delayMillis=100)}
            waitForIdle()
            onNodeWithTag("quadrant_drop_2",useUnmergedTree=true).assertIsDisplayed()
            assertEquals("Scrolling and the destination preview must not save a move",before,runtime.engine.state.value)
            savePreview("short-window")
            onNodeWithTag("desktop_root").performMouseInput {release()}
            waitUntil(timeoutMillis=5_000){runtime.engine.state.value.items.getValue("task:$A").content().number("quadrant")==2}
            assertEquals(listOf(A),OrganizeActions.tasks(runtime.engine.state.value,2).map {it.id})
            assertEquals(listOf(B),OrganizeActions.tasks(runtime.engine.state.value,1).map {it.id})
        }
    }

    private fun DesktopComposeUiTest.beginDragTo(target:String) {
        val source=onNodeWithTag("quadrant_drag_$A",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val destination=onNodeWithTag("task_$target").fetchSemanticsNode().boundsInRoot
        // Grab near the handle edge, away from its center icon.
        val from=Offset(source.left+3f,source.center.y)
        onNodeWithTag("desktop_root").performMouseInput {
            moveTo(from);press();moveTo(from+Offset(0f,12f),delayMillis=80)
            moveTo(Offset(destination.center.x,destination.top+6f),delayMillis=250)
        }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.savePreview(name:String) {
        val output=Path.of("build","test-screenshots");Files.createDirectories(output)
        val host=System.getProperty("os.name").lowercase().replace(Regex("[^a-z0-9]+"),"-")
        assertTrue(ImageIO.write(onNodeWithTag("desktop_root").captureToImage().toAwtImage(),"png",output.resolve("quadrant-drag-$host-$name.png").toFile()))
    }

    private fun java.awt.image.BufferedImage.pixelHash()=getRGB(0,0,width,height,null,0,width).contentHashCode()

    private suspend fun fixture(includeCompleted:Boolean=false):DesktopRuntime {
        val runtime=DesktopRuntime(Files.createTempDirectory("step-quadrant-drag-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient {_,_,_->error("No AI in fixture")})
        try {
            runtime.initialize();runtime.edit {original->
                val now=Instant.now()
                var state=StateMachine.edit(original,"task",A,ContentActions.save(null,"task","虚构拖动来源","",0,A,now))
                state=StateMachine.edit(state,"task",B,ContentActions.save(null,"task","虚构目标行动","",1,B,now))
                if(includeCompleted) {
                    state=StateMachine.edit(state,"task",C,ContentActions.save(null,"task","虚构已完成目标","",1,C,now))
                    state=CompletionActions.set(state,C,true,now)
                }
                state
            }.getOrThrow()
            return runtime
        } catch(error:Throwable) {runtime.close();throw error}
    }

    private companion object {
        const val A="00000000-0000-4000-8000-000000001011"
        const val B="00000000-0000-4000-8000-000000001012"
        const val C="00000000-0000-4000-8000-000000001013"
    }
}
