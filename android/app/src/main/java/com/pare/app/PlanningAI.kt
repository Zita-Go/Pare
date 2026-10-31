package com.pare.app

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

enum class PlanningAIKind(val title:String) { DAY("安排这一天"), IDEA("探索这个想法"), INBOX("整理这条收件"), GOAL("推进这个目标") }
data class PlanningAIOptions(val taskIds:List<String> = emptyList(),val capacity:Int=30,val energy:String="一般",val extra:MobileAIOptions=MobileAIOptions())
data class PlanningAIRequest(val id:String,val kind:PlanningAIKind,val subjectId:String?,val planId:String,val options:PlanningAIOptions,
    val owner:String?,val device:String,val preview:String,val evidence:JsonObject,val baselines:Map<String,Entity?>,val preparedAt:Instant)
internal fun planningRequire(ok:Boolean,message:String) {if(!ok) throw MobileAIException(message)}
internal fun JsonObject?.planningText(key:String)=(this?.get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject?.planningIds(key:String)=(this?.get(key) as? JsonArray).orEmpty().mapNotNull {(it as? JsonPrimitive)?.contentOrNull}
internal fun JsonObject.planningPick(vararg keys:String)=JsonObject(filterKeys {it in keys})
internal fun planningActive(e:Entity?)=e!=null && !e.deleted && e.content()!=null && e.conflict==null && e.conflictGroup.isEmpty() &&
    (e.kind!="task" || (!e.completed() && e.payload.planningText("lifecycle")!="archived")) &&
    (e.kind!="idea" || e.content().planningText("stage")!="archived")

object PlanningAIContext {
    fun eligible(state:SyncState,kind:PlanningAIKind,subjectId:String?):List<Entity> {
        val linked=when(kind) {
            PlanningAIKind.GOAL->state.items["goal:$subjectId"]?.content().planningIds("taskIDs").toSet()
            PlanningAIKind.IDEA->state.items["idea:$subjectId"]?.content().planningIds("linkedTaskIDs").toSet()
            else->null
        }
        return state.items.values.filter {it.kind=="task" && planningActive(it) && (linked==null || it.id in linked)}.sortedBy {it.title()}
    }
    fun defaults(state:SyncState,kind:PlanningAIKind,subjectId:String?,planId:String):PlanningAIOptions {
        val eligible=eligible(state,kind,subjectId)
        val today=state.items["day_plan:$planId"]?.content().planningIds("ordered_task_ids").toSet()
        val preferred=eligible.filter {it.id in today}.ifEmpty {eligible}
        return PlanningAIOptions(taskIds=if(kind==PlanningAIKind.DAY) preferred.take(8).map {it.id} else emptyList())
    }
    fun prepare(state:SyncState,kind:PlanningAIKind,subjectId:String?,planId:String,options:PlanningAIOptions,history:List<MobileAITrace>,now:Instant=Instant.now()):PlanningAIRequest {
        planningRequire(options.capacity in 5..480 && options.energy in setOf("低","一般","充足"),"请选择 5–480 分钟与本次精力")
        planningRequire(options.taskIds.size<=8 && options.taskIds.distinct().size==options.taskIds.size,"每次最多选择 8 个不同的候选任务")
        val allowed=eligible(state,kind,subjectId).map {it.id}.toSet()
        planningRequire(options.taskIds.all {it in allowed},"候选任务已改变，请重新选择")
        if(kind==PlanningAIKind.DAY) planningRequire(options.taskIds.isNotEmpty(),"请先选择至少一个可安排的任务")
        if(kind==PlanningAIKind.DAY) {
            val day=TimelineDay.fromPlanId(planId)
            planningRequire(day.date>=now.atZone(day.zone).toLocalDate(),"过去日期仅供回看，不能由 AI 追加安排")
            planningRequire(day.zone.id==timelinePlanningZone(state),"规划时区已改变，请重新打开")
            val plan=state.items["day_plan:$planId"]
            planningRequire(plan==null || planningActive(plan),"这一天的计划已删除或存在冲突，请先处理")
            planningRequire(visibleSchedules(state,planId).none {it.task.id in options.taskIds && it.sourcePlanId!=planId},"候选任务包含来自其它日期的时间块，请先在时间轴确认该安排，或取消勾选该任务")
        }
        val subjectKind=when(kind) {PlanningAIKind.IDEA->"idea";PlanningAIKind.INBOX->"inbox";PlanningAIKind.GOAL->"goal";else->null}
        val subject=subjectKind?.let {state.items["$it:$subjectId"]}
        if(subjectKind!=null) planningRequire(planningActive(subject) && (kind!=PlanningAIKind.GOAL || subject?.content().planningText("status")=="active"),"当前${kind.title}的资料不可用、已归档或存在冲突")
        val baselines=mutableMapOf<String,Entity?>()
        options.taskIds.forEach {baselines["task:$it"]=state.items["task:$it"]}
        subject?.let {baselines[it.key]=it}
        if(kind==PlanningAIKind.DAY) {
            baselines["day_plan:$planId"]=state.items["day_plan:$planId"]
            // These records inform available capacity, but their private titles/notes are not sent.
            visibleSchedules(state,planId).forEach {schedule->
                val source=state.items["day_plan:${schedule.sourcePlanId}"]
                planningRequire(planningActive(source) && schedule.task.conflict==null && schedule.task.conflictGroup.isEmpty(),"已有时间块存在同步冲突，请先处理")
                baselines["day_plan:${schedule.sourcePlanId}"]=source;baselines[schedule.task.key]=schedule.task
            }
        }
        val evidence=buildJsonObject {
            put("tasks",JsonArray(options.taskIds.map {taskId -> MobileAIContext.evidence(state,taskId,options.extra,now)}))
            subject?.content()?.let {c ->
                put("subject",when(kind) {
                    PlanningAIKind.IDEA->c.planningPick("id","text","notes","stage","nextQuestion",*(if(options.extra.history) arrayOf("entries") else emptyArray()))
                    PlanningAIKind.INBOX->c.planningPick("id","text","suggestedQuadrant","suggestedRemindAt")
                    else->c.planningPick("id","title","motivation","status","targetDate","taskIDs")
                })
                if(kind==PlanningAIKind.IDEA && options.extra.ideas) put("linkedTasks",JsonArray(eligible(state,kind,subjectId).take(8).map {it.content()!!.planningPick("id","text","note","nextAction","definitionOfDone")}))
            }
            if(kind==PlanningAIKind.IDEA && options.extra.goals) put("relatedGoals",JsonArray(state.items.values.filter {it.kind=="goal" && planningActive(it) && it.content().planningText("status")=="active" &&
                it.content().planningIds("taskIDs").any {id -> id in subject?.content().planningIds("linkedTaskIDs")}}.sortedBy {it.id}.map {it.content()!!.planningPick("id","title","motivation","status")}))
            if(kind in setOf(PlanningAIKind.IDEA,PlanningAIKind.INBOX,PlanningAIKind.GOAL) && options.extra.preferences) {
                val profile=state.items["profile:profile"]?.takeUnless {it.deleted}?.content()
                (profile?.get("preferences") as? JsonObject)?.let {put("declaredPreferences",it.planningPick("notes","startingMinutes","planningMinutes"))}
                put("confirmedPreferences",JsonArray((profile?.get("confirmedPreferences") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter {
                    (it.planningText("ideaID")==null || (kind==PlanningAIKind.IDEA && it.planningText("ideaID")==subjectId)) &&
                        (it.planningText("expiresAt")==null || runCatching {Instant.parse(it.planningText("expiresAt"))>now}.getOrDefault(false))
                }.map {it.planningPick("id","text","source","ideaID")}))
            }
            if(kind==PlanningAIKind.DAY) {
                put("planId",planId)
                put("selectedAllocations",JsonArray(options.taskIds.map {id -> buildJsonObject {
                    put("taskId",id)
                    val current=entry(state,planId,id)
                    put("entry",current?.let {e->buildJsonObject {
                        e.planningPick("task_id","scheduled_at","scheduled_minutes","schedule_reminder_lead_minutes").forEach {put(it.key,it.value)}
                        (e["allocation"] as? JsonObject)?.let {put("allocation",it.planningPick("id","kind","planned_minutes"))}
                    }} ?: JsonNull)
                }}))
                put("otherCommittedMinutes",otherMinutes(state,planId,options.taskIds.toSet()))
            }
        }
        val id=uid()
        val preview=buildJsonObject {
            put("version",1);put("requestId",id);put("workflow",kind.name);put("subjectId",subjectId?.let(::JsonPrimitive) ?: JsonNull)
            put("allowedTaskIds",JsonArray(options.taskIds.map(::JsonPrimitive)));put("capacityMinutes",options.capacity);put("energy",options.energy);put("evidence",evidence)
            if(options.extra.history) put("previousModelOutput",JsonArray(history.filter {it.kind==kind.name && it.taskId==subjectId.orEmpty() && it.taskIds.toSet()==options.taskIds.toSet()}.takeLast(3).map {buildJsonObject {put("at",it.at);put("output",it.output)}}))
        }.toString()
        planningRequire(preview.toByteArray().size<=32_000,"本次参考内容超过 32 KB，请减少候选任务或参考范围")
        return PlanningAIRequest(id,kind,subjectId,planId,options,state.owner,state.device,preview,evidence,baselines,now)
    }
    fun requireFresh(state:SyncState,input:PlanningAIRequest,now:Instant=Instant.now()) {
        planningRequire(state.owner==input.owner && state.device==input.device,"资料库已改变，请重新预览")
        input.baselines.forEach {(key,expected)->
            val current=state.items[key]
            planningRequire(current?.payload==expected?.payload && current?.deleted==expected?.deleted && current?.conflict==null && current?.conflictGroup.orEmpty().isEmpty(),"参考内容已更新，请重新预览；草稿已保留")
        }
        val fresh=prepare(state,input.kind,input.subjectId,input.planId,input.options,emptyList(),now)
        planningRequire(fresh.evidence==input.evidence,"额外参考内容已更新，请重新预览")
        if(input.kind==PlanningAIKind.DAY) {
            val day=TimelineDay.fromPlanId(input.planId)
            planningRequire(day.date>=now.atZone(day.zone).toLocalDate(),"日期已过去，请重新选择日期")
        }
    }
    internal fun entry(state:SyncState,planId:String,taskId:String)=(state.items["day_plan:$planId"]?.content()?.get("task_plans") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull {it.planningText("task_id")==taskId}
    private fun visibleSchedules(state:SyncState,planId:String):List<TimelineSchedule> {
        val day=TimelineDay.fromPlanId(planId)
        return TimelineProjection.schedules(state).filter {it.start<day.end && it.end>day.start}
    }
    internal fun otherMinutes(state:SyncState,planId:String,selected:Set<String>):Long {
        val day=TimelineDay.fromPlanId(planId)
        val schedules=visibleSchedules(state,planId)
        val scheduledIds=schedules.map {it.task.id}.toSet()
        val scheduled=schedules.filter {it.task.id !in selected}.sumOf {
            val seconds=java.time.Duration.between(maxOf(it.start,day.start),minOf(it.end,day.end)).seconds
            (seconds+59L)/60L
        }
        val ids=state.items["day_plan:$planId"]?.content().planningIds("ordered_task_ids").filter {it !in selected && it !in scheduledIds && planningActive(state.items["task:$it"])}
        return scheduled+ids.sumOf {id ->
            val allocation=entry(state,planId,id)?.get("allocation") as? JsonObject
            val amount=(allocation?.get("planned_minutes") as? JsonPrimitive)?.intOrNull
            planningRequire(amount!=null && amount>0,"已有未选中的安排尚无投入时长，请先填写时长或纳入本次候选")
            amount!!.toLong()
        }
    }

}

@Serializable data class PlanningAIAction(val id:String,val kind:String,val sourceId:String,val text:String="",val title:String="",val nextAction:String="",val stopCriterion:String="",val minutes:Int=0,val allocationKind:String="personal",val quadrant:Int=1,val reason:String)
@Serializable data class PlanningAIProposal(val requestId:String,val summary:String,val actions:List<PlanningAIAction>) {
    fun validate(input:PlanningAIRequest):PlanningAIProposal {
        planningRequire(requestId==input.id && summary.trim().length in 1..1000 && actions.size in 1..8 && actions.map {it.id}.distinct().size==actions.size,"AI 返回的请求、说明或行动数量无效")
        val writes=mutableSetOf<String>()
        actions.forEach {a ->
            planningRequire(runCatching {UUID.fromString(a.id).toString()==a.id}.getOrDefault(false),"AI 返回了无效的行动标识")
            planningRequire(a.reason.trim().length in 1..600 && a.text.length<=2000 && a.title.length<=200 && a.nextAction.length<=500 && a.stopCriterion.length<=500 && a.quadrant in 0..3 && a.minutes in 0..480 && a.allocationKind in setOf("personal","check"),"AI 返回的行动格式无效")
            planningRequire(listOf(a.reason,a.text,a.title,a.nextAction,a.stopCriterion,summary).none {it.any {c -> c.isISOControl() && c!='\n' && c!='\t'}},"AI 返回了无效文字")
            when(a.kind) {
                "allocate","next_step"->planningRequire(a.text.isBlank() && a.title.isBlank(),"任务建议包含未支持的额外修改")
                "finding","question"->planningRequire(a.title.isBlank() && a.nextAction.isBlank() && a.stopCriterion.isBlank() && a.minutes==0,"探索记录包含未支持的额外修改")
                "linked_task","goal_task"->planningRequire(a.text.isBlank(),"关联行动包含未支持的额外修改")
                "classify_task","classify_idea"->planningRequire(a.text.isBlank() && a.title.isBlank() && a.nextAction.isBlank() && a.stopCriterion.isBlank() && a.minutes==0,"收件整理仅支持类型与象限建议")
            }
            when(input.kind) {
                PlanningAIKind.DAY-> {
                    planningRequire(a.kind=="allocate" && a.sourceId in input.options.taskIds && writes.add(a.sourceId) && a.minutes in 1..480,"AI 引用了未授权任务或无效安排")
                    val plan=input.baselines["day_plan:${input.planId}"]?.content()
                    val e=(plan?.get("task_plans") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull {it.planningText("task_id")==a.sourceId}
                    val allocation=e?.get("allocation") as? JsonObject
                    planningRequire(a.allocationKind==(allocation.planningText("kind") ?: "personal"),"AI 不能擅自切换个人 / 检查投入")
                    if(e?.get("scheduled_at")!=null) planningRequire((e["scheduled_minutes"] as? JsonPrimitive)?.intOrNull==a.minutes,"已有时间块的时长须保留，请手动调整时间块")
                    planningRequire(a.nextAction.isBlank()==a.stopCriterion.isBlank(),"下一步与停止标准必须同时提供")
                }
                PlanningAIKind.GOAL-> {
                    val existing=a.kind=="next_step" && a.sourceId in input.options.taskIds && writes.add(a.sourceId)
                    val creating=a.kind=="goal_task" && a.sourceId==input.subjectId && a.title.isNotBlank()
                    planningRequire((existing || creating) && a.minutes in 2..5 && a.nextAction.isNotBlank() && a.stopCriterion.isNotBlank(),"目标建议只能创建一件关联行动，或更新已选择任务的最小下一步")
                }
                PlanningAIKind.IDEA-> {
                    planningRequire(a.sourceId==input.subjectId && a.kind in setOf("finding","question","linked_task"),"AI 引用了未授权的想法或操作")
                    if(a.kind=="linked_task") planningRequire(a.title.isNotBlank() && a.nextAction.isNotBlank() && a.stopCriterion.isNotBlank() && a.minutes in 2..5,"关联行动需要标题、最小下一步和停止标准")
                    else planningRequire(a.text.isNotBlank(),"探索记录不能为空")
                }
                PlanningAIKind.INBOX->planningRequire(actions.size==1 && a.sourceId==input.subjectId && a.kind in setOf("classify_task","classify_idea"),"收件建议只能整理为一个任务或想法")
            }
        }
        planningRequire(actions.count {it.kind=="linked_task"}<=3,"一次最多创建三个关联行动")
        planningRequire(actions.count {it.kind=="goal_task"}<=1,"每次只为目标创建一件能开始的小行动")
        val other=(input.evidence["otherCommittedMinutes"] as? JsonPrimitive)?.longOrNull ?: 0L
        var retained=0L
        if(input.kind==PlanningAIKind.DAY) {
            val plan=input.baselines["day_plan:${input.planId}"]?.content()
            val changed=actions.map {it.sourceId}.toSet()
            val scheduled=(plan?.get("task_plans") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter {it["scheduled_at"]!=null}.mapNotNull {it.planningText("task_id")}
            val remaining=(plan.planningIds("ordered_task_ids")+scheduled).distinct().filter {it in input.options.taskIds && it !in changed}
            remaining.forEach {id->
                val entry=(plan?.get("task_plans") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull {it.planningText("task_id")==id}
                val minutes=(entry?.get("scheduled_minutes") as? JsonPrimitive)?.intOrNull ?: ((entry?.get("allocation") as? JsonObject)?.get("planned_minutes") as? JsonPrimitive)?.intOrNull
                planningRequire(minutes!=null && minutes>0,"未提议的已有任务缺少投入时长，请补充安排后重试")
                retained+=minutes!!
            }
        }
        planningRequire(actions.sumOf {it.minutes.toLong()}+other+retained<=input.options.capacity,"建议超过本日容量，未修改安排")
        return this
    }
}
