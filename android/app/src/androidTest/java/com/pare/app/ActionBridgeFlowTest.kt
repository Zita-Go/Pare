package com.pare.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual MainActivity callbacks and serialized SQLite state in QA only. */
@RunWith(AndroidJUnit4::class)
class ActionBridgeFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: SyncRuntime
    private val taskId = "98100000-0000-4000-8000-000000000001"
    private val selectedId = "98100000-0000-4000-8000-000000000002"
    private val date = LocalDate.now(ZoneOffset.UTC)
    private val planId = "$date@UTC"

    @Before fun start() {
        check(context.packageName == "com.pare.app.qa")
        check(context.getSharedPreferences("session-vault", Context.MODE_PRIVATE).edit().clear().commit())
        val blank = PareRepository(context).use { it.update { SyncState() } }
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
        runtime = SyncRuntime.get(context)
        runBlocking { runtime.engine.initialize(); runtime.engine.pause() }
        runtime.presence.clear()
        runtime.engine.edit { fixture(it.device) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        visible("today_overview")
        compose.waitUntil(10_000) { runtime.executionReady.value }
        runtime.execution.reconcile()
        Espresso.closeSoftKeyboard()
    }

    @After fun close() {
        try { if (::scenario.isInitialized) scenario.close() }
        finally { if (::runtime.isInitialized) {
            runtime.presence.clear()
            val blank = runtime.engine.edit { SyncState(device = it.device) }
            if (runtime.executionReady.value) runtime.execution.reconcile()
            ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
            MobileNotificationRuntime(context).refresh(blank, force = true)
        } }
    }

    private fun fixture(device: String): SyncState {
        val now = Instant.now()
        val task = Entity("task", taskId, ContentActions.save(null, "task", "虚构：整理实验材料", "虚构观察记录", 0, taskId, now, nextAction = "打开样例材料"))
        val selected = Entity("task", selectedId, ContentActions.save(null, "task", "虚构：检查模拟结果", "", 1, selectedId, now))
        val profile = Entity("profile", "profile", buildJsonObject { put("content", buildJsonObject {
            put("background", "虚构验收资料"); put("planningTimeZone", "UTC")
            put("preferences", buildJsonObject { put("planningMinutes", 30); put("startingMinutes", 5); put("notes", "") })
            put("confirmedPreferences", JsonArray(emptyList()))
        }) })
        val plan = Entity("day_plan", planId, buildJsonObject {
            put("futureEnvelopeField", "preserve")
            put("content", buildJsonObject {
                put("day_key", date.toString()); put("time_zone", "UTC")
                put("ordered_task_ids", JsonArray(listOf(JsonPrimitive(selectedId)))); put("daily_win_task_id", selectedId)
                put("futurePlanField", "preserve")
                put("task_plans", buildJsonArray { add(buildJsonObject {
                    put("task_id", selectedId); put("scheduled_at", date.atTime(10, 0).toInstant(ZoneOffset.UTC).toString())
                    put("scheduled_minutes", 60); put("schedule_reminder_lead_minutes", 10)
                    put("allocation", buildJsonObject { put("id", "98100000-0000-4000-8000-000000000003"); put("kind", "check"); put("planned_minutes", 8) })
                }) })
            })
        })
        return SyncState(device = device, items = listOf(task, selected, profile, plan).associateBy { it.key })
    }

    private fun read() = PareRepository(context).use { it.read() }
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun visible(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun absent(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() } }
    private fun eventually(predicate: (SyncState) -> Boolean) { compose.waitUntil(10_000) { predicate(read()) } }
    private fun quadrant(state: SyncState) = state.items.getValue("task:$taskId").content()!!.timelineInt("quadrant")
    private fun organize() { node("tab_organize").performClick(); visible("main_list") }

    @Test fun directTodayButtonUsesCurrentDayPreservesPlanAndNoticeExpires() {
        val before = read()
        val original = before.items.getValue("day_plan:$planId")
        val reminders = ReminderPlanner.sources(before, emptyList(), Instant.now())
        // The organize shortcut always means today, even after browsing another day.
        node("timeline_next_day").performClick()
        organize()
        node("main_list").performScrollToNode(hasTestTag("quick_today_$taskId"))
        compose.onNodeWithTag("quick_today_$taskId").assertTextContains("今天").assertContentDescriptionEquals("加入今天").performClick()
        eventually { state ->
            state.items["day_plan:$planId"]?.content()?.get("ordered_task_ids")?.jsonArray?.map { it.jsonPrimitive.content } == listOf(selectedId, taskId)
        }
        visible("workspace_snackbar")
        compose.onNode(hasText("查看") and hasClickAction()).assertIsDisplayed()
        node("editor_surface").assertDoesNotExist()
        val saved = read()
        val updated = saved.items.getValue("day_plan:$planId")
        assertEquals(original.content()!!.filterKeys { it != "ordered_task_ids" }, updated.content()!!.filterKeys { it != "ordered_task_ids" })
        assertEquals(original.payload!!["futureEnvelopeField"], updated.payload!!["futureEnvelopeField"])
        assertEquals(before.items.getValue("task:$taskId"), saved.items.getValue("task:$taskId"))
        assertNull(saved.items["day_plan:${date.plusDays(1)}@UTC"])
        assertEquals(reminders, ReminderPlanner.sources(saved, emptyList(), Instant.now()))
        absent("workspace_snackbar") // Real short snackbar timeout, without interacting to dismiss it.
        compose.onNodeWithTag("quick_today_$taskId").assertTextContains("今天").assertContentDescriptionEquals("已加入今天，查看今天").performClick()
        visible("today_overview")
        node("today_overview_list").performScrollToNode(hasTestTag("overview_task_$taskId"))
        node("overview_effort_$taskId").assertTextEquals("未设投入 · 未定时间")
        assertEquals(updated.payload, read().items.getValue("day_plan:$planId").payload)
        scenario.recreate()
        visible("today_overview")
        assertEquals(updated.payload, read().items.getValue("day_plan:$planId").payload)
        node("today_overview_list").performScrollToNode(hasTestTag("overview_task_$taskId"))
        node("overview_task_$taskId").assertIsDisplayed()
    }

    @Test fun actualDragSnackbarUndoRestoresQuadrantAndPersistsAfterRecreation() {
        organize()
        val tag = "organize_drag_$taskId"
        node("main_list").performScrollToNode(hasTestTag(tag))
        val before = read()
        val bounds = node(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val origin = Offset(bounds.left + bounds.width / 3f, bounds.center.y)
        compose.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            compose.onRoot().performTouchInput { down(origin) }
            pointerDown = true
            compose.mainClock.advanceTimeBy(700)
            compose.onRoot().performTouchInput { advanceEventTime(700); moveBy(Offset(0f, -1f)) }
            compose.mainClock.advanceTimeByFrame()
            val target = node("organize_drop_3").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
            compose.onRoot().performTouchInput { moveTo(target) }
            compose.mainClock.advanceTimeByFrame()
            node("organize_drop_3").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "松手放入此象限"))
            node("organize_drag_preview").assertExists()
            assertEquals(before.items.getValue("task:$taskId"), read().items.getValue("task:$taskId"))
            screenshot("action-bridge-drag-hover.png")
            compose.onRoot().performTouchInput { up() }
            pointerDown = false
            compose.mainClock.advanceTimeByFrame()
        } finally {
            if (pointerDown) compose.onRoot().performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
        eventually { quadrant(it) == 3 }
        absent("organize_drop_targets")
        visible("workspace_snackbar")
        node("editor_surface").assertDoesNotExist()
        assertEquals(before.items.getValue("day_plan:$planId"), read().items.getValue("day_plan:$planId"))
        compose.onNode(hasText("撤销") and hasClickAction()).assertIsDisplayed().performClick()
        eventually { quadrant(it) == 0 }
        val restored = read()
        assertEquals(before.items.getValue("task:$taskId").payload, restored.items.getValue("task:$taskId").payload)
        assertEquals(before.items.getValue("day_plan:$planId"), restored.items.getValue("day_plan:$planId"))
        scenario.recreate()
        visible("tab_organize")
        organize()
        node("main_list").performScrollToNode(hasTestTag(tag))
        node(tag).assertIsDisplayed()
        val persisted = read()
        assertEquals(0, quadrant(persisted))
        assertEquals(before.items.getValue("task:$taskId").payload, persisted.items.getValue("task:$taskId").payload)
        assertEquals(before.items.getValue("day_plan:$planId"), persisted.items.getValue("day_plan:$planId"))
    }

    private fun screenshot(name: String) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val folder = File(directory).apply { mkdirs() }
        File(folder, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
