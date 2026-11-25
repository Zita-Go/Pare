package com.step.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.pare.app.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

/** Real mouse input against the task body; all records live in an isolated fictional library. */
@OptIn(ExperimentalTestApi::class)
class DesktopRowDragSurfaceTest {
    @Test fun taskBodyHasNoLeadingGripAndItsPreviewKeepsTheGrabPointWhileMoving() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {runtime->
            showBoard(runtime)
            val row=bounds("task_$A")
            val circle=onNodeWithContentDescription("完成任务：$TITLE").fetchSemanticsNode().boundsInRoot
            assertTrue("Completion must start the row without a reserved drag-grip column",
                circle.left-row.left<=with(density){8.dp.toPx()})
            // This legacy tag now identifies the title itself, never a separate grip.
            onNodeWithTag("quadrant_drag_$A",useUnmergedTree=true).assertTextContains(TITLE)
            val title=bounds("quadrant_drag_$A")
            val from=Offset(title.left+12f,title.center.y)
            val target=bounds("task_$B")
            val to=Offset(target.left+(from.x-row.left),target.top+(from.y-row.top))
            val before=runtime.engine.state.value
            beginDrag(from,to)

            onNodeWithTag("task_drag_preview",useUnmergedTree=true).assertIsDisplayed()
            onNode(hasText(TITLE) and (hasTestTag("task_drag_preview") or hasAnyAncestor(hasTestTag("task_drag_preview"))),useUnmergedTree=true).assertIsDisplayed()
            val first=bounds("task_drag_preview")
            assertEquals("The preview must preserve the horizontal grab offset",row.left+to.x-from.x,first.left,2f)
            assertEquals("The preview must preserve the vertical grab offset",row.top+to.y-from.y,first.top,2f)
            val delta=Offset(-18f,18f)
            onNodeWithTag("desktop_root").performMouseInput {moveTo(to+delta,delayMillis=100)}
            waitForIdle()
            val moved=bounds("task_drag_preview")
            assertEquals("The preview must follow horizontal mouse motion",delta.x,moved.left-first.left,2f)
            assertEquals("The preview must follow vertical mouse motion",delta.y,moved.top-first.top,2f)
            assertEquals("A floating preview must not edit the task",before,runtime.engine.state.value)

            onNodeWithTag("desktop_root").performMouseInput {release()}
            waitUntil(timeoutMillis=5_000){runtime.engine.state.value.items.getValue("task:$A").content().number("quadrant")==1}
            onNodeWithTag("task_drag_preview",useUnmergedTree=true).assertDoesNotExist()
            onNodeWithTag("desktop_dialog").assertDoesNotExist()
        }
    }

    @Test fun blankTaskBodyCanStartADragAndEscapePreventsALaterReleaseFromSaving() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {runtime->
            showBoard(runtime)
            val row=bounds("task_$A")
            val from=Offset(row.right-3f,row.bottom-3f)
            val target=bounds("task_$B").center
            val before=runtime.engine.state.value
            beginDrag(from,target)
            onNodeWithTag("task_drag_preview",useUnmergedTree=true).assertIsDisplayed()
            onNodeWithTag("desktop_root").performKeyInput {pressKey(Key.Escape)}
            waitForIdle()
            onNodeWithTag("task_drag_preview",useUnmergedTree=true).assertDoesNotExist()
            // Moving again while still pressed must not resurrect a cancelled gesture.
            onNodeWithTag("desktop_root").performMouseInput {moveTo(target+Offset(0f,8f));release()}
            waitForIdle()
            assertEquals(before,runtime.engine.state.value)
            onNodeWithTag("desktop_dialog").assertDoesNotExist()
        }
    }

    @Test fun releaseOutsideTheBoardDiscardsTheLastValidDropTarget() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {runtime->
            showBoard(runtime)
            val before=runtime.engine.state.value
            beginDrag(bounds("quadrant_drag_$A").center,bounds("task_$B").center)
            onNodeWithTag("task_drag_preview",useUnmergedTree=true).assertIsDisplayed()
            val outside=bounds("nav_四象限").center
            onNodeWithTag("desktop_root").performMouseInput {moveTo(outside,delayMillis=100);release()}
            waitForIdle()
            onNodeWithTag("task_drag_preview",useUnmergedTree=true).assertDoesNotExist()
            assertEquals("Leaving the board before release must not commit the earlier target",before,runtime.engine.state.value)
            onNodeWithTag("desktop_dialog").assertDoesNotExist()
        }
    }

    @Test fun draggingOutOfAnActionButtonNeitherDragsTheTaskNorClicksTheButton() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {runtime->
            showBoard(runtime)
            waitUntil(timeoutMillis=5_000){runtime.executionReady.value}
            val before=runtime.engine.state.value
            val controls=listOf(
                hasContentDescription("完成任务：$TITLE"),
                hasTestTag("day_win_$A"),hasTestTag("day_include_$A"),hasTestTag("task_detail_$A"),
                hasTestTag("quick_focus_$A"),hasTestTag("quick_parallel_$A"),
                hasTestTag("quick_next_$A"),hasTestTag("quick_support_$A")
            )
            controls.forEach {control->
                val from=onNode(control).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot.center
                val target=bounds("task_$B").center
                beginDrag(from,target)
                onNodeWithTag("task_drag_preview",useUnmergedTree=true).assertDoesNotExist()
                onNodeWithTag("desktop_root").performMouseInput {release()}
                waitForIdle()
                onNodeWithTag("desktop_dialog").assertDoesNotExist()
                onNodeWithText("5 分钟").assertDoesNotExist()
                assertEquals("Dragging out of $control must not invoke its click action",before,runtime.engine.state.value)
                assertNull(runtime.execution.state.value.focus)
                assertTrue(runtime.execution.state.value.external.isEmpty())
            }
        }
    }

    @Test fun titleClickAndSmallPointerMotionStillOpenTaskDetails() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {runtime->
            showBoard(runtime)
            val before=runtime.engine.state.value
            listOf(Offset.Zero,Offset(2f,1f)).forEach {jitter->
                val title=bounds("quadrant_drag_$A").center
                onNodeWithTag("desktop_root").performMouseInput {
                    moveTo(title);press();moveTo(title+jitter,delayMillis=80)
                }
                onNodeWithTag("task_drag_preview",useUnmergedTree=true).assertDoesNotExist()
                onNodeWithTag("desktop_root").performMouseInput {release()}
                onNodeWithTag("desktop_dialog").assertIsDisplayed()
                onNode(hasText("关闭") and hasAnyAncestor(hasTestTag("desktop_dialog"))).performClick()
                onNodeWithTag("desktop_dialog").assertDoesNotExist()
            }
            assertEquals(before,runtime.engine.state.value)
        }
    }

    @Test fun rightClickOnTheTaskTitleKeepsItsContextMenu() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {runtime->
            showBoard(runtime)
            val before=runtime.engine.state.value
            onNodeWithTag("quadrant_drag_$A",useUnmergedTree=true).performMouseInput {click(button=MouseButton.Secondary)}
            onNodeWithTag("task_drag_preview",useUnmergedTree=true).assertDoesNotExist()
            onNodeWithText("打开 / 编辑").assertIsDisplayed().performClick()
            onNodeWithTag("desktop_dialog").assertIsDisplayed()
            assertEquals(before,runtime.engine.state.value)
        }
    }

    private fun DesktopComposeUiTest.showBoard(runtime:DesktopRuntime) {
        setContent {DesktopUi(runtime,{}, {}, {})}
        onNodeWithTag("nav_四象限").performClick()
    }

    private fun DesktopComposeUiTest.bounds(tag:String):Rect =
        onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot

    private fun DesktopComposeUiTest.beginDrag(from:Offset,to:Offset) {
        onNodeWithTag("desktop_root").performMouseInput {
            moveTo(from);press();moveTo(from+Offset(0f,32f),delayMillis=80);moveTo(to,delayMillis=150)
        }
        waitForIdle()
    }

    private suspend fun fixture():DesktopRuntime {
        val runtime=DesktopRuntime(Files.createTempDirectory("step-row-drag-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient {_,_,_->error("No AI in fictional fixture")})
        try {
            runtime.initialize()
            runtime.edit {original->
                val now=Instant.now()
                var state=StateMachine.edit(original,"task",A,ContentActions.save(null,"task",TITLE,"",0,A,now,nextAction="虚构：先画一个圆"))
                state=StateMachine.edit(state,"task",B,ContentActions.save(null,"task","虚构：放置示例卡片","",1,B,now))
                DayTaskActions.setIncluded(state,A,true,ContentActions.planId(state,now),now)
            }.getOrThrow()
            return runtime
        } catch(error:Throwable){runtime.close();throw error}
    }

    private companion object {
        const val A="00000000-0000-4000-8000-000000009801"
        const val B="00000000-0000-4000-8000-000000009802"
        const val TITLE="虚构：整理示例卡片"
    }
}
