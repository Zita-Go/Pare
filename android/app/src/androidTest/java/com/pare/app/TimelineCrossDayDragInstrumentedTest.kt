package com.pare.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real pointer down/long-press/hover/release. State, account and data are entirely fictional/in memory. */
class TimelineCrossDayDragInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val id="00000000-0000-4000-8000-000000000095"
    private val peer="00000000-0000-4000-8000-000000000096"
    private val date=LocalDate.parse("2099-05-20")
    private val start=Instant.parse("2099-05-20T09:00:00Z")
    private val source="$date@UTC"
    private val target="2099-05-21@UTC"
    private fun fixture():SyncState {
        val task=Entity("task",id,ContentActions.save(null,"task","虚构：移动草图","",1,id,start))
        val other=Entity("task",peer,ContentActions.save(null,"task","虚构：保留约会","",1,peer,start))
        fun plan(planId:String,taskId:String,at:Instant)=Entity("day_plan",planId,buildJsonObject {put("content",buildJsonObject {
            put("day_key",planId.substringBefore('@'));put("time_zone","UTC");put("ordered_task_ids",JsonArray(listOf(JsonPrimitive(taskId))))
            put("task_plans",buildJsonArray {add(buildJsonObject {put("task_id",taskId);put("scheduled_at",at.toString());put("scheduled_minutes",60)})})
        })})
        return SyncState(device="fictional-cross-day-ui",items=listOf(task,other,plan(source,id,start),plan(target,peer,start.plusSeconds(90000))).associateBy {it.key})
    }
    private fun node(tag:String)=compose.onNodeWithTag(tag,useUnmergedTree=true)
    @Test fun longPressDragToNextDatePreviewsUntilReleaseAndSupportsUndoThenMovingAgain() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.pare.app.qa")
        val before=fixture();var state by mutableStateOf(before);var selected by mutableStateOf(date);var focus by mutableStateOf<Instant?>(null)
        var receipt:TimelineUndo?=null;var calls=0
        val scenario=ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity {activity->activity.setContent {PareTheme {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                TodayTimeline(state,selected,Instant.parse("2099-05-20T08:00:00Z"),onSelectDate={selected=it;focus=null},onOpenTask={},onComplete={},onCreateAt={_,_->},onScheduleTask={_,_,_,_->},onEditSchedule={},
                    onChangeSchedule={_,_,_,_->error("Dropping on date target must use cross-day callback")},onReorderUnscheduled={_,_,_->},
                    onMoveToDay={block,at,expected->calls++;val previous=state;state=TimelineActions.moveToDay(state,block.task.id,at,block.durationMinutes,expected);receipt=TimelineUndo.capture(previous,state);selected=at.atZone(java.time.ZoneOffset.UTC).toLocalDate();focus=at},revealAt=focus)
            }}}}
            compose.waitForIdle()
            fun gesture(cancelGesture:Boolean=false) {
                node("timeline_move_$id").assertIsDisplayed()
                val origin=node("timeline_move_$id").fetchSemanticsNode().boundsInRoot.center
                compose.mainClock.autoAdvance=false
                var pointerDown=false
                try {
                    compose.onRoot().performTouchInput {down(origin)};pointerDown=true
                    compose.mainClock.advanceTimeBy(700)
                    compose.onRoot().performTouchInput {advanceEventTime(700);moveBy(Offset(0f,-1f))}
                    compose.mainClock.advanceTimeByFrame()
                    node("timeline_drop_next_day").assertIsDisplayed()
                    val destination=node("timeline_drop_next_day").fetchSemanticsNode().boundsInRoot.center
                    compose.onRoot().performTouchInput {moveTo(destination)}
                    compose.mainClock.advanceTimeByFrame()
                    assertEquals(before.items["day_plan:$source"]?.payload,state.items["day_plan:$source"]?.payload)
                    assertEquals(0,calls)
                    compose.onRoot().performTouchInput {if(cancelGesture) cancel() else up()};pointerDown=false
                    compose.mainClock.advanceTimeByFrame()
                } finally {if(pointerDown)compose.onRoot().performTouchInput {cancel()};compose.mainClock.autoAdvance=true}
                compose.waitForIdle()
            }
            gesture(cancelGesture=true);assertEquals(before,state);assertEquals(0,calls)
            gesture()
            assertEquals(1,calls);assertEquals(date.plusDays(1),selected)
            node("timeline_move_$id").assertIsDisplayed()
            val targetEntries=state.items.getValue("day_plan:$target").content()!!.getValue("task_plans").jsonArray
            assertEquals(start.plusSeconds(86400).toString(),targetEntries.first {it.jsonObject.timelineString("task_id")==id}.jsonObject.timelineString("scheduled_at"))
            assertEquals(before.items.getValue("day_plan:$target").content()!!.getValue("task_plans").jsonArray.single(),targetEntries.first {it.jsonObject.timelineString("task_id")==peer})
            compose.runOnIdle {state=receipt!!.apply(state);selected=date;focus=start;calls=0}
            compose.waitForIdle();node("timeline_move_$id").assertIsDisplayed()
            gesture();assertEquals(1,calls)
        } finally {compose.mainClock.autoAdvance=true;scenario.close()}
    }
    @Test fun datePickerMovesToArbitraryDateAndOccupiedTargetKeepsDraft() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.pare.app.qa")
        val original=fixture();val occupiedId="2099-05-22@UTC"
        val occupied=original.items.getValue("day_plan:$source").let {e->e.copy(id=occupiedId,payload=buildJsonObject {put("content",JsonObject(e.content()!!.toMutableMap().apply {
            put("day_key",JsonPrimitive("2099-05-22"));put("task_plans",buildJsonArray {add(buildJsonObject {put("task_id",id);put("scheduled_at","2099-05-22T12:00:00Z");put("scheduled_minutes",45)})})
        }))})}
        var state by mutableStateOf(original.copy(items=original.items+(occupied.key to occupied)))
        val model=TimelineScheduleModel();model.initialize(state,TimelineDay.fromPlanId(source),state.items["task:$id"])
        val scenario=ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity {activity->activity.setContent {PareTheme {TimelineScheduleDialog(model,true,onSave={
                if(model.begin()) model.finish(runCatching {
                    val expected=requireNotNull(model.moveExpected)
                    state=TimelineActions.moveToDay(state,id,requireNotNull(model.startInstant()),requireNotNull(model.durationMinutes()),expected,model.reminderLeadMinutes());Unit
                })
            },onUnschedule=null,onDismiss={},onSelectDate={model.selectDate(state,it)})}}}
            fun pick(dayText:String) {
                node("timeline_schedule_choose_date").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNode(hasText(dayText,substring=true) and hasClickAction(),useUnmergedTree=true).performClick()
                compose.onNodeWithText("选择日期").performClick()
                compose.waitForIdle()
            }
            pick("23");assertEquals(LocalDate.parse("2099-05-23"),model.day!!.date)
            node("timeline_schedule_save").performClick();compose.waitUntil(10_000) {model.saved}
            assertTrue(state.items.containsKey("day_plan:2099-05-23@UTC"))
            compose.runOnIdle {model.clear();model.initialize(state,TimelineDay.fromPlanId("2099-05-23@UTC"),state.items["task:$id"])}
            val beforeFailure=state;pick("22");compose.waitUntil(10_000) {model.error!=null}
            node("timeline_schedule_error").assertTextContains("目标日期已有",substring=true)
            assertEquals(beforeFailure,state);assertEquals(LocalDate.parse("2099-05-23"),model.day!!.date)
            assertFalse(model.saved)
        } finally {scenario.close()}
    }

}
