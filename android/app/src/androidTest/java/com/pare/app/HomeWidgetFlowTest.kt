package com.pare.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Actual activity intents and execution callbacks, restricted to a fictional offline QA library. */
@RunWith(AndroidJUnit4::class)
class HomeWidgetFlowTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var runtime:SyncRuntime
    private lateinit var scenario:ActivityScenario<MainActivity>
    private lateinit var scenarioIdentity:Intent
    private val taskId="97000000-0000-4000-8000-000000000001"

    @Before fun prepareOfflineQAOnly() {
        check(context.packageName=="com.pare.app.qa") {"Widget acceptance must never access the production installation"}
        check(context.getSharedPreferences("session-vault",Context.MODE_PRIVATE).edit().clear().commit())
        val blank=PareRepository(context).use {it.update {SyncState()}}
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device=blank.device))
        runtime=SyncRuntime.get(context)
        runBlocking {runtime.engine.initialize();runtime.engine.pause()}
        runtime.presence.clear()
        runtime.engine.edit {state ->
            val now=Instant.now()
            val task=Entity("task",taskId,ContentActions.save(null,"task","虚构：桌面观察笔记","隔离组件验收",1,taskId,now,nextAction="写下第一条观察"))
            val profile=Entity("profile","profile",buildJsonObject {put("content",buildJsonObject {put("planningTimeZone","UTC")})})
            val initial=SyncState(device=state.device,items=listOf(task,profile).associateBy {it.key})
            val key=ContentActions.planId(initial,now)
            val plan=Entity("day_plan",key,buildJsonObject {put("content",buildJsonObject {
                put("day_key",key.substringBefore('@'));put("time_zone","UTC")
                put("ordered_task_ids",JsonArray(listOf(JsonPrimitive(taskId))));put("daily_win_task_id",taskId)
                put("task_plans",JsonArray(emptyList()))
            })})
            initial.copy(items=initial.items+(plan.key to plan))
        }
        launch()
        visible("add_item")
        compose.waitUntil(10_000) {runtime.executionReady.value}
        runtime.execution.reconcile()
        Espresso.closeSoftKeyboard()
        assertNull(runtime.engine.state.value.owner)
        assertFalse(runtime.engine.state.value.enabled)
        assertNull(runtime.engine.session.value)
    }

    @After fun releaseOnlyQAState() {
        try {closeScenario()}
        finally {
            if(::runtime.isInitialized) {
                runtime.presence.clear()
                val blank=runtime.engine.edit {SyncState(device=it.device)}
                if(runtime.executionReady.value)runtime.execution.reconcile()
                ExecutionFilePersistence(context).save(ExecutionSnapshot(device=blank.device))
                MobileNotificationRuntime(context).refresh(blank,force=true)
            }
        }
    }

    private fun visible(tag:String) {compose.waitUntil(10_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun gone(tag:String) {compose.waitUntil(10_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()}}
    private fun launch(intent:Intent?=null) {
        scenario=if(intent==null)ActivityScenario.launch(MainActivity::class.java) else ActivityScenario.launch(intent)
        scenario.onActivity {scenarioIdentity=Intent(it.intent)}
    }
    private fun closeScenario() {
        if(!::scenario.isInitialized)return
        if(scenario.state!=Lifecycle.State.DESTROYED && ::scenarioIdentity.isInitialized) {
            // Main correctly replaces its intent on onNewIntent. ActivityScenario only observes
            // lifecycles matching its original launch filter; restore that filter for teardown,
            // after all real widget dispatch and behavior assertions have completed.
            scenario.onActivity {it.intent=Intent(scenarioIdentity)}
        }
        scenario.close()
    }
    private fun send(request:HomeWidgetRequest) {
        // SingleTop delivers the same explicit activity Intent as the immutable launcher PendingIntent.
        scenario.onActivity {it.startActivity(HomeWidgetActions.intent(it,73,request))}
        compose.waitForIdle()
    }
    private fun focus()=runtime.execution.state.value.focus

    @Test fun coldCaptureCreatesInboxAndRepeatedCapturePreservesUnsavedDraft() {
        val baseline=runtime.engine.state.value.items
        closeScenario()
        launch(HomeWidgetActions.intent(context,73,HomeWidgetRequest(HomeWidgetAction.CAPTURE)))
        visible("editor_text")
        compose.onNodeWithText("随手记").assertIsDisplayed()
        compose.onNodeWithTag("editor_priority").assertDoesNotExist()
        compose.onNodeWithTag("editor_text").performTextReplacement("虚构：保留桌面收集的草稿")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("editor_cancel").performClick()
        visible("draft_keep_close");compose.onNodeWithTag("draft_keep_close").performClick()
        gone("editor_surface")
        send(HomeWidgetRequest(HomeWidgetAction.CAPTURE))
        visible("draft_switch_continue")
        compose.onNodeWithTag("draft_switch_continue").performClick()
        visible("editor_text")
        compose.onNodeWithTag("editor_text").assertTextContains("虚构：保留桌面收集的草稿")
        compose.onNodeWithTag("editor_save").performClick()
        gone("editor_surface")
        compose.waitUntil(10_000) {runtime.engine.state.value.items.values.any {it.kind=="inbox" && it.title()=="虚构：保留桌面收集的草稿"}}
        baseline.forEach {(key,value)->assertEquals(value,runtime.engine.state.value.items[key])}
        assertNull(focus())
    }

    @Test fun widgetStartOpensDurationPickerAndOnlyConfirmationStartsSelectedDuration() {
        send(HomeWidgetRequest(HomeWidgetAction.START_FOCUS,taskId))
        visible("focus_presets")
        assertNull("Opening the widget start route must not start a default timer",focus())
        compose.onNodeWithTag("focus_preset_15").performScrollTo().performClick()
        compose.onNodeWithTag("focus_minutes").assertTextContains("15")
        assertNull("Choosing a duration must still wait for confirmation",focus())
        compose.onNodeWithTag("execution_primary").performClick()
        compose.waitUntil(10_000) {focus()?.taskId==taskId}
        assertEquals(15,focus()!!.targetMinutes)
        assertFalse(focus()!!.paused)
        assertFalse(runtime.engine.state.value.items.getValue("task:$taskId").completed())
    }

    @Test fun pauseResumeAffectExactRunAndStaleClicksOnlyOpenControls() {
        runtime.execute(ExecutionCommand(taskId,ExecutionAction.START_FOCUS,5))
        val first=requireNotNull(focus())
        val pause=HomeWidgetRequest(HomeWidgetAction.PAUSE_FOCUS,taskId,first.id)
        val resume=pause.copy(action=HomeWidgetAction.RESUME_FOCUS)
        send(pause)
        compose.waitUntil(10_000) {focus()?.paused==true}
        assertEquals(first.id,focus()!!.id)
        send(resume)
        compose.waitUntil(10_000) {focus()?.paused==false}
        assertEquals(first.id,focus()!!.id)
        runtime.execute(ExecutionCommand(taskId,ExecutionAction.CANCEL))
        runtime.execute(ExecutionCommand(taskId,ExecutionAction.START_FOCUS,15))
        val replacement=requireNotNull(focus())
        assertNotEquals(first.id,replacement.id)
        send(pause)
        visible("execution_primary")
        assertEquals(replacement.id,focus()!!.id)
        assertFalse("Old pause may not pause a new run of the same task",focus()!!.paused)
        compose.onNodeWithText("关闭").performClick();gone("execution_primary")
        runtime.execute(ExecutionCommand(taskId,ExecutionAction.PAUSE))
        send(resume)
        visible("execution_primary")
        assertEquals(replacement.id,focus()!!.id)
        assertTrue("Old resume may not resume a new run of the same task",focus()!!.paused)
        val task=runtime.engine.state.value.items.getValue("task:$taskId")
        assertFalse(task.completed())
        assertTrue((task.content()?.get("progressSessions") as? JsonArray).isNullOrEmpty())
    }
}
