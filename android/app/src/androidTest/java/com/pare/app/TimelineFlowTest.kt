package com.pare.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Real MainActivity and pointer gestures; all persisted content belongs to the isolated QA app. */
@RunWith(AndroidJUnit4::class)
class TimelineFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: SyncRuntime
    private lateinit var date: LocalDate
    private lateinit var planId: String
    private val first = "00000000-0000-4000-8000-000000000051"
    private val second = "00000000-0000-4000-8000-000000000052"
    private val scheduled = "00000000-0000-4000-8000-000000000053"

    @Before fun launchFictionalOfflineCalendar() {
        check(context.packageName == "com.pare.app.qa") { "Timeline tests must never access the production application" }
        check(context.getSharedPreferences("session-vault", Context.MODE_PRIVATE).edit().clear().commit())
        val blank = PareRepository(context).use { it.update { SyncState() } }
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
        MobileNotificationRuntime(context).refresh(blank, force = true)
        runtime = SyncRuntime.get(context)
        runBlocking { runtime.engine.initialize(); runtime.engine.pause() }
        val now = Instant.now()
        date = now.atZone(ZoneOffset.UTC).toLocalDate()
        planId = "$date@UTC"
        runtime.engine.edit { current -> fixture(current.device, now) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        visible("today_mode_timeline"); compose.onNodeWithTag("today_mode_timeline").performClick()
        visible("today_timeline")
        compose.waitUntil(10_000) { runtime.executionReady.value }
        runtime.execution.reconcile()
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        assertTrue(read().owner == null && !read().enabled && read().pending == null)
        assertEquals("UTC", TimelineDay.from(read(), date).zone.id)
    }

    @After fun removeOnlyQAFixtures() {
        compose.mainClock.autoAdvance = true
        try {
            if (::scenario.isInitialized) scenario.close()
        } finally {
            if (::runtime.isInitialized) {
                val blank = runtime.engine.edit { SyncState(device = it.device) }
                if (runtime.executionReady.value) runtime.execution.reconcile()
                ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
                MobileNotificationRuntime(context).refresh(blank, force = true)
            }
        }
    }

    private fun fixture(device: String, now: Instant): SyncState {
        val tasks = listOf(
            first to "虚构：收集周末路线",
            second to "虚构：列出三项准备材料",
            scheduled to "虚构：画一页植物笔记"
        ).map { (id, title) ->
            Entity("task", id, ContentActions.save(null, "task", title, "仅用于隔离界面验收", 1, id, now,
                estimatedMinutes = 90, nextAction = "打开示例笔记，先写一个标题"))
        }
        val plan = Entity("day_plan", planId, buildJsonObject {
            put("content", buildJsonObject {
                put("day_key", date.toString()); put("time_zone", "UTC")
                put("ordered_task_ids", JsonArray(listOf(first, second, scheduled).map(::JsonPrimitive)))
                put("task_plans", buildJsonArray { add(buildJsonObject {
                    put("task_id", scheduled); put("scheduled_at", at(10, 0).toString()); put("scheduled_minutes", 60)
                    put("allocation", buildJsonObject {
                        put("id", "00000000-0000-4000-8000-000000000054")
                        put("kind", "personal"); put("planned_minutes", 20)
                    })
                }) })
            })
        })
        val profile = Entity("profile", "profile", buildJsonObject {
            put("content", buildJsonObject {
                put("background", "隔离 QA 的虚构资料")
                put("planningTimeZone", "UTC")
                put("preferences", buildJsonObject {
                    put("planningMinutes", 60); put("startingMinutes", 5); put("notes", "")
                })
                put("confirmedPreferences", JsonArray(emptyList()))
            })
        })
        return SyncState(device = device, items = (tasks + plan + profile).associateBy { it.key }).also { state ->
            state.items.values.forEach { PayloadValidation.validate(it.kind, it.id, requireNotNull(it.payload)) }
        }
    }

    private fun read() = PareRepository(context).use { it.read() }
    private fun at(hour: Int, minute: Int) = date.atTime(hour, minute).toInstant(ZoneOffset.UTC)
    private fun planContent() = read().items.getValue("day_plan:$planId").content()!!
    private fun entry(id: String) = (planContent()["task_plans"] as? JsonArray).orEmpty()
        .map { it.jsonObject }.firstOrNull { it["task_id"]?.jsonPrimitive?.content == id }
    private fun start(id: String) = entry(id)?.get("scheduled_at")?.jsonPrimitive?.contentOrNull?.let(Instant::parse)
    private fun minutes(id: String) = entry(id)?.get("scheduled_minutes")?.jsonPrimitive?.intOrNull
    private fun order() = planContent().getValue("ordered_task_ids").jsonArray.map { it.jsonPrimitive.content }
    private fun visible(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun gone(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    /** Scroll UI semantics only; derive gesture distances from an actual hour row's rendered pixels. */
    private fun scrollHourToTop(hour: Int): Float {
        Espresso.closeSoftKeyboard()
        node("timeline_viewport").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
        compose.waitForIdle()
        val hourPixels = node("timeline_create_0").fetchSemanticsNode().boundsInRoot.height
        assertTrue("An hour must have a measurable rendered height", hourPixels > 0f)
        node("timeline_viewport").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, hourPixels * hour) }
        compose.waitForIdle()
        node("timeline_create_$hour").assertIsDisplayed()
        val actualHour = node("timeline_create_$hour").fetchSemanticsNode().boundsInRoot.height
        assertEquals(hourPixels, actualHour, 1f)
        return actualHour / 60f
    }

    /** Hold a real pointer across frames so assertions and the preview screenshot happen before release. */
    private fun drag(from: Offset, delta: Offset, duringDrag: () -> Unit) {
        compose.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            compose.onRoot().performTouchInput { down(from) }
            pointerDown = true
            compose.mainClock.advanceTimeBy(700)
            compose.onRoot().performTouchInput { advanceEventTime(700); moveBy(delta) }
            compose.mainClock.advanceTimeByFrame()
            node("timeline_drag_preview").assertIsDisplayed()
            duringDrag()
            compose.onRoot().performTouchInput { up() }
            pointerDown = false
            compose.mainClock.advanceTimeByFrame()
        } finally {
            if (pointerDown) compose.onRoot().performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    private fun undoAndWait(restored: () -> Boolean) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("撤销") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("撤销") and hasClickAction()).performClick()
        compose.waitUntil(10_000, restored)
    }

    private fun screenshot(name: String, dialog: Boolean = false, popup: Boolean = false) {
        compose.waitForIdle()
        val directory = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(context.getExternalFilesDir(null), "ui-acceptance")).apply { mkdirs() }
        val target = when { dialog -> compose.onNode(isDialog()); popup -> compose.onNode(isPopup()); else -> compose.onRoot() }
        File(directory, name).outputStream().use {
            assertTrue(target.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun emptyHourCreatesScheduledTaskAndTaskMenuContainsThreeActions() {
        scrollHourToTop(9)
        node("timeline_create_9").performTouchInput { click(center) }
        visible("timeline_schedule_dialog")
        node("timeline_schedule_title").performTextReplacement("虚构：整理一张植物观察卡")
        node("timeline_schedule_time").performScrollTo().performTextReplacement("09:15")
        node("timeline_schedule_duration").performScrollTo().performTextReplacement("45")
        Espresso.closeSoftKeyboard()
        screenshot("53-task-time-form.png", dialog = true)
        node("timeline_schedule_save").assertIsEnabled().performClick()
        gone("timeline_schedule_dialog")
        val task = read().items.values.single { it.kind == "task" && it.title() == "虚构：整理一张植物观察卡" }
        assertEquals(at(9, 15), start(task.id))
        assertEquals(45, minutes(task.id))
        assertTrue(task.id in order())
        assertEquals(task.group, read().items.getValue("day_plan:$planId").group)
        assertNull("Creating a time block must not invent a total task estimate", task.content()!!["estimatedMinutes"])
        scrollHourToTop(9)
        screenshot("51-timeline-populated.png")
        val wireDirectory=File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")))
        File(wireDirectory,"56-timeline-wire.json").writeText(JsonObject(read().items.filterValues {it.kind in setOf("task","day_plan") && !it.deleted}.mapValues {it.value.payload!!}).toString())

        node("tab_organize").performClick()
        node("main_list").performScrollToNode(hasTestTag("task_$first"))
        node("more_$first").performClick()
        visible("schedule_$first")
        node("schedule_$first").assertIsDisplayed()
        node("run_$first").assertIsDisplayed()
        node("details_$first").assertIsDisplayed()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).assertCountEquals(3)
        screenshot("54-three-action-menu.png", popup = true)
        node("details_$first").performClick()
        visible("editor_text")
        node("editor_text").assertTextContains("虚构：收集周末路线")
    }

    @Test fun movingAndResizingPreviewBeforeReleaseAndUndoPreservesTaskEstimate() {
        val originalTask = read().items.getValue("task:$scheduled")
        val originalPlan = planContent()
        val pxPerMinute = scrollHourToTop(9)
        val move = node("timeline_move_$scheduled").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        drag(move.center, Offset(0f, 30f * pxPerMinute)) {
            assertEquals("Dragging must not save intermediate schedules", originalPlan, planContent())
            screenshot("52-timeline-during-drag.png")
        }
        compose.waitUntil(10_000) { start(scheduled) == at(10, 30) }
        assertEquals(60, minutes(scheduled))
        undoAndWait { planContent() == originalPlan }

        val resizeScale = scrollHourToTop(9)
        val resize = node("timeline_resize_$scheduled").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        drag(resize.center, Offset(0f, 30f * resizeScale)) {
            assertEquals("Resizing must stay a preview until release", originalPlan, planContent())
        }
        compose.waitUntil(10_000) { minutes(scheduled) == 90 }
        assertEquals(at(10, 0), start(scheduled))
        assertEquals(originalTask, read().items.getValue("task:$scheduled"))
        assertEquals(20, entry(scheduled)!!.getValue("allocation").jsonObject.getValue("planned_minutes").jsonPrimitive.int)
        undoAndWait { planContent() == originalPlan }
        // Short steps get shorter when dragging up; they must not jump to a fifteen-minute minimum.
        scrollHourToTop(9)
        node("timeline_move_$scheduled").performClick()
        node("timeline_edit_$scheduled").performClick()
        visible("timeline_schedule_duration")
        node("timeline_schedule_duration").performTextReplacement("5")
        Espresso.closeSoftKeyboard()
        node("timeline_schedule_save").performClick();gone("timeline_schedule_dialog")
        val shortScale=scrollHourToTop(9)
        val shortHandle=node("timeline_resize_$scheduled").fetchSemanticsNode().boundsInRoot
        drag(shortHandle.center,Offset(0f,-15f*shortScale)) {assertEquals(5,minutes(scheduled))}
        compose.waitUntil(10_000){minutes(scheduled)==1}
        assertEquals(at(10,0),start(scheduled))

    }

    @Test fun unscheduledTasksReorderAndDropOntoAxisOnlyWhenReleased() {
        node("timeline_unscheduled_toggle").performClick()
        visible("timeline_unscheduled_$first")
        val originalPlan = planContent()
        val firstRow = node("timeline_unscheduled_$first").fetchSemanticsNode().boundsInRoot
        val secondRow = node("timeline_unscheduled_$second").fetchSemanticsNode().boundsInRoot
        // Stay away from each row's trailing "定时" button.
        val origin = Offset(firstRow.left + firstRow.width / 3f, firstRow.center.y)
        drag(origin, Offset(0f, secondRow.center.y - firstRow.center.y)) {
            assertEquals("Reorder previews must not write the plan", originalPlan, planContent())
        }
        compose.waitUntil(10_000) { order() == listOf(second, first, scheduled) }
        assertNull(start(first))
        undoAndWait { planContent() == originalPlan }

        scrollHourToTop(9)
        val row = node("timeline_unscheduled_$second").fetchSemanticsNode().boundsInRoot
        val hour = node("timeline_create_9").fetchSemanticsNode().boundsInRoot
        val source = Offset(row.left + row.width / 3f, row.center.y)
        val destination = hour.center
        drag(source, destination - source) {
            assertEquals("Dropping into the axis must save only after release", originalPlan, planContent())
            assertNull(start(second))
        }
        compose.waitUntil(10_000) { start(second) == at(9, 30) }
        assertEquals(30, minutes(second))
        assertEquals(listOf(first, second, scheduled), order())
        undoAndWait { planContent() == originalPlan }
        node("timeline_unscheduled_$second").assertExists()
    }

    @Test fun titleOnlyCaptureOnFutureDateStaysInThatDaysExpandedUnscheduledList() {
        val todayBefore = planContent()
        val futurePlanId = "${date.plusDays(1)}@UTC"
        node("timeline_next_day").performClick()
        node("timeline_today").assertIsDisplayed()
        node("add_item").performClick()
        visible("editor_text")
        node("editor_text").assertIsFocused().performTextReplacement("虚构：明天只读一段资料")
        node("editor_next_action").assertDoesNotExist()
        node("editor_note").assertDoesNotExist()
        Espresso.closeSoftKeyboard()
        node("editor_save").assertIsDisplayed().performClick()
        gone("editor_text")

        val state = read()
        val task = state.items.values.single { it.kind == "task" && it.title() == "虚构：明天只读一段资料" }
        val futurePlan = state.items.getValue("day_plan:$futurePlanId")
        val futureContent = futurePlan.content()!!
        assertEquals(listOf(task.id), futureContent.getValue("ordered_task_ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Quick capture on a future date must preserve today's plan", todayBefore, planContent())
        assertFalse(task.id in order())
        assertTrue((futureContent["task_plans"] as? JsonArray).orEmpty().none {
            it.jsonObject["task_id"]?.jsonPrimitive?.content == task.id && it.jsonObject["scheduled_at"] != null
        })
        assertEquals("New task and selected-day membership must save together", task.group, futurePlan.group)
        visible("timeline_unscheduled_${task.id}")
        node("timeline_unscheduled_list").assertIsDisplayed()
        node("timeline_unscheduled_${task.id}").assertIsDisplayed()
    }

    @Test fun partiallyClippedUnscheduledRowDropsAtTheActualPointerTime() {
        val extras = (55..57).map { value ->
            val id = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
            Entity("task", id, ContentActions.save(null, "task", "虚构：待安排的第${value - 52}项小事", "", 1, id, Instant.now()))
        }
        runtime.engine.edit { state ->
            val oldPlan = state.items.getValue("day_plan:$planId")
            val content = oldPlan.content()!!.toMutableMap().apply {
                put("ordered_task_ids", JsonArray(getValue("ordered_task_ids").jsonArray + extras.map { JsonPrimitive(it.id) }))
            }
            val updated = oldPlan.copy(payload = JsonObject(oldPlan.payload!!.toMutableMap().apply { put("content", JsonObject(content)) }))
            (extras + updated).forEach { PayloadValidation.validate(it.kind, it.id, requireNotNull(it.payload)) }
            state.copy(items = state.items + extras.associateBy { it.key } + (updated.key to updated))
        }
        node("timeline_unscheduled_toggle").performClick()
        visible("timeline_unscheduled_${extras.last().id}")
        scrollHourToTop(9)
        val fullHeight = node("timeline_unscheduled_$first").fetchSemanticsNode().layoutInfo.height.toFloat()
        node("timeline_unscheduled_list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, fullHeight * .55f) }
        compose.waitForIdle()
        val clippedNode = node("timeline_unscheduled_$first").fetchSemanticsNode()
        val clipped = clippedNode.boundsInRoot
        assertTrue("The source row must still have a visible area to press", clipped.height > 0f)
        assertTrue("This regression needs a genuinely clipped row", clipped.height < clippedNode.layoutInfo.height.toFloat())
        val originalPlan = planContent()
        val hour = node("timeline_create_9").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val source = Offset(clipped.left + clipped.width / 3f, clipped.center.y)
        val destination = hour.center
        drag(source, destination - source) {
            assertEquals("A clipped-row drag must remain a preview before release", originalPlan, planContent())
            screenshot("55-clipped-row-drag.png")
        }
        compose.waitUntil(10_000) { start(first) != null }
        assertEquals("Use the pointer's actual position, not the clipped row's shifted origin", at(9, 30), start(first))
        assertEquals(30, minutes(first))
        undoAndWait { planContent() == originalPlan }
    }

    @Test fun dailyWinLoadAndReminderPermissionEntryRemainReachable() {
        runtime.engine.edit { state ->
            val oldPlan = state.items.getValue("day_plan:$planId")
            val content = oldPlan.content()!!.toMutableMap().apply {
                put("daily_win_task_id", JsonPrimitive(first))
                put("task_plans", JsonArray(getValue("task_plans").jsonArray + buildJsonObject {
                    put("task_id", second)
                    put("allocation", buildJsonObject {
                        put("id", "00000000-0000-4000-8000-000000000058")
                        put("kind", "check"); put("planned_minutes", 5)
                    })
                }))
            }
            val updated = oldPlan.copy(payload = JsonObject(oldPlan.payload!!.toMutableMap().apply { put("content", JsonObject(content)) }))
            PayloadValidation.validate(updated.kind, updated.id, requireNotNull(updated.payload))
            state.copy(items = state.items + (updated.key to updated))
        }
        visible("timeline_daily_win")
        node("timeline_daily_win").assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals("当天重点：虚构：收集周末路线，查看详情")
        node("timeline_planned_load").assertIsDisplayed()
            .assertContentDescriptionEquals("计划投入：个人 60 分钟，检查 5 分钟，1 项未设投入")
        val originalPlan = planContent()
        val notificationsAllowed = MobileNotificationRuntime(context).notificationsAllowed()
        node("timeline_unscheduled_toggle").performClick()
        node("timeline_schedule_$first").performClick()
        visible("timeline_schedule_dialog")
        node("timeline_schedule_reminder").performScrollTo().assertIsOff().performClick().assertIsOn()
        visible("timeline_schedule_notification_settings")
        node("timeline_schedule_notification_settings").performScrollTo().assertIsDisplayed()
        val insideSettings = hasAnyAncestor(hasTestTag("timeline_schedule_notification_settings"))
        if (!notificationsAllowed) {
            compose.onNode(hasText("开启手机通知") and hasClickAction() and insideSettings)
                .performScrollTo().assertIsDisplayed().assertIsEnabled()
        }
        compose.onNode(hasText("提醒设置") and hasClickAction() and insideSettings)
            .performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasText("系统通知设置") and hasClickAction() and insideSettings)
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
        // Inspect the permission route without launching system settings or saving a reminder.
        node("timeline_schedule_cancel").assertIsDisplayed().performClick()
        gone("timeline_schedule_dialog")
        assertEquals(originalPlan, planContent())
        assertEquals(notificationsAllowed, MobileNotificationRuntime(context).notificationsAllowed())
    }
}
