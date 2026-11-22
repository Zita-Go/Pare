package com.step.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.pare.app.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

@OptIn(ExperimentalTestApi::class)
class DesktopQuickSurfaceTest {
    @Test fun externalTaskOpenCannotDiscardAnEditorWithoutConfirmation()=runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {r->
            var requested by mutableStateOf<String?>(null)
            setContent {DesktopUi(r,{}, {}, {},requested,{requested=null})}
            onNodeWithTag("nav_四象限").performClick()
            onNodeWithTag("task_$A").performClick()
            val input=hasSetTextAction() and hasText("下一步具体做什么？")
            onNode(input).performTextReplacement("尚未保存的虚构动作")
            runOnIdle {requested=B}
            onNodeWithTag("deep_link_keep").performClick()
            assertEquals("尚未保存的虚构动作",onNode(input).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            runOnIdle {requested=B}
            onNodeWithTag("deep_link_discard").performClick()
            assertEquals("",onNode(input).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertEquals("打开虚构提纲",r.engine.state.value.items.getValue("task:$A").content().str("nextAction"))
        }
    }
    @Test fun widgetCanPauseTheRunningTaskEvenWhenItWasNotSelectedForToday()=runDesktopComposeUiTest(width=390,height=560) {
        fixture().use {r->
            r.execute {it.startFocus(B,5)}.getOrThrow()
            setContent {DesktopWidgetUi(r,{}, {})}
            onNodeWithTag("widget_pause_$B").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis=5_000){r.execution.state.value.focus?.paused==true}
            assertFalse(DayTaskSelection.contains(r.engine.state.value,B,ContentActions.planId(r.engine.state.value,Instant.now())))
        }
    }
    @Test fun failedSmallStepSaveKeepsDraftAndNeverStartsTimer()=runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {r->
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("quick_support_$A").performClick()
            onNodeWithTag("quick_next_action").performTextReplacement("先写一个虚构标题")
            onNodeWithTag("quick_stop_criterion").performTextReplacement("写完一个标题就停")
            val backup=r.home.resolve("sync-state.json.bak")
            Files.deleteIfExists(backup);Files.createDirectory(backup);Files.writeString(backup.resolve("blocked"),"fictional")
            onNodeWithTag("quick_save_start").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000){r.error.value!=null}
            assertNull(r.execution.state.value.focus)
            assertEquals("打开虚构提纲",r.engine.state.value.items.getValue("task:$A").content().str("nextAction"))
            assertEquals("先写一个虚构标题",onNodeWithTag("quick_next_action").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
    }
    @Test fun writingANextStepDoesNotInventABlockerOrShowAnEmptyCompleteAction()=runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {r->
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("nav_四象限").performClick()
            onNodeWithTag("quick_next_$B").assertDoesNotExist()
            onNodeWithTag("quick_support_$B").assertTextContains("写下一步").performClick()
            onNodeWithTag("quick_next_action").performTextReplacement("虚构：整理一个标题")
            onNodeWithTag("quick_stop_criterion").performTextReplacement("留下一个标题就停")
            onNodeWithTag("quick_save_only").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000){r.engine.state.value.items.getValue("task:$B").content().str("nextAction")=="虚构：整理一个标题"}
            waitUntil(timeoutMillis=5_000){onAllNodesWithTag("desktop_dialog").fetchSemanticsNodes().isEmpty()}
            assertTrue(r.engine.state.value.items.getValue("task:$B").content().array("blockerEntries").isEmpty())
            assertNull(r.execution.state.value.focus)
            assertFalse(DayTaskSelection.contains(r.engine.state.value,B,ContentActions.planId(r.engine.state.value,Instant.now())))
            onNodeWithTag("quick_next_$B").assertTextContains("完成这一步")
        }
    }

    private suspend fun fixture():DesktopRuntime {
        val r=DesktopRuntime(Files.createTempDirectory("step-quick-surface-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient {_,_,_->error("No model access")})
        try {
            r.initialize();r.edit {s->val now=Instant.now()
                var n=StateMachine.edit(s,"task",A,ContentActions.save(null,"task","虚构重点","",0,A,now,nextAction="打开虚构提纲"))
                n=StateMachine.edit(n,"task",B,ContentActions.save(null,"task","虚构未选任务","",1,B,now))
                DayTaskActions.setIncluded(n,A,true,ContentActions.planId(n,now),now)
            }.getOrThrow();return r
        } catch(e:Throwable){r.close();throw e}
    }
    private companion object {
        const val A="00000000-0000-4000-8000-000000000821"
        const val B="00000000-0000-4000-8000-000000000822"
    }
}
