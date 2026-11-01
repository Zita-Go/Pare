package com.pare.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Real application save/action handoff, using only fictional data in the isolated QA store. */
class TaskStartPersistenceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val taskId = "98000000-0000-4000-8000-000000000002"
    private fun task() = PareRepository(context).use { it.read().items.getValue("task:$taskId") }

    @Before fun launch() {
        check(context.packageName == "com.pare.app.qa")
        check(context.getSharedPreferences("session-vault", 0).edit().clear().commit())
        val state = PareRepository(context).use { repo -> repo.update {
            val payload = ContentActions.save(null, "task", "虚构：整理观察笔记", "保留虚构背景", 1,
                taskId, Instant.parse("2026-01-01T00:00:00Z"), estimatedMinutes = 15, nextAction = "先写一句")
            StateMachine.edit(SyncState(), "task", taskId, payload)
        } }
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device = state.device))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("tab_organize")
        compose.showTaskInLibrary(taskId)
        compose.onNodeWithTag("edit_$taskId").performClick()
        waitFor("editor_text")
    }

    @After fun close() { if (::scenario.isInitialized) scenario.close() }

    @Test fun changedNextStepIsSavedBeforeFocusChooserAndDoesNotStartAutomatically() {
        compose.onNodeWithTag("editor_next_action").performScrollTo().performTextReplacement("只写一个词")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("run_$taskId").performScrollTo().assertTextContains("保存后开始专注").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor_text").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("开始专注").assertExists()
        compose.onNodeWithText("只写一个词").assertExists()
        val saved = task().content()!!
        assertEquals("只写一个词", saved.getValue("nextAction").jsonPrimitive.content)
        assertEquals("保留虚构背景", saved.getValue("note").jsonPrimitive.content)
        assertEquals(15, saved.getValue("estimatedMinutes").jsonPrimitive.int)
        assertNull("Opening focus must still allow the user to choose its duration", ExecutionFilePersistence(context).load()?.focus)
    }

    @Test fun unchangedDraftOpensSupportWithoutRewritingTask() {
        val before = task()
        compose.onNodeWithTag("support_$taskId").performScrollTo().assertTextContains("拆小一点").performClick()
        waitFor("support_dialog")
        compose.onNodeWithTag("support_next_action").performScrollTo().assertTextContains("先写一句")
        assertEquals("Opening a tool from an unchanged draft must not rewrite stored content", before, task())
        assertNull(ExecutionFilePersistence(context).load()?.focus)
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
