@file:Suppress("DEPRECATION")

package com.pare.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.SizeF
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Places one fictional QA widget through the real launcher. QA uninstall removes it after the run. */
@RunWith(AndroidJUnit4::class)
class HomeWidgetPlacementTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val manager get() = AppWidgetManager.getInstance(context)
    private val provider get() = ComponentName(context, HomeWidgetProvider::class.java)
    private val taskId = "99000000-0000-4000-8000-000000000001"
    private val taskTitle = "虚构：整理桌面观察"
    private val nextStep = "写下一条虚构观察"
    private lateinit var runtime: SyncRuntime
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var launchIntent: Intent
    private lateinit var launcherPackage: String
    private var originalAutomationFlags: Int? = null
    private var confirmationAccepted = false
    private var addClickCount = 0
    private var lastPlacementDiagnostic = "No QA widget body observed"

    @Before fun prepareOnlyFictionalOfflineQA() {
        check(context.packageName == "com.pare.app.qa") { "Placement acceptance must never access production data" }
        assertTrue("Use a clean QA installation; this test never deletes an existing widget", manager.getAppWidgetIds(provider).isEmpty())
        check(context.getSharedPreferences("session-vault", Context.MODE_PRIVATE).edit().clear().commit())
        MobileAIVault(context).clear()
        val blank = PareRepository(context).use { it.update { SyncState() } }
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
        runtime = SyncRuntime.get(context)
        runBlocking { runtime.engine.initialize(); runtime.engine.pause() }
        runtime.presence.clear()
        runtime.engine.edit { state ->
            val now = Instant.now()
            val task = Entity("task", taskId, ContentActions.save(null, "task", taskTitle, "隔离的桌面放置验收", 1, taskId, now, nextAction = nextStep))
            val profile = Entity("profile", "profile", buildJsonObject { put("content", buildJsonObject { put("planningTimeZone", "UTC") }) })
            val initial = SyncState(device = state.device, items = listOf(task, profile).associateBy { it.key })
            val planId = ContentActions.planId(initial, now)
            val plan = Entity("day_plan", planId, buildJsonObject { put("content", buildJsonObject {
                put("day_key", planId.substringBefore('@')); put("time_zone", "UTC")
                put("ordered_task_ids", JsonArray(listOf(JsonPrimitive(taskId)))); put("daily_win_task_id", taskId)
                put("task_plans", JsonArray(emptyList()))
            }) })
            initial.copy(items = initial.items + (plan.key to plan))
        }
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
        launcherPackage = requireNotNull(context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName) { "No default HOME activity" }
        assertNotEquals("A HOME chooser is not a configured launcher", "android", launcherPackage)
        val info = requireNotNull(automation.serviceInfo)
        originalAutomationFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
    }

    @After fun clearOnlyQAFixture() {
        try {
            // A failed pre-acceptance attempt may leave the native confirmation open.
            if (!confirmationAccepted && ::launcherPackage.isInitialized && launcherConfirmationPresent()) {
                automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
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

    @Test fun realLauncherPlacementUsesCompactHostBoundsAndCompleteActions() {
        assumeTrue("Default HOME $launcherPackage does not support pinning", manager.isRequestPinAppWidgetSupported)
        val info = requireNotNull(manager.installedProviders.firstOrNull { it.provider == provider })
        if (Build.VERSION.SDK_INT >= 31) {
            assertEquals("Requested columns", 2, info.targetCellWidth)
            assertEquals("Requested rows", 2, info.targetCellHeight)
        }
        val before = manager.getAppWidgetIds(provider).toSet()
        compose.onNodeWithTag("more_workspace").assertIsDisplayed().performClick()
        visible("more_widget")
        compose.onNodeWithTag("more_widget").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        // All following UI operations use the native accessibility surface of the launcher.
        awaitCondition("No visible Pare confirmation with a real Add control") { launcherConfirmationPresent() }
        clickNativeAddExactlyOnce()
        awaitCondition("The real Add action did not bind a new QA widget") { manager.getAppWidgetIds(provider).toSet().minus(before).isNotEmpty() }
        val added = manager.getAppWidgetIds(provider).toSet().minus(before)
        assertEquals("One real Add click must create exactly one QA widget", 1, added.size)
        assertEquals(1, addClickCount)
        val id = added.single()
        assertEquals(provider, manager.getAppWidgetInfo(id)?.provider)
        var placement: NativePlacement? = nativePlacement()
        // Do not send HOME again if Add already revealed a newly created workspace page.
        if (placement == null) assertTrue("HOME must reveal the actual launcher workspace", automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        var stableBounds: Rect? = null
        var stableSince = SystemClock.uptimeMillis()
        awaitCondition("Bound QA widget never became visible on HOME") {
            placement = nativePlacement()
            val bounds = placement?.background?.bounds
            if (bounds != stableBounds) {
                stableBounds = bounds?.let(::Rect)
                stableSince = SystemClock.uptimeMillis()
            }
            placement != null && SystemClock.uptimeMillis() - stableSince >= 300
        }
        val actual = requireNotNull(placement)
        val options = manager.getAppWidgetOptions(id)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val density = context.resources.displayMetrics.density
        val padding = AppWidgetHostView.getDefaultPaddingForWidget(context, provider, null)
        val widthDp = actual.background.bounds.width() / density
        val heightDp = actual.background.bounds.height() / density
        val widthTolerance = (padding.left + padding.right) / density + 4f
        val heightTolerance = (padding.top + padding.bottom) / density + 4f
        // Save the measured evidence before geometry assertions so failures remain inspectable.
        saveEvidence(id, actual, minWidth, minHeight, maxWidth, maxHeight)
        assertTrue("Launcher must publish a positive size range", minWidth > 0 && minHeight > 0 && maxWidth >= minWidth && maxHeight >= minHeight)
        assertTrue("Actual width $widthDp dp must fit launcher range $minWidth..$maxWidth", widthDp in (minWidth - widthTolerance)..(maxWidth + widthTolerance))
        assertTrue("Actual height $heightDp dp must fit launcher range $minHeight..$maxHeight", heightDp in (minHeight - heightTolerance)..(maxHeight + heightTolerance))
        actual.gridSpan?.let { (columns, rows) ->
            assertEquals("Native launcher column span", 2, columns)
            assertEquals("Native launcher row span", 2, rows)
        }
        assertTrue("Widget background must be inside the real host", actual.host.bounds.contains(actual.background.bounds))
        val body = actual.body
        val capture = actual.capture
        val focus = actual.focus
        assertTrue("Full fictional task remains accessible", body.description.contains(taskTitle))
        assertTrue("Full minimum next step remains accessible", body.description.contains(nextStep))
        for (node in listOf(body, capture, focus)) {
            assertTrue("${node.id} must be clickable", node.clickable)
            assertTrue("${node.id} inside widget background", actual.background.bounds.contains(node.bounds))
            assertTrue("${node.id} touch width", node.bounds.width() + 1 >= 48 * density)
            assertTrue("${node.id} touch height", node.bounds.height() + 1 >= 48 * density)
        }
        assertTrue("Body must not overlap actions", body.bounds.bottom <= minOf(capture.bounds.top, focus.bounds.top))
        assertFalse("Action touch targets must not overlap", Rect.intersects(capture.bounds, focus.bounds))
        assertTrue("Capture icon must announce its purpose", capture.description.contains("快速收集"))
        assertEquals("Compact focus action", "开始", focus.text)
        assertFalse("Ordinary widget must not show duplicate generic encouragement", actual.nodes.any { it.id.endsWith(":id/widget_status") })
    }

    private fun visible(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do {
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("$message. $lastPlacementDiagnostic")
    }

    private fun <T> withLauncherRoot(block: (AccessibilityNodeInfo) -> T?): T? {
        val windows = automation.windows
        try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || (!window.isActive && !window.isFocused)) continue
                val root = window.root ?: continue
                try {
                    if (root.packageName?.toString() == launcherPackage) block(root)?.let { return it }
                } finally { root.recycle() }
            }
        } finally { windows.forEach { it.recycle() } }
        return null
    }

    private fun findNode(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { findNode(child, predicate)?.let { return it } } finally { child.recycle() }
        }
        return null
    }

    private fun isAddLabel(node: AccessibilityNodeInfo): Boolean = listOfNotNull(node.text, node.contentDescription).any { value ->
        value.toString().trim().lowercase() in setOf("add", "add widget", "add to home screen", "add automatically", "pin", "添加", "添加到主屏幕", "添加到桌面", "自动添加", "加入", "確定", "确定", "追加")
    }

    private fun launcherConfirmationPresent(): Boolean = withLauncherRoot { root ->
        val identity = findNode(root) { it.isVisibleToUser && listOfNotNull(it.text, it.contentDescription).any { text -> text.contains("Pare", ignoreCase = true) } }
        val add = findNode(root) { it.isVisibleToUser && it.isEnabled && isAddLabel(it) }
        try { if (identity != null && add != null) true else null } finally { identity?.recycle(); add?.recycle() }
    } == true

    private fun clickNativeAddExactlyOnce() {
        check(addClickCount == 0)
        val clicked = withLauncherRoot { root ->
            val label = findNode(root) { it.isVisibleToUser && it.isEnabled && isAddLabel(it) } ?: return@withLauncherRoot null
            var current: AccessibilityNodeInfo? = label
            try {
                while (current != null) {
                    if (current.isClickable && current.isEnabled) {
                        addClickCount++
                        confirmationAccepted = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return@withLauncherRoot confirmationAccepted
                    }
                    val parent = current.parent
                    current.recycle()
                    current = parent
                }
                false
            } finally { current?.recycle() }
        }
        assertEquals("Native Add must accept exactly one accessibility click", true, clicked)
    }

    private data class NativeNode(val id: String, val className: String, val text: String, val description: String, val bounds: Rect, val clickable: Boolean)
    private data class NativePlacement(val host: NativeNode, val background: NativeNode, val body: NativeNode, val capture: NativeNode, val focus: NativeNode,
        val gridSpan: Pair<Int, Int>?, val gridEvidence: String, val nodes: List<NativeNode>)
    private fun snapshot(node: AccessibilityNodeInfo) = NativeNode(node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(),
        node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), Rect().also(node::getBoundsInScreen), node.isClickable)

    private fun nativePlacement(): NativePlacement? = withLauncherRoot { root ->
        val body = findNode(root) { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/widget_body") == true && it.contentDescription?.contains(taskTitle) == true }
            ?: return@withLauncherRoot null
        var ancestor: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(body)
        var background: NativeNode? = null
        var host: AccessibilityNodeInfo? = null
        val ancestors = mutableListOf<String>()
        try {
            while (ancestor != null) {
                val value = snapshot(ancestor)
                ancestors += "${value.className} ${value.id} ${value.bounds}"
                if (value.id == "android:id/background") background = value
                if (value.className.contains("AppWidgetHostView")) { host = AccessibilityNodeInfo.obtain(ancestor); break }
                val parent = ancestor.parent
                ancestor.recycle()
                ancestor = parent
            }
            lastPlacementDiagnostic = "QA ancestors: ${ancestors.joinToString(" -> ")}"
            val widgetHost = host ?: return@withLauncherRoot null
            val rootBounds = background ?: return@withLauncherRoot null
            val capture = findNode(widgetHost) { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/widget_capture") == true }
            val focus = findNode(widgetHost) { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/widget_focus") == true }
            try {
                if (capture == null || focus == null) return@withLauncherRoot null
                val nodes = mutableListOf<NativeNode>()
                fun collect(node: AccessibilityNodeInfo) {
                    if (node.isVisibleToUser) nodes += snapshot(node)
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        try { collect(child) } finally { child.recycle() }
                    }
                }
                collect(widgetHost)
                val collection = widgetHost.collectionItemInfo
                var span = collection?.let { it.columnSpan to it.rowSpan }
                var evidence = if (span != null) "Native host collectionItemInfo" else "Host options and measured bounds; launcher exposes no collection cell spans"
                if (span == null) {
                    val parent = widgetHost.parent
                    try {
                        if (parent != null) for (index in 0 until parent.childCount) {
                            val sibling = parent.getChild(index) ?: continue
                            try {
                                val icon = snapshot(sibling)
                                if (sibling.isVisibleToUser && sibling.isLongClickable && (icon.className.endsWith("TextView") || icon.className.contains("BubbleTextView")) && !icon.bounds.isEmpty) {
                                    val columns = snapshot(widgetHost).bounds.width().toFloat() / icon.bounds.width()
                                    val rows = snapshot(widgetHost).bounds.height().toFloat() / icon.bounds.height()
                                    if (columns >= 1f && rows >= 1f && abs(columns - columns.roundToInt()) < 0.3f && abs(rows - rows.roundToInt()) < 0.3f) {
                                        span = columns.roundToInt() to rows.roundToInt()
                                        evidence = "Same native grid icon cell ${icon.bounds.width()}x${icon.bounds.height()} px; host spans $columns columns x $rows rows"
                                        break
                                    }
                                }
                            } finally { sibling.recycle() }
                        }
                    } finally { parent?.recycle() }
                }
                NativePlacement(snapshot(widgetHost), rootBounds, snapshot(body), snapshot(capture), snapshot(focus), span, evidence, nodes)
            } finally { capture?.recycle(); focus?.recycle() }
        } finally { ancestor?.recycle(); host?.recycle(); body.recycle() }
    }

    private fun saveEvidence(id: Int, actual: NativePlacement, minWidth: Int, minHeight: Int, maxWidth: Int, maxHeight: Int) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        check(context.packageName == "com.pare.app.qa" && runtime.engine.state.value.owner == null && runtime.engine.session.value == null)
        val folder = File(directory).apply { mkdirs() }
        val density = context.resources.displayMetrics.density
        fun rect(value: Rect) = buildJsonObject {
            put("left", value.left); put("top", value.top); put("right", value.right); put("bottom", value.bottom)
            put("widthDp", value.width() / density); put("heightDp", value.height() / density)
        }
        val options = manager.getAppWidgetOptions(id)
        val sizes = if (Build.VERSION.SDK_INT >= 31) options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty() else emptyList()
        val defaultPadding = AppWidgetHostView.getDefaultPaddingForWidget(context, provider, null)
        val metadata = buildJsonObject {
            put("package", context.packageName); put("launcher", launcherPackage); put("widgetId", id); put("addClickCount", addClickCount)
            put("targetColumns", 2); put("targetRows", 2); put("density", density)
            put("minWidthDp", minWidth); put("minHeightDp", minHeight); put("maxWidthDp", maxWidth); put("maxHeightDp", maxHeight)
            put("publishedSizesDp", buildJsonArray { sizes.forEach { add(buildJsonObject { put("width", it.width); put("height", it.height) }) } })
            put("hostBounds", rect(actual.host.bounds)); put("widgetBounds", rect(actual.background.bounds))
            put("defaultWidgetPaddingPx", buildJsonObject {
                put("left", defaultPadding.left); put("top", defaultPadding.top)
                put("right", defaultPadding.right); put("bottom", defaultPadding.bottom)
            })
            put("nativeGridEvidence", actual.gridEvidence)
            actual.gridSpan?.let { put("observedColumns", it.first); put("observedRows", it.second) }
            put("qaWidgetNodes", buildJsonArray { actual.nodes.forEach { node -> add(buildJsonObject {
                put("id", node.id); put("class", node.className); put("text", node.text); put("description", node.description)
                put("clickable", node.clickable); put("bounds", rect(node.bounds))
            }) } })
        }
        File(folder, "widget-placement-native.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), metadata))
        val screenshot = requireNotNull(automation.takeScreenshot()) { "Native screenshot unavailable" }
        try {
            // Only the known fictional QA widget is persisted, never icons or other home content.
            val bounds = Rect(actual.background.bounds)
            assertTrue(bounds.intersect(0, 0, screenshot.width, screenshot.height))
            val crop = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
            try { File(folder, "widget-placement-native.png").outputStream().use { assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { if (crop !== screenshot) crop.recycle() }
        } finally { screenshot.recycle() }
    }
}
