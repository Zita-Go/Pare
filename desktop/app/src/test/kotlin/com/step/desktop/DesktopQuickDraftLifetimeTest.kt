package com.step.desktop

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.pare.app.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

@OptIn(ExperimentalTestApi::class)
class DesktopQuickDraftLifetimeTest {
    @Test fun taskMovingToAnotherQuadrantDoesNotDisposeAnOpenQuickDraft()=runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {r->
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("nav_四象限").performClick()
            onNodeWithTag("quick_support_$A").performClick()
            onNodeWithTag("quick_next_action").performTextReplacement("尚未保存的跨端编辑草稿")
            r.edit {OrganizeActions.move(it,A,1,null,OrganizeMoveBaseline.from(it,A),Instant.now())}.getOrThrow()
            waitForIdle()
            assertEquals("尚未保存的跨端编辑草稿",onNodeWithTag("quick_next_action").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
    }
    @Test fun widgetChangingItsRunningTaskDoesNotDisposeAnOpenQuickDraft()=runDesktopComposeUiTest(width=390,height=560) {
        fixture().use {r->
            r.execute {it.startFocus(A,5)}.getOrThrow()
            setContent {DesktopWidgetUi(r,{}, {})}
            onNodeWithTag("widget_support_$A").performClick()
            onNodeWithTag("quick_next_action").performTextReplacement("尚未保存的小窗草稿")
            r.execute {it.finish(A);it.startFocus(B,5)}.getOrThrow()
            waitForIdle()
            assertEquals("尚未保存的小窗草稿",onNodeWithTag("quick_next_action").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertEquals(B,r.execution.state.value.focus?.taskId)
        }
    }
    private suspend fun fixture():DesktopRuntime {
        val r=DesktopRuntime(Files.createTempDirectory("step-draft-lifetime-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient {_,_,_->error("No model access")})
        try {
            r.initialize();r.edit {s->val now=Instant.now()
                var n=StateMachine.edit(s,"task",A,ContentActions.save(null,"task","虚构正在编辑任务","",0,A,now,nextAction="打开虚构提纲"))
                n=StateMachine.edit(n,"task",B,ContentActions.save(null,"task","虚构另一任务","",1,B,now))
                DayTaskActions.setIncluded(n,A,true,ContentActions.planId(n,now),now)
            }.getOrThrow();return r
        } catch(e:Throwable){r.close();throw e}
    }
    private companion object {
        const val A="00000000-0000-4000-8000-000000000831"
        const val B="00000000-0000-4000-8000-000000000832"
    }
}
