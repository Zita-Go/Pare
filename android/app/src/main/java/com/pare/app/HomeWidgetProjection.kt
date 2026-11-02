package com.pare.app

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HomeWidgetModel(
    val heading:String,val title:String,val nextStep:String,val status:String,
    val actionLabel:String,val request:HomeWidgetRequest,val statusImportant:Boolean=false
)

/** Read-only projection of the same planning date/order as Today. No estimates or invented progress. */
object HomeWidgetProjection {
    fun unavailable(locked:Boolean=false)=HomeWidgetModel("Step · 今日重点",
        if(locked)"解锁后查看今日重点" else "暂时无法读取今日重点",
        if(locked)"解锁设备后再打开 Step" else "请打开 Step 重试，原记录已保留",
        if(locked)"解锁后再打开 Step" else "打开 Step 重试，原记录已保留", "打开 Step",HomeWidgetRequest(HomeWidgetAction.TODAY),statusImportant=true)

    private fun usable(entity:Entity?)=entity!=null && !entity.deleted && entity.content()!=null && !entity.completed() &&
        entity.payload?.get("lifecycle")?.jsonPrimitive?.contentOrNull!="archived"
    private fun ownFocus(state:SyncState,execution:ExecutionSnapshot?):ExecutionRun?=
        execution?.takeIf {it.device==state.device && it.owner==state.owner}?.focus?.takeIf {usable(state.items["task:${it.taskId}"])}

    fun project(state:SyncState,execution:ExecutionSnapshot?,now:ExecutionMoment,executionUnavailable:Boolean=false):HomeWidgetModel {
        val planId=ContentActions.planId(state,Instant.ofEpochMilli(now.wallMillis))
        val date=LocalDate.parse(planId.substringBefore('@'))
        val plan=state.items["day_plan:$planId"]?.takeUnless {it.deleted}?.content()
        val ordered=(plan?.get("ordered_task_ids") as? JsonArray)?.mapNotNull {(it as? JsonPrimitive)?.contentOrNull}.orEmpty()
        val priority=(plan?.get("daily_win_task_id") as? JsonPrimitive)?.contentOrNull
        // The overview also owns dated legacy/overnight occurrences not listed in today's plan.
        // Reuse that authority, then prefer today's win and explicit selection order when present.
        val overview=TodayOverviewProjection.project(state,TimelineDay.fromPlanId(planId))
        val visible=(overview.scheduled+overview.unscheduled).map {it.task}.filter(::usable).associateBy {it.id}
        val selected=(listOfNotNull(priority)+ordered+visible.keys).firstNotNullOfOrNull {visible[it]}
        val focus=ownFocus(state,execution)
        val title=selected?.title()?.take(180) ?: "今天还没有选定行动"
        val next=selected?.content()?.get("nextAction")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf {it.isNotEmpty()}
        val nextStep=if(selected==null)"从四象限选出今天要推进的一件事" else next?.let {"下一步 · ${it.take(200)}"} ?: "下一步还未填写，打开任务补上一小步"
        var model=HomeWidgetModel("${date.monthValue}/${date.dayOfMonth} · 今日重点",title,nextStep,
            if(selected==null)"本机内容 · 离线可用" else "从最小下一步开始",
            if(selected==null)"打开今日" else "开始专注",
            if(selected==null)HomeWidgetRequest(HomeWidgetAction.TODAY) else HomeWidgetRequest(HomeWidgetAction.START_FOCUS,selected.id))
        if(executionUnavailable)return model.copy(heading="${date.monthValue}/${date.dayOfMonth} · 状态待检查",status="专注状态暂不可用，请打开 Step 检查",actionLabel="打开 Step",request=HomeWidgetRequest(HomeWidgetAction.TODAY),statusImportant=true)
        if(focus!=null) {
            val name=state.items.getValue("task:${focus.taskId}").title().take(80)
            val uncertain=!focus.paused && (focus.anchor?.bootId!=now.bootId || now.elapsedMillis<(focus.anchor?.elapsedMillis ?: Long.MAX_VALUE))
            val due=focus.due || focus.elapsedMillis(now)>=(focus.targetMinutes ?: Int.MAX_VALUE)*60_000L
            val conflict=state.items["task:${focus.taskId}"]?.conflict!=null
            val action=when {
                uncertain || due || conflict || focus.pendingFinish!=null -> HomeWidgetAction.REVIEW_FOCUS
                focus.paused -> HomeWidgetAction.RESUME_FOCUS
                else -> HomeWidgetAction.PAUSE_FOCUS
            }
            val status=when {
                focus.pendingFinish!=null -> "专注记录待保存"
                uncertain || conflict -> "专注状态需检查"
                due -> "专注已到点，请回看"
                focus.paused -> "专注已暂停"
                else -> "正在专注"
            }
            val focusedTask=state.items.getValue("task:${focus.taskId}")
            val focusedNext=focusedTask.content()?.timelineString("nextAction")?.trim()?.takeIf {it.isNotEmpty()}
            model=model.copy(heading="${date.monthValue}/${date.dayOfMonth} · $status",title=focusedTask.title().take(180),
                nextStep=focusedNext?.let {"下一步 · ${it.take(200)}"} ?: "打开任务补充一小步",
                status="$status · $name",actionLabel=when(action) {
                HomeWidgetAction.PAUSE_FOCUS -> "暂停专注"
                HomeWidgetAction.RESUME_FOCUS -> "继续专注"
                else -> "查看专注"
            },request=HomeWidgetRequest(action,focus.taskId,focus.id),statusImportant=action==HomeWidgetAction.REVIEW_FOCUS)
        } else if(selected?.conflict!=null) {
            model=model.copy(heading="${date.monthValue}/${date.dayOfMonth} · 待处理冲突",status="这件事有同步冲突，请先查看",actionLabel="打开今日",request=HomeWidgetRequest(HomeWidgetAction.TODAY),statusImportant=true)
        } else if(selected!=null) {
            val waiting=execution?.takeIf {it.device==state.device && it.owner==state.owner}
                ?.external?.firstOrNull {it.taskId==selected.id}
            if(waiting!=null)model=model.copy(heading="${date.monthValue}/${date.dayOfMonth} · "+if(waiting.paused)"等待已暂停" else "等待结果",status=if(waiting.paused)"等待记录已暂停" else "正在等待结果 · 未计入个人专注",
                actionLabel="查看推进",request=HomeWidgetRequest(HomeWidgetAction.REVIEW_FOCUS,selected.id,waiting.id))
        }
        return model
    }

    fun nextRefreshAt(state:SyncState,execution:ExecutionSnapshot?,now:ExecutionMoment):Long {
        val planId=ContentActions.planId(state,Instant.ofEpochMilli(now.wallMillis))
        val nextDay=LocalDate.parse(planId.substringBefore('@')).plusDays(1).atStartOfDay(ZoneId.of(planId.substringAfter('@'))).toInstant().toEpochMilli()
        val focus=ownFocus(state,execution)
        val remaining=focus?.takeIf {!it.paused && !it.due && it.pendingFinish==null && it.anchor?.bootId==now.bootId}
            ?.let {(it.targetMinutes ?: 0)*60_000L-it.elapsedMillis(now)}?.takeIf {it>0}
        return minOf(nextDay,remaining?.let {now.wallMillis+it} ?: nextDay)
    }
}
