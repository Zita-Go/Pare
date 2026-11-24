package com.step.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.pare.app.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalTestApi::class)
class DesktopSettingsClarityTest {
    @Test fun embeddedDesktopControlsKeepShortcutDraftAcrossTabsAndGuardClosing() = runDesktopComposeUiTest(width=1280,height=900) {
        val home=Files.createTempDirectory("step-settings-clarity-")
        val originalAppearance=DesktopAppearance.dark.value
        try {
            DesktopPlatform(home){}.use {platform->
                DesktopRuntime(home,DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient()).use {runtime->
                    runtime.initialize()
                    var open by mutableStateOf(true)
                    setContent {DesktopTheme {
                        CompositionLocalProvider(LocalDesktopOptionsContent provides {draft->DesktopOptionsContent(platform,draft)}) {
                            if(open)SettingsUi(runtime,{open=false},{})
                        }
                    }}
                    onNodeWithText("外观与快捷键").performClick()
                    onAllNodesWithTag("desktop_dialog").assertCountEquals(1)
                    onNodeWithText("浅色").performScrollTo().performClick()
                    assertFalse(platform.darkAppearance.value)
                    onNodeWithTag("desktop_widget_visible").performScrollTo().performClick()
                    assertTrue(platform.widgetVisible.value)
                    assertTrue(Files.readString(home.resolve("window.properties")).contains("widgetVisible=true"))
                    onNodeWithTag("desktop_shortcut").performScrollTo().performTextReplacement("Ctrl+Alt+F9")
                    onNodeWithText("账号与同步").performScrollTo().performClick()
                    assertTrue(DesktopDraftStatus.hasUnsavedEdits.value)
                    onNodeWithText("外观与快捷键").performClick()
                    onNodeWithTag("desktop_shortcut").performScrollTo().assertTextContains("Ctrl+Alt+F9")
                    onNodeWithText("关闭",useUnmergedTree=true).performClick()
                    onNodeWithText("放弃未保存的修改？").assertExists()
                    onNodeWithText("继续编辑").performClick()
                    onNodeWithTag("desktop_shortcut").performScrollTo().assertTextContains("Ctrl+Alt+F9")
                    onNodeWithText("关闭",useUnmergedTree=true).performClick()
                    onNodeWithText("放弃修改").performClick()
                    waitForIdle()
                    assertFalse(open)
                    assertFalse(DesktopDraftStatus.hasUnsavedEdits.value)
                    assertEquals("Ctrl+Shift+Space",platform.shortcut.value)
                }
            }
        } finally {DesktopAppearance.dark.value=originalAppearance}
    }

    @Test fun settingsKeepsSyncFailureVisibleAndUsesTheSamePhaseAsSidebar() = runDesktopComposeUiTest(width=1280,height=900) {
        val home=Files.createTempDirectory("step-settings-sync-error-")
        DesktopRuntime(home,DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient()).use {runtime->
            runtime.initialize()
            runtime.engine.status.value="连接超时 · 请重试，本机变更已保留"
            setContent {DesktopTheme {SettingsUi(runtime,{}, {})}}
            onNodeWithTag("settings_sync_status").assertTextContains("同步未完成")
            onNodeWithText("连接超时 · 请重试，本机变更已保留",substring=true).assertExists()
        }
    }
}
