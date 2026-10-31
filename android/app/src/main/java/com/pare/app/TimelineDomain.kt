package com.pare.app

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt
import kotlinx.serialization.json.*

/** Uses the same account/fallback zone as the existing Today workflow. */
internal fun timelinePlanningZone(state: SyncState): String = ContentActions.planId(state, Instant.EPOCH).substringAfter('@')

data class TimelineDay(val date: LocalDate, val zone: ZoneId) {
    val planId: String get() = "$date@${zone.id}"
    val start: Instant get() = date.atStartOfDay(zone).toInstant()
    val end: Instant get() = date.plusDays(1).atStartOfDay(zone).toInstant()
    val minutes: Int get() = Duration.between(start, end).toMinutes().toInt()

    companion object {
        fun from(state: SyncState, date: LocalDate) = TimelineDay(date, ZoneId.of(timelinePlanningZone(state)))
        fun today(state: SyncState, now: Instant): TimelineDay {
            val zone = ZoneId.of(timelinePlanningZone(state))
            return TimelineDay(now.atZone(zone).toLocalDate(), zone)
        }
        fun fromPlanId(planId: String): TimelineDay {
            val day = TimelineDay(LocalDate.parse(planId.substringBefore('@')), ZoneId.of(planId.substringAfter('@')))
            require(day.planId == planId) { "日期或规划时区无效" }
            return day
        }
    }
}

object TimelineTime {
    fun snapMinute(rawMinute: Double, step: Int = 15): Int {
        require(rawMinute.isFinite() && step > 0)
        return ((rawMinute / step).roundToInt().toLong() * step).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }
    /** Axis coordinates are elapsed minutes, so both occurrences of a repeated hour are reachable. */
    fun instantAt(day: TimelineDay, minute: Int): Instant {
        require(minute in 0..day.minutes) { "时间超出所选日期" }
        return day.start.plusSeconds(minute * 60L)
    }
    /** A text/time picker must not silently shift spring gaps or select one side of an autumn fold. */
    fun resolveLocal(day: TimelineDay, minuteOfDay: Int, preferredOffset: ZoneOffset? = null): Instant {
        require(minuteOfDay in 0..1439) { "请填写有效的时分" }
        val local = day.date.atStartOfDay().plusMinutes(minuteOfDay.toLong())
        val offsets = day.zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "夏令时切换使这个时间不存在，请选择其他时间" }
        val offset = if (offsets.size == 1) offsets.single() else {
            require(preferredOffset != null && preferredOffset in offsets) { "这个时间出现两次，请选择对应的时区偏移" }
            preferredOffset
        }
        return local.toInstant(offset)
    }
}

data class TimelineBlock(
    val key: String,
    val sourcePlanId: String,
    val task: Entity,
    val start: Instant,
    val end: Instant,
    val durationMinutes: Int,
    val startMinute: Double,
    val endMinute: Double,
    val lane: Int = 0,
    val laneCount: Int = 1
)

/** One authoritative occurrence shared by the calendar and local reminder planning. */
data class TimelineSchedule(val sourcePlanId: String, val task: Entity, val start: Instant, val end: Instant,
    val durationMinutes: Int, val reminderLeadMinutes: Int?)

data class TimelineProjection(val day: TimelineDay, val blocks: List<TimelineBlock>, val unscheduled: List<Entity>) {
    companion object {
        fun schedules(state: SyncState): List<TimelineSchedule> {
            // Completion remains part of the day's calendar history. Only unfinished tasks
            // belong on the unscheduled shelf; archive and deletion still remove either kind.
            val tasks = state.items.values.filter {
                it.kind == "task" && !it.deleted && it.content() != null && it.payload?.timelineString("lifecycle") != "archived"
            }.associateBy { it.id }
            val candidates = state.items.values.filter {it.kind == "day_plan" && !it.deleted}.flatMap { plan ->
                (plan.content()?.get("task_plans") as? JsonArray).orEmpty().mapNotNull { value ->
                    val entry = value as? JsonObject ?: return@mapNotNull null
                    val task = tasks[entry.timelineString("task_id")] ?: return@mapNotNull null
                    val start = entry.timelineString("scheduled_at")?.let {runCatching {Instant.parse(it)}.getOrNull()} ?: return@mapNotNull null
                    if (runCatching {TimelineDay.fromPlanId(plan.id)}.isFailure) return@mapNotNull null
                    // Legacy records without an explicit block duration have a neutral display default.
                    // Total task estimates describe different work and must not grow today's block.
                    val duration = entry.timelineInt("scheduled_minutes")?.takeIf {it > 0} ?: 30
                    val end = runCatching {start.plusSeconds(duration * 60L)}.getOrNull() ?: return@mapNotNull null
                    TimelineSchedule(plan.id, task, start, end, duration, entry.timelineInt("schedule_reminder_lead_minutes"))
                }
            }
            fun isCanonical(schedule: TimelineSchedule): Boolean {
                val source = TimelineDay.fromPlanId(schedule.sourcePlanId)
                return schedule.start.atZone(source.zone).toLocalDate() == source.date
            }
            val (canonical, legacy) = candidates.partition(::isCanonical)
            // Mac writes its single scheduledAt as snapshots in the active day's plan. Among
            // noncanonical entries only, the newest source day supplies that task's snapshot.
            // Select before authority filtering so superseded future dates cannot reappear when
            // the newest snapshot has moved into a canonical plan. Missing later entries say
            // nothing about cancellation. Canonical multi-day arrangements remain independent.
            val latestLegacy = legacy.groupBy {it.task.id to it.sourcePlanId.substringAfter('@')}.values
                .map {copies -> copies.maxBy {it.sourcePlanId.substringBefore('@')}}.filter {schedule ->
                    val source = TimelineDay.fromPlanId(schedule.sourcePlanId)
                    val originId = "${schedule.start.atZone(source.zone).toLocalDate()}@${source.zone.id}"
                    val origin = state.items["day_plan:$originId"]
                    val authoritative = (origin?.takeUnless {it.deleted}?.content()?.get("task_plans") as? JsonArray)
                        .orEmpty().filterIsInstance<JsonObject>().any {it.timelineString("task_id") == schedule.task.id}
                    // Full origin entry wins even if it was cleared or shortened past this day.
                    // An origin containing only other tasks is not evidence of cancellation.
                    origin?.deleted != true && !authoritative
                }
            return (canonical + latestLegacy).groupBy {it.task.id to it.start}.values.map {copies ->
                copies.minWith(compareBy<TimelineSchedule>({
                    if (isCanonical(it)) 0 else 1
                }).thenByDescending {it.sourcePlanId})
            }.sortedWith(compareBy<TimelineSchedule>({it.start}, {it.end}, {it.task.id}, {it.sourcePlanId}))
        }

        fun project(state: SyncState, day: TimelineDay): TimelineProjection {
            val visible = schedules(state).filter {it.start < day.end && it.end > day.start}.map {
                TimelineBlock("${it.sourcePlanId}:${it.task.id}", it.sourcePlanId, it.task, it.start, it.end, it.durationMinutes,
                    Duration.between(day.start, maxOf(it.start, day.start)).toMillis() / 60000.0,
                    Duration.between(day.start, minOf(it.end, day.end)).toMillis() / 60000.0)
            }.sortedWith(compareBy<TimelineBlock>({it.startMinute}, {it.endMinute}, {it.task.id}, {it.sourcePlanId}))
            val blocks = placeLanes(visible)
            val scheduledIds = blocks.map {it.task.id}.toSet()
            val plan = state.items["day_plan:${day.planId}"]?.takeUnless {it.deleted}?.content()
            val unscheduled = (plan?.get("ordered_task_ids") as? JsonArray).orEmpty()
                .mapNotNull {(it as? JsonPrimitive)?.contentOrNull}.distinct()
                .filterNot {it in scheduledIds}.mapNotNull {state.items["task:$it"]?.takeIf(::timelineActiveTask)}
            return TimelineProjection(day, blocks, unscheduled)
        }

        private fun placeLanes(blocks: List<TimelineBlock>): List<TimelineBlock> {
            val output = mutableListOf<TimelineBlock>()
            var index = 0
            while (index < blocks.size) {
                var end = blocks[index].endMinute
                var stop = index + 1
                while (stop < blocks.size && blocks[stop].startMinute < end) {
                    end = maxOf(end, blocks[stop].endMinute)
                    stop++
                }
                val laneEnds = mutableListOf<Double>()
                val group = blocks.subList(index, stop).map {block ->
                    val available = laneEnds.indexOfFirst {it <= block.startMinute}
                    val lane = if (available < 0) laneEnds.size.also {laneEnds.add(block.endMinute)}
                        else available.also {laneEnds[it] = block.endMinute}
                    block.copy(lane = lane)
                }
                output += group.map {it.copy(laneCount = laneEnds.size)}
                index = stop
            }
            return output
        }
    }
}

internal fun JsonObject.timelineString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.timelineInt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun timelineActiveTask(task: Entity): Boolean = task.kind == "task" && !task.deleted && task.content() != null &&
    !task.completed() && task.payload?.timelineString("lifecycle") != "archived"
