package com.step.desktop

import androidx.compose.ui.test.*
import com.pare.app.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalTestApi::class)
class DesktopIntelligenceUiTest {
    @Test fun aConfirmedPreferenceCanBeCreatedEditedAndExplicitlyForgotten() = runDesktopComposeUiTest(width=1100,height=900) {
        val runtime=runtime()
        try {
            setContent {DesktopTheme {PreferencesUi(runtime,{})}}
            onNodeWithText("添加偏好").performClick()
            onNode(hasSetTextAction() and hasText("希望如何安排工作？ · 最多500字")).performTextReplacement("虚构偏好：从三分钟开始")
            onNodeWithTag("preference_save").performScrollTo().performClick()
            waitUntil(timeoutMillis=5000) {PreferencesActions.entries(runtime.engine.state.value.items["profile:profile"]).size==1}
            waitForIdle()
            onNodeWithText("修改").performScrollTo().performClick()
            onNode(hasSetTextAction() and hasText("希望如何安排工作？ · 最多500字")).performTextReplacement("虚构偏好：先写一行")
            onNodeWithTag("preference_save").performScrollTo().performClick()
            waitUntil(timeoutMillis=5000) {PreferencesActions.entries(runtime.engine.state.value.items["profile:profile"]).single().str("text")=="虚构偏好：先写一行"}
            waitForIdle()
            onNodeWithText("忘记").performScrollTo().performClick()
            onNodeWithText("取消").performClick()
            assertEquals(1,PreferencesActions.entries(runtime.engine.state.value.items["profile:profile"]).size)
            onNodeWithText("忘记").performClick()
            onNodeWithText("忘记偏好").performClick()
            waitUntil(timeoutMillis=5000) {PreferencesActions.entries(runtime.engine.state.value.items["profile:profile"]).isEmpty()}
        } finally {runtime.close()}
    }

    @Test fun rawHistoryDeletionRequiresConfirmationAndKeepsTheAIService() = runDesktopComposeUiTest(width=1100,height=900) {
        val home=Files.createTempDirectory("step-history-ui-")
        val protector=TestProtector()
        DesktopAIVault(home,protector).apply {
            update(MobileAIConfig("https://fixture.invalid/chat","fixture"),"fictional-key")
            append(MobileAITrace("fictional-trace","fictional-task","2026-11-22T00:00:00Z","虚构原始输入","虚构原始输出",true))
        }
        val runtime=DesktopRuntime(home,DesktopConfig(),{_,_->},protector,NoNetwork,FixtureClock(),MobileAIClient {_,_,_->error("No network in UI fixture")})
        try {
            runtime.initialize()
            setContent {DesktopTheme {AIHistoryUi(runtime,{})}}
            onNodeWithText("查看原始内容").performClick()
            onNodeWithText("虚构原始输入").assertIsDisplayed()
            onNodeWithTag("delete_trace_fictional-trace").performClick()
            onNodeWithText("取消").performClick()
            assertEquals(1,runtime.aiHistory.value.size)
            onNodeWithTag("delete_trace_fictional-trace").performClick()
            onNodeWithText("确认删除").performClick()
            waitUntil(timeoutMillis=5000) {runtime.aiHistory.value.isEmpty()}
            assertTrue(runtime.aiSettings.value.hasKey)
            assertEquals("fixture",runtime.aiSettings.value.config.model)
        } finally {runtime.close()}
    }

    private suspend fun runtime():DesktopRuntime {
        val runtime=DesktopRuntime(Files.createTempDirectory("step-preferences-ui-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient {_,_,_->error("No network in UI fixture")})
        try {runtime.initialize();return runtime} catch(error:Throwable) {runtime.close();throw error}
    }
}
