package com.pare.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Clipboard and UI tests run only in the isolated QA app with fictional data; no notifications posted. */
class ReviewImplementationFlowInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val now=Instant.parse("2099-05-20T08:00:00Z")
    private val id="00000000-0000-4000-8000-000000000098"
    private fun state():SyncState {
        val task=Entity("task",id,ContentActions.save(null,"task","虚构：回看小行动","不要复制此任务说明",1,id,now))
        return SyncState(device="fictional-summary-ui",items=mapOf(task.key to task))
    }
    @Test fun reviewStartsCollapsedAndCopiesOnlyAfterExplicitTap() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.pare.app.qa")
        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val scenario=ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity {activity->
                clipboard.setPrimaryClip(ClipData.newPlainText("fictional","fictional-before-review"))
                activity.setContent {PareTheme {MobileReviewDialog(state(),now,false,onHintsChange={},onOpen={},onAI={},onDismiss={})}}
            }
            compose.waitForIdle()
            compose.onNodeWithTag("review_analytics").assertDoesNotExist()
            assertEquals("fictional-before-review",clipboard.primaryClip?.getItemAt(0)?.text.toString())
            compose.onNodeWithTag("review_analytics_toggle").performScrollTo().performClick()
            compose.onNodeWithTag("review_analytics").assertExists()
            assertEquals("fictional-before-review",clipboard.primaryClip?.getItemAt(0)?.text.toString())
            compose.onNodeWithTag("review_copy").performScrollTo().performClick()
            compose.onNodeWithTag("review_copy_status").assertTextContains("已复制",substring=true)
            val copied=clipboard.primaryClip?.getItemAt(0)?.text.toString()
            assertTrue(copied.contains("Pare 本地回顾"));assertFalse(copied.contains("虚构：回看小行动"));assertFalse(copied.contains("不要复制"))
        } finally {clipboard.setPrimaryClip(ClipData.newPlainText("fictional",""));scenario.close()}
    }
    @Test fun timePlanSaveFailureRetainsDraftAndRetrySavesOnlyImplementationFields() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.pare.app.qa")
        var currentState by mutableStateOf(state());var open by mutableStateOf(true);var attempts=0
        val model=ImplementationPlanModel();val scenario=ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity {activity->activity.setContent {PareTheme {if(open)ImplementationPlanDialog(currentState,id,onEdit={edit,done->
                attempts++
                if(attempts==1)done(Result.failure(IllegalStateException("虚构磁盘失败"))) else done(runCatching {currentState=edit(currentState);Unit})
            },onDismiss={open=false},model=model)}}}
            compose.onNodeWithTag("implementation_enabled").performClick()
            compose.onNodeWithTag("implementation_hour").performTextReplacement("9")
            compose.onNodeWithTag("implementation_minute").performTextReplacement("30")
            compose.onNodeWithTag("implementation_action").performTextReplacement("先画一条线")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("implementation_save").performClick()
            compose.onNodeWithTag("implementation_error").assertTextContains("虚构磁盘失败",substring=true)
            compose.onNodeWithTag("implementation_action").assertTextContains("先画一条线",substring=true)
            assertFalse(currentState.items["task:$id"]!!.content()!!.containsKey("implementationPlan"))
            compose.onNodeWithTag("implementation_save").performClick()
            compose.waitUntil(10_000) {!open}
            val plan=currentState.items["task:$id"]!!.content()!!.getValue("implementationPlan").jsonObject
            assertEquals("time",plan.timelineString("kind"));assertEquals(9,plan.timelineInt("hour"));assertEquals(30,plan.timelineInt("minute"));assertEquals("先画一条线",plan.timelineString("action"))
            assertEquals(2,attempts)
        } finally {scenario.close()}
    }
}
