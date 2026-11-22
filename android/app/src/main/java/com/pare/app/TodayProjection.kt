package com.pare.app

import kotlinx.serialization.json.*

data class TodayOverviewEntry(val task:Entity,val blocks:List<TimelineBlock>)

data class TodayTaskEffort(val kind: String? = null, val minutes: Int? = null) {
    val label: String get() = if (minutes == null) "未设投入" else "${if (kind == "check") "检查" else "个人投入"} $minutes 分钟"
}

/** Effort belongs to a dated allocation. Calendar lengths, task estimates and waiting are different facts. */
object TodayEffort {
    fun forTask(state: SyncState, day: TimelineDay, taskId: String): TodayTaskEffort {
        val plan = state.items["day_plan:${day.planId}"]
            ?.takeUnless { it.deleted || it.conflict != null || it.conflictGroup.isNotEmpty() }?.content()
        val allocation = (plan?.get("task_plans") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .firstOrNull { it.timelineString("task_id") == taskId }?.get("allocation") as? JsonObject
        val kind = allocation?.timelineString("kind")?.takeIf { it in setOf("personal", "check") }
        val minutes = allocation?.timelineInt("planned_minutes")?.takeIf { it in 1..1440 }
        return if (kind != null && minutes != null) TodayTaskEffort(kind, minutes) else TodayTaskEffort()
    }

    fun summary(state: SyncState, day: TimelineDay, projection: TodayOverviewProjection): TodayLoad {
        val efforts = (projection.scheduled + projection.unscheduled).distinctBy { it.task.id }.map { forTask(state, day, it.task.id) }
        return TodayLoad(efforts.filter { it.kind == "personal" }.sumOf { it.minutes?.toLong() ?: 0L },
            efforts.filter { it.kind == "check" }.sumOf { it.minutes?.toLong() ?: 0L }, efforts.count { it.minutes == null })
    }
}

data class TodayOverviewProjection(val scheduled:List<TodayOverviewEntry>,val unscheduled:List<TodayOverviewEntry>,val completed:List<TodayOverviewEntry>) {
    companion object {
        fun project(state:SyncState,day:TimelineDay):TodayOverviewProjection {
            val timeline=TimelineProjection.project(state,day)
            val grouped=timeline.blocks.groupBy {it.task.id}
            val scheduled=grouped.values.map {TodayOverviewEntry(it.first().task,it)}
            val ordered=(state.items["day_plan:${day.planId}"]?.takeUnless {it.deleted}?.content()?.get("ordered_task_ids") as? JsonArray).orEmpty()
                .mapNotNull {(it as? JsonPrimitive)?.contentOrNull}.distinct()
            val selected=ordered.mapNotNull {state.items["task:$it"]}.filter {
                it.kind=="task" && !it.deleted && it.content()!=null && it.payload?.timelineString("lifecycle")!="archived"
            }
            val done=(scheduled+selected.filter {it.id !in grouped}.map {TodayOverviewEntry(it,emptyList())})
                .filter {it.task.completed()}.distinctBy {it.task.id}
            return TodayOverviewProjection(scheduled.filterNot {it.task.completed()},
                timeline.unscheduled.map {TodayOverviewEntry(it,emptyList())},done)
        }
    }
}
