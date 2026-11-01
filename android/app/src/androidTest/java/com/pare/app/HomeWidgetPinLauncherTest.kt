@file:Suppress("DEPRECATION")

package com.pare.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Characterizes the real launcher boundary; an API true without visible confirmation must fail. */
@RunWith(AndroidJUnit4::class)
class HomeWidgetPinLauncherTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val manager get() = AppWidgetManager.getInstance(context)
    private val provider get() = ComponentName(context, HomeWidgetProvider::class.java)
    private lateinit var runtime: SyncRuntime
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var launchIntent: Intent
    private lateinit var launcherPackage: String
    private var originalAutomationFlags: Int? = null
    private var pinRequested = false

    @Before fun prepareFictionalOfflineQA() {
        check(context.packageName == "com.pare.app.qa") { "Launcher acceptance must never access the production installation" }
        check(context.getSharedPreferences("session-vault", Context.MODE_PRIVATE).edit().clear().commit())
        MobileAIVault(context).clear()
        val blank = PareRepository(context).use { it.update { SyncState() } }
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
        runtime = SyncRuntime.get(context)
        runBlocking { runtime.engine.initialize(); runtime.engine.pause() }
        runtime.presence.clear()
        runtime.engine.edit { state ->
            val taskId = "98000000-0000-4000-8000-000000000001"
            val task = Entity("task", taskId, ContentActions.save(null, "task", "虚构：观察桌面组件", "隔离的桌面确认验收", 1, taskId, Instant.now(), nextAction = "写下一条虚构观察"))
            SyncState(device = state.device, items = mapOf(task.key to task))
        }
        // Preserve MAIN/LAUNCHER identity throughout ActivityScenario lifecycle bookkeeping.
        launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(context, MainActivity::class.java)
        scenario = ActivityScenario.launch(launchIntent)
        visible("more_workspace")
        compose.waitUntil(10_000) { runtime.executionReady.value }
        runtime.execution.reconcile()
        Espresso.closeSoftKeyboard()
        assertNull(runtime.engine.state.value.owner)
        assertFalse(runtime.engine.state.value.enabled)
        assertNull(runtime.engine.session.value)
        assertTrue(MobileAIVault(context).load().apiKey.isEmpty())

        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        launcherPackage = requireNotNull(context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName) {
            "No default HOME activity is available for launcher acceptance"
        }
        assertNotEquals("A HOME chooser is not a configured default launcher", "android", launcherPackage)
        val info = requireNotNull(automation.serviceInfo)
        originalAutomationFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
    }

    @After fun cancelConfirmationAndClearOnlyQA() {
        try {
            if (pinRequested && ::launcherPackage.isInitialized) cancelNativeConfirmation()
            if (::scenario.isInitialized && scenario.state != Lifecycle.State.DESTROYED) {
                scenario.onActivity { it.intent = Intent(launchIntent) }
                scenario.close()
            }
        } finally {
            originalAutomationFlags?.let { flags ->
                automation.serviceInfo = requireNotNull(automation.serviceInfo).apply { this.flags = flags }
            }
            if (::runtime.isInitialized) {
                runtime.presence.clear()
                val blank = runtime.engine.edit { SyncState(device = it.device) }
                if (runtime.executionReady.value) runtime.execution.reconcile()
                ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
                MobileNotificationRuntime(context).refresh(blank, force = true)
                MobileAIVault(context).clear()
            }
        }
    }

    @Test fun addWidgetEntryShowsActualDefaultLauncherConfirmation() {
        assumeTrue("Default HOME $launcherPackage does not support pinning; manual guidance is covered separately", manager.isRequestPinAppWidgetSupported)
        assertTrue("QA widget provider must be registered", manager.installedProviders.any { it.provider == provider })
        val before = manager.getAppWidgetIds(provider).toSet()
        openAddWidget()

        // Do not call Compose/Espresso while a different package owns the modal window.
        val confirmation = awaitNativeConfirmation()
        assertEquals(launcherPackage, confirmation.packageName)
        assertTrue(confirmation.identifiesPare)
        assertTrue(confirmation.hasConfirmationControl)
        assertTrue("Default widget must request 2×2 launcher cells: ${confirmation.labels}",confirmation.labels.any {
            it.replace(" ","").contains("2×2") || it.contains("2 by 2",ignoreCase=true)
        })
        optionalNativeScreenshot(confirmation)
        cancelNativeConfirmation()

        visible("widget_pin_status")
        compose.onNodeWithTag("widget_pin_status").assertIsDisplayed().assertTextContains("尚未检测到新增组件",substring=true)
        assertEquals("The test cancels confirmation and must not attach a widget", before, manager.getAppWidgetIds(provider).toSet())
        scenario.recreate()
        visible("widget_pin_status")
        compose.onNodeWithTag("widget_pin_status").assertTextContains("尚未检测到新增组件",substring=true)
        assertNull("Restoring the page must not send a new pin request",nativeConfirmation())
    }

    @Test fun unsupportedDefaultLauncherGetsVisibleManualGuidance() {
        assumeFalse("Default HOME $launcherPackage supports pinning; the native confirmation test covers this environment", manager.isRequestPinAppWidgetSupported)
        openAddWidget()
        visible("widget_pin_status")
        compose.onNodeWithTag("widget_pin_status").assertIsDisplayed().assertTextContains("不支持", substring = true)
        compose.onNodeWithTag("widget_pin_manual").assertIsDisplayed().assertTextContains("长按", substring = true)
    }

    private fun visible(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openAddWidget() {
        compose.onNodeWithTag("more_workspace").assertIsDisplayed().performClick()
        visible("more_widget")
        pinRequested = true
        compose.onNodeWithTag("more_widget").performScrollTo().assertIsDisplayed().performClick()
        // Flush Compose's controlled test clock so the menu closes and the request effect runs
        // before observing a different process's native window.
        compose.waitForIdle()
    }

    private data class NativeConfirmation(val packageName: String, val labels: List<String>, val controls: List<String>, val contentBounds: Rect) {
        val identifiesPare get() = labels.any { it.contains("Pare", ignoreCase = true) }
        val hasConfirmationControl get() = controls.any { label ->
            listOf("add", "pin", "confirm", "添加", "加入", "确定", "確認", "追加").any { label.contains(it, ignoreCase = true) }
        }
    }

    private fun nativeConfirmation(): NativeConfirmation? {
        var result: NativeConfirmation? = null
        automation.windows.forEach { window ->
            try {
                if (result == null && window.type == AccessibilityWindowInfo.TYPE_APPLICATION && (window.isActive || window.isFocused)) {
                    val root = window.root
                    if (root != null) try {
                        if (root.packageName?.toString() == launcherPackage) {
                            val labels = mutableListOf<String>()
                            val controls = mutableListOf<String>()
                            val bounds = Rect()
                            collectVisibleLabels(root, labels, controls, bounds)
                            val candidate = NativeConfirmation(launcherPackage, labels, controls, bounds)
                            if (candidate.identifiesPare && candidate.hasConfirmationControl) result = candidate
                        }
                    } finally { root.recycle() }
                }
            } finally { window.recycle() }
        }
        return result
    }

    private fun collectVisibleLabels(node: AccessibilityNodeInfo, labels: MutableList<String>, controls: MutableList<String>, bounds: Rect, clickableAncestor: Boolean = false) {
        val clickable = clickableAncestor || node.isClickable
        if (node.isVisibleToUser) {
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).filter { it.isNotBlank() }.forEach {
                labels += it
                if (clickable && node.isEnabled) controls += it
                val nodeBounds = Rect().also(node::getBoundsInScreen)
                if (!nodeBounds.isEmpty) bounds.union(nodeBounds)
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { collectVisibleLabels(child, labels, controls, bounds, clickable) } finally { child.recycle() }
        }
    }

    private fun awaitNativeConfirmation(): NativeConfirmation {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            nativeConfirmation()?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        val directory=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        if(directory!=null) {
            val folder=File(directory).apply {mkdirs()}
            val diagnostics=mutableListOf<String>()
            automation.windows.forEach {window->
                val root=window.root
                try {
                    val pkg=root?.packageName?.toString().orEmpty()
                    diagnostics+="package=$pkg active=${window.isActive} focused=${window.isFocused} type=${window.type}"
                    if(root!=null && pkg in setOf(context.packageName,launcherPackage)) {
                        val labels=mutableListOf<String>();val controls=mutableListOf<String>();val bounds=Rect()
                        collectVisibleLabels(root,labels,controls,bounds)
                        diagnostics+="labels=$labels controls=$controls"
                    }
                }finally{root?.recycle();window.recycle()}
            }
            File(folder,"widget-pin-window-diagnostic.txt").writeText(diagnostics.joinToString("\n"))
            val root=automation.rootInActiveWindow
            try {
                if(root?.packageName?.toString() in setOf(context.packageName,launcherPackage)) {
                    automation.takeScreenshot()?.let {bitmap->try {File(folder,"widget-pin-failure-native.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}}
                }
            }finally{root?.recycle()}
        }
        throw AssertionError("No visible Pare widget confirmation from default HOME $launcherPackage within 10 seconds; API support alone is not success")
    }

    private fun cancelNativeConfirmation() {
        if (nativeConfirmation() == null) return
        assertTrue("Back must dismiss the launcher confirmation without accepting it", automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (nativeConfirmation() != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertNull("Launcher confirmation must close after Back", nativeConfirmation())
    }

    private fun optionalNativeScreenshot(confirmation: NativeConfirmation) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        check(context.packageName == "com.pare.app.qa" && runtime.engine.state.value.owner == null && runtime.engine.session.value == null)
        val screenshot = requireNotNull(automation.takeScreenshot()) { "Native screenshot was unavailable" }
        try {
            // Save only this confirmation's visible content, not unrelated launcher/system windows.
            val bounds = Rect(confirmation.contentBounds)
            assertTrue(bounds.intersect(0, 0, screenshot.width, screenshot.height))
            val cropped = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
            try {
                File(File(directory).apply { mkdirs() }, "widget-pin-launcher-confirmation-native.png").outputStream().use {
                    assertTrue(cropped.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { if (cropped !== screenshot) cropped.recycle() }
        } finally { screenshot.recycle() }
    }
}
