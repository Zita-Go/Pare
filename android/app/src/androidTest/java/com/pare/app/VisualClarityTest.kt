package com.pare.app

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Exercises real dialogs with synthetic QA data; no authentication, sync, or AI actions. */
class VisualClarityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private var contentView: ComposeView? = null
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val taskId = "00000000-0000-4000-8000-000000000041"
    private val completedId = "00000000-0000-4000-8000-000000000042"
    private val longTitle = "为虚构的周末阅读计划整理第一章笔记，并选择一个可以在今天开始的小步骤"

    @After fun releaseModels() {
        compose.runOnUiThread { contentView?.disposeComposition(); models.clear() }
    }

    @Test fun accountAndReviewKeepPrimaryActionsReachable() {
        val now = Instant.now()
        val state = fixture(now)
        val model = isolatedModel(state, now)
        val screen = mutableStateOf("account")
        val hints = mutableStateOf(true)
        var closedAccount = false
        var closedReview = false
        var openedTask: String? = null
        var aiCalls = 0
        setContentWithFontScale(1f) {
            PareTheme(darkTheme = screen.value == "review") {
                when (screen.value) {
                    "account" -> AccountDialog(model, state, "本机保存 · 离线可用", session = null,
                        busy = false, previewReady = false, onDismiss = { closedAccount = true; screen.value = "" })
                    "review" -> MobileReviewDialog(state, now, hints.value, { hints.value = it },
                        onOpen = { openedTask = it.id }, onAI = { aiCalls++ },
                        onDismiss = { closedReview = true; screen.value = "" })
                }
            }
        }

        assertDialogFontScale("账号与同步", "account", 1f)
        reachableAction("account_close")
        reachableAction("account_login", enabled = false)
        reachableAction("account_register", enabled = false)
        compose.onNodeWithTag("account_email").assertIsDisplayed()
        compose.onNodeWithTag("account_password").assertIsDisplayed()
        screenshot("account_surface", "41-account-light.png")
        compose.onNodeWithTag("account_close").performClick()
        compose.runOnIdle { assertTrue(closedAccount); screen.value = "review" }

        assertDialogFontScale("回看最近7天", "review", 1f)
        reachableAction("review_close")
        compose.onNodeWithText("1 件").assertIsDisplayed()
        compose.onNodeWithText("25 分钟").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(longTitle))
        compose.onNodeWithText(longTitle).assertIsDisplayed().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(taskId, openedTask); assertEquals(0, aiCalls) }
        reachableAction("review_close")
        assertBelowHeader(longTitle, "review_close")
        screenshot("review_dialog", "42-review-dark.png")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("hints_enabled"))
        compose.onNodeWithTag("hints_enabled").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(!hints.value) }
        reachableAction("review_close")
        compose.onNodeWithTag("review_close").performClick()
        compose.runOnIdle { assertTrue(closedReview); assertEquals(0, aiCalls) }
    }

    @Test fun planningAndSupportKeepPinnedActionsVisibleWithLargeText() {
        val now = Instant.now()
        val model = isolatedModel(fixture(now), now)
        val screen = mutableStateOf("planning")
        var dismissals = 0
        setContentWithFontScale(1.6f) {
            PareTheme(darkTheme = false) {
                when (screen.value) {
                    "planning" -> TodayAllocationDialog(taskId, model) { dismissals++; screen.value = "" }
                    "support" -> TaskSupportDialog(taskId, model) { dismissals++; screen.value = "" }
                }
            }
        }

        assertDialogFontScale("安排今天的时间", "planning", 1.6f)
        assertTitleAboveFooter("allocation_save")
        reachableAction("allocation_cancel")
        reachableAction("allocation_save")
        compose.onNodeWithTag("allocation_check").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("allocation_minutes").performScrollTo().performTextReplacement("3")
        // The primary actions remain visible even while the keyboard occupies part of the window.
        reachableAction("allocation_cancel")
        reachableAction("allocation_save")
        closeSoftKeyboard()
        compose.onNodeWithText(longTitle).performScrollTo()
        assertTitleAboveFooter("allocation_save")
        screenshot("allocation_dialog", "43-planning-large-font.png")
        compose.onNodeWithTag("allocation_save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("allocation_dialog").fetchSemanticsNodes().isEmpty() }
        assertEquals(3L, TodayLoad.calculate(read(), now).checkMinutes)

        compose.runOnIdle { assertEquals(1, dismissals); screen.value = "support" }
        assertDialogFontScale("我卡住了", "support", 1.6f)
        assertTitleAboveFooter("support_save")
        reachableAction("support_cancel")
        reachableAction("support_save")
        compose.onNodeWithTag("support_reason_lowEnergy").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("support_next_action").performScrollTo().performTextReplacement("只读第一段，写下一句理解")
        compose.onNodeWithTag("support_done").performScrollTo().performTextReplacement("写下一句理解就停")
        reachableAction("support_cancel")
        reachableAction("support_save")
        closeSoftKeyboard()
        compose.onNodeWithTag("support_minutes_5").performScrollTo().performClick()
        compose.onNodeWithTag("support_next_action").performScrollTo().assertIsDisplayed()
        reachableAction("support_cancel")
        reachableAction("support_save")
        screenshot("support_dialog", "44-support-large-font.png")
        compose.onNodeWithTag("support_save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("support_dialog").fetchSemanticsNodes().isEmpty() }
        val saved = read().items.getValue("task:$taskId")
        assertEquals("只读第一段，写下一句理解", saved.content()?.get("nextAction")?.jsonPrimitive?.content)
        assertEquals("写下一句理解就停", saved.content()?.get("definitionOfDone")?.jsonPrimitive?.content)
        assertEquals(5L, TodayLoad.calculate(read(), now).checkMinutes)
        compose.runOnIdle { assertEquals(2, dismissals) }
        // Reopening also checks that the close action works after a successful local save.
        compose.runOnIdle { screen.value = "support" }
        reachableAction("support_cancel")
        compose.onNodeWithTag("support_cancel").performClick()
        compose.runOnIdle { assertEquals(3, dismissals) }
    }

    private fun reachableAction(tag: String, enabled: Boolean = true) {
        val action = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        if (enabled) action.assertIsEnabled() else action.assertIsNotEnabled()
    }

    /** Dialog creates a new AndroidComposeView from its host View's Context, not LocalDensity. */
    private fun setContentWithFontScale(requestedFontScale: Float, content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity { activity ->
            val configuredContext = ContextThemeWrapper(activity, activity.theme).apply {
                applyOverrideConfiguration(Configuration().apply { fontScale = requestedFontScale })
            }
            assertEquals(requestedFontScale, configuredContext.resources.configuration.fontScale, 0.001f)
            contentView = ComposeView(configuredContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent(content)
            }
            activity.setContentView(requireNotNull(contentView))
        }
        compose.waitForIdle()
    }

    /** Read the real text layout inside the dialog, so an ineffective outer override fails. */
    private fun assertDialogFontScale(title: String, key: String, expected: Float) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResults ->
            assertTrue("Dialog title must expose its actual text layout", getResults(results))
        }
        assertEquals(1, results.size)
        val layout = results.single()
        assertEquals("$key dialog must render at the requested font scale", expected, layout.layoutInput.density.fontScale, 0.001f)
        val metrics = buildJsonObject {
            put("requested_font_scale", expected)
            put("rendered_font_scale", layout.layoutInput.density.fontScale)
            put("density", layout.layoutInput.density.density)
            put("title_font_size_sp", layout.layoutInput.style.fontSize.value)
            put("title_line_count", layout.lineCount)
            put("title_height_px", layout.size.height)
        }
        File(outputDirectory(), "visual-font-scale-$key.json").writeText(metrics.toString())
    }

    private fun assertTitleAboveFooter(saveTag: String) {
        val title = compose.onNodeWithText(longTitle).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag(saveTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Long title overlaps pinned action $saveTag", title.bottom <= action.top)
    }

    private fun assertBelowHeader(title: String, closeTag: String) {
        val item = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithTag(closeTag).fetchSemanticsNode().boundsInRoot
        assertTrue("Long review title overlaps its close action", item.top >= close.bottom)
    }

    private fun isolatedModel(state: SyncState, now: Instant): PareViewModel {
        check(context.packageName == "com.pare.app.qa")
        check(context.getSharedPreferences("session-vault", 0).edit().clear().commit())
        PareRepository(context).use { it.update { state } }
        val model = ViewModelProvider(models,
            ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))[PareViewModel::class.java]
        compose.waitUntil(10_000) { model.initialized.value }
        // Guard the shared QA runtime as well as persisted state against any network work.
        SyncRuntime.get(context).engine.revoke()
        model.session.value = null
        model.planningInstant.value = now
        assertTrue(!model.state.value.enabled && model.state.value.owner == null)
        return model
    }

    private fun read() = PareRepository(context).use { it.read() }

    private fun fixture(now: Instant): SyncState {
        fun payload(id: String, title: String, created: Instant, extra: JsonObjectBuilder.() -> Unit): JsonObject {
            val base = ContentActions.save(null, "task", title, "仅供界面验收的虚构内容", 1, id, created,
                estimatedMinutes = 30, nextAction = "翻开第一段，先读一句")
            return JsonObject(base + ("content" to buildJsonObject {
                base.getValue("content").jsonObject.forEach { (key, value) -> put(key, value) }
                put("definitionOfDone", "写下一句理解就停")
                extra()
            }))
        }
        var state = StateMachine.edit(SyncState(), "task", taskId,
            payload(taskId, longTitle, now.minusSeconds(10 * 86400L)) {
                put("blockerEntries", buildJsonArray { add(buildJsonObject {
                    put("id", "00000000-0000-4000-8000-000000000043")
                    put("at", now.minusSeconds(86400).toString()); put("reason", "lowEnergy")
                }) })
            })
        state = StateMachine.edit(state, "task", completedId,
            payload(completedId, "完成虚构读书记录的第一页", now.minusSeconds(2 * 86400L)) {
                put("progressSessions", buildJsonArray { add(buildJsonObject {
                    put("id", "00000000-0000-4000-8000-000000000044")
                    put("startedAt", now.minusSeconds(86400 + 25 * 60L).toString())
                    put("endedAt", now.minusSeconds(86400).toString()); put("minutes", 25)
                }) })
                put("parallelRunSessions", buildJsonArray { add(buildJsonObject {
                    put("id", "00000000-0000-4000-8000-000000000045")
                    put("startedAt", now.minusSeconds(86400 + 12 * 60L).toString()); put("reason", "completed")
                    put("endedAt", now.minusSeconds(86400).toString()); put("elapsedMinutes", 12)
                }) })
            })
        return CompletionActions.set(state, completedId, true, now.minusSeconds(86400)).also { fixture ->
            fixture.items.values.forEach { PayloadValidation.validate(it.kind, it.id, requireNotNull(it.payload)) }
        }
    }

    private fun screenshot(tag: String, name: String) {
        compose.waitForIdle()
        File(outputDirectory(), name).outputStream().use {
            assertTrue(compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun outputDirectory(): File = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it) }
        ?: File(context.getExternalFilesDir(null), "ui-acceptance")).apply { mkdirs() }
}
