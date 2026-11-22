package com.pare.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Permission presentation in isolation: no OS permission request, persistence or account. */
class NotificationDisclosureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun disabledNotificationsKeepEnableVisibleAndSettingsFoldedUntilRequested() {
        var enables = 0
        var settings = 0
        compose.setContent {
            PareTheme {
                MobileNotificationControls(allowed = false, exact = false,
                    onEnable = { enables++ }, onSystemSettings = { settings++ }, onExactSettings = null)
            }
        }
        compose.onNodeWithTag("notification_status").assertIsDisplayed().assertTextContains("手机通知未开启", substring = true)
        compose.onNodeWithTag("notification_system_settings").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, enables); assertEquals(0, settings) }
        compose.onNodeWithTag("notification_enable").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, enables); assertEquals(0, settings) }
        compose.onNodeWithTag("notification_disclosure").performClick()
        compose.onNodeWithTag("notification_system_settings").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, enables); assertEquals(1, settings) }
        compose.onNodeWithTag("notification_disclosure").performClick()
        compose.onNodeWithTag("notification_system_settings").assertDoesNotExist()
        compose.onNodeWithTag("notification_enable").assertIsDisplayed()
    }

    @Test fun approximateDeliveryAndFailuresStayVisibleWithSettingsCollapsed() {
        var systemActions = 0
        compose.setContent {
            PareTheme {
                MobileNotificationControls(allowed = true, exact = false,
                    onEnable = { systemActions++ }, onSystemSettings = { systemActions++ },
                    onExactSettings = { systemActions++ }, error = "虚构：提醒暂未恢复")
            }
        }
        compose.onNodeWithTag("notification_status").assertTextContains("大约时间", substring = true)
        compose.onNodeWithText("虚构：提醒暂未恢复").assertIsDisplayed()
        compose.onNodeWithTag("notification_enable").assertDoesNotExist()
        compose.onNodeWithText("允许更准时的提醒").assertDoesNotExist()
        compose.onNodeWithTag("notification_disclosure").performClick()
        compose.onNodeWithText("允许更准时的提醒").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, systemActions) }
    }
}
