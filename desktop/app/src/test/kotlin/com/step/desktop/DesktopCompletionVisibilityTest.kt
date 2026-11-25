package com.step.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalTestApi::class)
class DesktopCompletionVisibilityTest {
    @Test fun completionJustAfterMidnightRemainsVisibleBeforeTheNextIdleClockRefresh()=runDesktopComposeUiTest(width=1280,height=900) {
        val before=Instant.parse("2031-04-10T15:59:50Z")
        val runtimeClock=FixtureClock().apply {time=time.copy(wallMillis=before.toEpochMilli())}
        var current=before
        val displayClock=object:Clock() {
            override fun instant()=current
            override fun getZone():ZoneId=ZoneOffset.UTC
            override fun withZone(zone:ZoneId):Clock=Clock.fixed(current,zone)
        }
        val r=DesktopRuntime(Files.createTempDirectory("step-completion-after-midnight-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,runtimeClock,MobileAIClient())
        try {
            r.initialize()
            r.edit {original->
                val state=PreferencesActions.saveProfile(original,ProfileDraft(background="虚构午夜后完成"),ProfileBaseline.from(null),ZoneId.of("Asia/Shanghai"))
                StateMachine.edit(state,"task",A,ContentActions.save(null,"task","虚构午夜后完成","",0,A,before))
            }.getOrThrow()
            setContent {DesktopUi(r,{}, {},clock=displayClock)}
            runOnIdle {current=before.plusSeconds(11);runtimeClock.time=runtimeClock.time.copy(wallMillis=current.toEpochMilli())}
            onNodeWithContentDescription("完成任务：虚构午夜后完成").performClick()
            waitUntil(timeoutMillis=5_000){r.engine.state.value.items.getValue("task:$A").completed()}
            onNodeWithTag("task_$A").assertIsDisplayed()
            onNodeWithContentDescription("完成任务：虚构午夜后完成").assertIsOn()
        } finally {r.close()}
    }

    @Test fun mouseCompletionStaysCheckedAcrossNavigationAndCanBeReopenedInItsQuadrant()=runDesktopComposeUiTest(width=1280,height=900) {
        val now=Instant.now();val clock=FixtureClock().apply {time=time.copy(wallMillis=now.toEpochMilli())}
        val r=DesktopRuntime(Files.createTempDirectory("step-completion-visibility-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,clock,MobileAIClient())
        try {
            r.initialize()
            r.edit {StateMachine.edit(it,"task",A,ContentActions.save(null,"task","虚构：完成后留在象限","",0,A,now))}.getOrThrow()
            setContent {DesktopUi(r,{}, {},clock=Clock.fixed(now,ZoneOffset.UTC))}
            val circle=onNodeWithContentDescription("完成任务：虚构：完成后留在象限")
            circle.performClick()
            waitUntil(timeoutMillis=5_000){r.engine.state.value.items.getValue("task:$A").completed()}
            onNodeWithTag("task_$A").assertIsDisplayed()
            circle.assertIsOn()
            onNodeWithTag("nav_今天").performClick()
            onNodeWithTag("nav_四象限").performClick()
            onNodeWithTag("task_$A").assertIsDisplayed()
            onNodeWithTag("quadrant_drag_$A",useUnmergedTree=true).assertDoesNotExist()
            waitUntil(timeoutMillis=5_000){onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
            circle.performClick()
            waitUntil(timeoutMillis=5_000){!r.engine.state.value.items.getValue("task:$A").completed()}
            circle.assertIsOff()
            onNodeWithTag("quadrant_drag_$A",useUnmergedTree=true).assertExists()
            assertFalse(LibrarySearch.archived(r.engine.state.value.items.getValue("task:$A")))
        } finally {r.close()}
    }

    @Test fun changingPlanningDateKeepsTodaysCompletionUntilTheActualPlanningDayRollsOver()=runDesktopComposeUiTest(width=1280,height=900) {
        val before=Instant.parse("2031-04-10T15:59:00Z")
        val runtimeClock=FixtureClock().apply {time=time.copy(wallMillis=before.toEpochMilli())}
        val r=DesktopRuntime(Files.createTempDirectory("step-completion-midnight-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,runtimeClock,MobileAIClient())
        var displayClock by mutableStateOf<Clock>(Clock.fixed(before,ZoneOffset.UTC))
        try {
            r.initialize()
            r.edit {original->
                var state=PreferencesActions.saveProfile(original,ProfileDraft(background="虚构跨午夜验收"),ProfileBaseline.from(null),ZoneId.of("Asia/Shanghai"))
                state=StateMachine.edit(state,"task",A,ContentActions.save(null,"task","虚构：当天完成保留","",0,A,before))
                CompletionActions.set(state,A,true,before)
            }.getOrThrow()
            setContent {DesktopUi(r,{}, {},clock=displayClock)}
            onNodeWithTag("task_$A").assertIsDisplayed()
            onNodeWithTag("planning_next_day").performClick()
            onNodeWithTag("task_$A").assertIsDisplayed()
            runOnIdle {displayClock=Clock.fixed(before.plusSeconds(60),ZoneOffset.UTC)}
            onNodeWithTag("task_$A").assertDoesNotExist()
            assertTrue(r.engine.state.value.items.getValue("task:$A").completed())
            assertFalse(LibrarySearch.archived(r.engine.state.value.items.getValue("task:$A")))
            onNodeWithTag("nav_资料库").performClick()
            onNodeWithTag("library_scope_COMPLETED").performClick()
            onNodeWithTag("library_$A").assertIsDisplayed()
        } finally {r.close()}
    }

    @Test fun clearingKeyboardSelectionDoesNotHideTodaysCompletedTask()=runDesktopComposeUiTest(width=1280,height=900) {
        val now=Instant.now();val clock=FixtureClock().apply {time=time.copy(wallMillis=now.toEpochMilli())}
        val r=DesktopRuntime(Files.createTempDirectory("step-completion-keyboard-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,clock,MobileAIClient())
        try {
            r.initialize()
            r.edit {StateMachine.edit(it,"task",A,ContentActions.save(null,"task","虚构：完成选择","",0,A,now))}.getOrThrow()
            setContent {DesktopUi(r,{}, {},clock=Clock.fixed(now,ZoneOffset.UTC))}
            onNodeWithTag("organize_keyboard").performClick()
            onNodeWithTag("desktop_root").performKeyInput {pressKey(Key.DirectionDown);pressKey(Key.Spacebar)}
            waitUntil(timeoutMillis=5_000){r.engine.state.value.items.getValue("task:$A").completed()}
            waitUntil(timeoutMillis=5_000){onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
            onNodeWithTag("desktop_root").performKeyInput {pressKey(Key.Escape)}
            onNodeWithTag("task_$A").assertIsDisplayed()
            assertEquals(1,onAllNodesWithTag("task_$A").fetchSemanticsNodes().size)
        } finally {r.close()}
    }
    companion object {const val A="00000000-0000-4000-8000-000000008101"}
}
