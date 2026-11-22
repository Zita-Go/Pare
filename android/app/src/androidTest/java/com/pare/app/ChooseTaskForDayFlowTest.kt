package com.pare.app

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
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

/** Fictional fixtures in the isolated QA application only. */
class ChooseTaskForDayFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: SyncRuntime
    private val ids = (1..4).map { "98000000-0000-0000-0000-${it.toString().padStart(12, '0')}" }
    private val date = LocalDate.now(ZoneOffset.UTC)
    private val planId = "$date@UTC"

    @Before fun start() {
        check(context.packageName == "com.pare.app.qa")
        check(context.getSharedPreferences("session-vault", Context.MODE_PRIVATE).edit().clear().commit())
        val blank = PareRepository(context).use { it.update { SyncState() } }
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
        runtime = SyncRuntime.get(context)
        runBlocking { runtime.engine.initialize(); runtime.engine.pause() }
        runtime.engine.edit { s ->
            var next = s.copy(items = emptyMap())
            listOf("虚构：整理实验材料", "虚构：检查并行结果", "虚构：留出思考时段", "虚构：已结束的事项").forEachIndexed { index, title ->
                next = StateMachine.edit(next, "task", ids[index], ContentActions.save(null, "task", title, "", index.coerceAtMost(3), ids[index], Instant.now(), estimatedMinutes = 600))
            }
            next = CompletionActions.set(next, ids.last(), true, Instant.now())
            val profile = Entity("profile", "profile", buildJsonObject { put("content", buildJsonObject {
                put("background", ""); put("planningTimeZone", "UTC")
                put("preferences", buildJsonObject { put("planningMinutes", 30); put("startingMinutes", 5); put("notes", "") })
                put("confirmedPreferences", JsonArray(emptyList()))
            }) })
            next.copy(items = next.items + (profile.key to profile))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        visible("today_overview")
    }

    @After fun close() {
        if (::scenario.isInitialized) scenario.close()
        if (::runtime.isInitialized) {
            val blank = runtime.engine.edit { SyncState(device = it.device) }
            runtime.execution.reconcile()
            ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
            MobileNotificationRuntime(context).refresh(blank, force = true)
        }
    }

    private fun visible(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun absent(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() } }
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun read() = runtime.engine.state.value

    @Test fun emptyDayChoosesExistingTaskAndPersistsWithoutInventingEffortOrSchedule() {
        val before = read().items.getValue("task:${ids[0]}")
        node("today_choose_existing").performScrollTo().performClick()
        visible("choose_task_for_day")
        node("choose_day_task_${ids.last()}").assertDoesNotExist()
        node("choose_day_task_${ids[0]}").performScrollTo().performClick()
        absent("choose_task_for_day")
        visible("overview_task_${ids[0]}")
        node("overview_effort_${ids[0]}").assertTextEquals("未设投入 · 未定时间")
        assertEquals(before, read().items.getValue("task:${ids[0]}"))
        val plan = read().items.getValue("day_plan:$planId").content()!!
        assertEquals(listOf(ids[0]), plan["ordered_task_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue((plan["task_plans"] as? JsonArray).orEmpty().isEmpty())
        assertTrue(ReminderPlanner.sources(read(), emptyList(), Instant.now()).isEmpty())
        scenario.recreate()
        visible("overview_task_${ids[0]}")
        node("overview_effort_${ids[0]}").assertTextEquals("未设投入 · 未定时间")
        node("today_effort_summary").assertTextEquals("1件未设投入")
    }

    @Test fun overviewShowsSavedEffortSeparatelyFromCalendarDurationAndCompletedTasks() {
        runtime.engine.edit { state -> StateMachine.edit(state, "day_plan", planId, buildJsonObject { put("content", buildJsonObject {
            put("day_key", date.toString()); put("time_zone", "UTC")
            put("ordered_task_ids", JsonArray(ids.map(::JsonPrimitive)))
            put("task_plans", JsonArray(ids.mapIndexed { index, id -> buildJsonObject {
                put("task_id", id)
                if (index == 0 || index == 2) {
                    put("scheduled_at", date.atTime(9 + index, 0).toInstant(ZoneOffset.UTC).toString())
                    put("scheduled_minutes", if (index == 0) 90 else 120)
                }
                if (index != 2) put("allocation", buildJsonObject {
                    put("id", "99000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}")
                    put("kind", if (index == 1) "check" else "personal")
                    put("planned_minutes", when (index) { 0 -> 25; 1 -> 8; else -> 50 })
                })
            } }))
        }) }) }
        visible("overview_effort_${ids[0]}")
        node("overview_effort_${ids[0]}").assertTextEquals("个人投入 25 分钟")
        node("overview_effort_${ids[1]}").assertTextEquals("检查8分钟 · 未定时间")
        node("overview_effort_${ids[2]}").assertTextEquals("未设投入")
        node("today_effort_summary").assertTextEquals("自己做25分 · 检查8分 · 1件未设投入")
        compose.onNodeWithText("09:00–10:30 · 90分").assertExists()
        node("overview_task_${ids.last()}").assertDoesNotExist()
        node("today_completed_toggle").performScrollTo().performClick()
        node("overview_effort_${ids.last()}").performScrollTo().assertTextEquals("自己做50分钟 · 未定时间")
        node("today_effort_summary").assertTextEquals("自己做25分 · 检查8分 · 1件未设投入")
    }
}
