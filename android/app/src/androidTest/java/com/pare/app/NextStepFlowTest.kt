package com.pare.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises the actual save/complete/continue handoff using only the isolated QA database. */
class NextStepFlowTest {
    @get:Rule val compose=createEmptyComposeRule()
    private lateinit var scenario:ActivityScenario<MainActivity>
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val taskId="ba000000-0000-4000-8000-000000000010"
    private fun task()=PareRepository(context).use {it.read().items.getValue("task:$taskId")}
    @Before fun launch() {
        check(context.packageName=="com.pare.app.qa")
        check(context.getSharedPreferences("session-vault",0).edit().clear().commit())
        val state=PareRepository(context).use {repo->repo.update {
            val payload=ContentActions.save(null,"task","虚构：整理观察笔记","保留背景",1,taskId,Instant.parse("2026-01-01T00:00:00Z"),estimatedMinutes=45,nextAction="先写一句")
            StateMachine.edit(SyncState(),"task",taskId,JsonObject(payload.toMutableMap().apply {put("content",JsonObject(payload.getValue("content").jsonObject.toMutableMap().apply {put("definitionOfDone",JsonPrimitive("写完一句就停"))}))}))
        }}
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device=state.device))
        scenario=ActivityScenario.launch(MainActivity::class.java)
        waitFor("tab_organize");compose.showTaskInLibrary(taskId)
        compose.onNodeWithTag("edit_$taskId").performClick();waitFor("editor_text")
    }
    @After fun close() {if(::scenario.isInitialized)scenario.close()}
    @Test fun completeFromFocusKeepsMainTaskOpenAndSavesNextStepWithHistory() {
        compose.onNodeWithTag("run_$taskId").performScrollTo().performClick()
        waitFor("next_step_complete")
        compose.onNodeWithTag("next_step_complete").performScrollTo().performClick()
        waitFor("next_step_new_action")
        compose.onNodeWithTag("next_step_history").performScrollTo().performClick()
        compose.onNodeWithText("先写一句",substring=false).assertExists()
        compose.onNodeWithTag("next_step_new_action").performScrollTo().performTextReplacement("再写一个问题")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("next_step_save").performScrollTo().performClick()
        compose.waitUntil(10_000){task().content()?.get("nextAction")?.jsonPrimitive?.contentOrNull=="再写一个问题"}
        val saved=task();assertFalse(saved.completed());assertEquals(45,saved.content()!!.getValue("estimatedMinutes").jsonPrimitive.int)
        assertEquals(1,saved.content()!!.getValue("completedSteps").jsonArray.size)
        assertNull("Step completion does not start a focus timer",ExecutionFilePersistence(context).load()?.focus)
    }
    @Test fun undoInSupportRestoresStepAndUnsavedDraftCannotBeCompleted() {
        compose.onNodeWithTag("support_$taskId").performScrollTo().performClick();waitFor("support_next_action")
        compose.onNodeWithTag("support_next_action").performScrollTo().performTextReplacement("未保存的新想法")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("next_step_complete").performScrollTo().assertIsNotEnabled()
        assertEquals("先写一句",task().content()!!.getValue("nextAction").jsonPrimitive.content)
        compose.onNodeWithTag("support_next_action").performScrollTo().performTextReplacement("先写一句")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("next_step_complete").performScrollTo().assertIsEnabled().performClick()
        waitFor("next_step_undo")
        compose.onNodeWithTag("support_next_action").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        compose.onNodeWithTag("next_step_undo").performScrollTo().performClick()
        compose.waitUntil(10_000){task().content()?.get("nextAction")?.jsonPrimitive?.contentOrNull=="先写一句"}
        compose.onNodeWithTag("support_next_action").performScrollTo().assertTextContains("先写一句")
        assertFalse(task().completed());assertTrue(task().content()!!.getValue("completedSteps").jsonArray.isEmpty())
    }
    private fun waitFor(tag:String) {compose.waitUntil(10_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
}
