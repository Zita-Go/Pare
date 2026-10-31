package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Isolated fictional fixtures and injected transport only; never invokes a real provider. */
class PlanningAITest {
    private val now=Instant.parse("2099-05-20T08:00:00Z")
    private val day="2099-05-20@UTC"
    private val task="00000000-0000-4000-8000-000000000001"
    private val second="00000000-0000-4000-8000-000000000002"
    private val idea="00000000-0000-4000-8000-000000000003"
    private val inbox="00000000-0000-4000-8000-000000000004"
    private val goal="00000000-0000-4000-8000-000000000005"
    private fun state():SyncState {
        val first=Entity("task",task,ContentActions.save(null,"task","Fictional garden sketch","Draw a bed",1,task,now,nextAction="Find paper"))
        val other=Entity("task",second,ContentActions.save(null,"task","Fictional appointment","Private unselected text",0,second,now))
        val source=Entity("idea",idea,ContentActions.save(null,"idea","Garden idea","Private idea background",1,idea,now).let {p->JsonObject(p.toMutableMap().apply {
            put("content",JsonObject(p.getValue("content").jsonObject.toMutableMap().apply {put("linkedTaskIDs",JsonArray(listOf(JsonPrimitive(task))))}))
        })})
        val entry=Entity("inbox",inbox,ContentActions.save(null,"inbox","Original fictional inbox text","",1,inbox,now))
        val g=Entity("goal",goal,buildJsonObject {put("content",buildJsonObject {put("id",goal);put("title","Fictional gardening goal");put("motivation","Grow something");put("createdAt",now.toString());put("updatedAt",now.toString());put("status","active");put("taskIDs",JsonArray(listOf(JsonPrimitive(task))))})})
        val plan=Entity("day_plan",day,buildJsonObject {put("content",buildJsonObject {
            put("day_key","2099-05-20");put("time_zone","UTC");put("ordered_task_ids",JsonArray(listOf(JsonPrimitive(second))))
            put("task_plans",buildJsonArray {add(buildJsonObject {put("task_id",second);put("scheduled_at",now.toString());put("scheduled_minutes",10)
                put("allocation",buildJsonObject {put("id",uid());put("kind","check");put("planned_minutes",10)})
            })})
        })})
        return SyncState(device="fictional-device",items=listOf(first,other,source,entry,g,plan).associateBy {it.key})
    }
    private fun request(s:SyncState=state(),kind:PlanningAIKind=PlanningAIKind.DAY,subject:String?=null,extra:MobileAIOptions=MobileAIOptions())=
        PlanningAIContext.prepare(s,kind,subject,day,PlanningAIOptions(if(kind in setOf(PlanningAIKind.DAY,PlanningAIKind.GOAL)) listOf(task) else emptyList(),30,"一般",extra),emptyList(),now)
    private fun allocation(input:PlanningAIRequest)=PlanningAIProposal(input.id,"Use the selected task",listOf(PlanningAIAction(uid(),"allocate",task,minutes=15,reason="Selected sketch")))
    private fun envelope(p:String)=buildJsonObject {put("choices",buildJsonArray {add(buildJsonObject {put("finish_reason","stop");put("message",buildJsonObject {put("content",p)})})})}.toString()
    private fun modify(s:SyncState,key:String,field:String,value:JsonElement):SyncState {
        val e=s.items.getValue(key)
        return StateMachine.edit(s,e.kind,e.id,JsonObject(e.payload!!.toMutableMap().apply {put("content",JsonObject(e.content()!!.toMutableMap().apply {put(field,value)}))}))
    }
    @Test fun defaultPayloadContainsOnlySelectedTasksAndAnonymousOtherLoad() {
        val input=request()
        assertTrue(input.preview.contains("Fictional garden sketch"));assertFalse(input.preview.contains("Private unselected text"));assertFalse(input.preview.contains("Private idea background"));assertFalse(input.preview.contains("Fictional gardening goal"))
        assertEquals(10,input.evidence.getValue("otherCommittedMinutes").jsonPrimitive.int)
        assertTrue(request(extra=MobileAIOptions(ideas=true,goals=true)).preview.contains("Private idea background"))
        assertTrue(request(extra=MobileAIOptions(goals=true)).preview.contains("Fictional gardening goal"))
    }
    @Test fun transportUsesExactPreviewRecordsKindAndRedactsSecret() {
        val input=request();val p=allocation(input);var calls=0;var trace:MobileAITrace?=null
        val client=MobileAIClient(MobileAITransport {_,_,body->calls++
            val messages=wire.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
            assertEquals(input.preview,messages[1].jsonObject.getValue("content").jsonPrimitive.content)
            MobileAIResponse(200,envelope(wire.encodeToString(p.copy(summary="Uses fake-secret"))))
        })
        client.generate(MobileAIConfig("https://model.invalid/v1/chat/completions","fictional-model"),"fake-secret",input) {trace=it}
        assertEquals(1,calls);assertEquals("DAY",trace!!.kind);assertEquals(listOf(task),trace!!.taskIds);assertFalse(trace!!.output.contains("fake-secret"))
    }
    @Test fun rejectsMalformedNestedDuplicateUnknownFieldsWrongReferencesAndBudget() {
        val input=request();val p=allocation(input);val raw=wire.encodeToString(p)
        val duplicate=raw.replace("\"minutes\":15","\"minutes\":15,\"minutes\":15")
        val unknown=raw.replace("\"minutes\":15","\"minutes\":15,\"shell\":\"execute\"")
        val badNumber=raw.replace("\"minutes\":15","\"minutes\":\"15\"")
        for(json in listOf(duplicate,unknown,badNumber,wire.encodeToString(p.copy(requestId=uid())),wire.encodeToString(p.copy(actions=listOf(p.actions[0].copy(sourceId=second)))),wire.encodeToString(p.copy(actions=listOf(p.actions[0].copy(minutes=21))))))
            assertThrows(MobileAIException::class.java) {PlanningAIWire.decode(envelope(json),input)}
    }
    @Test fun adoptionPreservesOtherAppointmentOtherDaysAndUndoIsConditional() {
        val s=state();val input=request(s);val p=allocation(input)
        val saved=PlanningReviewActions.adopt(s,input,p,p.actions.map {it.id}.toSet(),now)
        assertEquals(PlanningAIContext.entry(s,day,second),PlanningAIContext.entry(saved,day,second))
        assertEquals(listOf(second,task),saved.items["day_plan:$day"]?.content().planningIds("ordered_task_ids"))
        val receipt=PlanningAIUndo.capture(s,saved);val restored=receipt.apply(saved)
        assertEquals(s.items["day_plan:$day"]?.payload,restored.items["day_plan:$day"]?.payload)
        val changed=modify(saved,"day_plan:$day","daily_win_task_id",JsonPrimitive(task))
        assertThrows(MobileAIException::class.java) {receipt.apply(changed)}
        assertThrows(MobileAIException::class.java) {PlanningReviewActions.adopt(modify(s,"task:$task","note",JsonPrimitive("Changed")),input,p,p.actions.map {it.id}.toSet(),now)}
    }
    @Test fun refusesPastDatesUnknownLoadAndCheckModeSwitch() {
        val s=state()
        assertThrows(MobileAIException::class.java) {PlanningAIContext.prepare(s,PlanningAIKind.DAY,null,"2099-05-19@UTC",PlanningAIOptions(listOf(task)),emptyList(),now)}
        val input=PlanningAIContext.prepare(s,PlanningAIKind.DAY,null,day,PlanningAIOptions(listOf(second)),emptyList(),now)
        val p=PlanningAIProposal(input.id,"Keep appointment",listOf(PlanningAIAction(uid(),"allocate",second,minutes=10,allocationKind="personal",reason="bad switch")))
        assertThrows(MobileAIException::class.java) {p.validate(input)}
        assertThrows(MobileAIException::class.java) {PlanningReviewActions.adopt(s,request(s),allocation(request(s)),emptySet(),now)}
    }
    @Test fun ideaReviewAppendsAndCreatesLinkedTaskInOneGroupWithStableIds() {
        val s=state();val input=request(s,PlanningAIKind.IDEA,idea)
        val finding=PlanningAIAction(uid(),"finding",idea,text="Fictional soil observation",reason="Idea background")
        val linked=PlanningAIAction(uid(),"linked_task",idea,title="Sketch one bed",nextAction="Draw one line",stopCriterion="One line exists",minutes=3,reason="Idea context")
        val p=PlanningAIProposal(input.id,"Explore gently",listOf(finding,linked));val saved=PlanningReviewActions.adopt(s,input,p,setOf(finding.id,linked.id),now)
        val target=PlanningReviewActions.targetId(input,linked);assertEquals(listOf(task,target),saved.items["idea:$idea"]?.content().planningIds("linkedTaskIDs"))
        assertEquals(saved.items["idea:$idea"]?.group,saved.items["task:$target"]?.group)
        assertEquals(s.items["day_plan:$day"],saved.items["day_plan:$day"])
        assertThrows(MobileAIException::class.java) {PlanningReviewActions.adopt(saved,input,p,setOf(finding.id,linked.id),now)}
        val undone=PlanningAIUndo.capture(s,saved).apply(saved);assertTrue(undone.items.getValue("task:$target").deleted);assertEquals(s.items["idea:$idea"]?.payload,undone.items["idea:$idea"]?.payload)
    }
    @Test fun inboxClassificationPreservesOriginalAndCannotDuplicate() {
        val s=state();val input=request(s,PlanningAIKind.INBOX,inbox)
        val a=PlanningAIAction(uid(),"classify_idea",inbox,reason="Needs exploration")
        val p=PlanningAIProposal(input.id,"Save for exploration",listOf(a));val saved=PlanningReviewActions.adopt(s,input,p,setOf(a.id),now)
        assertTrue(saved.items.getValue("inbox:$inbox").deleted)
        val target=PlanningReviewActions.targetId(input,a);assertEquals("Original fictional inbox text",saved.items.getValue("idea:$target").title())
        assertEquals(saved.items["inbox:$inbox"]?.group,saved.items["idea:$target"]?.group)
        assertThrows(MobileAIException::class.java) {PlanningReviewActions.adopt(saved,input,p,setOf(a.id),now)}
        val restored=PlanningAIUndo.capture(s,saved).apply(saved);assertFalse(restored.items.getValue("inbox:$inbox").deleted)
    }
    @Test fun goalOnlyUpdatesSelectedLinkedNextStepAndKeepsPlan() {
        val s=state();val input=request(s,PlanningAIKind.GOAL,goal)
        val a=PlanningAIAction(uid(),"next_step",task,nextAction="Draw line",stopCriterion="One line",minutes=3,reason="Gardening goal")
        val p=PlanningAIProposal(input.id,"Small action",listOf(a));val saved=PlanningReviewActions.adopt(s,input,p,setOf(a.id),now)
        assertEquals("Draw line",saved.items["task:$task"]?.content().planningText("nextAction"));assertEquals(s.items["day_plan:$day"],saved.items["day_plan:$day"])
        assertThrows(MobileAIException::class.java) {p.copy(actions=listOf(a.copy(sourceId=second))).validate(input)}
    }
    @Test fun oldTracesDecodeAndHistoryDoesNotCrossWorkflowScope() {
        val raw="""{"id":"old","taskId":"$task","at":"${now}","input":"x","output":"old private nextstep","success":true}"""
        val trace=wire.decodeFromString<MobileAITrace>(raw);assertEquals("NEXT_STEP",trace.kind)
        val input=PlanningAIContext.prepare(state(),PlanningAIKind.DAY,null,day,PlanningAIOptions(listOf(task),extra=MobileAIOptions(history=true)),listOf(trace),now)
        assertFalse(input.preview.contains("old private nextstep"))
    }
    @Test fun sourceOwnerDeviceAndUnrelatedDateCannotChangeDuringAdoption() {
        val s=state();val input=request(s);val p=allocation(input);val ids=p.actions.map {it.id}.toSet()
        assertThrows(MobileAIException::class.java) {PlanningReviewActions.adopt(s.copy(owner="other-owner"),input,p,ids,now)}
        assertThrows(MobileAIException::class.java) {PlanningReviewActions.adopt(s.copy(device="other-device"),input,p,ids,now)}
        val future="2099-05-21@UTC"
        val plan=Entity("day_plan",future,buildJsonObject {put("content",buildJsonObject {put("day_key","2099-05-21");put("time_zone","UTC");put("ordered_task_ids",JsonArray(emptyList()));put("task_plans",JsonArray(emptyList()))})})
        val before=s.copy(items=s.items+(plan.key to plan));val fresh=request(before)
        val proposed=allocation(fresh);val saved=PlanningReviewActions.adopt(before,fresh,proposed,proposed.actions.map {it.id}.toSet(),now)
        assertEquals(plan,saved.items[plan.key])
    }
    @Test fun partialAdoptionCannotHideRetainedMinutesAndNegativeDurationsRejected() {
        var s=state()
        val plan=s.items.getValue("day_plan:$day");val c=plan.content()!!.toMutableMap()
        c["ordered_task_ids"]=JsonArray(listOf(JsonPrimitive(second),JsonPrimitive(task)))
        c["task_plans"]=JsonArray(c.getValue("task_plans").jsonArray+buildJsonObject {put("task_id",task);put("allocation",buildJsonObject {put("id",uid());put("kind","personal");put("planned_minutes",25)})})
        s=StateMachine.edit(s,"day_plan",day,buildJsonObject {put("content",JsonObject(c))})
        val input=PlanningAIContext.prepare(s,PlanningAIKind.DAY,null,day,PlanningAIOptions(listOf(task,second)),emptyList(),now)
        val a=PlanningAIAction(uid(),"allocate",task,minutes=15,reason="Reduce sketch to fit")
        val b=PlanningAIAction(uid(),"allocate",second,minutes=10,allocationKind="check",reason="Keep appointment")
        val p=PlanningAIProposal(input.id,"Fits when both selected",listOf(a,b))
        p.validate(input)
        assertThrows(MobileAIException::class.java) {PlanningReviewActions.adopt(s,input,p,setOf(b.id),now)}
        assertThrows(MobileAIException::class.java) {p.copy(actions=listOf(b)).validate(input)}
        val ideaInput=request(s,PlanningAIKind.IDEA,idea)
        assertThrows(MobileAIException::class.java) {PlanningAIProposal(ideaInput.id,"Invalid negative",listOf(PlanningAIAction(uid(),"finding",idea,text="Observation",minutes=-10,reason="Context"))).validate(ideaInput)}
    }

    @Test fun legacyAndCrossMidnightAppointmentsConsumeCapacityWithoutLeakingContent() {
        val base=state();val previous="2099-05-19@UTC"
        val source=base.items.getValue("day_plan:$day")
        val content=source.content()!!.toMutableMap().apply {put("day_key",JsonPrimitive("2099-05-19"))}
        val legacy=Entity("day_plan",previous,buildJsonObject {put("content",JsonObject(content))})
        val s=base.copy(items=base.items.minus("day_plan:$day")+(legacy.key to legacy))
        val input=request(s)
        assertEquals(10,input.evidence.getValue("otherCommittedMinutes").jsonPrimitive.int)
        assertFalse(input.preview.contains("Private unselected text"))
        assertTrue("day_plan:$previous" in input.baselines)
        val p=allocation(input);val saved=PlanningReviewActions.adopt(s,input,p,p.actions.map {it.id}.toSet(),now)
        assertEquals(legacy,saved.items[legacy.key])
        assertThrows(MobileAIException::class.java) {PlanningAIContext.prepare(s,PlanningAIKind.DAY,null,day,PlanningAIOptions(listOf(second)),emptyList(),now)}
        val entry=buildJsonObject {put("task_id",second);put("scheduled_at","2099-05-19T23:55:00Z");put("scheduled_minutes",20)}
        val cross=modify(s,legacy.key,"task_plans",JsonArray(listOf(entry)))
        assertEquals(15,request(cross).evidence.getValue("otherCommittedMinutes").jsonPrimitive.int)
    }
    @Test fun inboxPreservesUnknownEnvelopeAndContentFields() {
        val base=state();val e=base.items.getValue("inbox:$inbox")
        val payload=JsonObject(e.payload!!.toMutableMap().apply {put("futureEnvelope",JsonPrimitive("keep"));put("content",JsonObject(e.content()!!.toMutableMap().apply {put("futureContent",JsonPrimitive("keep content"))}))})
        val s=base.copy(items=base.items+(e.key to e.copy(payload=payload)))
        val input=request(s,PlanningAIKind.INBOX,inbox);val a=PlanningAIAction(uid(),"classify_task",inbox,reason="Ready to act")
        val p=PlanningAIProposal(input.id,"Convert",listOf(a));val saved=PlanningReviewActions.adopt(s,input,p,setOf(a.id),now)
        val created=saved.items.getValue("task:${PlanningReviewActions.targetId(input,a)}")
        assertEquals(JsonPrimitive("keep"),created.payload?.get("futureEnvelope"));assertEquals(JsonPrimitive("keep content"),created.content()?.get("futureContent"))
    }

    @Test fun expiredPreferenceInvalidatesReviewedContextBeforeSendOrAdoption() {
        val base=state()
        val profile=Entity("profile","profile",buildJsonObject {put("content",buildJsonObject {
            put("planningTimeZone","UTC")
            put("confirmedPreferences",buildJsonArray {add(buildJsonObject {put("id",uid());put("text","Fictional short starts");put("expiresAt",now.plusSeconds(60).toString())})})
        })})
        val s=base.copy(items=base.items+(profile.key to profile));val input=request(s,extra=MobileAIOptions(preferences=true))
        assertTrue(input.preview.contains("Fictional short starts"))
        assertThrows(MobileAIException::class.java) {PlanningAIContext.requireFresh(s,input,now.plusSeconds(120))}
    }

    @Test fun inheritedMutationGroupCannotExceedCountOrByteLimit() {
        val initial=state();val source=initial.items.getValue("idea:$idea").copy(dirty=true,group="fictional-large-group")
        val peers=(1..100).map {index->
            val id=java.util.UUID.nameUUIDFromBytes("fictional-peer-$index".toByteArray()).toString()
            Entity("inbox",id,ContentActions.save(null,"inbox","Fictional peer $index","",1,id,now),dirty=true,group=source.group)
        }
        val many=initial.copy(items=initial.items+(source.key to source)+peers.associateBy {it.key})
        fun append(s:SyncState) {
            val input=request(s,PlanningAIKind.IDEA,idea)
            val a=PlanningAIAction(uid(),"finding",idea,text="Fictional observation",reason="Selected idea")
            PlanningReviewActions.adopt(s,input,PlanningAIProposal(input.id,"Append",listOf(a)),setOf(a.id),now)
        }
        assertThrows(MobileAIException::class.java) {append(many)}
        val huge=peers.first().let {e->e.copy(payload=JsonObject(e.payload!!.toMutableMap().apply {put("futureExtension",JsonPrimitive("x".repeat(1_048_576)))}))}
        val oversized=initial.copy(items=initial.items+(source.key to source)+(huge.key to huge))
        assertThrows(MobileAIException::class.java) {append(oversized)}
        assertEquals(source.payload,many.items[source.key]?.payload)
    }

}
