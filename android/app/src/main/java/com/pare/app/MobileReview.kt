package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*

data class ReviewAttention(val kind:String,val id:String,val title:String,val reason:String,val sourceAt:String) {
    val key get()="$kind:$id:$sourceAt"
}
data class MobileReviewSummary(val completedTasks:List<Entity>,val focusMinutes:Long,val externalMinutes:Long,
    val blockerCounts:Map<String,Int>,val attention:List<ReviewAttention>)

/** Read-only local reflection. No model calls, inferred achievements, or estimated-time totals. */
object MobileReview {
    fun calculate(state:SyncState,now:Instant):MobileReviewSummary {
        val start=now.minusSeconds(7*86400)
        fun instant(value:JsonElement?)=value?.jsonPrimitive?.contentOrNull?.let {runCatching {Instant.parse(it)}.getOrNull()}
        fun recent(value:JsonElement?)=instant(value)?.let {!it.isBefore(start) && !it.isAfter(now)}==true
        val entities=state.items.values.filter {!it.deleted && it.content()!=null}
        val tasks=entities.filter {it.kind=="task"}
        val completed=tasks.filter {it.completed() && recent(it.content()?.get("completedAt"))}.sortedByDescending {instant(it.content()?.get("completedAt"))}
        fun minutes(field:String,key:String)=tasks.sumOf {task ->
            (task.content()?.get(field) as? JsonArray).orEmpty().sumOf {entry ->
                val c=entry as? JsonObject
                if(c!=null && recent(c["endedAt"])) c[key]?.jsonPrimitive?.longOrNull?.coerceIn(0,Int.MAX_VALUE.toLong()) ?: 0L else 0L
            }
        }
        val blockers=mutableMapOf<String,Int>()
        tasks.forEach {task -> (task.content()?.get("blockerEntries") as? JsonArray).orEmpty().forEach {entry ->
            val c=entry as? JsonObject
            if(c!=null && recent(c["at"])) c["reason"]?.jsonPrimitive?.contentOrNull?.takeIf {it in setOf("unclear","tooLarge","lowEnergy","resistance")}?.let {blockers[it]=(blockers[it] ?: 0)+1}
        } }
        val ideas=entities.filter {it.kind=="idea" && it.conflict==null && it.content()?.get("stage")?.jsonPrimitive?.contentOrNull!="archived"}.mapNotNull {idea ->
            val due=instant(idea.content()?.get("reviewAt"))
            if(due!=null && !due.isAfter(now)) ReviewAttention("idea",idea.id,idea.title(),"到了你安排的回看时间",due.toString()) else null
        }.sortedBy {it.sourceAt}
        val stalled=tasks.filter {!it.completed() && it.payload?.get("lifecycle")?.jsonPrimitive?.contentOrNull!="archived" && it.conflict==null}.mapNotNull {task ->
            val last=instant(task.content()?.get("lastProgressAt")) ?: instant(task.content()?.get("createdAt"))
            if(last!=null && !last.isAfter(start)) ReviewAttention("task",task.id,task.title(),"至少7天没有记录推进，可决定继续或暂放",last.toString()) else null
        }.sortedBy {it.sourceAt}
        return MobileReviewSummary(completed,minutes("progressSessions","minutes"),minutes("parallelRunSessions","elapsedMinutes"),blockers,ideas+stalled)
    }
}
