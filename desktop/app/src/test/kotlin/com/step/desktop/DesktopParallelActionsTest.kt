package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.pare.app.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

/** Real shared row actions against isolated execution persistence; no accounts or AI access. */
@OptIn(ExperimentalTestApi::class)
class DesktopParallelActionsTest {
    @Test fun twoParallelTasksCanStartAlongsidePersonalFocusWithoutInventingCheckTimes()=runDesktopComposeUiTest(width=360,height=700) {
        fixture().use {runtime->
            setContent { actionRows(runtime) }
            onNodeWithTag("test_parallel_$A").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis=5_000){runtime.execution.state.value.external.any {it.taskId==A}}
            onNodeWithTag("test_focus_$C").assertIsEnabled().performClick()
            onNodeWithText("30 分钟").performClick()
            waitUntil(timeoutMillis=5_000){runtime.execution.state.value.focus?.taskId==C}
            onNodeWithTag("test_parallel_$B").assertIsEnabled().performClick()
            waitUntil(timeoutMillis=5_000){runtime.execution.state.value.external.size==2}
            val execution=runtime.execution.state.value
            assertEquals(setOf(A,B),execution.external.map {it.taskId}.toSet())
            assertTrue(execution.external.all {it.nextCheckAtMillis==null&&!it.paused})
            assertEquals(C,execution.focus?.taskId)
            assertEquals(30,execution.focus?.targetMinutes)
            listOf(A,B,C).forEach {id->
                onNodeWithTag("test_parallel_$id").assertDoesNotExist()
                assertFalse(runtime.engine.state.value.items.getValue("task:$id").completed())
            }
        }
    }

    @Test fun pausingResumingAndEndingOneParallelRunPreservesOtherRunsAndPersonalEffort()=runDesktopComposeUiTest(width=360,height=700) {
        val clock=FixtureClock()
        fixture(clock).use {runtime->
            runtime.execute {it.startExternal(A);it.startExternal(B);it.startFocus(C,30)}.getOrThrow()
            val otherRunId=runtime.execution.state.value.external.single {it.taskId==B}.id
            val focusId=runtime.execution.state.value.focus!!.id
            setContent { actionRows(runtime) }
            advance(clock,120_000)
            onNodeWithTag("test_pause_$A").performClick()
            waitUntil(timeoutMillis=5_000){runtime.execution.state.value.external.single {it.taskId==A}.paused}
            waitUntil(timeoutMillis=5_000){onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
            assertFalse(runtime.execution.state.value.external.single {it.taskId==B}.paused)
            assertFalse(runtime.execution.state.value.focus!!.paused)
            advance(clock,180_000)
            onNodeWithTag("test_resume_$A").performClick()
            waitUntil(timeoutMillis=5_000){!runtime.execution.state.value.external.single {it.taskId==A}.paused}
            waitUntil(timeoutMillis=5_000){onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
            advance(clock,60_000)
            onNodeWithTag("test_finish_$A").performClick()
            waitUntil(timeoutMillis=5_000){runtime.execution.state.value.external.none {it.taskId==A}}
            val a=runtime.engine.state.value.items.getValue("task:$A")
            assertEquals(3,a.content().array("parallelRunSessions").single().jsonObject.number("elapsedMinutes"))
            assertEquals(0,a.content().number("actualMinutes")?:0)
            assertTrue(a.content().array("progressSessions").isEmpty())
            assertEquals(otherRunId,runtime.execution.state.value.external.single().id)
            assertEquals(focusId,runtime.execution.state.value.focus?.id)
            assertFalse(runtime.execution.state.value.focus!!.paused)
            onNodeWithTag("test_finish_$B").performClick()
            waitUntil(timeoutMillis=5_000){runtime.execution.state.value.external.isEmpty()}
            val b=runtime.engine.state.value.items.getValue("task:$B")
            assertEquals(6,b.content().array("parallelRunSessions").single().jsonObject.number("elapsedMinutes"))
            assertEquals(0,b.content().number("actualMinutes")?:0)
            onNodeWithTag("test_finish_$C").performClick()
            waitUntil(timeoutMillis=5_000){runtime.execution.state.value.focus==null}
            val c=runtime.engine.state.value.items.getValue("task:$C")
            assertEquals(6,c.content().number("actualMinutes"))
            assertEquals(1,c.content().array("progressSessions").size)
            assertTrue(c.content().array("parallelRunSessions").isEmpty())
            listOf(A,B,C).forEach {assertFalse(runtime.engine.state.value.items.getValue("task:$it").completed())}
        }
    }

    @Test fun checkTimeCanBeSetAndCancelledWithoutStoppingParallelWork()=runDesktopComposeUiTest(width=360,height=700) {
        val clock=FixtureClock()
        fixture(clock).use {runtime->
            runtime.execute {it.startExternal(A);it.startExternal(B,30)}.getOrThrow()
            val otherCheck=clock.time.wallMillis+1_800_000
            setContent { actionRows(runtime) }
            onNodeWithTag("test_check_$A").performClick()
            onNodeWithTag("test_check_15_$A").performClick()
            waitUntil(timeoutMillis=5_000){runtime.execution.state.value.external.single {it.taskId==A}.nextCheckAtMillis==clock.time.wallMillis+900_000}
            // Execution state is published before the runtime finishes saving reminder/presence state.
            waitUntil(timeoutMillis=5_000){onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
            onNodeWithTag("test_check_$A").assertIsEnabled().performClick()
            onNodeWithTag("test_check_cancel_$A").performClick()
            waitUntil(timeoutMillis=5_000){runtime.execution.state.value.external.single {it.taskId==A}.nextCheckAtMillis==null}
            assertEquals(otherCheck,runtime.execution.state.value.external.single {it.taskId==B}.nextCheckAtMillis)
            assertTrue(runtime.execution.state.value.external.all {!it.paused})
            assertNull(runtime.execution.state.value.focus)
            listOf(A,B).forEach {id->assertTrue(runtime.engine.state.value.items.getValue("task:$id").content().array("parallelRunSessions").isEmpty())}
        }
    }

    @Test fun narrowQuickActionsStayVisibleAndDoNotOverlapBeforeAndDuringParallelWork()=runDesktopComposeUiTest(width=280,height=600) {
        fixture().use {runtime->
            var compact by mutableStateOf(false)
            setContent { DesktopTheme {
                Column(Modifier.width(220.dp).testTag("narrow_actions")) {
                    val state by runtime.engine.state.collectAsState()
                    QuickTaskActions(runtime,state.items.getValue("task:$A"),"narrow",compact=compact,featured=!compact)
                }
            } }
            assertInsideHost("narrow_actions",listOf("featured_start_$A","narrow_focus_$A","narrow_parallel_$A","narrow_next_$A","narrow_support_$A"))
            runOnIdle {compact=true}
            assertInsideHost("narrow_actions",listOf("narrow_focus_$A","narrow_parallel_$A","narrow_next_$A","narrow_support_$A"))
            onNodeWithTag("narrow_parallel_$A").performClick()
            waitUntil(timeoutMillis=5_000){runtime.execution.state.value.external.any {it.taskId==A}}
            assertInsideHost("narrow_actions",listOf("narrow_pause_$A","narrow_finish_$A","narrow_check_$A","narrow_next_$A","narrow_support_$A"))
            runOnIdle {compact=false}
            assertInsideHost("narrow_actions",listOf("narrow_pause_$A","narrow_finish_$A","narrow_check_$A","narrow_next_$A","narrow_support_$A"))
        }
    }

    @Test fun completedTasksDoNotOfferParallelOrFocusActions()=runDesktopComposeUiTest(width=360,height=700) {
        fixture().use {runtime->
            runtime.setTaskCompleted(A,true).getOrThrow()
            setContent { actionRows(runtime) }
            onNodeWithTag("test_parallel_$A").assertDoesNotExist()
            onNodeWithTag("test_focus_$A").assertDoesNotExist()
            assertTrue(runtime.execution.state.value.external.isEmpty())
            assertNull(runtime.execution.state.value.focus)
        }
    }

    private fun DesktopComposeUiTest.assertInsideHost(hostTag:String,tags:List<String>) {
        val host=onNodeWithTag(hostTag).fetchSemanticsNode().boundsInRoot
        val bounds=tags.map {tag->
            val rectangle=onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must fit the narrow row",rectangle.left>=host.left&&rectangle.right<=host.right&&rectangle.top>=host.top&&rectangle.bottom<=host.bottom)
            rectangle
        }
        bounds.forEachIndexed {index,rectangle->bounds.drop(index+1).forEach {other->assertFalse("Quick controls must not overlap",rectangle.overlaps(other))}}
    }

    @Composable private fun actionRows(runtime:DesktopRuntime) {
        DesktopTheme {
            val state by runtime.engine.state.collectAsState()
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                listOf(A,B,C).forEach {id->key(id){QuickTaskActions(runtime,state.items.getValue("task:$id"),"test",compact=true)}}
            }
        }
    }
    private fun advance(clock:FixtureClock,millis:Long) {
        clock.time=clock.time.copy(wallMillis=clock.time.wallMillis+millis,elapsedMillis=clock.time.elapsedMillis+millis)
    }
    private suspend fun fixture(clock:FixtureClock=FixtureClock()):DesktopRuntime {
        val runtime=DesktopRuntime(Files.createTempDirectory("step-parallel-actions-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,clock,MobileAIClient {_,_,_->error("No AI in fictional fixture")})
        try {
            runtime.initialize()
            runtime.edit {original->
                var state=original
                listOf(A,B,C).forEachIndexed {index,id->state=StateMachine.edit(state,"task",id,ContentActions.save(null,"task","虚构并行任务 ${index+1}","",index,id,Instant.ofEpochMilli(clock.time.wallMillis),nextAction="打开虚构提纲"))}
                state
            }.getOrThrow()
            return runtime
        } catch(error:Throwable){runtime.close();throw error}
    }
    private companion object {
        const val A="00000000-0000-4000-8000-000000000861"
        const val B="00000000-0000-4000-8000-000000000862"
        const val C="00000000-0000-4000-8000-000000000863"
    }
}
