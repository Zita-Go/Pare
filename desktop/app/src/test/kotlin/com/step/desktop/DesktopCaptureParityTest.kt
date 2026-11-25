package com.step.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import com.pare.app.*
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

/** Fictional stores only. These tests exercise Compose; they do not certify Windows IME/foreground policy. */
@OptIn(ExperimentalTestApi::class,InternalComposeUiApi::class,ExperimentalComposeUiApi::class)
class DesktopCaptureParityTest {
    @Test fun anOpenCaptureDetailCannotSilentlySwitchPlansWhenThePlanningZoneChanges()=runDesktopComposeUiTest(width=460,height=520) {
        fixture().use {runtime->
            val originalPlan=ContentActions.planId(runtime.engine.state.value,Instant.now())
            setContent {QuickCaptureUi(runtime,{})}
            onNodeWithTag("capture_detail_$FIRST").performScrollTo().performClick()
            onNodeWithText("任务详情").performClick()
            runtime.edit {state->PreferencesActions.saveProfile(state,ProfileDraft(background="虚构的时区变更验收"),
                ProfileBaseline.from(state.items["profile:profile"]),ZoneId.of("Pacific/Kiritimati"))}.getOrThrow()
            waitForIdle()
            onNodeWithText("移出${planningDayLabel(originalPlan)}").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000){onAllNodesWithText("规划时区已改变，请重新打开这一天").fetchSemanticsNodes().isNotEmpty()}
            assertTrue(DayTaskSelection.contains(runtime.engine.state.value,FIRST,originalPlan))
            assertFalse(DayTaskSelection.contains(runtime.engine.state.value,FIRST,ContentActions.planId(runtime.engine.state.value,Instant.now())))
        }
    }

    @Test fun composingReturnAndEscapeDoNotSubmitOrCloseButCommittedReturnDoes()=runDesktopComposeUiTest(width=460,height=480) {
        fixture().use {runtime->
            var input:PlatformTextInputMethodRequest?=null
            var closed=0
            setContent {
                InterceptPlatformTextInput({request,_->input=request;awaitCancellation()}) {
                    QuickCaptureUi(runtime,{closed++})
                }
            }
            val field=onNode(hasSetTextAction() and hasText("此刻想到什么？"))
            field.performClick()
            waitUntil(timeoutMillis=5_000){input!=null}
            runOnIdle {requireNotNull(input).onEditCommand(listOf(SetComposingTextCommand("虚构输入法候选",1)))}
            field.performKeyInput {pressKey(Key.Enter)}
            field.performKeyInput {withKeyDown(Key.CtrlLeft){pressKey(Key.Enter)}}
            field.performKeyInput {pressKey(Key.Escape)}
            runOnIdle {
                assertTrue(runtime.engine.state.value.items.values.none {it.kind=="inbox"})
                assertEquals(0,closed)
                requireNotNull(input).onEditCommand(listOf(CommitTextCommand("虚构输入法已确认",1)))
            }
            field.performKeyInput {pressKey(Key.Enter)}
            waitUntil(timeoutMillis=5_000){runtime.engine.state.value.items.values.any {it.kind=="inbox"}}
            assertEquals("虚构输入法已确认",runtime.engine.state.value.items.values.single {it.kind=="inbox"}.title())
        }
    }

    @Test fun platformSelectCopyCutAndPasteKeepMixedTextInsideCapture()=runDesktopComposeUiTest(width=460,height=480) {
        fixture().use {runtime->
            val clipboard=object:Clipboard {
                var entry:ClipEntry?=null
                override val nativeClipboard=java.awt.datatransfer.Clipboard("fictional capture clipboard")
                override suspend fun getClipEntry()=entry
                override suspend fun setClipEntry(clipEntry:ClipEntry?) {entry=clipEntry}
            }
            setContent {CompositionLocalProvider(LocalClipboard provides clipboard){QuickCaptureUi(runtime,{})}}
            val field=onNode(hasSetTextAction() and hasText("此刻想到什么？"))
            val modifier=if(System.getProperty("os.name").startsWith("Mac"))Key.MetaLeft else Key.CtrlLeft
            val text="虚构中文 English 123"
            field.performTextReplacement(text)
            field.performKeyInput {withKeyDown(modifier){pressKey(Key.A);pressKey(Key.C)}}
            field.performTextReplacement("")
            field.performKeyInput {withKeyDown(modifier){pressKey(Key.V)}}
            assertEquals(text,field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            field.performKeyInput {withKeyDown(modifier){pressKey(Key.A);pressKey(Key.X)}}
            assertEquals("",field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            field.performKeyInput {withKeyDown(modifier){pressKey(Key.V)}}
            assertEquals(text,field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertTrue(runtime.engine.state.value.items.values.none {it.kind=="inbox"})
        }
    }

    @Test fun returnSavesOnceAndLeavesFeedbackAndFocusReadyForTheNextCapture()=runDesktopComposeUiTest(width=460,height=480) {
        fixture().use {runtime->
            var closed=0
            setContent {QuickCaptureUi(runtime,{closed++})}
            val field=onNode(hasSetTextAction() and hasText("此刻想到什么？"))
            field.performTextReplacement("虚构：写一个标题")
            field.performKeyInput {pressKey(Key.Enter)}
            waitUntil(timeoutMillis=5_000){runtime.engine.state.value.items.values.any {it.kind=="inbox"}}
            onNodeWithText("已记下 · 待整理").assertIsDisplayed()
            field.assertIsFocused()
            assertEquals("",field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            field.performKeyInput {pressKey(Key.Enter)}
            assertEquals(1,runtime.engine.state.value.items.values.count {it.kind=="inbox"})
            assertEquals(0,closed)
            field.performTextReplacement("虚构：补充第二个标题")
            field.performKeyInput {withKeyDown(Key.CtrlLeft){pressKey(Key.Enter)}}
            waitUntil(timeoutMillis=5_000){runtime.engine.state.value.items.values.count {it.kind=="inbox"}==2}
            assertEquals(0,closed)
        }
    }

    @Test fun escapeRetainsDraftAndReopeningRestoresInputFocusWithoutChangingRunningWork()=runDesktopComposeUiTest(width=460,height=480) {
        fixture().use {runtime->
            runtime.execute {it.startFocus(RUNNING,5)}.getOrThrow()
            var visible by mutableStateOf(true)
            setContent {QuickCaptureUi(runtime,{visible=false},visible)}
            val field=onNode(hasSetTextAction() and hasText("此刻想到什么？"))
            field.performTextReplacement("虚构：尚未记下的草稿")
            field.performKeyInput {pressKey(Key.Escape)}
            runOnIdle {assertFalse(visible);visible=true}
            field.assertIsFocused()
            assertEquals("虚构：尚未记下的草稿",field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertEquals(RUNNING,runtime.execution.state.value.focus?.taskId)
            assertFalse(runtime.execution.state.value.focus!!.paused)
        }
    }

    @Test fun runningUnselectedTaskAndAllTodayTasksRemainReachableWithCollapseAndDetails()=runDesktopComposeUiTest(width=340,height=300) {
        fixture().use {runtime->
            runtime.execute {it.startFocus(RUNNING,5)}.getOrThrow()
            setContent {QuickCaptureUi(runtime,{})}
            onNodeWithTag("capture_pause_$RUNNING").performScrollTo().assertIsDisplayed()
            onNodeWithTag("capture_detail_$FOURTH").performScrollTo().performClick()
            onNodeWithTag("desktop_dialog").assertIsDisplayed()
            onNodeWithText("关闭").performClick()
            onNodeWithTag("desktop_dialog").assertDoesNotExist()
            onNodeWithTag("capture_today_toggle").performScrollTo().performClick()
            onNodeWithTag("capture_detail_$FOURTH").assertDoesNotExist()
            onNodeWithTag("capture_today_toggle").performClick()
            onNodeWithTag("capture_complete_$FOURTH").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000){runtime.engine.state.value.items.getValue("task:$FOURTH").completed()}
            onNodeWithTag("capture_progress").performScrollTo().assertTextContains("已完成 1",substring=true)
            assertEquals(RUNNING,runtime.execution.state.value.focus?.taskId)
            assertFalse(runtime.execution.state.value.focus!!.paused)
        }
    }

    @Test fun failedCaptureRetainsDraftAndRetryCreatesOnlyOneInboxEntry()=runDesktopComposeUiTest(width=460,height=480) {
        fixture().use {runtime->
            setContent {QuickCaptureUi(runtime,{})}
            val field=onNode(hasSetTextAction() and hasText("此刻想到什么？"))
            field.performTextReplacement("虚构：失败后仍要保留")
            val backup=runtime.home.resolve("sync-state.json.bak")
            Files.deleteIfExists(backup);Files.createDirectory(backup)
            Files.writeString(backup.resolve("blocked"),"fictional")
            onNodeWithTag("quick_capture_save").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis=5_000){runtime.error.value!=null}
            waitUntil(timeoutMillis=5_000){onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
            assertEquals("虚构：失败后仍要保留",field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertTrue(runtime.engine.state.value.items.values.none {it.kind=="inbox"})
            Files.delete(backup.resolve("blocked"));Files.delete(backup)
            onNodeWithTag("quick_capture_save").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis=5_000){runtime.engine.state.value.items.values.count {it.kind=="inbox"}==1}
            onNodeWithText("已记下 · 待整理").assertIsDisplayed()
            onNodeWithTag("quick_capture_save").assertIsNotEnabled()
        }
    }

    private suspend fun fixture():DesktopRuntime {
        val clock=FixtureClock().apply {time=time.copy(wallMillis=Instant.now().toEpochMilli())}
        val runtime=DesktopRuntime(Files.createTempDirectory("step-capture-parity-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,clock,MobileAIClient {_,_,_->error("No model access")})
        try {
            runtime.initialize();runtime.edit {state->
                val now=Instant.now();var next=state
                listOf(FIRST,SECOND,THIRD,FOURTH,RUNNING).forEachIndexed {index,id->
                    next=StateMachine.edit(next,"task",id,ContentActions.save(null,"task","虚构随手记行动 ${index+1}","",1,id,now))
                    if(id!=RUNNING)next=DayTaskActions.setIncluded(next,id,true,ContentActions.planId(next,now),now)
                };next
            }.getOrThrow();return runtime
        }catch(error:Throwable){runtime.close();throw error}
    }

    private companion object {
        const val FIRST="00000000-0000-4000-8000-000000000891"
        const val SECOND="00000000-0000-4000-8000-000000000892"
        const val THIRD="00000000-0000-4000-8000-000000000893"
        const val FOURTH="00000000-0000-4000-8000-000000000894"
        const val RUNNING="00000000-0000-4000-8000-000000000895"
    }
}
