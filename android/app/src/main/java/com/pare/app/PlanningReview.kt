package com.pare.app

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Captures only changed entities. A changed result is never overwritten during conditional undo. */
data class PlanningAIUndo(val owner:String?,val device:String,val before:Map<String,Entity?>,val after:Map<String,Entity>) {
    fun apply(state:SyncState):SyncState {
        planningRequire(state.owner==owner && state.device==device,"资料库已改变，无法撤销")
        after.forEach {(key,e)->val current=state.items[key]
            planningRequire(current?.payload==e.payload && current?.deleted==e.deleted && current?.conflict==null && current?.conflictGroup.orEmpty().isEmpty(),"采纳后的内容已再次修改，后续编辑已保留")
        }
        val created=before.filterValues {it==null}.keys
        // A newly created task/idea may have been linked elsewhere since adoption.
        created.forEach {key-> val id=key.substringAfter(':')
            planningRequire(state.items.values.none {e -> e.key !in after && !e.deleted && (
                id in e.content().planningIds("taskIDs") || id in e.content().planningIds("linkedTaskIDs") || id in e.content().planningIds("ordered_task_ids") ||
                    (e.content()?.get("task_plans") as? JsonArray).orEmpty().any {(it as? JsonObject).planningText("task_id")==id})},"新行动已有后续关联，无法撤销；关联已保留")
        }
        var next=state
        before.forEach {(key,e)->
            val kind=key.substringBefore(':');val id=key.substringAfter(':')
            val payload=if(e==null && kind=="day_plan") buildJsonObject {put("content",buildJsonObject {
                put("day_key",id.substringBefore('@'));put("time_zone",id.substringAfter('@'));put("ordered_task_ids",JsonArray(emptyList()));put("task_plans",JsonArray(emptyList()))
            })} else e?.payload
            next=StateMachine.edit(next,kind,id,payload,deleted=payload==null)
        }
        return PlanningReviewActions.checkedGroup(next,before.keys)
    }
    companion object {
        fun capture(before:SyncState,after:SyncState):PlanningAIUndo {
            val keys=after.items.keys.filter {before.items[it]?.payload!=after.items[it]?.payload || before.items[it]?.deleted!=after.items[it]?.deleted}.toSet()
            return PlanningAIUndo(before.owner,before.device,keys.associateWith {before.items[it]},keys.associateWith {after.items.getValue(it)})
        }
    }
}

object PlanningReviewActions {
    /** IDs derive from the reviewed request + action, so save retries cannot duplicate creations. */
    fun targetId(input:PlanningAIRequest,action:PlanningAIAction)=UUID.nameUUIDFromBytes("pare:${input.id}:${action.id}".toByteArray()).toString()
    fun adopt(state:SyncState,input:PlanningAIRequest,proposal:PlanningAIProposal,selected:Set<String>,now:Instant=Instant.now()):SyncState {
        proposal.validate(input)
        planningRequire(selected.isNotEmpty() && selected.all {id->proposal.actions.any {it.id==id}},"请勾选要采纳的行动")
        PlanningAIContext.requireFresh(state,input,now)
        val actions=proposal.actions.filter {it.id in selected}
        var next=state
        when(input.kind) {
            PlanningAIKind.DAY->next=allocate(next,input,actions)
            PlanningAIKind.GOAL->actions.forEach {a ->next=if(a.kind=="goal_task") PlanningAIGoalActions.create(next,input,a,now) else nextStep(next,a,now)}
            PlanningAIKind.IDEA->actions.forEach {a ->
                next=when(a.kind) {
                    "linked_task"->IdeaActions.createLinkedTask(next,a.sourceId,targetId(input,a),a.title,a.nextAction,a.stopCriterion,a.quadrant,null,now)
                    else->IdeaActions.appendEntry(next,a.sourceId,targetId(input,a),if(a.kind=="question") "待探索的问题：${a.text.trim()}" else a.text,"${if(a.kind=="finding") "finding" else "note"}",now)
                }
            }
            PlanningAIKind.INBOX-> {
                val a=actions.single();val source=next.items.getValue("inbox:${a.sourceId}");val id=targetId(input,a)
                val kind=if(a.kind=="classify_task") "task" else "idea"
                planningRequire(next.items["$kind:$id"]==null,"该收件已经整理，请查看最新内容")
                val created=ContentActions.save(null,kind,source.title(),"",a.quadrant,id,now,nextAction=a.nextAction)
                val payload=source.payload!!.toMutableMap().apply {putAll(created)}
                // Carry the full original inbox record as extension fields; original text/time are never rewritten by AI.
                val c=source.content()!!.toMutableMap().apply {putAll(payload.getValue("content").jsonObject)}
                source.content()?.get("createdAt")?.let {c["createdAt"]=it}
                if(kind=="task") source.content()?.get("suggestedRemindAt")?.let {c["remindAt"]=it}
                payload["content"]=JsonObject(c)
                next=StateMachine.edit(next,kind,id,JsonObject(payload));next=StateMachine.edit(next,"inbox",source.id,null,true)
            }
        }
        if(input.kind==PlanningAIKind.DAY) actions.filter {it.nextAction.isNotBlank()}.forEach {next=nextStep(next,it,now)}
        val changed=next.items.keys.filter {state.items[it]?.payload!=next.items[it]?.payload || state.items[it]?.deleted!=next.items[it]?.deleted}.toSet()
        planningRequire(changed.isNotEmpty(),"建议与现有内容相同，无需重复采纳")
        return checkedGroup(next,changed)
    }
    private fun nextStep(state:SyncState,a:PlanningAIAction,now:Instant):SyncState {
        val task=state.items.getValue("task:${a.sourceId}")
        return SmallStepActions.save(state,a.sourceId,a.nextAction,a.stopCriterion,SmallStepBaseline.from(task),null,null,null,now)
    }
    private fun allocate(state:SyncState,input:PlanningAIRequest,actions:List<PlanningAIAction>):SyncState {
        val old=state.items["day_plan:${input.planId}"]
        val c:MutableMap<String,JsonElement> = old?.content()?.toMutableMap() ?: mutableMapOf("day_key" to JsonPrimitive(input.planId.substringBefore('@')),"time_zone" to JsonPrimitive(input.planId.substringAfter('@')))
        val order=c["ordered_task_ids"]?.let {it.jsonArray.map {p->p.jsonPrimitive.content}}.orEmpty()
        val chosen=actions.map {it.sourceId};val iterator=chosen.filter {it in order}.iterator()
        // Only reorder selected slots, append new selections, keep all unselected slots and entries.
        c["ordered_task_ids"]=JsonArray((order.map {if(it in chosen) iterator.next() else it}+chosen.filter {it !in order}).map(::JsonPrimitive))
        val entries=(c["task_plans"] as? JsonArray).orEmpty().toMutableList()
        actions.forEach {a ->
            val index=entries.indexOfFirst {(it as? JsonObject).planningText("task_id")==a.sourceId}
            val entry:MutableMap<String,JsonElement> = if(index<0) mutableMapOf("task_id" to JsonPrimitive(a.sourceId)) else entries[index].jsonObject.toMutableMap()
            val allocation:MutableMap<String,JsonElement> = (entry["allocation"] as? JsonObject)?.toMutableMap() ?: mutableMapOf("id" to JsonPrimitive(targetId(input,a)))
            allocation["kind"]=JsonPrimitive(a.allocationKind);allocation["planned_minutes"]=JsonPrimitive(a.minutes)
            entry["allocation"]=JsonObject(allocation)
            if(index<0) entries.add(JsonObject(entry)) else entries[index]=JsonObject(entry)
        }
        c["task_plans"]=JsonArray(entries)
        val payload=JsonObject((old?.payload ?: JsonObject(emptyMap())).toMutableMap().apply {put("content",JsonObject(c))})
        val next=StateMachine.edit(state,"day_plan",input.planId,payload)
        planningRequire(PlanningAIContext.otherMinutes(next,input.planId,emptySet())<=input.options.capacity,"勾选的行动与保留安排超过本日容量，请调整容量或选择")
        return next
    }
    internal fun checkedGroup(state:SyncState,keys:Set<String>):SyncState {
        keys.forEach {key->state.items[key]?.let {e->e.payload?.let {PayloadValidation.validate(e.kind,e.id,it)}}}
        val grouped=StateMachine.group(state,keys)
        val groups=keys.mapNotNull {grouped.items[it]?.group}.toSet()
        val members=grouped.items.values.filter {it.dirty && it.group in groups}
        planningRequire(members.size<=100,"关联同步内容超过 100 条，本次修改未保存")
        val mutations=members.map {Mutation(it.kind,it.id,it.revision,deleted=it.deleted,payload=it.payload)}
        planningRequire(wire.encodeToString(mutations).toByteArray(Charsets.UTF_8).size<=1_048_576,"关联同步内容超过大小上限，本次修改未保存")
        return grouped
    }
}
