package com.pare.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Fixed logical viewport, real PareScreen and system pixel density; no screenshot resizing. */
class UnifiedInterfaceTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = UnifiedInterfaceFixture()
    private val models = ViewModelStore()

    @Before fun seed() = fixture.start(compose)
    @After fun clean() {
        try { models.clear() } finally { fixture.clean() }
    }

    @Test fun todayShowsSixTasksWithinCompactHeaderInBothThemes() {
        val model = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory.getInstance(
            fixture.context.applicationContext as Application))[PareViewModel::class.java]
        val dark = mutableStateOf(false)
        var actualFontScale = 0f
        compose.setContent {
            val systemDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(systemDensity, 1f)) {
                actualFontScale = LocalDensity.current.fontScale
                PareTheme(darkTheme = dark.value) {
                    Box(Modifier.size(360.dp, 800.dp).testTag("unified_viewport")) { PareScreen(model) }
                }
            }
        }
        compose.waitForTag("overview_task_${fixture.ids.first()}")
        val metrics = buildJsonObject {
            put("viewport_width_dp", 360)
            put("viewport_height_dp", 800)
            put("font_scale", actualFontScale)
            listOf(false, true).forEach { isDark ->
                compose.runOnIdle { dark.value = isDark }
                compose.waitForIdle()
                val viewport = compose.onNodeWithTag("unified_viewport").fetchSemanticsNode().boundsInRoot
                val scale = viewport.width / 360f
                val theme = if (isDark) "dark" else "light"
                // Retain the actual failing layout before asserting its density target.
                compose.onNodeWithTag("unified_viewport").saveUnifiedScreenshot("unified-today-$theme.png")
                File(unifiedOutput(), "unified-today-$theme-layout.json").writeText(buildJsonObject {
                    put("theme", theme)
                    put("font_scale", actualFontScale)
                    put("system_timezone", ZoneId.systemDefault().id)
                    put("planning_timezone", TimelineDay.from(fixture.runtime.engine.state.value,
                        LocalDate.now(ZoneId.systemDefault())).zone.id)
                    listOf("page_topbar", "today_mode_list", "today_overview_summary",
                        "overview_task_${fixture.ids.first()}").forEach { tag ->
                        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                        put(tag, buildJsonObject {
                            put("left_dp", (bounds.left - viewport.left) / scale)
                            put("top_dp", (bounds.top - viewport.top) / scale)
                            put("right_dp", (bounds.right - viewport.left) / scale)
                            put("bottom_dp", (bounds.bottom - viewport.top) / scale)
                            put("width_dp", bounds.width / scale)
                            put("height_dp", bounds.height / scale)
                        })
                    }
                }.toString())
                assertEquals("The fixture must really occupy 360×800dp", 800f, viewport.height / scale, 1f)
                assertEquals(1f, actualFontScale, 0f)
                compose.onNodeWithTag("today_mode_list").assertIsSelected()
                compose.onNodeWithTag("sync_attention").assertDoesNotExist()
                compose.onNodeWithTag("execution_running").assertDoesNotExist()
                val first = compose.onNodeWithTag("overview_task_${fixture.ids.first()}").fetchSemanticsNode().boundsInRoot
                val firstTop = (first.top - viewport.top) / scale
                assertTrue("First task starts at ${firstTop}dp; compact header target is ≤180dp", firstTop <= 180f)
                val navigationTop = compose.onNodeWithTag("tab_today").fetchSemanticsNode().boundsInRoot.top
                fixture.ids.forEach { id ->
                    val row = compose.onNodeWithTag("overview_task_$id").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    compose.onNodeWithTag("overview_complete_$id").assertIsDisplayed()
                    assertTrue("Task $id must fit above navigation without scrolling", row.bottom <= navigationTop)
                }
                put(if (isDark) "dark_first_task_top_dp" else "light_first_task_top_dp", firstTop)
            }
            put("visible_task_count", fixture.ids.size)
        }
        File(unifiedOutput(), "unified-today-metrics.json").writeText(metrics.toString())
        fixture.assertOffline()
    }

    @Test fun narrowNonTodayToolbarKeepsDateTargetsAndModeLabelsReachableAtBothFontScales() {
        val now = Instant.now()
        val selectedDate = now.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(2)
        val day = TimelineDay.from(fixture.runtime.engine.state.value, selectedDate)
        val fontScale = mutableStateOf(1f)
        var selectedByClick: LocalDate? = null
        compose.setContent {
            val systemDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(systemDensity, fontScale.value)) {
                PareTheme {
                    Box(Modifier.size(336.dp, 200.dp).testTag("unified_toolbar_viewport")) {
                        TodayViewControls(day, now, timeline = false, onTimeline = {}, onSelectDate = { selectedByClick = it })
                    }
                }
            }
        }
        listOf(1f, 1.6f).forEach { textScale ->
            compose.runOnIdle { fontScale.value = textScale }
            compose.waitForIdle()
            val viewport = compose.onNodeWithTag("unified_toolbar_viewport").fetchSemanticsNode().boundsInRoot
            val density = viewport.width / 336f
            val tags = listOf("today_mode_list", "today_mode_timeline", "timeline_today", "timeline_previous_day", "timeline_next_day")
            val bounds = tags.associateWith { tag ->
                val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
                node.fetchSemanticsNode().boundsInRoot.also { rect ->
                    assertTrue("$tag must remain at least 48dp wide at fontScale=$textScale", rect.width / density >= 47.9f)
                    assertTrue("$tag must remain at least 48dp tall at fontScale=$textScale", rect.height / density >= 47.9f)
                    assertTrue("$tag must fit inside the 336dp parent at fontScale=$textScale",
                        rect.left >= viewport.left && rect.right <= viewport.right &&
                            rect.top >= viewport.top && rect.bottom <= viewport.bottom)
                }
            }
            tags.forEachIndexed { index, tag ->
                tags.drop(index + 1).forEach { other ->
                    val a = bounds.getValue(tag)
                    val b = bounds.getValue(other)
                    assertFalse("$tag and $other must not overlap at fontScale=$textScale",
                        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
                }
            }
            listOf("清单" to "today_mode_list", "时间轴" to "today_mode_timeline").forEach { (label, tag) ->
                val labelNode = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                val labelBounds = labelNode.fetchSemanticsNode().boundsInRoot
                val control = bounds.getValue(tag)
                assertTrue("$label must fit inside its control at fontScale=$textScale",
                    labelBounds.left >= control.left && labelBounds.right <= control.right &&
                        labelBounds.top >= control.top && labelBounds.bottom <= control.bottom)
                labelNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { readLayout ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(readLayout(layouts))
                    assertTrue(layouts.isNotEmpty())
                    layouts.forEach { layout ->
                        assertEquals(textScale, layout.layoutInput.density.fontScale, 0.001f)
                        assertEquals("$label must remain on one line at fontScale=$textScale", 1, layout.lineCount)
                        assertFalse(layout.multiParagraph.didExceedMaxLines)
                        // Paragraph constraint width can include blank space beyond Text's tight size.
                        // Check the rendered line bounds, as in FocusPresetLayoutTest.
                        assertTrue(layout.getLineLeft(0) >= -1f)
                        assertTrue("$label must fit horizontally at fontScale=$textScale",
                            layout.getLineRight(0) <= layout.size.width + 1f)
                        assertTrue(layout.getLineTop(0) >= -1f)
                        assertTrue("$label must fit vertically at fontScale=$textScale",
                            layout.getLineBottom(0) <= layout.size.height + 1f)
                    }
                }
            }
            // Capture the callback without changing the fixture's date, so both scales test the same non-today layout.
            compose.onNodeWithTag("timeline_next_day").performClick()
            assertEquals(selectedDate.plusDays(1), selectedByClick)
            compose.onNodeWithTag("timeline_previous_day").performClick()
            assertEquals(selectedDate.minusDays(1), selectedByClick)
            compose.onNodeWithTag("timeline_today").performClick()
            assertEquals(now.atZone(day.zone).toLocalDate(), selectedByClick)
        }
    }
}

/** Real Activity windows verify that shared running controls stay above a page with an unsaved draft. */
class UnifiedInterfaceNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fixture = UnifiedInterfaceFixture()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun launch() {
        fixture.start(compose)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForTag("today_overview")
        Espresso.closeSoftKeyboard()
    }
    @After fun clean() {
        try { if (::scenario.isInitialized) scenario.close() } finally { fixture.clean() }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun executionButton(label: String) = compose.onNode(hasText(label) and hasClickAction() and
        hasAnyAncestor(isDialog() and hasAnyDescendant(hasTestTag("execution_primary"))))
    private fun waitForExecutionClosed() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("execution_primary").fetchSemanticsNodes().isEmpty()
    }

    @Test fun runningHeaderPausesAndResumesAboveIdeaDraftWithoutReplacingItsRun() {
        node("today_mode_list").assertIsSelected()
        node("overview_execution_${fixture.ids.first()}").performClick()
        compose.waitForTag("execution_primary")
        node("focus_preset_5").assertIsSelected()
        node("execution_primary").performClick()
        waitForExecutionClosed()
        compose.waitUntil(10_000) { fixture.runtime.execution.state.value.focus?.taskId == fixture.ids.first() }
        val original = requireNotNull(fixture.runtime.execution.state.value.focus)
        assertEquals(5, original.targetMinutes)
        assertFalse(original.paused)
        node("execution_running").assertIsDisplayed()
        node("tab_ideas").performClick()
        compose.waitForTag("idea_${fixture.ideaId}")
        node("idea_${fixture.ideaId}").performClick()
        compose.waitForTag("idea_workspace")
        node("idea_add_entry").performScrollTo().performClick()
        compose.waitForTag("idea_entry_text")
        val draft = "虚构草稿：先试读一段，再记下一个问题。"
        node("idea_entry_text").performTextReplacement(draft)
        Espresso.closeSoftKeyboard()
        node("page_execution_status").assertIsDisplayed().performClick()
        compose.waitForTag("execution_primary")
        // Clicking a real overlay action also verifies that the reading window does not cover it.
        executionButton("暂停").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { fixture.runtime.execution.state.value.focus?.paused == true }
        executionButton("关闭").performClick()
        waitForExecutionClosed()
        node("idea_entry_text").assertTextContains(draft)
        node("idea_entry_save").assertExists()
        node("page_execution_status").assert(hasAnyDescendant(hasText("已暂停"))).performClick()
        compose.waitForTag("execution_primary")
        executionButton("继续").performScrollTo().performClick()
        compose.waitUntil(10_000) { fixture.runtime.execution.state.value.focus?.paused == false }
        executionButton("关闭").performClick()
        waitForExecutionClosed()
        node("idea_entry_text").assertTextContains(draft)
        node("page_execution_status").assert(hasAnyDescendant(hasText("专注中")))
        val resumed = requireNotNull(fixture.runtime.execution.state.value.focus)
        assertEquals("Returning through the header must preserve the same timer", original.id, resumed.id)
        assertEquals(original.deadlineGeneration + 1, resumed.deadlineGeneration)
        assertTrue(fixture.runtime.execution.state.value.external.isEmpty())
        assertFalse("Opening execution must never save the idea draft", fixture.runtime.engine.state.value.items.values.any {
            it.payload.toString().contains(draft)
        })
        fixture.assertOffline()
    }

    @Test fun compactDateHeaderOpensPickerAndCancelPreservesSelectedDateAndMode() {
        node("today_mode_list").assertIsSelected()
        val before = compose.onNodeWithTag("today_choose_date").fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        assertTrue("Date header must expose its actual date label", before.size >= 2)
        node("today_choose_date").assertHasClickAction().performClick()
        compose.waitForTag("today_date_picker")
        node("today_date_confirm").assertIsDisplayed()
        compose.onNode(hasText("取消") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("today_date_picker").fetchSemanticsNodes().isEmpty() }
        node("today_mode_list").assertIsSelected()
        val after = compose.onNodeWithTag("today_choose_date").fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        assertEquals(before, after)
        fixture.ids.forEach { node("overview_task_$it").assertIsDisplayed() }
        fixture.assertOffline()
    }
}

private class UnifiedInterfaceFixture {
    val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    lateinit var runtime: SyncRuntime
    val ids = (1..6).map { "98000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
    val ideaId = "98000000-0000-4000-8000-000000000007"
    private var previousHints: Boolean? = null

    fun start(compose: ComposeTestRule) {
        check(context.packageName == "com.pare.app.qa") { "UI acceptance must only touch the isolated QA package" }
        check(context.getSharedPreferences("session-vault", Context.MODE_PRIVATE).edit().clear().commit())
        previousHints = MobileHintPreferences(context).enabled()
        MobileHintPreferences(context).setEnabled(false)
        val blank = PareRepository(context).use { it.update { SyncState() } }
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
        runtime = SyncRuntime.get(context)
        runBlocking { runtime.engine.initialize(); runtime.engine.pause() }
        // The singleton may survive another test. Clear its in-memory task/run sources before reseeding.
        runtime.engine.edit { SyncState(device = it.device) }
        compose.waitUntil(10_000) { runtime.executionReady.value }
        runtime.execution.reconcile()
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now(zone)
        val now = Instant.now()
        runtime.engine.edit { state ->
            var next = state
            listOf("虚构晨读", "整理示例", "准备纸笔", "午间散步", "写一句话", "晚间回看").forEachIndexed { index, title ->
                next = StateMachine.edit(next, "task", ids[index], ContentActions.save(null, "task", title,
                    "", 1, ids[index], now, estimatedMinutes = 5, nextAction = "先打开示例笔记"))
            }
            next = StateMachine.edit(next, "idea", ideaId, ContentActions.save(null, "idea", "虚构阅读角", "", 1, ideaId, now))
            next = StateMachine.edit(next, "profile", "profile", buildJsonObject { put("content", buildJsonObject {
                put("background", ""); put("planningTimeZone", zone.id)
                put("preferences", buildJsonObject { put("planningMinutes", 30); put("startingMinutes", 5); put("notes", "") })
                put("confirmedPreferences", JsonArray(emptyList()))
            }) })
            StateMachine.edit(next, "day_plan", "$date@${zone.id}", buildJsonObject { put("content", buildJsonObject {
                put("day_key", date.toString()); put("time_zone", zone.id)
                put("daily_win_task_id", ids.first())
                put("ordered_task_ids", JsonArray(ids.map(::JsonPrimitive)))
                put("task_plans", JsonArray(emptyList()))
            }) })
        }
        assertOffline()
    }

    fun assertOffline() {
        val state = runtime.engine.state.value
        assertNull(state.owner)
        assertFalse(state.enabled)
        assertNull(state.pending)
    }

    fun clean() {
        if (::runtime.isInitialized) {
            val blank = runtime.engine.edit { SyncState(device = it.device) }
            if (runtime.executionReady.value) runtime.execution.reconcile()
            ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
            MobileNotificationRuntime(context).refresh(blank, force = true)
        }
        previousHints?.let { MobileHintPreferences(context).setEnabled(it) }
    }
}

private fun ComposeTestRule.waitForTag(tag: String) = waitUntil(10_000) {
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
}

private fun unifiedOutput(): File = File(requireNotNull(
    InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
    "Pass additionalTestOutputDir to retain native-resolution UI acceptance screenshots"
}).apply { mkdirs() }

private fun SemanticsNodeInteraction.saveUnifiedScreenshot(name: String) {
    File(unifiedOutput(), name).outputStream().use {
        assertTrue(captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
    }
}
