package com.pare.app

import java.time.Instant
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.json.*

data class ReviewWeek(val start:LocalDate,val completed:Int,val focusMinutes:Long,val waitingMinutes:Long,val current:Boolean)
data class ReviewCalibration(val pairs:Int,val estimatedMinutes:Long,val actualMinutes:Long) {
    val ratio:Double get()=actualMinutes.toDouble()/estimatedMinutes
}
data class ReviewAnalyticsSummary(val weeks:List<ReviewWeek>,val quadrantCompletions:Map<Int,Int>,val calibration:ReviewCalibration?,val zone:String) {
    val hasRecords get()=weeks.any {it.completed>0 || it.focusMinutes>0 || it.waitingMinutes>0}
}
object ReviewAnalytics {
    fun calculate(state:SyncState,now:Instant):ReviewAnalyticsSummary {
        val zone=ZoneId.of(ContentActions.planId(state,now).substringAfter('@'))
        val monday=now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val starts=(7 downTo 0).map {monday.minusWeeks(it.toLong())}
        val tasks=state.items.values.filter {it.kind=="task" && !it.deleted && it.conflict==null && it.conflictGroup.isEmpty() && it.content()!=null}
        fun at(value:JsonElement?)=(value as? JsonPrimitive)?.contentOrNull?.let {runCatching {Instant.parse(it)}.getOrNull()}
        fun inWeek(value:JsonElement?,start:LocalDate):Boolean {
            val instant=at(value) ?: return false
            return instant>=start.atStartOfDay(zone).toInstant() && instant<start.plusWeeks(1).atStartOfDay(zone).toInstant() && instant<=now
        }
        fun minutes(start:LocalDate,field:String,key:String):Long=tasks.sumOf {task->
            (task.content()?.get(field) as? JsonArray).orEmpty().filterIsInstance<JsonObject>().distinctBy {it["id"] ?: it}.sumOf {entry->
                val ended=at(entry["endedAt"]);val began=at(entry["startedAt"])
                if(!inWeek(entry["endedAt"],start) || ended==null || began==null || ended<began) 0L
                else (entry[key] as? JsonPrimitive)?.longOrNull?.takeIf {it in 0..Int.MAX_VALUE.toLong()} ?: 0L
            }
        }
        val weeks=starts.map {start->ReviewWeek(start,tasks.count {it.completed() && inWeek(it.content()?.get("completedAt"),start)},minutes(start,"progressSessions","minutes"),minutes(start,"parallelRunSessions","elapsedMinutes"),start==monday)}
        val completed=tasks.filter {it.completed() && starts.any {start->inWeek(it.content()?.get("completedAt"),start)}}
        val distribution=(0..3).associateWith {quadrant->completed.count {(it.content()?.get("quadrant") as? JsonPrimitive)?.intOrNull==quadrant}}
        val pairs=completed.mapNotNull {task->
            val estimated=(task.content()?.get("estimatedMinutes") as? JsonPrimitive)?.intOrNull?.takeIf {it>0}
            val actual=(task.content()?.get("actualMinutes") as? JsonPrimitive)?.intOrNull?.takeIf {it>=0}
            if(estimated!=null && actual!=null) estimated.toLong() to actual.toLong() else null
        }
        return ReviewAnalyticsSummary(weeks,distribution,if(pairs.isEmpty()) null else ReviewCalibration(pairs.size,pairs.sumOf {it.first},pairs.sumOf {it.second}),zone.id)
    }
    /** Aggregate-only export is built on demand; no titles, credentials, or raw AI history are copied. */
    fun export(state:SyncState,now:Instant):String {
        val recent=MobileReview.calculate(state,now);val trend=calculate(state,now)
        return buildString {
            appendLine("Pare 本地回顾 · ${now.atZone(ZoneId.of(trend.zone)).toLocalDate()} · ${trend.zone}")
            appendLine("最近7天：完成 ${recent.completedTasks.size} 件；自己专注 ${recent.focusMinutes} 分钟；等待结果 ${recent.externalMinutes} 分钟。")
            appendLine("最近8个自然周（本周截至当前，仅已有记录）：")
            trend.weeks.forEach {appendLine("${it.start}起${if(it.current) "（本周未结束）" else ""}：完成${it.completed}件，专注${it.focusMinutes}分钟，等待${it.waitingMinutes}分钟")}
            appendLine("完成分布："+trend.quadrantCompletions.entries.joinToString("；") {"象限${it.key+1} ${it.value}件"})
            val calibration=trend.calibration
            if(calibration==null) appendLine("估时校准：没有同时记录预计与实际分钟的已完成任务，不作推算。")
            else appendLine("估时校准：${calibration.pairs}件完整样本，预计合计${calibration.estimatedMinutes}分钟，实际合计${calibration.actualMinutes}分钟；实际/预计 ${String.format(java.util.Locale.ROOT,"%.2f",calibration.ratio)}倍。")
            append("等待不计作个人专注；不含已删除或冲突条目的8周统计。")
        }
    }
}
