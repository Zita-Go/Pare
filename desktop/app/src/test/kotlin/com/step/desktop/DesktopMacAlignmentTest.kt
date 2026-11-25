package com.step.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import com.pare.app.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

@OptIn(ExperimentalTestApi::class)
class DesktopMacAlignmentTest {
    @Test fun matrixKeyboardMovesCompletesAndRestoresWhileTextFieldsKeepTheirKeys()=runDesktopComposeUiTest(width=960,height=760) {
        val r=fixture(FixtureClock().apply {time=time.copy(wallMillis=Instant.now().toEpochMilli())})
        try {
            val id="00000000-0000-4000-8000-000000001451"
            r.edit {StateMachine.edit(it,"task",id,ContentActions.save(null,"task","虚构键盘归类","",0,id,Instant.now()))}.getOrThrow()
            setContent {DesktopUi(r,{}, {})}
            onNodeWithTag("organize_keyboard").performClick()
            onNodeWithTag("desktop_root").performKeyInput {pressKey(Key.DirectionDown);pressKey(Key.Four)}
            waitUntil(timeoutMillis=5_000){r.engine.state.value.items.getValue("task:$id").content().number("quadrant")==3}
            waitUntil(timeoutMillis=5_000){onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
            onNodeWithTag("desktop_root").performKeyInput {pressKey(Key.Spacebar)}
            waitUntil(timeoutMillis=5_000){r.engine.state.value.items.getValue("task:$id").completed()}
            onNodeWithTag("task_$id").assertIsDisplayed()
            waitUntil(timeoutMillis=5_000){onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
            onNodeWithTag("desktop_root").performKeyInput {pressKey(Key.Spacebar)}
            waitUntil(timeoutMillis=5_000){!r.engine.state.value.items.getValue("task:$id").completed()}
            onNodeWithTag("quadrant_add_0").performTextInput("数字 1234 不归类")
            onNodeWithTag("quadrant_add_0").performKeyInput {pressKey(Key.One);pressKey(Key.Spacebar)}
            assertEquals(3,r.engine.state.value.items.getValue("task:$id").content().number("quadrant"))
            assertFalse(r.engine.state.value.items.getValue("task:$id").completed())
            onNodeWithTag("task_detail_$id").assertIsDisplayed().performClick()
            onNode(hasSetTextAction() and hasText("下一步具体做什么？")).assertIsDisplayed()
        } finally {r.close()}
    }

    @Test fun quadrantDraftSurvivesWorkspaceSwitchAndFailedSaveAndSearchDoesNotEatInput()=runDesktopComposeUiTest(width=960,height=760) {
        val r=fixture()
        try {
            setContent {DesktopUi(r,{}, {})}
            onNodeWithTag("quadrant_add_3").performTextInput("虚构 1234 空格")
            onNodeWithTag("nav_今天").performClick()
            assertTrue(DesktopDraftStatus.hasUnsavedEdits.value)
            onNodeWithTag("nav_四象限").performClick()
            val field=onNodeWithTag("quadrant_add_3")
            field.assertTextContains("虚构 1234 空格")
            val backup=r.home.resolve("sync-state.json.bak")
            Files.deleteIfExists(backup);Files.createDirectory(backup);Files.writeString(backup.resolve("blocked"),"fictional")
            field.performClick().performKeyInput {pressKey(Key.Enter)}
            waitUntil(timeoutMillis=5_000){r.error.value!=null}
            field.assertTextContains("虚构 1234 空格")
            assertTrue(r.engine.state.value.items.isEmpty())
            field.performKeyInput {withKeyDown(Key.CtrlLeft){pressKey(Key.F)}}
            onNodeWithTag("library_search_input").assertIsFocused().performTextInput("1234")
            assertTrue(r.engine.state.value.items.isEmpty())
        } finally {r.close()}
    }

    @Test fun eachQuadrantAddsInlineWithoutAddingToTodayOrDuplicatingReturn()=runDesktopComposeUiTest(width=960,height=760) {
        val r=fixture()
        try {
            setContent {DesktopUi(r,{}, {})}
            onNodeWithTag("nav_四象限").performClick()
            for(q in 0..3) {
                val input=onNodeWithTag("quadrant_add_$q")
                input.assertIsDisplayed().performTextInput("虚构｜象限 ${q+1} 新行动")
                input.performKeyInput {pressKey(Key.Enter);pressKey(Key.Enter)}
                waitUntil(timeoutMillis=5_000){r.engine.state.value.items.values.any {it.title()=="虚构｜象限 ${q+1} 新行动"}}
                val state=r.engine.state.value
                val task=state.items.values.single {it.title()=="虚构｜象限 ${q+1} 新行动"}
                assertEquals(q,task.content().number("quadrant"))
                assertFalse(DayTaskSelection.contains(state,task.id,ContentActions.planId(state,Instant.now())))
                assertEquals("",input.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            }
        } finally {r.close()}
    }

    @Test fun workspaceKeepsMacNavigationAndGoalsReviewSettingsReachableAtCompactSize()=runDesktopComposeUiTest(width=960,height=620) {
        val r=fixture()
        try {
            setContent {DesktopUi(r,{}, {})}
            onNodeWithTag("nav_四象限").assertIsDisplayed()
            onNodeWithText("我的空间").assertDoesNotExist()
            onNodeWithTag("nav_今天").assertIsDisplayed().performClick()
            onNodeWithTag("create_day_task").assertIsDisplayed()
            onNodeWithTag("nav_资料库").assertIsDisplayed().performClick()
            onNodeWithTag("nav_想法").assertIsDisplayed().performClick()
            onNodeWithTag("nav_目标").assertIsDisplayed().performClick()
            onNodeWithText("搜索目标").assertExists()
            onNodeWithTag("nav_回顾").assertIsDisplayed().performClick()
            onNodeWithTag("workspace_settings").assertIsDisplayed().performClick()
            onNodeWithText("账号与同步").assertExists()
        } finally {r.close()}
    }

    private suspend fun fixture(clock:FixtureClock=FixtureClock()):DesktopRuntime = DesktopRuntime(Files.createTempDirectory("step-alignment-test-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,clock,MobileAIClient()).also {it.initialize()}
}
