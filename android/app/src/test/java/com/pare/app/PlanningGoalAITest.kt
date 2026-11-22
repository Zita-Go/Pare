package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Fictional goals and an injected provider; no external requests or credentials. */
class PlanningGoalAITest {
    private val now=Instant.parse("2099-05-20T08:00:00Z")
    private val goalId="00000000-0000-4000-8000-000000000111"
    private val taskId="00000000-0000-4000-8000-000000000112"
    private val day="2099-05-20@UTC"
    private fun state(linked:Boolean=false):SyncState {
        val goal=Entity("goal",goalId,buildJsonObject {put("futureEnvelope","preserve");put("content",buildJsonObject {
            put("id",goalId);put("title","Fictional gardening goal");put("motivation","Try one manageable experiment");put("status","active");put("createdAt",now.toString());put("updatedAt",now.toString());put("futureGoalField","keep")
            put("taskIDs",JsonArray(if(linked) listOf(JsonPrimitive(taskId)) else emptyList()))
        })})
        val task=Entity("task",taskId,ContentActions.save(null,"task","Private existing task","Private existing note",1,taskId,now))
        return SyncState(device="fictional-goal-ai",items=if(linked) mapOf(goal.key to goal,task.key to task) else mapOf(goal.key to goal))
    }
    private fun request(s:SyncState=state(),ids:List<String> = emptyList(),capacity:Int=30)=PlanningAIContext.prepare(s,PlanningAIKind.GOAL,goalId,day,PlanningAIOptions(ids,capacity),emptyList(),now)
    private fun create(input:PlanningAIRequest)=PlanningAIAction("00000000-0000-4000-8000-000000000113","goal_task",goalId,title="Sketch one garden bed",nextAction="Draw one line on paper",stopCriterion="One line is visible",minutes=3,quadrant=1,reason="Uses the authorized gardening goal")
    private fun proposal(input:PlanningAIRequest)=PlanningAIProposal(input.id,"Start with one concrete action",listOf(create(input)))
    private fun change(s:SyncState,key:String,field:String,value:JsonElement):SyncState {
        val e=s.items.getValue(key)
        return StateMachine.edit(s,e.kind,e.id,JsonObject(e.payload!!.toMutableMap().apply {put("content",JsonObject(e.content()!!.toMutableMap().apply {put(field,value)}))}))
    }
    @Test fun goalWithoutTasksCanPreviewAndDefaultContextIsOnlyTheAuthorizedGoal() {
        val empty=request()
        assertTrue(empty.preview.contains("Fictional gardening goal"));assertTrue(empty.options.taskIds.isEmpty())
        val linked=state(linked=true)
        val options=PlanningAIContext.defaults(linked,PlanningAIKind.GOAL,goalId,day)
        assertTrue(options.taskIds.isEmpty())
        val input=PlanningAIContext.prepare(linked,PlanningAIKind.GOAL,goalId,day,options,emptyList(),now)
        assertFalse(input.preview.contains("Private existing task"));assertFalse(input.preview.contains("Private existing note"))
        assertTrue(request(linked,listOf(taskId)).preview.contains("Private existing task"))
    }
    @Test fun fakeProviderProposalCreatesOneStableTaskAndAtomicallyLinksWithoutScheduling() {
        val s=state();val input=request(s);val p=proposal(input);var calls=0
        val client=MobileAIClient(MobileAITransport {_,_,_->calls++;MobileAIResponse(200,buildJsonObject {put("choices",buildJsonArray {add(buildJsonObject {
            put("finish_reason","stop");put("message",buildJsonObject {put("content",wire.encodeToString(p))})
        })})}.toString())})
        val returned=client.generate(MobileAIConfig("https://model.invalid/v1/chat/completions","fictional-model"),"fictional-unusable-key",input)
        assertEquals(1,calls);assertEquals(p,returned)
        val next=PlanningReviewActions.adopt(s,input,returned,setOf(p.actions.single().id),now)
        val target=PlanningReviewActions.targetId(input,p.actions.single())
        assertEquals(listOf(target),next.items["goal:$goalId"]?.content().planningIds("taskIDs"))
        assertEquals("active",next.items["goal:$goalId"]?.content().planningText("status"))
        assertEquals(JsonPrimitive("keep"),next.items["goal:$goalId"]?.content()?.get("futureGoalField"))
        assertEquals(JsonPrimitive("preserve"),next.items["goal:$goalId"]?.payload?.get("futureEnvelope"))
        assertEquals(next.items["goal:$goalId"]?.group,next.items["task:$target"]?.group)
        assertEquals("Draw one line on paper",next.items["task:$target"]?.content().planningText("nextAction"))
        assertEquals("One line is visible",next.items["task:$target"]?.content().planningText("definitionOfDone"))
        assertFalse(next.items.keys.any {it.startsWith("day_plan:")})
        assertEquals(1,next.items.values.count {it.kind=="task"})
        assertThrows(MobileAIException::class.java) {PlanningReviewActions.adopt(next,input,p,setOf(p.actions.single().id),now)}
        next.items.values.forEach {PayloadValidation.validate(it.kind,it.id,it.payload!!)}
    }
    @Test fun oneNewActionMayAccompanySelectedNextStepButCannotExceedBudgetOrCreateRoadmap() {
        val input=request(state(linked=true),listOf(taskId),capacity=5)
        val new=create(input)
        val existing=PlanningAIAction(uid(),"next_step",taskId,nextAction="Open existing sketch",stopCriterion="File open",minutes=2,reason="Selected existing task")
        PlanningAIProposal(input.id,"Two small starts",listOf(new,existing)).validate(input)
        assertThrows(MobileAIException::class.java) {PlanningAIProposal(input.id,"Too much",listOf(new,existing.copy(minutes=3))).validate(input)}
        assertThrows(MobileAIException::class.java) {proposal(input).copy(actions=listOf(new,new.copy(id=uid()))).validate(input)}
        assertThrows(MobileAIException::class.java) {proposal(input).copy(actions=listOf(new.copy(sourceId=taskId))).validate(input)}
        assertThrows(MobileAIException::class.java) {proposal(input).copy(actions=listOf(new.copy(stopCriterion=""))).validate(input)}
    }
    @Test fun staleGoalBlocksAdoptionAndUndoProtectsLaterEditsAndExternalLinks() {
        val s=state();val input=request(s);val p=proposal(input);val selected=setOf(p.actions.single().id)
        assertThrows(MobileAIException::class.java) {PlanningReviewActions.adopt(change(s,"goal:$goalId","motivation",JsonPrimitive("Later motivation")),input,p,selected,now)}
        val next=PlanningReviewActions.adopt(s,input,p,selected,now);val target=PlanningReviewActions.targetId(input,p.actions.single())
        val receipt=PlanningAIUndo.capture(s,next)
        assertThrows(MobileAIException::class.java) {receipt.apply(change(next,"task:$target","note",JsonPrimitive("Later edit")))}
        val second=next.items.getValue("goal:$goalId").let {e->e.copy(id=taskId,payload=JsonObject(e.payload!!.toMutableMap().apply {put("content",JsonObject(e.content()!!.toMutableMap().apply {put("id",JsonPrimitive(taskId))}))}))}
        assertThrows(MobileAIException::class.java) {receipt.apply(next.copy(items=next.items+(second.key to second)))}
        val restored=receipt.apply(next)
        assertEquals(s.items["goal:$goalId"]?.payload,restored.items["goal:$goalId"]?.payload)
        assertTrue(restored.items.getValue("task:$target").deleted)
    }
    @Test fun goalCreationStillHonorsInheritedAtomicGroupByteLimit() {
        val s=state();val goal=s.items.getValue("goal:$goalId").copy(dirty=true,group="fictional-oversize")
        val peer=Entity("task",taskId,ContentActions.save(null,"task","Fictional large peer","x".repeat(1_048_576),1,taskId,now),dirty=true,group=goal.group)
        val current=s.copy(items=s.items+(goal.key to goal)+(peer.key to peer));val input=request(current);val p=proposal(input)
        assertThrows(MobileAIException::class.java) {PlanningReviewActions.adopt(current,input,p,setOf(p.actions.single().id),now)}
        assertTrue(current.items["goal:$goalId"]?.content().planningIds("taskIDs").isEmpty())
    }
}
