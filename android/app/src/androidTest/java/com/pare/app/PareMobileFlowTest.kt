package com.pare.app

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.lifecycle.ViewModelProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.*

/** Runs against com.pare.app.qa only; it cannot touch a real Pare installation. */
class PareMobileFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun startEmptyOfflinePreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.pare.app.qa")
        context.deleteDatabase("pare.db")
        check(context.getSharedPreferences("session-vault", 0).edit().clear().commit())
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun closePreview() { scenario.close() }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun saveAndWaitUntilClosed() {
        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor_save").fetchSemanticsNodes().isEmpty() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { File(it) } ?: File(context.getExternalFilesDir(null), "ui-acceptance")
        folder.mkdirs()
        File(folder, name).outputStream().use {
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun captureOrganizeTodayCompleteAndIdeaRemainLocal() {
        waitFor("today_add_task")
        screenshot("01-today-empty.png")
        compose.onNodeWithTag("today_add_task").performClick()
        waitFor("editor_text")
        compose.onNodeWithTag("editor_text").performTextReplacement("写出旅行清单的前三项")
        compose.onNodeWithTag("editor_next_action").performScrollTo().performTextReplacement("打开笔记，列出证件和车票")
        scenario.recreate()
        waitFor("editor_text")
        compose.onNodeWithTag("editor_text").assertTextContains("写出旅行清单的前三项")
        compose.onNodeWithTag("editor_next_action").performScrollTo().assertTextContains("打开笔记，列出证件和车票")
        compose.onNodeWithTag("editor_estimate").performScrollTo().performTextReplacement("15")
        saveAndWaitUntilClosed()
        compose.onNodeWithText("写出旅行清单的前三项").assertIsDisplayed()
        screenshot("02-today-task.png")

        // Simulate a newly received model version while an editing draft is
        // open; saving must not overwrite the newer note or close the draft.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalTask = PareRepository(context).use { it.read().items.values.first { e -> e.kind == "task" && e.title() == "写出旅行清单的前三项" } }
        val topBeforeSync = compose.onNodeWithTag("task_${originalTask.id}").fetchSemanticsNode().boundsInRoot.top
        scenario.onActivity { ViewModelProvider(it)[PareViewModel::class.java].busy.value = true }
        compose.waitForIdle()
        val topDuringSync = compose.onNodeWithTag("task_${originalTask.id}").fetchSemanticsNode().boundsInRoot.top
        assertEquals("Automatic sync must not move the task list", topBeforeSync, topDuringSync, 0.5f)
        scenario.onActivity { ViewModelProvider(it)[PareViewModel::class.java].busy.value = false }
        compose.onNodeWithTag("edit_${originalTask.id}").performClick()
        waitFor("editor_text")
        compose.onNodeWithTag("editor_text").performTextReplacement("手机尚未保存的修改")
        PareRepository(context).use { repo -> repo.update { state ->
            val current = state.items.getValue(originalTask.key)
            val content = current.content()!!.toMutableMap().apply { put("note", JsonPrimitive("另一端刚补充的虚构备注")) }
            val payload = JsonObject(current.payload!!.toMutableMap().apply { put("content", JsonObject(content)) })
            state.copy(items = state.items + (current.key to current.copy(payload = payload)))
        } }
        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()
        waitFor("editor_error")
        compose.onNodeWithTag("editor_text").assertTextContains("手机尚未保存的修改")
        assertTrue(PareRepository(context).use { it.read().items.getValue(originalTask.key).content()!!["note"]!!.jsonPrimitive.content } == "另一端刚补充的虚构备注")
        compose.onNodeWithTag("editor_cancel").performClick()
        compose.onNodeWithTag("draft_discard").performClick()

        compose.onNodeWithTag("tab_organize").performClick()
        compose.onNodeWithTag("add_item").performClick()
        waitFor("editor_text")
        compose.onNodeWithTag("editor_text").performTextReplacement("询问周末活动的集合时间")
        saveAndWaitUntilClosed()
        val inbox = PareRepository(context).use { it.read().items.values.first { e -> e.kind == "inbox" && e.title() == "询问周末活动的集合时间" } }
        compose.onNodeWithTag("organize_${inbox.id}").performScrollTo().performClick()
        compose.waitUntil(10_000) { PareRepository(context).use { it.read().items.values.any { e -> e.kind == "task" && e.title() == inbox.title() } } }
        screenshot("03-organize.png")

        compose.onNodeWithTag("tab_ideas").performClick()
        compose.onNodeWithTag("add_item").performClick()
        waitFor("editor_text")
        compose.onNodeWithTag("editor_text").performTextReplacement("做一本周末探索手册")
        compose.onNodeWithTag("editor_note").performScrollTo().performTextReplacement("先收集路线和照片，慢慢完善。")
        saveAndWaitUntilClosed()
        compose.onNodeWithText("萌芽").assertExists()
        screenshot("04-ideas.png")

        compose.onNodeWithTag("tab_today").performClick()
        val task = PareRepository(context).use { it.read().items.values.first { e -> e.kind == "task" && e.title() == "写出旅行清单的前三项" } }
        compose.onNodeWithTag("complete_${task.id}").performScrollTo().performClick()
        compose.waitUntil(10_000) { PareRepository(context).use { it.read().items.getValue(task.key).completed() } }
        scenario.recreate()
        waitFor("tab_today")
        PareRepository(context).use { repo ->
            val state = repo.read()
            assertTrue(state.items.getValue(task.key).completed())
            assertTrue(state.owner == null && !state.enabled && state.pending == null)
            assertTrue(state.items.values.any { it.kind == "idea" && it.title() == "做一本周末探索手册" })
        }
    }
}
