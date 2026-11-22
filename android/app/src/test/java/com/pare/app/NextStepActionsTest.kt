package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NextStepActionsTest {
    private val now=Instant.parse("2026-11-19T08:00:00Z")
    private val taskId="ba000000-0000-4000-8000-000000000001"
    private val eventId="ba000000-0000-4000-8000-000000000002"
    private val secondId="ba000000-0000-4000-8000-000000000003"
    private fun initial():SyncState {
        val payload=ContentActions.save(null,"task","虚构：整理观察笔记","背景",1,taskId,now,estimatedMinutes=45,nextAction="先写一句")
        val task=Entity("task",taskId,JsonObject(payload.toMutableMap().apply {put("content",JsonObject(payload.getValue("content").jsonObject.toMutableMap().apply {
            put("definitionOfDone",JsonPrimitive("写完一句就停"));put("remindAt",JsonPrimitive(now.plusSeconds(600).toString()));put("future",JsonPrimitive(true))
        }))}))
        val state=SyncState(items=mapOf(task.key to task));val base=DayAllocationBaseline.from(state,taskId,now)
        return DayPlanActions.allocate(state,taskId,TodayAllocationDraft(base.planId,"personal",5,secondId,base),now)
    }
    private fun task(state:SyncState)=state.items.getValue("task:$taskId")
    private fun baseline(state:SyncState)=SmallStepBaseline.from(task(state))
    private fun change(state:SyncState,edit:(MutableMap<String,JsonElement>)->Unit):SyncState {
        val task=task(state);val c=task.content()!!.toMutableMap();edit(c)
        return StateMachine.edit(state,"task",taskId,JsonObject(task.payload!!.toMutableMap().apply {put("content",JsonObject(c))}))
    }

    @Test fun completingOneStepKeepsTaskPlanReminderAndEstimateAndAppendsHistory() {
        val before=initial();val after=NextStepActions.complete(before,taskId,eventId,baseline(before),now)
        val c=task(after).content()!!
        assertFalse(task(after).completed());assertNull(c["completedAt"]);assertNull(c["nextAction"]);assertNull(c["definitionOfDone"])
        assertEquals(JsonPrimitive(now.toString()),c["lastProgressAt"])
        assertEquals(wire.parseToJsonElement("""[{"id":"$eventId","text":"先写一句","completedAt":"2026-11-19T08:00:00Z","definitionOfDone":"写完一句就停"}]"""),c["completedSteps"])
        val untouched=setOf("text","note","quadrant","createdAt","completed","estimatedMinutes","remindAt","future")
        untouched.forEach {assertEquals(it,task(before).content()!![it],c[it])}
        assertEquals(before.items.filterKeys {it.startsWith("day_plan:")},after.items.filterKeys {it.startsWith("day_plan:")})
        assertNull(c["actualMinutes"]);assertNull(c["progressSessions"])
        assertEquals(after,wire.decodeFromString<SyncState>(wire.encodeToString(after)))
        PayloadValidation.validate("task",taskId,task(after).payload!!)
    }

    @Test fun duplicateCompletionAfterNewStepNeverClearsItAndConcurrentNotesAreKept() {
        val s=initial();val expected=baseline(s)
        val finished=NextStepActions.complete(s,taskId,eventId,expected,now)
        val next=NextStepActions.save(finished,taskId,"再写一个问题","",baseline(finished),now.plusSeconds(60))
        assertEquals(next,NextStepActions.complete(next,taskId,eventId,expected,now.plusSeconds(120)))
        val noted=change(next){it["note"]=JsonPrimitive("新背景")}
        val result=NextStepActions.complete(noted,taskId,secondId,baseline(next),now.plusSeconds(180))
        assertEquals(JsonPrimitive("新背景"),task(result).content()!!["note"])
        assertEquals(2,task(result).content()!!.getValue("completedSteps").jsonArray.size)
    }

    @Test fun staleBlankClosedAndConflictedTasksCannotCompleteAStep() {
        val s=initial();val expected=baseline(s)
        listOf("nextAction","definitionOfDone","text").forEach {field ->
            assertThrows(StaleContentEditException::class.java) {NextStepActions.complete(change(s){it[field]=JsonPrimitive("其他设备新内容")},taskId,eventId,expected,now)}
        }
        val blank=change(s){it.remove("nextAction")}
        assertThrows(IllegalArgumentException::class.java) {NextStepActions.complete(blank,taskId,eventId,baseline(blank),now)}
        val t=task(s)
        val closed=listOf(t.copy(deleted=true,payload=null),t.copy(payload=JsonObject(t.payload!!.toMutableMap().apply {put("lifecycle",JsonPrimitive("archived"))})),
            task(change(s){it["completed"]=JsonPrimitive(true)}),t.copy(conflict=Remote("task",taskId,1,payload=t.payload,device=s.device,sequence=1)))
        closed.forEach {entity ->assertThrows(IllegalArgumentException::class.java) {NextStepActions.complete(s.copy(items=s.items+(t.key to entity)),taskId,eventId,expected,now)}}
    }

    @Test fun undoRestoresOnlyLatestStepAndPreservesLaterNotesWithoutErasingNewStep() {
        val s=initial();val finished=NextStepActions.complete(s,taskId,eventId,baseline(s),now)
        val noted=change(finished){it["note"]=JsonPrimitive("后来的背景")}
        val undone=NextStepActions.undo(noted,taskId,eventId,now.plusSeconds(60))
        assertEquals(JsonPrimitive("先写一句"),task(undone).content()!!["nextAction"])
        assertEquals(JsonPrimitive("写完一句就停"),task(undone).content()!!["definitionOfDone"])
        assertEquals(JsonPrimitive("后来的背景"),task(undone).content()!!["note"])
        assertTrue(task(undone).content()!!.getValue("completedSteps").jsonArray.isEmpty())
        val next=NextStepActions.save(finished,taskId,"后一步","",baseline(finished),now)
        assertThrows(IllegalArgumentException::class.java) {NextStepActions.undo(next,taskId,eventId,now)}
        val later=NextStepActions.complete(next,taskId,secondId,baseline(next),now.plusSeconds(120))
        assertThrows(IllegalArgumentException::class.java) {NextStepActions.undo(later,taskId,eventId,now)}
        assertEquals(1,task(NextStepActions.undo(later,taskId,secondId,now)).content()!!.getValue("completedSteps").jsonArray.size)
    }

    @Test fun optionalHistorySupportsOldTasksButMalformedOrDuplicateRecordsAreRejected() {
        val s=initial();PayloadValidation.validate("task",taskId,task(s).payload!!)
        val valid=buildJsonObject {put("id",eventId);put("text","一步");put("completedAt",now.toString());put("future",true)}
        fun check(entries:JsonElement)=PayloadValidation.validate("task",taskId,task(change(s){it["completedSteps"]=entries}).payload!!)
        check(JsonArray(listOf(valid)))
        for((field,value) in listOf("id" to JsonPrimitive("invalid"),"text" to JsonPrimitive("  "),"text" to JsonPrimitive(3),"completedAt" to JsonPrimitive("not-date"),"definitionOfDone" to JsonPrimitive(false))) {
            assertThrows(IllegalArgumentException::class.java) {check(JsonArray(listOf(JsonObject(valid.toMutableMap().apply {put(field,value)}))))}
        }
        assertThrows(IllegalArgumentException::class.java) {check(JsonArray(listOf(valid,valid)))}
        assertThrows(IllegalArgumentException::class.java) {check(JsonPrimitive("wrong"))}
    }
    @Test fun clearingNextActionInEditorAlsoClearsItsStopCriterionWithoutCompletingIt() {
        val task=task(initial())
        val saved=ContentActions.save(task,"task",task.title(),"背景",1,taskId,now,nextAction="  ")
        val content=saved.getValue("content").jsonObject
        assertNull(content["nextAction"]);assertNull(content["definitionOfDone"])
        assertNull(content["completedSteps"]);assertEquals(JsonPrimitive(false),content["completed"])
    }
    @Test fun oldDraftCannotCompleteOrReplaceSuccessorWithIdenticalText() {
        val before=initial();val old=baseline(before)
        val finished=NextStepActions.complete(before,taskId,eventId,old,now)
        val repeated=NextStepActions.save(finished,taskId,old.nextAction,old.definitionOfDone,baseline(finished),now.plusSeconds(60))
        assertThrows(StaleContentEditException::class.java) {NextStepActions.complete(repeated,taskId,secondId,old,now.plusSeconds(120))}
        assertThrows(StaleContentEditException::class.java) {NextStepActions.save(repeated,taskId,"旧草稿修改","",old,now.plusSeconds(120))}
        assertThrows(StaleContentEditException::class.java) {SmallStepActions.save(repeated,taskId,old.nextAction,old.definitionOfDone,old,null,null,null,now.plusSeconds(120))}
        assertEquals(repeated,NextStepActions.complete(repeated,taskId,eventId,old,now.plusSeconds(120)))
    }
    @Test fun generalEditorDraftAlsoDetectsIdenticalSuccessorAfterCompletion() {
        val before=initial();val draft=EditorDraft.from(task(before));val old=baseline(before)
        val finished=NextStepActions.complete(before,taskId,eventId,old,now)
        val repeated=NextStepActions.save(finished,taskId,old.nextAction,old.definitionOfDone,baseline(finished),now.plusSeconds(60))
        assertThrows(StaleContentEditException::class.java) {
            ContentActions.save(task(repeated),"task",draft.text,draft.note,draft.quadrant,taskId,now,nextAction="旧详情草稿",expected=draft.editBaseline())
        }
    }
}
