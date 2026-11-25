package com.step.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

/** Real idea editor and isolated persistence; no account, model or live user data. */
@OptIn(ExperimentalTestApi::class)
class DesktopIdeaReviewTest {
    @Test fun savingAndClearingReviewTimePreservesOtherDraftsAndLinkedActions() = runDesktopComposeUiTest(width=1100,height=1000) {
        val runtime=fixture()
        try {
            setContent {DesktopTheme {IdeaEditor(runtime,IDEA,PLAN,{},{})}}
            input("想法").performTextReplacement("虚构｜还没保存的标题")
            input(REVIEW).performScrollTo().performTextReplacement("2031-04-10 09:30")
            onNodeWithTag("idea_review_save").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {runtime.engine.state.value.items.getValue("idea:$IDEA").content().str("reviewAt")=="2031-04-10T01:30:00Z"}
            waitSaved()
            assertEquals("虚构｜还没保存的标题",input("想法").editable())
            val idea=runtime.engine.state.value.items.getValue("idea:$IDEA")
            assertEquals("虚构｜稍后探索的纸卡",idea.title())
            assertEquals(listOf(TASK),idea.content().array("linkedTaskIDs").map {it.jsonPrimitive.content})
            // Read the fictional content file without contending for the running store lock.
            val persisted=wire.decodeFromJsonElement<SyncState>(wire.parseToJsonElement(Files.readString(runtime.home.resolve("sync-state.json"))).jsonObject.getValue("state"))
            assertEquals("2031-04-10T01:30:00Z",persisted.items.getValue("idea:$IDEA").content().str("reviewAt"))
            input(REVIEW).performScrollTo().performTextReplacement("")
            onNodeWithTag("idea_review_save").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {runtime.engine.state.value.items.getValue("idea:$IDEA").content()?.containsKey("reviewAt")==false}
            waitSaved()
            assertEquals("虚构｜还没保存的标题",input("想法").editable())
            runOnIdle {assertTrue(DesktopDraftStatus.hasUnsavedEdits.value)}
            onNodeWithText("关闭").performClick()
            onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        } finally {runtime.close()}
    }

    @Test fun reviewDraftUsesItsOpeningTimeZoneWhenParentPlanningDateChanges() = runDesktopComposeUiTest(width=1100,height=1000) {
        val runtime=fixture()
        var plan by mutableStateOf(PLAN)
        try {
            setContent {DesktopTheme {IdeaEditor(runtime,IDEA,plan,{},{})}}
            assertEquals("2031-04-09 07:15",input(REVIEW).editable())
            input(REVIEW).performScrollTo().performTextReplacement("2031-04-10 09:30")
            runOnIdle {plan="2031-04-11@America/Los_Angeles"}
            assertEquals("2031-04-10 09:30",input(REVIEW).editable())
            onNodeWithTag("idea_review_save").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {runtime.engine.state.value.items.getValue("idea:$IDEA").content().str("reviewAt")=="2031-04-10T01:30:00Z"}
            waitSaved()
            runOnIdle {assertFalse(DesktopDraftStatus.hasUnsavedEdits.value)}
        } finally {runtime.close()}
    }

    @Test fun staleReviewCannotOverwriteNewerDateAndReloadKeepsOtherDrafts() = runDesktopComposeUiTest(width=1100,height=1000) {
        val runtime=fixture()
        try {
            setContent {DesktopTheme {IdeaEditor(runtime,IDEA,PLAN,{},{})}}
            input("想法").performTextReplacement("虚构｜保留这份标题草稿")
            input(REVIEW).performScrollTo().performTextReplacement("2031-04-10 09:30")
            runtime.edit {state->IdeaManagementActions.review(state,IDEA,Instant.parse("2031-04-12T02:00:00Z"),IdeaManagementBaseline.from(state.items.getValue("idea:$IDEA")),Instant.now())}.getOrThrow()
            waitForIdle()
            onNodeWithTag("idea_review_save").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {runtime.error.value!=null}
            waitSaved()
            assertEquals("2031-04-10 09:30",input(REVIEW).editable())
            assertEquals("2031-04-12T02:00:00Z",runtime.engine.state.value.items.getValue("idea:$IDEA").content().str("reviewAt"))
            onNodeWithTag("idea_review_reload").performScrollTo().performClick()
            assertEquals("2031-04-12 10:00",input(REVIEW).editable())
            assertEquals("虚构｜保留这份标题草稿",input("想法").editable())
        } finally {runtime.close()}
    }

    private fun DesktopComposeUiTest.input(label:String)=onNode(hasSetTextAction() and hasText(label))
    private fun SemanticsNodeInteraction.editable()=fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun DesktopComposeUiTest.waitSaved() {
        waitUntil(timeoutMillis=5_000) {onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
        waitForIdle()
    }
    private suspend fun fixture():DesktopRuntime {
        val runtime=DesktopRuntime(Files.createTempDirectory("step-idea-review-fictional-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient {_,_,_->error("Fictional review tests never call AI")})
        try {
            runtime.initialize()
            runtime.edit {state->
                val idea=StateMachine.edit(state,"idea",IDEA,ContentActions.save(null,"idea","虚构｜稍后探索的纸卡","虚构背景",1,IDEA,Instant.now()))
                val linked=IdeaActions.createLinkedTask(idea,IDEA,TASK,"虚构｜检查纸卡","翻开第一张纸卡","",1,null,Instant.now())
                IdeaManagementActions.review(linked,IDEA,Instant.parse("2031-04-08T23:15:00Z"),IdeaManagementBaseline.from(linked.items.getValue("idea:$IDEA")),Instant.now())
            }.getOrThrow()
            return runtime
        } catch(error:Throwable) {runtime.close();throw error}
    }
    private companion object {
        const val IDEA="00000000-0000-4000-8000-000000000751"
        const val TASK="00000000-0000-4000-8000-000000000752"
        const val PLAN="2031-04-09@Asia/Shanghai"
        const val REVIEW="回看时间 yyyy-MM-dd HH:mm（留空清除）"
    }
}
