package com.pare.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
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

class TodayOverviewFlowTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario:ActivityScenario<MainActivity>
    private lateinit var runtime:SyncRuntime
    private val ids=(1..7).map {"97000000-0000-0000-0000-${it.toString().padStart(12,'0')}"}
    private val date=LocalDate.now(ZoneOffset.UTC)
    private val planId="$date@UTC"
    @Before fun start() {
        check(context.packageName=="com.pare.app.qa")
        check(context.getSharedPreferences("session-vault",Context.MODE_PRIVATE).edit().clear().commit())
        val blank=PareRepository(context).use {it.update {SyncState()}}
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device=blank.device))
        runtime=SyncRuntime.get(context)
        runBlocking {runtime.engine.initialize();runtime.engine.pause()}
        runtime.engine.edit {s->
            var next=s.copy(items=emptyMap())
            val titles=listOf("虚构：晨读","虚构：午间散步","虚构：晚间复盘","虚构：准备材料","虚构：整理笔记","虚构：联系同伴","虚构：已完成事项")
            ids.forEachIndexed {index,id->next=StateMachine.edit(next,"task",id,ContentActions.save(null,"task",titles[index],"",1,id,Instant.now()))}
            next=CompletionActions.set(next,ids.last(),true,Instant.now())
            val profile=Entity("profile","profile",buildJsonObject {put("content",buildJsonObject {
                put("background","");put("planningTimeZone","UTC");put("preferences",buildJsonObject {put("planningMinutes",30);put("startingMinutes",5);put("notes","")});put("confirmedPreferences",JsonArray(emptyList()))
            })})
            next=next.copy(items=next.items+(profile.key to profile))
            StateMachine.edit(next,"day_plan",planId,buildJsonObject {put("content",buildJsonObject {
                put("day_key",date.toString());put("time_zone","UTC");put("daily_win_task_id",ids[0]);put("ordered_task_ids",JsonArray(ids.map(::JsonPrimitive)))
                put("task_plans",JsonArray(listOf(9,14,20).mapIndexed {index,hour->buildJsonObject {
                    put("task_id",ids[index]);put("scheduled_at",date.atTime(hour,0).toInstant(ZoneOffset.UTC).toString());put("scheduled_minutes",30)
                }}))
            })})
        }
        scenario=ActivityScenario.launch(MainActivity::class.java)
        visible("today_overview");compose.waitUntil(10_000) {runtime.executionReady.value};runtime.execution.reconcile()
        Espresso.closeSoftKeyboard()
    }
    @After fun close() {
        if(::scenario.isInitialized)scenario.close()
        if(::runtime.isInitialized) {
            val blank=runtime.engine.edit {SyncState(device=it.device)}
            runtime.execution.reconcile();ExecutionFilePersistence(context).save(ExecutionSnapshot(device=blank.device))
            MobileNotificationRuntime(context).refresh(blank,force=true)
        }
    }
    private fun read()=runtime.engine.state.value
    private fun visible(tag:String) {compose.waitUntil(10_000) {compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
    private fun absent(tag:String) {compose.waitUntil(10_000) {compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}}
    private fun node(tag:String)=compose.onNodeWithTag(tag,useUnmergedTree=true)
    private fun screenshot(name:String) {
        val dir=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        File(dir).mkdirs();File(dir,name).outputStream().use {assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    private fun editFirst(time:String) {
        node("overview_schedule_${ids[0]}").performClick();visible("timeline_schedule_dialog")
        node("timeline_schedule_time").performScrollTo().performTextReplacement(time)
        Espresso.closeSoftKeyboard();node("timeline_schedule_save").performClick();absent("timeline_schedule_dialog")
        visible("workspace_snackbar")
    }

    @Test fun defaultListShowsMorningAndEveningTogetherAndPreservesTimelineScrollAndDate() {
        node("today_mode_list").assertIsSelected();node("today_timeline").assertDoesNotExist()
        node("today_overview_summary").assertTextEquals("待办 6 件 · 已完成 1 件")
        ids.take(6).forEach {node("overview_task_$it").assertIsDisplayed()}
        compose.onNodeWithText("09:00–09:30 · 30分").assertIsDisplayed()
        compose.onNodeWithText("20:00–20:30 · 30分").assertIsDisplayed()
        screenshot("today-compact-list.png")
        node("today_mode_timeline").performClick();visible("today_timeline")
        node("timeline_next_day").performClick()
        node("timeline_viewport").performSemanticsAction(SemanticsActions.ScrollBy) {it(0f,-100_000f)}
        compose.waitForIdle()
        val hour=node("timeline_create_0").fetchSemanticsNode().boundsInRoot.height
        node("timeline_viewport").performSemanticsAction(SemanticsActions.ScrollBy) {it(0f,13*hour)}
        compose.waitForIdle()
        val before=node("timeline_create_13").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        node("today_mode_list").performClick();visible("today_overview")
        compose.onNodeWithText("这一天还没有任务").assertExists()
        node("today_mode_timeline").performClick();visible("today_timeline");compose.waitForIdle()
        assertEquals(before,node("timeline_create_13").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top,1f)
        scenario.recreate();visible("today_timeline");node("today_mode_timeline").assertIsSelected()
        node("today_mode_list").performClick();node("timeline_today").performClick()
        node("today_completed_toggle").performScrollTo().performClick()
        node("overview_task_${ids.last()}").performScrollTo().assertIsDisplayed()
        node("today_overview_list").performScrollToNode(hasTestTag("overview_execution_${ids.first()}"))
        node("overview_execution_${ids.first()}").performClick();visible("execution_primary")
        node("execution_primary").performClick()
        compose.waitUntil(10_000) {runtime.execution.state.value.focus?.taskId==ids.first()}
        node("overview_execution_${ids.first()}").performClick()
        compose.waitUntil(10_000) {runtime.execution.state.value.focus?.paused==true}
        node("overview_execution_${ids.first()}").performClick()
        compose.waitUntil(10_000) {runtime.execution.state.value.focus?.paused==false}
    }

    @Test fun savedNoticeExpiresWithoutInteractionAndUndoAndCloseRemainUsable() {
        editFirst("09:15")
        compose.onNode(hasText("撤销") and hasClickAction()).assertIsDisplayed()
        screenshot("saved-notice-with-close.png")
        absent("workspace_snackbar") // Real host timeout, no clicking, navigation, or destruction.
        assertTrue(read().items.getValue("day_plan:$planId").payload.toString().contains("09:15:00Z"))
        node("today_mode_list").assertIsSelected()
        editFirst("09:30")
        compose.onNode(hasText("撤销") and hasClickAction()).performClick()
        compose.waitUntil(10_000) {read().items.getValue("day_plan:$planId").payload.toString().contains("09:15:00Z")}
        absent("workspace_snackbar")
        editFirst("09:45")
        compose.onNode(hasContentDescription("Dismiss",substring=true) and hasAnyAncestor(hasTestTag("workspace_snackbar"))).performClick()
        absent("workspace_snackbar")
        assertTrue(read().items.getValue("day_plan:$planId").payload.toString().contains("09:45:00Z"))
    }
}
