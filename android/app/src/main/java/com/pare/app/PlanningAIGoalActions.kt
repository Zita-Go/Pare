package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*

/** A reviewed proposal creates one stable task and links it, preserving the goal and every day plan. */
internal object PlanningAIGoalActions {
    fun create(state:SyncState,input:PlanningAIRequest,action:PlanningAIAction,now:Instant):SyncState {
        planningRequire(input.kind==PlanningAIKind.GOAL && action.kind=="goal_task" && action.sourceId==input.subjectId,"目标行动不在本次授权范围")
        val goal=state.items["goal:${action.sourceId}"]
        planningRequire(planningActive(goal) && goal?.content().planningText("status")=="active","目标已暂停、完成、删除或存在冲突")
        val taskId=PlanningReviewActions.targetId(input,action)
        planningRequire(state.items["task:$taskId"]==null,"这件行动已创建或撤销，请重新预览；没有重复创建")
        val content=goal!!.content()!!.toMutableMap()
        val links=goal.content().planningIds("taskIDs")
        planningRequire(links.distinct().size<100,"目标已关联100件任务，请先整理关联行动")
        val taskPayload=ContentActions.save(null,"task",action.title.trim(),"",action.quadrant,taskId,now,nextAction=action.nextAction).let {payload->
            JsonObject(payload.toMutableMap().apply {put("content",JsonObject(payload.getValue("content").jsonObject.toMutableMap().apply {put("definitionOfDone",JsonPrimitive(action.stopCriterion.trim()))}))})
        }
        content["taskIDs"]=JsonArray((links+taskId).distinct().map(::JsonPrimitive))
        content["updatedAt"]=JsonPrimitive(now.toString())
        val goalPayload=JsonObject(goal.payload!!.toMutableMap().apply {put("content",JsonObject(content))})
        var next=StateMachine.edit(state,"task",taskId,taskPayload)
        next=StateMachine.edit(next,"goal",goal.id,goalPayload)
        // PlanningReviewActions groups the whole chosen proposal and checks the inherited 100-item/1MiB limits.
        return next
    }
}
