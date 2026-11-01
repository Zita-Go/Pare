package com.pare.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** No account, sync engine, real vault or HTTP service; only fictional in-memory state. */
class PlanningAIFlowInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun screenshotOrPrivacyNote() {
        compose.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val output=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(context.getExternalFilesDir(null),"ui-acceptance")
        output.mkdirs()
        val bitmap=runCatching {compose.onNodeWithTag("planning_ai_dialog").captureToImage().asAndroidBitmap()}.getOrElse {e->
            File(output,"19-ai-planning-screenshot-unavailable.txt").writeText("Planning AI keeps SecureFlagPolicy.SecureOn. captureToImage was unavailable (${e.javaClass.simpleName}); privacy flags were not changed. Only fictional fixtures were used.")
            return
        }
        File(output,"19-ai-planning.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    @Test fun reviewedDayPlanningRequiresOneManualCallAndKeepsUnselectedAppointmentThroughUndo() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.pare.app.qa")
        val taskId="00000000-0000-4000-8000-000000000081"
        val appointmentId="00000000-0000-4000-8000-000000000082"
        val day="2099-05-20@UTC";val now=Instant.parse("2099-05-20T08:00:00Z")
        val task=Entity("task",taskId,ContentActions.save(null,"task","虚构花园草图","只画一条线",1,taskId,now))
        val appointment=Entity("task",appointmentId,ContentActions.save(null,"task","虚构固定约会","不要发送未选中任务的说明",0,appointmentId,now))
        val plan=Entity("day_plan",day,buildJsonObject {put("content",buildJsonObject {
            put("day_key","2099-05-20");put("time_zone","UTC");put("ordered_task_ids",JsonArray(listOf(JsonPrimitive(appointmentId))))
            put("task_plans",buildJsonArray {add(buildJsonObject {put("task_id",appointmentId);put("scheduled_at",now.toString());put("scheduled_minutes",10)})})
        })})
        val before=SyncState(device="fictional-planning-ui",items=listOf(task,appointment,plan).associateBy {it.key})
        var state by mutableStateOf(before)
        val calls=AtomicInteger()
        val store=object:MobileAIStore {
            var data=MobileAIPrivateData(MobileAIConfig("https://model.invalid/v1/chat/completions","fictional-model"),"fictional-unusable-key")
            override fun load()=data
            override fun save(data:MobileAIPrivateData) {this.data=data}
            override fun clear() {data=MobileAIPrivateData()}
        }
        val client=MobileAIClient(MobileAITransport {_,_,body->
            calls.incrementAndGet()
            val raw=wire.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content
            assertFalse(raw.contains("不要发送未选中任务的说明"))
            val id=wire.parseToJsonElement(raw).jsonObject.getValue("requestId").jsonPrimitive.content
            val proposal=PlanningAIProposal(id,"从已选草图开始，保留已有约会",listOf(PlanningAIAction("00000000-0000-4000-8000-000000000083","allocate",taskId,minutes=15,nextAction="在纸上画一条短边",stopCriterion="画出第一条线就可以停",reason="根据所选草图和30分钟容量")))
            MobileAIResponse(200,buildJsonObject {put("choices",buildJsonArray {add(buildJsonObject {put("finish_reason","stop");put("message",buildJsonObject {put("content",wire.encodeToString(proposal))})})})}.toString())
        })
        val scenario=ActivityScenario.launch(ComponentActivity::class.java)
        lateinit var model:MobileAIModel
        try {
            scenario.onActivity {activity->
                model=ViewModelProvider(activity,object:ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=MobileAIModel(activity.application,store,client) as T
                })[MobileAIModel::class.java]
                model.planningDraft(state,PlanningAIKind.DAY,null,day).change(PlanningAIOptions(listOf(taskId)))
                activity.setContent {PareTheme {PlanningAIDialog(state,day,onEdit={edit,complete->complete(runCatching {state=edit(state);Unit})},onDismiss={},model=model)}}
            }
            compose.waitUntil(10_000) {model.ready}
            compose.onNodeWithTag("planning_preview").performScrollTo().performClick()
            assertEquals(0,calls.get())
            compose.onNodeWithTag("planning_raw_toggle").performScrollTo().performClick()
            compose.onNodeWithTag("planning_payload").performScrollTo().assertTextContains("虚构花园草图",substring=true)
            val draft=model.planningDraft(state,PlanningAIKind.DAY,null,day)
            val reviewed=draft.input
            val authorizedOptions=draft.options
            for(tag in listOf("planning_scope_toggle","planning_settings_toggle","planning_history_toggle")) {
                compose.onNodeWithTag(tag).performScrollTo().performClick()
                compose.onNodeWithTag(tag).performScrollTo().performClick()
            }
            assertEquals(reviewed,draft.input);assertEquals(authorizedOptions,draft.options);assertEquals(0,calls.get())
            compose.onNodeWithTag("planning_generate").performClick()
            compose.waitUntil(10_000) {model.planningDraft(state,PlanningAIKind.DAY,null,day).proposal!=null && !model.planningDraft(state,PlanningAIKind.DAY,null,day).busy}
            assertEquals(1,calls.get());assertEquals(before,state)
            compose.onNodeWithTag("planning_target_context").assertIsDisplayed().assertTextContains("安排日期 · 2099-05-20 · UTC",substring=true)
            compose.onNodeWithTag("planning_summary").assertIsDisplayed()
            val actionId="00000000-0000-4000-8000-000000000083"
            // Reviewing rationale must not conceal or change the actual writes and selection.
            compose.onNodeWithTag("planning_action_$actionId").performScrollTo().assertTextEquals("虚构花园草图")
            compose.onNodeWithTag("planning_time_$actionId").performScrollTo().assertTextContains("个人 15 分钟",substring=true)
            compose.onNodeWithTag("planning_next_$actionId").performScrollTo().assertTextContains("在纸上画一条短边",substring=true)
            compose.onNodeWithTag("planning_stop_$actionId").performScrollTo().assertTextContains("画出第一条线就可以停",substring=true)
            compose.onNodeWithTag("planning_reason_$actionId").assertDoesNotExist()
            compose.onNodeWithTag("planning_select_$actionId").performScrollTo().assertIsOn().performClick().assertIsOff()
            compose.onNodeWithTag("planning_adopt").assertIsNotEnabled()
            for(tag in listOf("planning_scope_toggle","planning_raw_toggle","planning_settings_toggle","planning_history_toggle")) {
                compose.onNodeWithTag(tag).performScrollTo().performClick()
                compose.onNodeWithTag(tag).performScrollTo().performClick()
            }
            assertEquals(emptySet<String>(),draft.selected)
            assertEquals(reviewed,draft.input);assertEquals(authorizedOptions,draft.options);assertEquals(1,calls.get())
            compose.onNodeWithTag("planning_reason_toggle_$actionId").performScrollTo().performClick()
            compose.onNodeWithTag("planning_reason_$actionId").performScrollTo().assertTextContains("30分钟容量",substring=true)
            compose.onNodeWithTag("planning_select_$actionId").performScrollTo().assertIsOff().performClick().assertIsOn()
            compose.onNodeWithTag("planning_reason_toggle_$actionId").performScrollTo().performClick()
            compose.onNodeWithTag("planning_next_$actionId").assertExists()
            compose.onNodeWithTag("planning_stop_$actionId").assertExists()
            assertEquals(1,calls.get());assertEquals(before,state)
            compose.onNodeWithTag("planning_target_context").performScrollTo().assertIsDisplayed().assertTextContains("安排日期 · 2099-05-20 · UTC",substring=true)
            compose.onNodeWithTag("planning_summary").assertIsDisplayed()
            screenshotOrPrivacyNote()
            compose.onNodeWithTag("planning_adopt").performClick()
            compose.waitUntil(10_000) {model.planningDraft(state,PlanningAIKind.DAY,null,day).undo!=null}
            compose.onNodeWithTag("planning_status").performScrollTo().assertTextEquals("已采纳勾选行动，可以撤销本次采纳")
            compose.onNodeWithTag("planning_select_$actionId").performScrollTo().assertIsOn().assertIsNotEnabled()
            compose.onNodeWithTag("planning_target_context").performScrollTo().assertIsDisplayed().assertTextContains("安排日期 · 2099-05-20 · UTC",substring=true)
            assertEquals(PlanningAIContext.entry(before,day,appointmentId),PlanningAIContext.entry(state,day,appointmentId))
            assertTrue(taskId in state.items["day_plan:$day"]?.content().planningIds("ordered_task_ids"))
            assertEquals("DAY",store.data.history.single().kind)
            assertFalse(wire.encodeToString(state).contains("fictional-unusable-key"))
            compose.onNodeWithTag("planning_undo").performClick()
            compose.waitUntil(10_000) {model.planningDraft(state,PlanningAIKind.DAY,null,day).undo==null}
            assertEquals(before.items["day_plan:$day"]?.payload,state.items["day_plan:$day"]?.payload)
            assertEquals(1,calls.get())
        } finally {scenario.close()}
    }
}
