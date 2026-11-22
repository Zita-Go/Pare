package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowActionsTest {
    private val now = Instant.parse("2026-10-30T00:00:00Z")
    private val ideaId = "00000000-0000-4000-8000-000000000001"
    private val taskId = "00000000-0000-4000-8000-000000000002"
    private val eventId = "00000000-0000-4000-8000-000000000003"
    private val allocationId = "00000000-0000-4000-8000-000000000004"
    private fun initial(): SyncState {
        val payload = ContentActions.save(null,"idea","Fictional garden","",1,ideaId,now)
        return SyncState(items = mapOf("idea:$ideaId" to Entity("idea",ideaId,payload)))
    }
    private fun linked(s:SyncState=initial()):SyncState = IdeaActions.createLinkedTask(s,ideaId,taskId,"Sketch one bed","Draw rectangle","One sketch",1,null,now)
    private fun draft(s:SyncState,minutes:Int=10,kind:String="personal"):TodayAllocationDraft {
        val baseline=DayAllocationBaseline.from(s,taskId,now)
        return TodayAllocationDraft(baseline.planId,kind,minutes,allocationId,baseline)
    }
    @Test fun linkedCreationAndTodayAreAtomicAndRetryDoesNotOverwrite() {
        val s=initial();val d=draft(s)
        val result=IdeaActions.createLinkedTask(s,ideaId,taskId,"Sketch one bed","Draw rectangle","One sketch",1,d,now)
        assertEquals(3,result.items.values.count {it.dirty})
        assertEquals(1,result.items.values.filter {it.dirty}.map {it.group}.distinct().size)
        assertEquals(3,StateMachine.prepare(result).pending!!.mutations.size)
        val reloaded=wire.decodeFromString<SyncState>(wire.encodeToString(result))
        assertEquals(reloaded,IdeaActions.createLinkedTask(reloaded,ideaId,taskId,"Sketch one bed","Draw rectangle","One sketch",1,d,now))
        result.items.values.forEach {PayloadValidation.validate(it.kind,it.id,it.payload!!)}
    }
    @Test fun linkedTaskRetryRejectsChangedDraftFieldsOrAllocation() {
        val initial=initial();val allocation=draft(initial)
        val saved=IdeaActions.createLinkedTask(initial,ideaId,taskId,"Sketch one bed","Draw rectangle","One sketch",1,allocation,now)
        for((title,next,done,q) in listOf(listOf("Edited title","Draw rectangle","One sketch","1"),listOf("Sketch one bed","Edited next","One sketch","1"),listOf("Sketch one bed","Draw rectangle","Edited done","1"),listOf("Sketch one bed","Draw rectangle","One sketch","2"))) {
            assertThrows(IllegalArgumentException::class.java) {IdeaActions.createLinkedTask(saved,ideaId,taskId,title,next,done,q.toInt(),allocation,now)}
        }
        for(changed in listOf(allocation.copy(minutes=11),allocation.copy(kind="check"),allocation.copy(planId="2026-10-31@UTC"))) {
            assertThrows(IllegalArgumentException::class.java) {IdeaActions.createLinkedTask(saved,ideaId,taskId,"Sketch one bed","Draw rectangle","One sketch",1,changed,now)}
        }
        assertThrows(IllegalArgumentException::class.java) {IdeaActions.createLinkedTask(linked(),ideaId,taskId,"Sketch one bed","Draw rectangle","One sketch",1,allocation,now)}
    }
    @Test fun blockerRetryRejectsChangedDetailsAllocationAndRemoteTitle() {
        val s=linked();val baseline=SmallStepBaseline.from(s.items.getValue("task:$taskId"));val allocation=draft(s,5)
        val saved=SmallStepActions.save(s,taskId,"One line","Line drawn",baseline,allocation,"tooLarge",eventId,now)
        assertEquals(saved,SmallStepActions.save(saved,taskId,"One line","Line drawn",baseline,allocation,"tooLarge",eventId,now.plusSeconds(60)))
        assertThrows(IllegalArgumentException::class.java) {SmallStepActions.save(saved,taskId,"Edited line","Line drawn",baseline,allocation,"tooLarge",eventId,now)}
        assertThrows(IllegalArgumentException::class.java) {SmallStepActions.save(saved,taskId,"One line","Edited stop",baseline,allocation,"tooLarge",eventId,now)}
        assertThrows(IllegalArgumentException::class.java) {SmallStepActions.save(saved,taskId,"One line","Line drawn",baseline,allocation.copy(minutes=10),"tooLarge",eventId,now)}
        val task=saved.items.getValue("task:$taskId")
        val updated=task.copy(payload=JsonObject(task.payload!!.toMutableMap().apply {put("content",JsonObject(task.content()!!.toMutableMap().apply {put("text",JsonPrimitive("Changed elsewhere"))}))}))
        val concurrent=saved.copy(items=saved.items+(task.key to updated))
        assertThrows(StaleContentEditException::class.java) {SmallStepActions.save(concurrent,taskId,"One line","Line drawn",baseline,allocation,"tooLarge",eventId,now)}
    }
    @Test fun archivedIdeaRejectsNewMutationsButAllowsExactRetriesAndRestoration() {
        val recorded=IdeaActions.appendEntry(linked(),ideaId,eventId,"Existing finding","finding",now)
        val archived=IdeaActions.saveContext(recorded,ideaId,"archived","",IdeaContextBaseline.from(recorded.items.getValue("idea:$ideaId")),now)
        assertThrows(IllegalArgumentException::class.java) {IdeaActions.appendEntry(archived,ideaId,allocationId,"New finding","finding",now)}
        assertThrows(IllegalArgumentException::class.java) {IdeaActions.createLinkedTask(archived,ideaId,allocationId,"New action","Start","Done",1,null,now)}
        assertEquals(archived,IdeaActions.appendEntry(archived,ideaId,eventId,"Existing finding","finding",now))
        assertEquals(archived,IdeaActions.createLinkedTask(archived,ideaId,taskId,"Sketch one bed","Draw rectangle","One sketch",1,null,now))
        val restored=IdeaActions.saveContext(archived,ideaId,"exploring","",IdeaContextBaseline.from(archived.items.getValue("idea:$ideaId")),now)
        val appended=IdeaActions.appendEntry(restored,ideaId,allocationId,"New finding","finding",now)
        assertEquals(2,appended.items.getValue("idea:$ideaId").content()!!.getValue("entries").jsonArray.size)
    }
    @Test fun appendMergesConcurrentEntriesAndStableIdDoesNotOverwrite() {
        val one=IdeaActions.appendEntry(initial(),ideaId,eventId,"First finding","finding",now)
        val two=IdeaActions.appendEntry(one,ideaId,allocationId,"Decision","decision",now)
        assertEquals(2,two.items.getValue("idea:$ideaId").content()!!.getValue("entries").jsonArray.size)
        assertEquals(two,IdeaActions.appendEntry(two,ideaId,eventId,"First finding","finding",now.plusSeconds(60)))
        assertThrows(IllegalArgumentException::class.java) {IdeaActions.appendEntry(two,ideaId,eventId,"Overwrite","note",now)}
    }
    @Test fun contextGuardIgnoresEntriesButRejectsChangedQuestion() {
        val s=initial();val base=IdeaContextBaseline.from(s.items.getValue("idea:$ideaId"))
        val concurrent=IdeaActions.appendEntry(s,ideaId,eventId,"Finding","finding",now)
        val changed=IdeaActions.saveContext(concurrent,ideaId,"exploring","Which bed?",base,now)
        assertEquals(1,changed.items.getValue("idea:$ideaId").content()!!.getValue("entries").jsonArray.size)
        assertThrows(StaleContentEditException::class.java) {IdeaActions.saveContext(changed,ideaId,"parked","Other",base,now)}
    }
    @Test fun smallStepPreservesEstimateAndConcurrentHistoryAndRejectsClosedTask() {
        var s=linked();val original=s.items.getValue("task:$taskId")
        val base=SmallStepBaseline.from(original)
        val content=original.content()!!.toMutableMap().apply {put("estimatedMinutes",JsonPrimitive(45));put("blockerEntries",JsonArray(emptyList()));put("unknown",JsonPrimitive(true))}
        s=s.copy(items=s.items+(original.key to original.copy(payload=JsonObject(original.payload!!.toMutableMap().apply {put("content",JsonObject(content))}))))
        val d=draft(s,5)
        val result=SmallStepActions.save(s,taskId,"Draw a line","One line",base,d,"tooLarge",eventId,now)
        val task=result.items.getValue(original.key)
        assertEquals(JsonPrimitive(45),task.content()!!["estimatedMinutes"])
        assertEquals(JsonPrimitive(true),task.content()!!["unknown"])
        assertFalse(task.completed());assertNull(task.content()!!["progressSessions"])
        assertEquals(1,task.content()!!.getValue("blockerEntries").jsonArray.size)
        assertEquals(result,SmallStepActions.save(result,taskId,"Draw a line","One line",base,d,"tooLarge",eventId,now))
        for(closed in listOf(task.copy(deleted=true,payload=null),task.copy(payload=JsonObject(task.payload!!.toMutableMap().apply {put("lifecycle",JsonPrimitive("archived"))})),task.copy(payload=JsonObject(task.payload!!.toMutableMap().apply {put("content",JsonObject(task.content()!!.toMutableMap().apply {put("completed",JsonPrimitive(true))}))})))) {
            val state=result.copy(items=result.items+(closed.key to closed))
            assertThrows(IllegalArgumentException::class.java) {SmallStepActions.save(state,taskId,"x","y",base,null,null,null,now)}
            assertThrows(IllegalArgumentException::class.java) {DayPlanActions.allocate(state,taskId,d,now)}
        }
    }
    @Test fun allocationPreservesCalendarUnknownsAndOtherEntriesAndGuardsOnlyRelevantFields() {
        var s=linked();val id=ContentActions.planId(s,now)
        val plan=Entity("day_plan",id,wire.parseToJsonElement("""{"future":1,"content":{"day_key":"${id.substringBefore('@')}","time_zone":"${id.substringAfter('@')}","ordered_task_ids":["$taskId"],"task_plans":[{"task_id":"$taskId","scheduled_at":"2026-10-30T01:00:00Z","scheduled_minutes":30,"schedule_reminder_lead_minutes":5,"future":true},{"task_id":"$ideaId","future":2}]}}""").jsonObject)
        s=s.copy(items=s.items+(plan.key to plan));val d=draft(s,7,"check")
        val result=DayPlanActions.allocate(s,taskId,d,now)
        val p=result.items.getValue(plan.key).content()!!.getValue("task_plans").jsonArray
        assertEquals(JsonPrimitive(7),p[0].jsonObject["scheduled_minutes"])
        assertEquals(JsonPrimitive(5),p[0].jsonObject["schedule_reminder_lead_minutes"])
        assertEquals(JsonPrimitive(true),p[0].jsonObject["future"])
        assertEquals(plan.content()!!.getValue("task_plans").jsonArray[1],p[1])
        assertEquals(7L,TodayLoad.calculate(result,now).checkMinutes)
        assertEquals(0L,TodayLoad.calculate(result,now).personalMinutes)
        assertThrows(StaleContentEditException::class.java) {DayPlanActions.allocate(result,taskId,d.copy(minutes=8),now)}
        assertThrows(IllegalArgumentException::class.java) {DayPlanActions.allocate(s,taskId,d.copy(minutes=1441),now)}
        assertThrows(StaleContentEditException::class.java) {DayPlanActions.allocate(s,taskId,d,now.plusSeconds(172800))}
    }
    @Test fun todayLoadUsesPlanningZoneAndNeverTotalEstimate() {
        val s=linked();val task=s.items.getValue("task:$taskId")
        val changed=task.copy(payload=JsonObject(task.payload!!.toMutableMap().apply {put("content",JsonObject(task.content()!!.toMutableMap().apply {put("estimatedMinutes",JsonPrimitive(Int.MAX_VALUE))}))}))
        val plan=DayPlanActions.allocate(s.copy(items=s.items+(task.key to changed)),taskId,draft(s,5),now)
        assertEquals(5L,TodayLoad.calculate(plan,now).personalMinutes)
        assertEquals(0L,TodayLoad.calculate(plan,now.plusSeconds(172800)).personalMinutes)
        val zoneState=s.copy(items=s.items+("profile:profile" to Entity("profile","profile",wire.parseToJsonElement("""{"content":{"planningTimeZone":"America/Los_Angeles"}}""").jsonObject)))
        assertEquals("2026-10-29@America/Los_Angeles",DayAllocationBaseline.from(zoneState,taskId,now).planId)
    }
    @Test fun unrelatedPlanAndTaskChangesMergeButRelevantFieldsDoNot() {
        val s=linked();val base=SmallStepBaseline.from(s.items.getValue("task:$taskId"))
        val task=s.items.getValue("task:$taskId")
        val changed=task.copy(payload=JsonObject(task.payload!!.toMutableMap().apply {put("content",JsonObject(task.content()!!.toMutableMap().apply {put("text",JsonPrimitive("Changed title"))}))}))
        assertThrows(StaleContentEditException::class.java) {SmallStepActions.save(s.copy(items=s.items+(task.key to changed)),taskId,"Draw rectangle","One sketch",base,null,null,null,now)}
        val d=draft(s)
        val result=DayPlanActions.allocate(s,taskId,d,now)
        val plan=result.items.getValue("day_plan:${d.planId}")
        val concurrent=plan.copy(payload=JsonObject(plan.payload!!.toMutableMap().apply {put("content",JsonObject(plan.content()!!.toMutableMap().apply {put("future",JsonPrimitive(true))}))}))
        val next=result.copy(items=result.items+(plan.key to concurrent))
        val baseline=DayAllocationBaseline.from(next,taskId,now)
        val updated=DayPlanActions.allocate(next,taskId,d.copy(minutes=8,expected=baseline),now)
        assertEquals(JsonPrimitive(true),updated.items.getValue(plan.key).content()!!["future"])
    }
    @Test fun dayLoadHandlesUnknownAndLongTotalsWithoutEstimateFallback() {
        val s=linked();val d=draft(s)
        val planned=DayPlanActions.allocate(s,taskId,d,now)
        val plan=planned.items.getValue("day_plan:${d.planId}")
        val other="00000000-0000-4000-8000-000000000005"
        val unknown="00000000-0000-4000-8000-000000000006"
        val ids=listOf(taskId,other,unknown)
        val content=plan.content()!!.toMutableMap().apply {
            put("ordered_task_ids",JsonArray(ids.map {JsonPrimitive(it)}))
            put("task_plans",JsonArray(ids.take(2).map {id->buildJsonObject {put("task_id",id);put("allocation",buildJsonObject {put("id",uid());put("kind","personal");put("planned_minutes",Int.MAX_VALUE)})}}))
        }
        val items=planned.items.toMutableMap();items[plan.key]=plan.copy(payload=buildJsonObject {put("content",JsonObject(content))})
        for(id in listOf(other,unknown)) items["task:$id"]=Entity("task",id,ContentActions.save(null,"task","Fictional extra","",1,id,now,estimatedMinutes=999))
        val load=TodayLoad.calculate(planned.copy(items=items),now)
        assertEquals(4294967294L,load.personalMinutes);assertEquals(1,load.unplannedCount)
    }
    @Test fun allocationOnlyResizesCalendarOnPlanningDayInPlanningZone() {
        // UTC Oct 30 is still Oct 29 in the account's planning zone.
        val taskState=linked()
        val profile=Entity("profile","profile",wire.parseToJsonElement("""{"content":{"planningTimeZone":"America/Los_Angeles"}}""").jsonObject)
        val state=taskState.copy(items=taskState.items+(profile.key to profile))
        val id=ContentActions.planId(state,now)
        assertEquals("2026-10-29@America/Los_Angeles",id)
        for((start,isCurrentDay) in listOf("2026-10-30T01:00:00Z" to true,"2026-10-30T08:00:00Z" to false)) {
            val entry=buildJsonObject {put("task_id",taskId);put("scheduled_at",start);put("scheduled_minutes",120);put("schedule_reminder_lead_minutes",15);put("futureCalendarField",true)}
            val plan=Entity("day_plan",id,buildJsonObject {put("content",buildJsonObject {put("day_key",id.substringBefore('@'));put("time_zone",id.substringAfter('@'));put("ordered_task_ids",JsonArray(listOf(JsonPrimitive(taskId))));put("task_plans",JsonArray(listOf(entry)))})})
            val original=state.copy(items=state.items+(plan.key to plan))
            val baseline=DayAllocationBaseline.from(original,taskId,now)
            assertEquals(isCurrentDay,baseline.hasCurrentDaySchedule)
            val d=TodayAllocationDraft(id,"check",8,allocationId,baseline)
            val changed=DayPlanActions.allocate(original,taskId,d,now)
            val updated=changed.items.getValue(plan.key).content()!!.getValue("task_plans").jsonArray.single().jsonObject
            assertEquals(JsonPrimitive(if(isCurrentDay) 8 else 120),updated["scheduled_minutes"])
            assertEquals(JsonPrimitive(start),updated["scheduled_at"])
            assertEquals(JsonPrimitive(15),updated["schedule_reminder_lead_minutes"])
            assertEquals(JsonPrimitive(true),updated["futureCalendarField"])
            assertEquals(8L,TodayLoad.calculate(changed,now).checkMinutes)
            assertEquals(changed,DayPlanActions.allocate(changed,taskId,d,now))
            if(!isCurrentDay) {
                PayloadValidation.validate(plan.kind,plan.id,changed.items.getValue(plan.key).payload!!)
                java.io.File("/private/tmp/pare-workflow-future-calendar-wire.json").writeText(JsonObject(mapOf(plan.key to changed.items.getValue(plan.key).payload!!)).toString())
            }
        }
    }
    @Test fun fixtureIncludesIdeaHistoryTaskBlockerAndDayAllocation() {
        var state=IdeaActions.appendEntry(initial(),ideaId,eventId,"Soil needs checking","finding",now)
        state=IdeaActions.saveContext(state,ideaId,"exploring","Which herbs fit?",IdeaContextBaseline.from(state.items.getValue("idea:$ideaId")),now)
        state=linked(state)
        state=SmallStepActions.save(state,taskId,"Draw one line","One line",SmallStepBaseline.from(state.items.getValue("task:$taskId")),draft(state,5,"check"),"tooLarge",allocationId,now)
        state.items.values.forEach {PayloadValidation.validate(it.kind,it.id,it.payload!!)}
        java.io.File("/private/tmp/pare-workflow-wire.json").writeText(JsonObject(state.items.mapValues {it.value.payload!!}).toString())
    }
    @Test fun projectionsRetainCompletedArchivedAndMissingLinks() {
        val s=linked();val task=s.items.getValue("task:$taskId")
        val archived=task.copy(payload=JsonObject(task.payload!!.toMutableMap().apply {put("lifecycle",JsonPrimitive("archived"));put("content",JsonObject(task.content()!!.toMutableMap().apply {put("completed",JsonPrimitive(true))}))}))
        val projection=IdeaLinkedTasks.project(s.copy(items=s.items+(task.key to archived)),s.items.getValue("idea:$ideaId"))
        assertTrue(projection.single().completed);assertTrue(projection.single().archived)
        assertTrue(IdeaLinkedTasks.project(initial(),s.items.getValue("idea:$ideaId")).single().missing)
    }
}
