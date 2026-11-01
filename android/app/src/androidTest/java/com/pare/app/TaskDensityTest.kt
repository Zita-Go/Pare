package com.pare.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking

/** Fixed logical viewport verifies task-library density, independently of the emulator resolution. */
class TaskDensityTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private var runtime:SyncRuntime?=null
    @After fun releaseModel() { models.clear();runtime?.let {r->r.engine.edit {SyncState(device=it.device)};r.execution.reconcile()} }

    @Test fun sixTasksFitStandardViewportInBothThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.pare.app.qa")
        val ids = (1..6).map { "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
        val running=SyncRuntime.get(context);runtime=running
        runBlocking {running.engine.initialize();running.engine.pause()}
        running.engine.edit { existing ->
            var state = SyncState(device=existing.device)
            ids.forEachIndexed { index, id ->
                state = StateMachine.edit(state, "task", id, ContentActions.save(null,"task","第${index + 1}件：完成一个具体步骤","",1,id,Instant.now(),estimatedMinutes=15,nextAction="先写出这一小步"))
            }
            val key = ContentActions.planId(state, Instant.now())
            val plan = buildJsonObject { put("content",buildJsonObject {
                put("day_key",key.substringBefore('@'));put("time_zone",key.substringAfter('@'))
                put("ordered_task_ids",JsonArray(ids.map { JsonPrimitive(it) }));put("task_plans",JsonArray(emptyList()))
            }) }
            StateMachine.edit(state,"day_plan",key,plan)
        }
        val firstId=OrganizeActions.tasks(running.engine.state.value,1).first().id
        val model = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))[PareViewModel::class.java]
        val dark = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                PareTheme(darkTheme = dark.value) {
                    Box(Modifier.size(360.dp,800.dp).testTag("density_screen")) { PareScreen(model) }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tab_organize").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab_organize").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("task_${firstId}").fetchSemanticsNodes().isNotEmpty() }
        val screen = compose.onNodeWithTag("density_screen").fetchSemanticsNode().boundsInRoot
        val scale = screen.width/360f
        val first = compose.onNodeWithTag("task_${firstId}").fetchSemanticsNode().boundsInRoot
        assertTrue("First task starts too low: ${(first.top-screen.top)/scale}dp", (first.top-screen.top)/scale <= 240f)
        saveScreenshot("density-before-assertion.png")
        val diagnostic=File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),"density-before-assertion.txt")
        diagnostic.writeText(ids.joinToString("\n") {id->val n=compose.onAllNodesWithTag("task_$id").fetchSemanticsNodes().firstOrNull();"$id: ${n?.boundsInRoot}"})
        ids.forEach { id ->
            compose.onNodeWithTag("complete_$id").assertIsDisplayed()
            val row = compose.onNodeWithTag("task_$id").fetchSemanticsNode().boundsInRoot
            assertTrue("Task row extends under navigation", row.bottom <= screen.bottom - 80*scale)
        }
        val output = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))).apply { mkdirs() }
        File(output, "density-metrics.json").writeText(buildJsonObject {
            put("viewport_width_dp", 360); put("viewport_height_dp", 800); put("font_scale", 1)
            put("first_task_top_dp", (first.top-screen.top)/scale)
            put("visible_task_count", ids.size)
            put("task_row_heights_dp", JsonArray(ids.map { id ->
                val row = compose.onNodeWithTag("task_$id").fetchSemanticsNode().boundsInRoot
                JsonPrimitive(row.height/scale)
            }))
        }.toString())
        saveScreenshot("08-density-light.png")
        compose.runOnIdle { dark.value = true }
        compose.waitForIdle()
        ids.forEach { id ->
            compose.onNodeWithTag("complete_$id").assertIsDisplayed()
            val row = compose.onNodeWithTag("task_$id").fetchSemanticsNode().boundsInRoot
            assertTrue("Dark-theme task row extends under navigation", row.bottom <= screen.bottom - 80*scale)
        }
        saveScreenshot("09-density-dark.png")
        compose.onNodeWithTag("tab_today").performClick()
        compose.onNodeWithTag("add_item").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor_text").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor_text").assertIsFocused().performTextReplacement("只写标题也能创建的任务")
        compose.onNodeWithTag("editor_next_action").assertDoesNotExist()
        compose.onNodeWithTag("editor_note").assertDoesNotExist()
        File(output, "10-quick-capture.png").outputStream().use {
            compose.onNodeWithTag("editor_surface").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { PareRepository(context).use { repo -> repo.read().items.values.any { it.title() == "只写标题也能创建的任务" } } }
    }

    private fun saveScreenshot(name: String) {
        val folder = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")))
        folder.mkdirs()
        File(folder,name).outputStream().use { compose.onNodeWithTag("density_screen").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
