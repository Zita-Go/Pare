package com.pare.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Synthetic in-memory state only; this Activity never initializes an account or a sync runtime. */
class GoalsPreferencesFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val now=Instant.parse("2026-10-31T08:00:00Z")
    private val taskId="00000000-0000-4000-8000-000000000081"
    private val ideaId="00000000-0000-4000-8000-000000000082"
    private fun isolated() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.pare.app.qa")}

    @Test fun goalFailureRetainsDraftThenSaveLinksAndDeletePreservesActions() {
        isolated()
        val task=Entity("task",taskId,ContentActions.save(null,"task","虚构：观察一片叶子","",1,taskId,now))
        var state by mutableStateOf(SyncState(items=mapOf(task.key to task)))
        var fail=true
        compose.setContent {PareTheme {GoalsWorkspace(state,onEdit={change,done ->
            if(fail) {fail=false;done(Result.failure(IllegalStateException("虚构保存失败，草稿已保留")))}
            else done(runCatching {state=change(state)})
        },onDismiss={})}}
        compose.onNodeWithTag("goal_add").performClick()
        compose.onNodeWithTag("goal_title").performTextReplacement("虚构：完成自然观察册")
        compose.onNodeWithTag("goal_status_paused").performScrollTo().performClick()
        compose.onNodeWithTag("goal_link_tasks").performScrollTo().performClick()
        compose.onNodeWithTag("goal_link_$taskId").performScrollTo().performClick()
        compose.onNodeWithTag("goal_new_step").performScrollTo().performTextReplacement("虚构：先画一条轮廓")
        compose.onNodeWithTag("goal_save").assertIsDisplayed().performClick()
        compose.onNodeWithTag("goals_workspace_error").assertTextContains("虚构保存失败",substring=true)
        compose.onNodeWithTag("goal_title").performScrollTo().assertTextContains("虚构：完成自然观察册")
        compose.runOnIdle {assertEquals(1,state.items.size)}
        compose.onNodeWithTag("goal_save").performClick()
        compose.onNodeWithTag("goal_saved").assertIsDisplayed()
        lateinit var goal:Entity
        compose.runOnIdle {
            goal=state.items.values.single {it.kind=="goal"}
            assertEquals("paused",goal.content()!!.stringValue("status"))
            assertEquals(2,goal.content()!!["taskIDs"]!!.jsonArray.size)
        }
        compose.onNodeWithTag("goal_${goal.id}").performClick()
        compose.onNodeWithTag("goal_delete").performScrollTo().performClick()
        compose.onNodeWithTag("goal_delete_confirm").performClick()
        compose.runOnIdle {
            assertTrue(state.items.getValue(goal.key).deleted)
            assertEquals(2,state.items.values.count {it.kind=="task" && !it.deleted})
        }
    }

    @Test fun preferencesValidateMinutesAndManageIdeaScopedConfirmedPreference() {
        isolated()
        val idea=Entity("idea",ideaId,ContentActions.save(null,"idea","虚构：自然观察册","",1,ideaId,now))
        var state by mutableStateOf(SyncState(items=mapOf(idea.key to idea)))
        compose.setContent {PareTheme {PreferencesWorkspace(state,onEdit={change,done ->done(runCatching {state=change(state)})},onDismiss={})}}
        compose.onNodeWithTag("preferences_edit_profile").performClick()
        compose.onNodeWithTag("preferences_background").performTextReplacement("虚构：周末练习观察")
        compose.onNodeWithTag("preferences_planning_minutes").performScrollTo().performTextReplacement("481")
        compose.onNodeWithTag("preferences_save").assertIsNotEnabled()
        compose.onNodeWithTag("preferences_planning_minutes").performTextReplacement("45")
        compose.onNodeWithTag("preferences_starting_minutes").performScrollTo().performTextReplacement("3")
        compose.onNodeWithTag("preferences_save").performClick()
        compose.onNodeWithTag("preference_add").performScrollTo().performClick()
        compose.onNodeWithTag("preference_text").performTextReplacement("虚构：每次只画一片叶子")
        compose.onNodeWithTag("preference_scope").performClick()
        compose.onNodeWithTag("preference_scope_$ideaId").performScrollTo().performClick()
        compose.onNodeWithTag("preferences_save").performClick()
        var preferenceId=""
        compose.runOnIdle {
            val profile=state.items.getValue("profile:profile")
            PayloadValidation.validate("profile",profile.id,profile.payload!!)
            val entry=PreferencesActions.entries(profile).single()
            preferenceId=entry.stringValue("id")!!
            assertEquals(ideaId,entry.stringValue("ideaID"))
            assertEquals(45,ProfileDraft.from(profile).planningMinutes)
        }
        compose.onNodeWithTag("preference_edit_$preferenceId").performScrollTo().performClick()
        compose.onNodeWithTag("preference_text").performTextReplacement("虚构：先画五分钟")
        compose.onNodeWithTag("preferences_save").performClick()
        compose.onNodeWithTag("preference_forget_$preferenceId").performScrollTo().performClick()
        compose.onNodeWithTag("preference_forget_confirm").performClick()
        compose.runOnIdle {assertTrue(PreferencesActions.entries(state.items["profile:profile"]).isEmpty())}
    }
}
