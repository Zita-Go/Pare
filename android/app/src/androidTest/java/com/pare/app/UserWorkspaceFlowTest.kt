package com.pare.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
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
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual MainActivity navigation, exclusively in the separate QA app with fictional offline data. */
@RunWith(AndroidJUnit4::class)
class UserWorkspaceFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var runtime: SyncRuntime
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val taskId = "94000000-0000-0000-0000-000000000001"
    private val secondTaskId = "94000000-0000-0000-0000-000000000002"
    private val ideaId = "94000000-0000-0000-0000-000000000003"
    private val goalId = "94000000-0000-0000-0000-000000000004"

    @Before fun launchIsolatedOfflineWorkspace() {
        check(context.packageName == "com.pare.app.qa") { "Workspace acceptance must not access a production installation" }
        check(context.getSharedPreferences("session-vault", Context.MODE_PRIVATE).edit().clear().commit())
        val blank = PareRepository(context).use { it.update { SyncState() } }
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
        runtime = SyncRuntime.get(context)
        runBlocking { runtime.engine.initialize(); runtime.engine.pause() }
        runtime.presence.clear()
        runtime.engine.edit { fixture(it.device) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        visible("today_mode_timeline"); compose.onNodeWithTag("today_mode_timeline").performClick()
        visible("today_timeline")
        compose.waitUntil(10_000) { runtime.executionReady.value }
        runtime.execution.reconcile()
        Espresso.closeSoftKeyboard()
        assertNull(read().owner); assertFalse(read().enabled); assertNull(runtime.engine.session.value)
    }

    @After fun clearOnlyQAWorkspace() {
        try { if (::scenario.isInitialized) scenario.close() }
        finally {
            if (::runtime.isInitialized) {
                runtime.presence.clear()
                val blank = runtime.engine.edit { SyncState(device = it.device) }
                if (runtime.executionReady.value) runtime.execution.reconcile()
                ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
                MobileNotificationRuntime(context).refresh(blank, force = true)
            }
        }
    }

    private fun fixture(device: String): SyncState {
        val now = Instant.now()
        val task = Entity("task", taskId, ContentActions.save(null, "task", "虚构：制作观察小册", "琥珀主题的观察背景", 1, taskId, now, nextAction = "打开样例笔记，写下第一种植物"))
        val second = Entity("task", secondTaskId, ContentActions.save(null, "task", "虚构：整理航线材料", "用于目标关联验收", 1, secondTaskId, now))
        val idea = Entity("idea", ideaId, ContentActions.save(null, "idea", "虚构：城市自然观察", "仅用于隔离界面验收", 1, ideaId, now))
        val profile = Entity("profile", "profile", buildJsonObject { put("content", buildJsonObject {
            put("background", "虚构的周末观察者"); put("planningTimeZone", "UTC")
            put("preferences", buildJsonObject { put("planningMinutes", 30); put("startingMinutes", 5); put("notes", "虚构规划节奏") })
            put("confirmedPreferences", JsonArray(emptyList()))
        }) })
        val initial = SyncState(device = device, items = listOf(task, second, idea, profile).associateBy { it.key })
        return GoalsActions.save(initial, GoalDraft(id = goalId, title = "虚构：完成自然观察手册", motivation = "把观察连成可回看的记录", taskIds = listOf(taskId)), GoalBaseline.from(null), now)
    }

    private fun read() = PareRepository(context).use { it.read() }
    private fun visible(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun gone(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() } }
    private fun eventually(check: (SyncState) -> Boolean) { compose.waitUntil(10_000) { check(read()) } }
    private fun more(destination: String) {
        Espresso.closeSoftKeyboard(); visible("more_workspace")
        compose.onNodeWithTag("more_workspace").assertIsDisplayed().performClick()
        visible("more_$destination"); compose.onNodeWithTag("more_$destination").performClick()
    }
    private fun scope(name: String) {
        // The search keyboard was already closed before the task menu opened. Wait for
        // that popup to leave instead of asking Espresso to choose an unfocused base root.
        compose.waitUntil(5_000) { compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("library_scope").performClick()
        visible("library_scope_$name"); compose.onNodeWithTag("library_scope_$name").performScrollTo().performClick()
    }
    private fun taskMenu() {
        compose.onNodeWithTag("library_results").performScrollToNode(hasTestTag("library_menu_$taskId"))
        compose.onNodeWithTag("library_menu_$taskId").performClick()
    }
    private fun closeWorkspace(tag: String) {
        compose.onNode((hasText("关闭") or hasContentDescription("关闭")) and hasAnyAncestor(hasTestTag(tag))).performClick()
        gone(tag)
    }
    private fun optionalScreenshot(name: String, rootTag: String) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        compose.waitForIdle()
        val folder = File(directory).apply { mkdirs() }
        File(folder, name).outputStream().use { output ->
            assertTrue(compose.onNodeWithTag(rootTag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    @Test fun librarySearchArchiveAndDurableTrashAreReachableFromMore() {
        more("library"); visible("library_search")
        compose.onNodeWithTag("library_search").performTextReplacement("琥珀")
        Espresso.closeSoftKeyboard(); visible("library_item_$taskId")
        compose.onNodeWithTag("library_item_$secondTaskId").assertDoesNotExist()
        taskMenu(); compose.onNodeWithTag("library_archive").performClick()
        eventually { LibrarySearch.archived(it.items.getValue("task:$taskId")) }
        scope("archived"); visible("library_item_$taskId")
        optionalScreenshot("workspace-archive.png", "library_dialog")
        taskMenu(); compose.onNodeWithTag("library_restore_archive").performClick()
        eventually { !LibrarySearch.archived(it.items.getValue("task:$taskId")) }
        scope("all"); visible("library_item_$taskId")
        taskMenu(); compose.onNodeWithTag("library_delete").performClick()
        visible("library_delete_confirm"); compose.onNodeWithTag("library_delete_confirm").performClick()
        eventually { it.items.getValue("task:$taskId").deleted && it.localTrash.any { entry -> entry.before.id == taskId } }
        val entry = read().localTrash.single()
        scope("trash"); visible("trash_item_${entry.id}")
        scenario.recreate(); visible("library_trash")
        compose.onNodeWithTag("library_search").assertTextContains("琥珀")
        compose.onNodeWithTag("library_trash").performScrollToNode(hasTestTag("trash_restore_original_${entry.id}"))
        optionalScreenshot("workspace-trash.png", "library_dialog")
        compose.onNodeWithTag("trash_restore_original_${entry.id}").performClick()
        eventually { !it.items.getValue("task:$taskId").deleted && it.localTrash.single().restoredKey == "task:$taskId" }
        assertEquals("琥珀主题的观察背景", read().items.getValue("task:$taskId").content()?.get("note")?.jsonPrimitive?.content)
        assertEquals("打开样例笔记，写下第一种植物", read().items.getValue("task:$taskId").content()?.get("nextAction")?.jsonPrimitive?.content)
        compose.onNodeWithTag("library_close").performClick(); gone("library_dialog")
    }

    @Test fun goalLibraryLinkOpensTheSelectedGoalAndSavesAnExistingTaskAssociation() {
        more("goals"); visible("goals_workspace")
        compose.onNodeWithTag("goal_$goalId").performScrollTo().assertExists()
        closeWorkspace("goals_workspace")
        more("library"); visible("library_scope"); scope("goals")
        compose.onNodeWithTag("library_item_$goalId").performClick()
        visible("goal_title")
        compose.onNodeWithTag("goal_title").assertTextContains("虚构：完成自然观察手册")
        compose.onNodeWithTag("goal_unlink_$taskId").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("goal_link_tasks").performScrollTo().performClick()
        compose.onNodeWithTag("goal_task_search").performScrollTo().performTextReplacement("航线")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("goal_link_$secondTaskId").performScrollTo().performClick()
        compose.onNodeWithTag("goal_save").performClick()
        eventually { state -> state.items.getValue("goal:$goalId").content()?.get("taskIDs")?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() == setOf(taskId, secondTaskId) }
        gone("goal_save"); optionalScreenshot("workspace-goals.png", "goals_workspace")
        closeWorkspace("goals_workspace")
        compose.onNodeWithTag("library_close").performClick(); gone("library_dialog")
        more("goals"); visible("goal_$goalId")
        compose.onNodeWithTag("goal_$goalId").performScrollTo().performClick(); visible("goal_title")
        compose.onNodeWithTag("goal_unlink_$secondTaskId").performScrollTo().assertIsDisplayed()
        assertFalse(read().items.getValue("task:$taskId").deleted)
        assertFalse(read().items.getValue("task:$secondTaskId").deleted)
    }

    @Test fun preferencesSupportScopedSaveRetainedDraftAndExplicitForget() {
        more("preferences"); visible("preferences_workspace")
        compose.onNodeWithTag("preference_add").performScrollTo().performClick(); visible("preference_text")
        compose.onNodeWithTag("preference_text").performTextReplacement("虚构偏好：每次先观察五分钟")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("preference_scope").performScrollTo().performClick()
        compose.onNodeWithTag("preference_scope_$ideaId").performScrollTo().performClick()
        compose.onNodeWithTag("preferences_save").performClick()
        eventually { PreferencesActions.entries(it.items["profile:profile"]).size == 1 }
        gone("preferences_save")
        val entryId = PreferencesActions.entries(read().items["profile:profile"]).single().stringValue("id")!!
        assertEquals(ideaId, PreferencesActions.entries(read().items["profile:profile"]).single().stringValue("ideaID"))
        compose.onNodeWithTag("preference_edit_$entryId").performScrollTo().performClick(); visible("preference_text")
        compose.onNodeWithTag("preference_text").performTextReplacement("虚构偏好：先观察五分钟，再留一句记录")
        Espresso.closeSoftKeyboard(); scenario.recreate(); visible("preference_text")
        compose.onNodeWithTag("preference_text").assertTextContains("虚构偏好：先观察五分钟，再留一句记录")
        compose.onNodeWithTag("preferences_save").performClick()
        eventually { PreferencesActions.entries(it.items["profile:profile"]).singleOrNull()?.stringValue("text") == "虚构偏好：先观察五分钟，再留一句记录" }
        gone("preferences_save")
        optionalScreenshot("workspace-preferences.png", "preferences_workspace")
        compose.onNodeWithTag("preference_forget_$entryId").performScrollTo().performClick()
        visible("preference_forget_confirm"); compose.onNodeWithTag("preference_forget_confirm").performClick()
        eventually { PreferencesActions.entries(it.items["profile:profile"]).isEmpty() }
        assertEquals("虚构的周末观察者", read().items.getValue("profile:profile").content()?.get("background")?.jsonPrimitive?.content)
        closeWorkspace("preferences_workspace")
    }

    @Test fun scheduleFormCrossDayUndoReturnsToSourceAndRevealsTheSameTaskForAnotherMove() {
        val sourceDate = LocalDate.now(ZoneOffset.UTC)
        val targetDate = if (sourceDate.dayOfMonth < sourceDate.lengthOfMonth()) sourceDate.plusDays(1) else sourceDate.minusDays(1)
        val sourceId = "$sourceDate@UTC"; val targetId = "$targetDate@UTC"
        val start = sourceDate.atTime(16, 0).toInstant(ZoneOffset.UTC)
        val targetStart = targetDate.atTime(16, 0).toInstant(ZoneOffset.UTC)
        runtime.engine.edit { current -> StateMachine.edit(current, "day_plan", sourceId, buildJsonObject { put("content", buildJsonObject {
            put("day_key", sourceDate.toString()); put("time_zone", "UTC")
            put("ordered_task_ids", JsonArray(listOf(JsonPrimitive(taskId)))); put("daily_win_task_id", taskId)
            put("task_plans", buildJsonArray { add(buildJsonObject {
                put("task_id", taskId); put("scheduled_at", start.toString()); put("scheduled_minutes", 45)
                put("allocation", buildJsonObject { put("id", "94000000-0000-0000-0000-000000000010"); put("kind", "personal"); put("planned_minutes", 45) })
            }) })
        }) }) }
        val originalPlan = read().items.getValue("day_plan:$sourceId").payload
        fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
        fun header(date: LocalDate) = date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE))
        fun containsSchedule(state: SyncState, planId: String, at: Instant): Boolean =
            (state.items["day_plan:$planId"]?.content()?.get("task_plans") as? JsonArray).orEmpty().any {
                it.jsonObject["task_id"] == JsonPrimitive(taskId) && it.jsonObject["scheduled_at"] == JsonPrimitive(at.toString())
            }
        node("timeline_viewport").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
        compose.waitForIdle()
        val hourHeight = node("timeline_create_0").fetchSemanticsNode().boundsInRoot.height
        assertTrue(hourHeight > 0f)
        node("timeline_viewport").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 15.5f * hourHeight) }
        compose.waitForIdle(); node("timeline_move_$taskId").assertIsDisplayed()

        fun moveUsingActualDatePicker() {
            node("timeline_move_$taskId").assertIsDisplayed().performClick()
            visible("timeline_edit_$taskId"); node("timeline_edit_$taskId").performClick()
            visible("timeline_schedule_dialog")
            node("timeline_schedule_choose_date").performScrollTo().performClick()
            compose.waitForIdle()
            // A digit such as "2" also appears in 2026 and many other day labels.
            // Match the complete localized date; substring allows Material's optional Today prefix.
            val dateLabel=targetDate.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL)
                .withLocale(context.resources.configuration.locales[0]))
            compose.onNode(hasText(dateLabel, substring = true) and hasClickAction(), useUnmergedTree = true).performClick()
            compose.onNodeWithText("选择日期").performClick()
            node("timeline_schedule_date").assertTextContains(targetDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), substring = true)
            node("timeline_schedule_save").performClick(); gone("timeline_schedule_dialog")
            eventually { containsSchedule(it, targetId, targetStart) && !containsSchedule(it, sourceId, start) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(header(targetDate)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(header(targetDate)).assertIsDisplayed()
            node("timeline_move_$taskId").assertIsDisplayed()
        }

        moveUsingActualDatePicker()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("撤销") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("撤销") and hasClickAction()).performClick()
        eventually { it.items["day_plan:$sourceId"]?.payload == originalPlan && !containsSchedule(it, targetId, targetStart) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(header(sourceDate)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(header(sourceDate)).assertIsDisplayed()
        // No scroll, date click, or state mutation here: root undo must restore the viewport too.
        node("timeline_move_$taskId").assertIsDisplayed()
        optionalScreenshot("workspace-cross-day-undo-source.png", "today_timeline")
        moveUsingActualDatePicker()
        assertEquals(JsonPrimitive(45), (read().items.getValue("day_plan:$targetId").content()!!.getValue("task_plans").jsonArray
            .first { it.jsonObject["task_id"] == JsonPrimitive(taskId) }).jsonObject["scheduled_minutes"])
    }

    @Test fun deviceSharingStartsOffAndStaysOffAcrossRotationWithoutAccountOrHttp() {
        more("devices"); visible("device_presence_sharing")
        compose.onNodeWithTag("device_presence_sharing").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("请先登录账号，再选择是否共享。").assertExists()
        compose.onNodeWithText("暂未确认清空", substring = true).assertDoesNotExist()
        assertFalse(DevicePresenceSharingStore(context).enabled(null, read().device))
        assertNull(runtime.engine.session.value); assertFalse(read().enabled); assertNull(read().pending)
        assertTrue(runtime.presence.state.value.peers.isEmpty())
        scenario.recreate(); visible("device_presence_sharing")
        compose.onNodeWithTag("device_presence_sharing").assertIsOff().assertIsNotEnabled()
        optionalScreenshot("workspace-presence-opt-in-off.png", "device_presence")
        compose.onNodeWithTag("device_presence_close").performClick(); gone("device_presence")
        more("devices"); visible("device_presence_sharing")
        compose.onNodeWithTag("device_presence_sharing").assertIsOff()
        assertNull(runtime.engine.session.value); assertFalse(read().enabled); assertNull(read().pending)
        assertTrue(runtime.presence.state.value.peers.isEmpty())
    }
}
