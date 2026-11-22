package com.pare.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GoalsPreferencesTest {
    private val now = Instant.parse("2026-10-31T08:00:00Z")
    private val taskId = "11111111-1111-4111-8111-111111111111"
    private fun task() = Entity("task", taskId, ContentActions.save(null,"task","虚构行动","",1,taskId,now))
    private fun goalState(): SyncState = GoalsActions.save(SyncState(), GoalDraft(title="虚构目标"), GoalBaseline.from(null), now)
    private fun profileState(): SyncState = PreferencesActions.saveProfile(SyncState(), ProfileDraft(background="虚构背景"), ProfileBaseline.from(null), ZoneId.of("Asia/Shanghai"))
    private fun rejects(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }

    @Test fun goalCreatesMacPayloadAndAtomicallyLinksNewNextStep() {
        val draft = GoalDraft(title=" 学习绘画 ", motivation="记录观察", newStep="画一个杯子")
        val result = GoalsActions.save(SyncState(),draft,GoalBaseline.from(null),now)
        val goal = result.items.getValue("goal:${draft.id}")
        val task = result.items.getValue("task:${draft.newTaskId}")
        assertEquals("学习绘画", goal.content()!!["title"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive(draft.newTaskId))),goal.content()!!["taskIDs"])
        assertEquals(goal.group, task.group)
        PayloadValidation.validate("goal",goal.id,goal.payload!!)
        PayloadValidation.validate("task",task.id,task.payload!!)
        assertEquals(result,GoalsActions.save(result,draft,GoalBaseline.from(null),now.plusSeconds(60)))
        rejects { GoalsActions.save(result,draft.copy(title="不同目标"),GoalBaseline.from(null),now) }
    }

    @Test fun goalPreservesUnknownFieldsAndAcceptsSyncAcknowledgement() {
        val state = goalState(); val goal = state.items.values.single()
        val enriched = goal.copy(payload=JsonObject(goal.payload!! + ("futureWrapper" to JsonPrimitive(7)) +
            ("content" to JsonObject(goal.content()!! + ("futureContent" to JsonPrimitive("keep"))))))
        val draft = GoalDraft.from(enriched).copy(status="achieved",targetDate=now.toString())
        val result = GoalsActions.save(state.copy(items=mapOf(enriched.key to enriched.copy(revision=8))),draft,GoalBaseline.from(enriched),now)
        val saved=result.items.getValue(goal.key)
        assertEquals(JsonPrimitive(7),saved.payload!!["futureWrapper"])
        assertEquals(JsonPrimitive("keep"),saved.content()!!["futureContent"])
        assertEquals(JsonPrimitive("achieved"),saved.content()!!["status"])
    }

    @Test fun goalRejectsNoopDeletionConflictAndStaleFieldsWithoutOverwriting() {
        val state=goalState(); val goal=state.items.values.single(); val draft=GoalDraft.from(goal); val baseline=GoalBaseline.from(goal)
        rejects { GoalsActions.save(state,draft,baseline,now) }
        rejects { GoalsActions.save(state.copy(items=mapOf(goal.key to goal.copy(deleted=true,payload=null))),draft.copy(title="改名"),baseline,now) }
        val changed=goal.copy(payload=JsonObject(goal.payload!! + ("content" to JsonObject(goal.content()!! + ("motivation" to JsonPrimitive("其他端修改"))))))
        rejects { GoalsActions.save(state.copy(items=mapOf(goal.key to changed)),draft.copy(title="改名"),baseline,now) }
        val remote=Remote("goal",goal.id,1,payload=goal.payload,device="fake",sequence=1)
        rejects { GoalsActions.save(state.copy(items=mapOf(goal.key to goal.copy(conflict=remote))),draft.copy(title="改名"),baseline,now) }
        assertEquals("虚构目标",goal.content()!!["title"]!!.jsonPrimitive.content)
    }

    @Test fun goalLinksArchivedTaskRejectsDeletedLinkAndDeleteKeepsTask() {
        val task=task().copy(payload=JsonObject(task().payload!! + ("lifecycle" to JsonPrimitive("archived"))))
        val draft=GoalDraft(title="目标",taskIds=listOf(task.id))
        val result=GoalsActions.save(SyncState(items=mapOf(task.key to task)),draft,GoalBaseline.from(null),now)
        val goal=result.items.getValue("goal:${draft.id}")
        val deleted=GoalsActions.delete(result,goal)
        assertTrue(deleted.items.getValue(goal.key).deleted)
        assertEquals(task,deleted.items[task.key])
        rejects { GoalsActions.save(SyncState(),draft,GoalBaseline.from(null),now) }
    }

    @Test fun profileCreationUsesCompleteSafeDefaultsAndRejectsBlankNoop() {
        rejects { PreferencesActions.saveProfile(SyncState(),ProfileDraft(),ProfileBaseline.from(null),ZoneId.of("UTC")) }
        val state=profileState(); val p=state.items.getValue("profile:profile")
        PayloadValidation.validate("profile","profile",p.payload!!)
        assertEquals(JsonArray(emptyList()),p.content()!!["confirmedPreferences"])
        assertEquals(JsonPrimitive("Asia/Shanghai"),p.content()!!["planningTimeZone"])
        assertEquals(JsonPrimitive(30),p.content()!!["preferences"]!!.jsonObject["planningMinutes"])
        rejects { PreferencesActions.saveProfile(state,ProfileDraft(planningMinutes=481),ProfileBaseline.from(p),ZoneId.of("UTC")) }
    }

    @Test fun explicitPreferencesPreserveConcurrentConfirmedEntriesAndTimezone() {
        val state=profileState(); val p=state.items.getValue("profile:profile"); val expected=ProfileBaseline.from(p)
        val confirmation=ConfirmedPreferenceDraft(text="先做五分钟")
        val concurrent=PreferencesActions.saveConfirmed(state,confirmation,PreferenceBaseline.from(p,confirmation.id),now,ZoneId.of("UTC"))
        val result=PreferencesActions.saveProfile(concurrent,ProfileDraft.from(p).copy(notes="每天留空档"),expected,ZoneId.of("UTC"))
        val content=result.items.getValue(p.key).content()!!
        assertEquals("Asia/Shanghai",content["planningTimeZone"]!!.jsonPrimitive.content)
        assertEquals(confirmation.id,content["confirmedPreferences"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun confirmedPreferenceCanBeEditedForgottenAndPreservesOtherEntries() {
        val first=ConfirmedPreferenceDraft(text="每天一步",expiresAt=now.plusSeconds(3600).toString())
        var state=PreferencesActions.saveConfirmed(SyncState(),first,PreferenceBaseline.from(null,first.id),now,ZoneId.of("UTC"))
        val original=state.items.getValue("profile:profile")
        val second=ConfirmedPreferenceDraft(text="先看重点")
        state=PreferencesActions.saveConfirmed(state,second,PreferenceBaseline.from(original,second.id),now,ZoneId.of("UTC"))
        state=PreferencesActions.saveConfirmed(state,first.copy(text="每天五分钟",expiresAt=null),PreferenceBaseline.from(original,first.id),now,ZoneId.of("UTC"))
        val profile=state.items.getValue("profile:profile")
        val entries=PreferencesActions.entries(profile)
        assertEquals("用户填写",entries.first()["source"]!!.jsonPrimitive.content)
        assertFalse(entries.first().containsKey("expiresAt"))
        state=PreferencesActions.forget(state,first.id,PreferenceBaseline.from(profile,first.id))
        assertEquals(second.id,PreferencesActions.entries(state.items["profile:profile"]).single()["id"]!!.jsonPrimitive.content)
        rejects { PreferencesActions.forget(state,first.id,PreferenceBaseline.from(profile,first.id)) }
    }

    @Test fun preferenceRejectsDeletedProfileChangedEntryAndMissingIdeaScope() {
        val state=profileState(); val p=state.items.getValue("profile:profile")
        val draft=ConfirmedPreferenceDraft(text="项目限定",ideaId=taskId)
        rejects { PreferencesActions.saveConfirmed(state,draft,PreferenceBaseline.from(p,draft.id),now,ZoneId.of("UTC")) }
        rejects { PreferencesActions.saveProfile(state.copy(items=mapOf(p.key to p.copy(deleted=true,payload=null))),ProfileDraft(background="新背景"),ProfileBaseline.from(p),ZoneId.of("UTC")) }
        val first=draft.copy(ideaId=null)
        val created=PreferencesActions.saveConfirmed(state,first,PreferenceBaseline.from(p,first.id),now,ZoneId.of("UTC"))
        val saved=created.items.getValue(p.key); val baseline=PreferenceBaseline.from(saved,first.id)
        val updated=PreferencesActions.saveConfirmed(created,first.copy(text="另一端改写"),baseline,now,ZoneId.of("UTC"))
        rejects { PreferencesActions.saveConfirmed(updated,first.copy(text="过期草稿"),baseline,now,ZoneId.of("UTC")) }
    }

    @Test fun retainedModelsKeepDraftBaselineAndStableIdsAcrossFailureAndReopen() {
        val goal=GoalsWorkspaceModel(); goal.open(null)
        val initial=goal.draft!!; goal.change(initial.copy(title="虚构目标",newStep="一个动作"))
        assertTrue(goal.begin()); assertFalse(goal.begin())
        goal.clear(); assertNotNull(goal.draft)
        goal.finish(Result.failure(IllegalStateException("磁盘不可写")))
        goal.open(null)
        assertEquals(initial.id,goal.draft!!.id); assertEquals(initial.newTaskId,goal.draft!!.newTaskId)
        assertEquals("虚构目标",goal.draft!!.title); assertTrue(goal.baseline!!.isNew)
        assertEquals("磁盘不可写",goal.error)
        assertTrue(goal.begin()); goal.finish(Result.success(Unit)); assertNull(goal.draft)

        val prefs=PreferencesWorkspaceModel(); prefs.openPreference(null,null)
        val first=prefs.preference!!; prefs.changePreference(first.copy(text="虚构偏好"))
        assertTrue(prefs.begin()); prefs.finish(Result.failure(IllegalStateException("尚未保存")))
        prefs.openPreference(null,null)
        assertEquals(first.id,prefs.preference!!.id); assertEquals("虚构偏好",prefs.preference!!.text)
        assertTrue(prefs.begin()); prefs.finish(Result.success(Unit)); assertNull(prefs.preference)
    }

    @Test fun profileUnknownsConfirmedUnknownsAndSourceSurviveEditing() {
        val base=profileState(); val p=base.items.getValue("profile:profile")
        val pref=ConfirmedPreferenceDraft(text="旧偏好")
        val created=PreferencesActions.saveConfirmed(base,pref,PreferenceBaseline.from(p,pref.id),now)
        val c=created.items.getValue(p.key).content()!!.toMutableMap()
        c["futureProfile"]=JsonPrimitive("keep")
        c["preferences"]=JsonObject(c["preferences"]!!.jsonObject + ("futureSetting" to JsonPrimitive(12)))
        val entry=PreferencesActions.entries(created.items[p.key]).single()
        c["confirmedPreferences"]=JsonArray(listOf(JsonObject(entry + ("source" to JsonPrimitive("用户明确确认")) + ("futurePreference" to JsonPrimitive(true)))))
        val enriched=p.copy(payload=JsonObject(p.payload!! + ("content" to JsonObject(c)) + ("futureWrapper" to JsonPrimitive(9))))
        val state=created.copy(items=created.items+(p.key to enriched))
        val modified=PreferencesActions.saveConfirmed(state,pref.copy(text="新偏好"),PreferenceBaseline.from(enriched,pref.id),now)
        val saved=modified.items.getValue(p.key)
        assertEquals(JsonPrimitive(9),saved.payload!!["futureWrapper"])
        assertEquals(JsonPrimitive("keep"),saved.content()!!["futureProfile"])
        assertEquals(JsonPrimitive(12),saved.content()!!["preferences"]!!.jsonObject["futureSetting"])
        val result=PreferencesActions.entries(saved).single()
        assertEquals(JsonPrimitive(true),result["futurePreference"])
        assertEquals(JsonPrimitive("用户明确确认"),result["source"])
    }

    @Test fun preferenceCreateRetryIsIdempotentButUnchangedExistingEditIsRejected() {
        val draft=ConfirmedPreferenceDraft(text="先列一个动作")
        val expected=PreferenceBaseline.from(null,draft.id)
        val created=PreferencesActions.saveConfirmed(SyncState(),draft,expected,now,ZoneId.of("UTC"))
        assertEquals(created,PreferencesActions.saveConfirmed(created,draft,expected,now.plusSeconds(30),ZoneId.of("UTC")))
        rejects { PreferencesActions.saveConfirmed(created,draft,PreferenceBaseline.from(created.items["profile:profile"],draft.id),now) }
    }

    @Test fun manyOfflineGoalStepsCannotCreateAnUnsyncableAtomicGroup() {
        var state=goalState()
        repeat(99) {
            val goal=state.items.values.single {it.kind=="goal"}
            state=GoalsActions.save(state,GoalDraft.from(goal).copy(newStep="虚构步骤 $it"),GoalBaseline.from(goal),now)
        }
        assertEquals(100,StateMachine.prepare(state).pending!!.mutations.size)
        val goal=state.items.values.single {it.kind=="goal"}
        rejects { GoalsActions.save(state,GoalDraft.from(goal).copy(newStep="超出同步组容量"),GoalBaseline.from(goal),now) }
        assertEquals(99,goal.content()!!["taskIDs"]!!.jsonArray.size)
    }
}
