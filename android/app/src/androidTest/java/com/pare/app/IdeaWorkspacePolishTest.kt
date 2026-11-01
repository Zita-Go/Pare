package com.pare.app

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Catches clipped large text, lost history, hidden draft routes and misleading linked actions. */
class IdeaWorkspacePolishTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private var contentView: ComposeView? = null
    private var runtime: SyncRuntime? = null
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ideaId = "96000000-0000-4000-8000-000000000001"
    private val pendingId = "96000000-0000-4000-8000-000000000002"
    private val completedId = "96000000-0000-4000-8000-000000000003"
    private val archivedId = "96000000-0000-4000-8000-000000000004"
    private val missingId = "96000000-0000-4000-8000-000000000005"
    private val title = "虚构：把周末的自然观察慢慢整理成一本可以持续补充的小册子"
    private val question = "怎样让每次散步时的一点发现，都能变成下次愿意继续观察的线索？"
    private val background = "虚构背景原文：不急着做成项目，先保留每次观察里感兴趣的细节。"

    @After fun releaseQAWorkspace() {
        compose.runOnUiThread { contentView?.disposeComposition(); models.clear() }
        runtime?.engine?.edit { SyncState(device = it.device) }
    }

    @Test fun largeTextKeepsHistoryDraftsAndLinkedActionsAccessible() {
        check(context.packageName == "com.pare.app.qa")
        check(context.getSharedPreferences("session-vault", 0).edit().clear().commit())
        val qaRuntime = SyncRuntime.get(context).also { runtime = it }
        runBlocking { qaRuntime.engine.initialize(); qaRuntime.engine.pause() }
        val before = qaRuntime.engine.edit { fixture(it.device) }
        val model = ViewModelProvider(models,
            ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))[PareViewModel::class.java]
        compose.waitUntil(10_000) { model.initialized.value }
        assertNull(model.session.value)
        var openedTask: String? = null
        var aiOpens = 0
        var managementOpens = 0
        var closes = 0
        compose.activityRule.scenario.onActivity { activity ->
            val configuredContext = ContextThemeWrapper(activity, activity.theme).apply {
                applyOverrideConfiguration(Configuration().apply { fontScale = 1.6f })
            }
            contentView = ComposeView(configuredContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    PareTheme(darkTheme = true) {
                        IdeaWorkspaceDialog(ideaId, model, onDismiss = { closes++ }, onEditIdea = {},
                            onOpenTask = { openedTask = it.id }, onAI = { aiOpens++ },
                            onManage = { managementOpens++ })
                    }
                }
            }
            activity.setContentView(requireNotNull(contentView))
        }

        readable(title)
        readable(question)
        compose.onNodeWithTag("idea_context")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("idea_workspace_title").performScrollTo()
        screenshot("71-idea-dark-large-font.png")

        compose.onNodeWithText("虚构记录：第一次只记下叶片的形状。").assertDoesNotExist()
        compose.onNodeWithText("查看更多记录", substring = true).performScrollTo().performClick()
        readable("虚构记录：第一次只记下叶片的形状。")
        compose.onNodeWithTag("idea_background_toggle").performScrollTo().performClick()
        readable(background)
        compose.onNodeWithTag("idea_background_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("idea_background").assertDoesNotExist()

        action("idea_add_entry").performClick()
        action("idea_entry_kind_decision").performClick().assertIsSelected()
        compose.onNodeWithTag("idea_entry_text").performScrollTo().performTextReplacement("虚构草稿：每次只记录一种植物。")
        closeSoftKeyboard()
        compose.onNodeWithTag("idea_workspace_back").performClick()
        action("idea_add_entry").performClick()
        compose.onNodeWithTag("idea_entry_kind_decision").assertIsSelected()
        compose.onNodeWithTag("idea_entry_text").assertTextContains("虚构草稿：每次只记录一种植物。")
        compose.onNodeWithTag("idea_workspace_back").performClick()

        action("idea_create_action").performClick()
        compose.onNodeWithTag("idea_action_title").performTextReplacement("虚构行动：画出第一片叶子的轮廓")
        closeSoftKeyboard()
        compose.onNodeWithTag("idea_workspace_back").performClick()
        action("idea_create_action").performClick()
        compose.onNodeWithTag("idea_action_title").assertTextContains("虚构行动：画出第一片叶子的轮廓")
        compose.onNodeWithTag("idea_workspace_back").performClick()

        action("linked_task_$pendingId").assertTextContains("待推进").performClick()
        compose.runOnIdle { assertEquals(pendingId, openedTask) }
        action("linked_task_$completedId").assertTextContains("已完成").performClick()
        compose.runOnIdle { assertEquals(completedId, openedTask) }
        action("linked_task_$archivedId").assertTextContains("已归档").performClick()
        compose.runOnIdle { assertEquals(archivedId, openedTask) }
        compose.onNodeWithTag("linked_task_$missingId").performScrollTo().assertIsNotEnabled()
            .assertTextContains("行动已删除或暂不可用")
        screenshot("72-idea-linked-states-large-font.png")

        action("idea_ai").performClick()
        action("idea_manage").performClick()
        compose.onNodeWithTag("idea_workspace_close").performClick()
        compose.runOnIdle {
            assertEquals(1, aiOpens)
            assertEquals(1, managementOpens)
            assertEquals(1, closes)
            // Browsing, following links and returning from drafts must never save or discard history.
            assertEquals(before, model.state.value)
        }
    }

    private fun action(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)
        .performScrollTo().assertIsDisplayed().assertHasClickAction().assertIsEnabled()
        .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

    private fun readable(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(1, layouts.size)
        assertEquals(1.6f, layouts.single().layoutInput.density.fontScale, 0.001f)
        assertFalse("The entire text must remain readable: $text", layouts.single().hasVisualOverflow)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it) }
            ?: File(context.getExternalFilesDir(null), "ui-acceptance")).apply { mkdirs() }
        File(directory, name).outputStream().use {
            assertTrue(compose.onNodeWithTag("idea_workspace").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun fixture(device: String): SyncState {
        val now = Instant.parse("2026-11-01T04:00:00Z")
        var state = SyncState(device = device)
        for ((id, text) in listOf(pendingId to "虚构：观察一片叶子的纹路", completedId to "虚构：记下散步路线", archivedId to "虚构：旧观察计划")) {
            state = StateMachine.edit(state, "task", id, ContentActions.save(null, "task", text, "", 1, id, now))
        }
        state = CompletionActions.set(state, completedId, true, now)
        val archived = state.items.getValue("task:$archivedId")
        state = StateMachine.edit(state, "task", archivedId, JsonObject(archived.payload!! + ("lifecycle" to JsonPrimitive("archived"))))
        val base = ContentActions.save(null, "idea", title, background, 1, ideaId, now)
        val entries = listOf(
            "note" to "虚构记录：第一次只记下叶片的形状。",
            "finding" to "虚构发现：沿着同一条小路更容易发现变化。",
            "note" to "虚构记录：今天观察了三种不同的叶缘。",
            "finding" to "虚构发现：画出轮廓比记住名字更容易开始。",
            "decision" to "虚构决定：下一次只观察一片叶子的纹路。"
        )
        return StateMachine.edit(state, "idea", ideaId, JsonObject(base + ("content" to buildJsonObject {
            base.getValue("content").jsonObject.forEach { (key, value) -> put(key, value) }
            put("stage", "exploring"); put("nextQuestion", question)
            put("linkedTaskIDs", JsonArray(listOf(pendingId, completedId, archivedId, missingId).map(::JsonPrimitive)))
            put("entries", buildJsonArray { entries.forEachIndexed { index, (kind, text) -> add(buildJsonObject {
                put("id", "96000000-0000-4000-8000-00000000001$index")
                put("kind", kind); put("text", text); put("createdAt", now.plusSeconds(index.toLong()).toString())
            }) } })
        })))
    }
}
