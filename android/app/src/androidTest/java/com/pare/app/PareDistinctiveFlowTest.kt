package com.pare.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant

class PareDistinctiveFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun read() = PareRepository(context).use { it.read() }
    @Before fun launch() {
        check(context.packageName == "com.pare.app.qa")
        PareRepository(context).use { it.update { SyncState() } }
        check(context.getSharedPreferences("session-vault",0).edit().clear().commit())
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }
    @After fun finish() { scenario.close() }
    private fun visible(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun gone(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() } }
    private fun snapshot(tag: String, name: String) {
        compose.waitForIdle()
        val dir = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))).apply {mkdirs()}
        File(dir,name).outputStream().use { compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun ideaBecomesActionAndSmallStepWithoutLosingItsHistory() {
        visible("tab_ideas");compose.onNodeWithTag("tab_ideas").performClick()
        compose.onNodeWithTag("add_item").performClick();visible("editor_text")
        compose.onNodeWithTag("editor_text").performTextReplacement("建立一套长期阅读方法")
        compose.onNodeWithTag("editor_save").performClick();gone("editor_save")
        val idea = read().items.values.first { it.kind=="idea" }
        compose.onNodeWithTag("idea_${idea.id}").performClick();visible("idea_workspace")
        compose.onNodeWithTag("idea_context").performScrollTo().performClick()
        compose.onNodeWithTag("idea_stage_exploring").performClick()
        compose.onNodeWithTag("idea_question").performScrollTo().performTextReplacement("怎样让每周阅读更容易坚持？")
        compose.onNodeWithTag("idea_context_save").performScrollTo().performClick();gone("idea_context_save")
        compose.onNodeWithTag("idea_add_entry").performScrollTo().performClick()
        compose.onNodeWithTag("idea_entry_kind_finding").performClick()
        compose.onNodeWithTag("idea_entry_text").performTextReplacement("一次只读一小节，比要求读完一章更容易开始。")
        scenario.recreate();visible("idea_entry_text")
        compose.onNodeWithTag("idea_entry_text").assertTextContains("一次只读一小节", substring = true)
        compose.onNodeWithTag("idea_entry_save").performScrollTo().performClick();gone("idea_entry_save")
        compose.onNodeWithTag("idea_create_action").performScrollTo().performClick()
        compose.onNodeWithTag("idea_action_title").performTextReplacement("试读第一小节并留下一句总结")
        compose.onNodeWithTag("idea_action_next").performScrollTo().performTextReplacement("翻开第一页，先读一段")
        compose.onNodeWithTag("idea_action_dod").performScrollTo().performTextReplacement("留下一句理解就停")
        compose.onNodeWithTag("idea_action_today").performScrollTo().performClick()
        compose.onNodeWithTag("idea_action_minutes").performScrollTo().performTextReplacement("10")
        compose.onNodeWithTag("idea_action_create").performScrollTo().performClick();gone("idea_action_create")
        val updatedIdea = read().items.getValue(idea.key)
        val taskId = updatedIdea.content()!!.getValue("linkedTaskIDs").jsonArray.single().jsonPrimitive.content
        assertEquals(1,updatedIdea.content()!!.getValue("entries").jsonArray.size)
        compose.onNodeWithTag("linked_task_$taskId").performScrollTo().assertExists()
        snapshot("idea_workspace","11-idea-workspace.png")
        compose.onNodeWithTag("idea_workspace_close").performClick();gone("idea_workspace")
        compose.openTaskAction(taskId,"plan");visible("allocation_dialog")
        compose.onNodeWithTag("allocation_check").performClick()
        compose.onNodeWithTag("allocation_minutes").performTextReplacement("3")
        snapshot("allocation_dialog","12-today-check-plan.png")
        compose.onNodeWithTag("allocation_save").performClick();gone("allocation_dialog")
        assertEquals(3L,TodayLoad.calculate(read(),Instant.now()).checkMinutes)
        compose.openTaskAction(taskId,"support")
        visible("support_dialog")
        compose.onNodeWithTag("support_reason_lowEnergy").performScrollTo().performClick()
        compose.onNodeWithTag("support_next_action").performScrollTo().performTextReplacement("只读第一段，圈出一个词")
        compose.onNodeWithTag("support_done").performScrollTo().performTextReplacement("圈出一个词就停")
        compose.onNodeWithTag("support_minutes_5").performScrollTo().performClick()
        compose.onNodeWithTag("support_save").performClick();gone("support_dialog")
        val task = read().items.getValue("task:$taskId")
        assertEquals("只读第一段，圈出一个词",task.content()!!.getValue("nextAction").jsonPrimitive.content)
        assertEquals(1,task.content()!!.getValue("blockerEntries").jsonArray.size)
        assertEquals(5L,TodayLoad.calculate(read(),Instant.now()).checkMinutes)
        assertEquals(0L,TodayLoad.calculate(read(),Instant.now()).personalMinutes)
        assertFalse(task.completed())
        compose.openTaskAction(taskId,"win")
        compose.waitUntil(10_000) { read().items.values.any { it.kind=="day_plan" && it.content()?.get("daily_win_task_id")?.jsonPrimitive?.contentOrNull==taskId } }
        snapshot("main_list","13-today-small-step.png")
        compose.onNodeWithTag("complete_$taskId").performClick()
        compose.waitUntil(10_000) { read().items.getValue("task:$taskId").completed() }
        compose.onNodeWithTag("tab_ideas").performClick();compose.onNodeWithTag("idea_${idea.id}").performClick()
        visible("idea_workspace")
        assertTrue(IdeaLinkedTasks.project(read(),read().items.getValue(idea.key)).single().completed)
        assertEquals(1,ideaTimeline(read().items.getValue(idea.key)).size)
        assertEquals("怎样让每周阅读更容易坚持？",read().items.getValue(idea.key).content()!!.getValue("nextQuestion").jsonPrimitive.content)
    }
}
