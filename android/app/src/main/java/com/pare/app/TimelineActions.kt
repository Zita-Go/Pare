package com.pare.app

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

data class TimelineCanonicalBaseline(val planId: String, val planExists: Boolean, val entry: JsonObject?, val included: Boolean)
data class TimelineLegacyPeerBaseline(val planId: String, val scheduledAt: String, val scheduledMinutes: Int?, val reminderLeadMinutes: Int?)

/** Baselines compare only fields consumed by this action; unrelated task/plan data can merge. */
data class TimelineScheduleBaseline(
    val planId: String,
    val taskId: String,
    val planningZone: String,
    val scheduledAt: String?,
    val scheduledMinutes: Int?,
    val reminderLeadMinutes: Int?,
    val included: Boolean,
    val taskTitle: String?,
    val taskExists: Boolean,
    val planDeleted: Boolean,
    val planExists: Boolean,
    val canonical: TimelineCanonicalBaseline? = null,
    val legacyPeers: List<TimelineLegacyPeerBaseline> = emptyList(),
    val owner: String? = null,
    val device: String? = null
) {
    companion object {
        fun from(state: SyncState, planId: String, taskId: String): TimelineScheduleBaseline {
            val plan = state.items["day_plan:$planId"]
            val content = plan?.takeUnless {it.deleted}?.content()
            val entry = (content?.get("task_plans") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .firstOrNull {it.timelineString("task_id") == taskId}
            val task = state.items["task:$taskId"]
            val canonicalId = entry?.timelineString("scheduled_at")?.let {value -> runCatching {
                val sourceDay = TimelineDay.fromPlanId(planId)
                "${Instant.parse(value).atZone(sourceDay.zone).toLocalDate()}@${sourceDay.zone.id}"
            }.getOrNull()}?.takeUnless {it == planId}
            val canonical = canonicalId?.let {id ->
                val target = state.items["day_plan:$id"]
                TimelineCanonicalBaseline(id, target != null, timelineEntry(state, id, taskId),
                    taskId in target?.takeUnless {it.deleted}?.content().timelineIds())
            }
            val peers = if (canonical == null) emptyList() else timelineLegacyPeers(state, taskId, planId.substringAfter('@'))
                .filter {it.planId.substringBefore('@') < planId.substringBefore('@')}
            return TimelineScheduleBaseline(planId, taskId, timelinePlanningZone(state), entry?.timelineString("scheduled_at"),
                entry?.timelineInt("scheduled_minutes"), entry?.timelineInt("schedule_reminder_lead_minutes"),
                taskId in content.timelineIds(), task?.title(), task != null, plan?.deleted == true, plan != null, canonical, peers, state.owner, state.device)
        }
    }
}

/** Captured when the destination is selected; no destination state is inferred at commit time. */
data class TimelineMoveBaseline(val owner:String?,val device:String,val source:TimelineScheduleBaseline,val targetPlanId:String,
    val plans:Map<String,Entity?>,val peerPlanIds:Set<String>) {
    companion object {
        fun capture(state:SyncState,source:TimelineScheduleBaseline,targetPlanId:String):TimelineMoveBaseline {
            val target=TimelineDay.fromPlanId(targetPlanId)
            check(source.device==null || (source.device==state.device && source.owner==state.owner)) {"资料库已改变，请重新打开安排"}
            check(TimelineDay.fromPlanId(source.planId).zone.id==source.planningZone) {"原安排采用不同的规划时区，请先确认原日期的时区"}
            check(target.zone.id==source.planningZone && timelinePlanningZone(state)==source.planningZone) {"规划时区已改变，请重新打开安排"}
            val current=TimelineScheduleBaseline.from(state,source.planId,source.taskId)
            check(source.scheduledAt!=null && current.scheduledAt==source.scheduledAt && current.scheduledMinutes==source.scheduledMinutes &&
                current.reminderLeadMinutes==source.reminderLeadMinutes && current.included==source.included && current.taskTitle==source.taskTitle) {"原安排已更新，请重新打开"}
            check(TimelineProjection.schedules(state).any {it.sourcePlanId==source.planId && it.task.id==source.taskId && it.start==Instant.parse(source.scheduledAt)}) {"原安排已被新版本替代，请重新打开"}
            require(TimelineProjection.schedules(state).none {it.task.id==source.taskId && it.start.atZone(target.zone).toLocalDate()==target.date &&
                (it.sourcePlanId!=source.planId || it.start!=Instant.parse(source.scheduledAt))}) {"目标日期已有这项任务的时间块，请先处理目标安排"}
            val legacy=timelineLegacyPeers(state,source.taskId,source.planningZone)
            val lastSnapshot=legacy.filter {Instant.parse(it.scheduledAt)==Instant.parse(source.scheduledAt)}.maxByOrNull {it.planId}
            val peers=if(lastSnapshot==null) emptySet() else legacy.filter {it.planId<=lastSnapshot.planId && it.planId!=source.planId}.map {it.planId}.toSet()
            val ids=setOf(source.planId,targetPlanId)+peers
            ids.forEach {id->state.items["day_plan:$id"]?.let {check(!it.deleted && it.content()!=null && it.conflict==null && it.conflictGroup.isEmpty()) {"相关日期已删除或存在冲突，请先处理"}}}
            return TimelineMoveBaseline(state.owner,state.device,source,targetPlanId,ids.associateWith {state.items["day_plan:$it"]},peers)
        }
    }
}

data class TimelineOrderBaseline(val planId: String, val planningZone: String, val orderedTaskIds: List<String>, val planExists: Boolean) {
    companion object {
        fun from(state: SyncState, planId: String) = TimelineOrderBaseline(planId, timelinePlanningZone(state),
            state.items["day_plan:$planId"]?.takeUnless {it.deleted}?.content().timelineIds(), state.items["day_plan:$planId"] != null)
    }
}

object TimelineActions {
    fun setSchedule(state: SyncState, taskId: String, planId: String, start: Instant, durationMinutes: Int,
        expected: TimelineScheduleBaseline, reminderLeadMinutes: Int? = expected.reminderLeadMinutes): SyncState {
        val day = TimelineDay.fromPlanId(planId)
        val targetId = "${start.atZone(day.zone).toLocalDate()}@${day.zone.id}"
        require(targetId == (expected.canonical?.planId ?: planId)) { "开始日期已改变，请重新打开对应日期的安排；跨午夜的结束时间会保留" }
        require(durationMinutes in 1..1440) { "时间块请填写 1–1440 分钟" }
        require(reminderLeadMinutes == null || reminderLeadMinutes in 0..525600) { "提醒提前量无效" }
        val current = guardSchedule(state, taskId, planId, expected)
        if (expected.canonical != null) return migrateLegacy(state, taskId, expected, current, start, durationMinutes, reminderLeadMinutes)
        if (current.included && current.scheduledAt == start.toString() && current.scheduledMinutes == durationMinutes &&
            current.reminderLeadMinutes == reminderLeadMinutes) return state
        requireScheduleUnchanged(current, expected)
        return updateEntry(state, taskId, planId, include = true) {entry ->
            entry["scheduled_at"] = JsonPrimitive(start.toString())
            entry["scheduled_minutes"] = JsonPrimitive(durationMinutes)
            if (reminderLeadMinutes == null) entry.remove("schedule_reminder_lead_minutes")
            else entry["schedule_reminder_lead_minutes"] = JsonPrimitive(reminderLeadMinutes)
        }
    }

    /** Relocate one occurrence; preserve its allocation/reminder and leave unrelated occurrences intact. */
    fun moveToDay(state:SyncState,taskId:String,start:Instant,durationMinutes:Int,expected:TimelineMoveBaseline,
        reminderLeadMinutes:Int?=expected.source.reminderLeadMinutes):SyncState {
        check(state.owner==expected.owner && state.device==expected.device) {"资料库已改变，安排未移动"}
        val source=expected.source;val destination=TimelineDay.fromPlanId(expected.targetPlanId)
        require(taskId==source.taskId && start.atZone(destination.zone).toLocalDate()==destination.date) {"目标日期不匹配"}
        require(durationMinutes in 1..1440 && (reminderLeadMinutes==null || reminderLeadMinutes in 0..525600)) {"时长或提醒提前量无效"}
        check(timelinePlanningZone(state)==source.planningZone && destination.zone.id==source.planningZone) {"规划时区已改变，请重新打开安排"}
        state.items["profile:profile"]?.let {check(it.conflict==null && it.conflictGroup.isEmpty()) {"请先处理规划时区的同步冲突"}}
        val task=state.items["task:$taskId"] ?: throw StaleContentEditException(StaleEditReason.DELETED)
        guardEntity(task);check(timelineActiveTask(task) && task.title()==source.taskTitle) {"任务状态已改变，安排未移动"}
        val original=expected.plans.getValue(source.planId) ?: throw StaleContentEditException(StaleEditReason.DELETED)
        val sourceEntry=(original.content()?.get("task_plans") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().first {it.timelineString("task_id")==taskId}
        val target=expected.plans.getValue(expected.targetPlanId)
        if(expected.targetPlanId!=source.planId) {
            val occupied=taskId in target?.content().timelineIds() || (target?.content()?.get("task_plans") as? JsonArray).orEmpty().any {(it as? JsonObject)?.timelineString("task_id")==taskId}
            require(!occupied) {"目标日期已有这项任务的安排或投入，请先在目标日期处理；没有覆盖已有内容"}
        }
        val desired=expected.plans.mapValues {(_,e)->e?.payload}.toMutableMap()
        fun content(id:String):MutableMap<String,JsonElement> = (desired[id]?.get("content") as? JsonObject)?.toMutableMap() ?: mutableMapOf(
            "day_key" to JsonPrimitive(id.substringBefore('@')),"time_zone" to JsonPrimitive(id.substringAfter('@')),
            "ordered_task_ids" to JsonArray(emptyList()),"task_plans" to JsonArray(emptyList()))
        fun put(id:String,c:Map<String,JsonElement>) {desired[id]=JsonObject((desired[id] ?: JsonObject(emptyMap())).toMutableMap().apply {this["content"]=JsonObject(c)})}
        if(source.planId!=expected.targetPlanId) {
            val c=content(source.planId)
            c["ordered_task_ids"]=JsonArray(JsonObject(c).timelineIds().filterNot {it==taskId}.map(::JsonPrimitive))
            c["task_plans"]=JsonArray((c["task_plans"] as? JsonArray).orEmpty().filterNot {(it as? JsonObject)?.timelineString("task_id")==taskId})
            if((c["daily_win_task_id"] as? JsonPrimitive)?.contentOrNull==taskId)c.remove("daily_win_task_id")
            put(source.planId,c)
        }
        expected.peerPlanIds.filter {it!=expected.targetPlanId}.forEach {id->
            val c=content(id)
            c["task_plans"]=JsonArray((c["task_plans"] as? JsonArray).orEmpty().map {value->
                val e=value as? JsonObject
                if(e?.timelineString("task_id")==taskId) JsonObject(e.toMutableMap().apply {remove("scheduled_at");remove("scheduled_minutes");remove("schedule_reminder_lead_minutes")}) else value
            });put(id,c)
        }
        val targetContent=content(expected.targetPlanId)
        targetContent["ordered_task_ids"]=JsonArray((JsonObject(targetContent).timelineIds()+taskId).distinct().map(::JsonPrimitive))
        val moved=sourceEntry.toMutableMap().apply {
            put("scheduled_at",JsonPrimitive(start.toString()));put("scheduled_minutes",JsonPrimitive(durationMinutes))
            if(reminderLeadMinutes==null)remove("schedule_reminder_lead_minutes") else put("schedule_reminder_lead_minutes",JsonPrimitive(reminderLeadMinutes))
        }
        targetContent["task_plans"]=JsonArray((targetContent["task_plans"] as? JsonArray).orEmpty().filterNot {(it as? JsonObject)?.timelineString("task_id")==taskId}+JsonObject(moved))
        put(expected.targetPlanId,targetContent)
        // Repeated callback after a successful commit is a no-op; any later edit is protected.
        if(desired.all {(id,payload)->state.items["day_plan:$id"]?.let {!it.deleted && it.conflict==null && it.conflictGroup.isEmpty() && it.payload==payload}==true}) return state
        expected.plans.forEach {(id,old)->
            guardPlan(state,id,source.planningZone,old!=null)
            val current=state.items["day_plan:$id"]
            staleIf(current?.payload!=old?.payload || current?.deleted!=old?.deleted)
        }
        // New source snapshots must not be left behind to revive after the move.
        val recaptured=TimelineMoveBaseline.capture(state,source,expected.targetPlanId)
        staleIf(recaptured.peerPlanIds!=expected.peerPlanIds)
        var next=state
        desired.forEach {(id,payload)->next=StateMachine.edit(next,"day_plan",id,requireNotNull(payload))}
        val keys=desired.keys.map {"day_plan:$it"}.toSet()
        val grouped=StateMachine.group(next,keys);val groups=keys.map {grouped.items.getValue(it).group}.toSet()
        val members=grouped.items.values.filter {it.dirty && it.group in groups}
        require(members.size<=100 && wire.encodeToString(members.map {Mutation(it.kind,it.id,it.revision,deleted=it.deleted,payload=it.payload)}).toByteArray().size<=1_048_576) {"这次移动关联内容超过同步上限，尚未保存"}
        desired.forEach {(id,payload)->PayloadValidation.validate("day_plan",id,requireNotNull(payload))}
        return grouped
    }

    /** Keeps membership, ordering, daily win, allocation and every unknown field. */
    fun clearSchedule(state: SyncState, taskId: String, planId: String, expected: TimelineScheduleBaseline): SyncState {
        val current = guardSchedule(state, taskId, planId, expected)
        if (expected.canonical != null) return migrateLegacy(state, taskId, expected, current, null, null, null)
        if (current.scheduledAt == null && current.scheduledMinutes == null && current.reminderLeadMinutes == null) return state
        requireScheduleUnchanged(current, expected)
        return updateEntry(state, taskId, planId, include = false) {
            it.remove("scheduled_at"); it.remove("scheduled_minutes"); it.remove("schedule_reminder_lead_minutes")
        }
    }

    /** One immutable transition, then the repository persists the task and plan in one atomic group. */
    fun createScheduledTask(state: SyncState, taskId: String, title: String, quadrant: Int, planId: String,
        start: Instant, durationMinutes: Int, expected: TimelineScheduleBaseline, now: Instant,
        nextAction: String = "", reminderLeadMinutes: Int? = null): SyncState {
        require(UUID.fromString(taskId).toString() == taskId) { "任务标识无效" }
        require(title.isNotBlank() && quadrant in 0..3) { "请填写任务并选择象限" }
        check(!expected.taskExists && expected.taskId == taskId && expected.planId == planId) { "创建草稿不匹配，请重新打开" }
        guardPlan(state, planId, expected.planningZone, expected.planExists)
        val old = state.items["task:$taskId"]
        if (old != null) {
            guardEntity(old)
            val current = TimelineScheduleBaseline.from(state, planId, taskId)
            if (!timelineActiveTask(old) || old.title() != title.trim() || old.content()?.timelineInt("quadrant") != quadrant ||
                old.content()?.timelineString("nextAction").orEmpty() != nextAction.trim() || !current.included ||
                current.scheduledAt != start.toString() || current.scheduledMinutes != durationMinutes || current.reminderLeadMinutes != reminderLeadMinutes) {
                throw StaleContentEditException(StaleEditReason.ALREADY_EXISTS)
            }
            return state
        }
        val payload = ContentActions.save(null, "task", title.trim(), "", quadrant, taskId, now, nextAction = nextAction)
        val created = StateMachine.edit(state, "task", taskId, payload)
        val scheduled = setSchedule(created, taskId, planId, start, durationMinutes,
            expected.copy(taskExists = true, taskTitle = title.trim()), reminderLeadMinutes)
        return StateMachine.group(scheduled, setOf("task:$taskId", "day_plan:$planId"))
    }

    /** A subset permutation changes only its slots. New remote suffix appends remain in place. */
    fun reorder(state: SyncState, planId: String, orderedTaskIds: List<String>, expected: TimelineOrderBaseline): SyncState {
        staleIf(expected.planId != planId)
        guardPlan(state, planId, expected.planningZone, expected.planExists)
        require(orderedTaskIds.distinct().size == orderedTaskIds.size && expected.orderedTaskIds.containsAll(orderedTaskIds)) { "任务顺序无效" }
        val selected = orderedTaskIds.toSet()
        val iterator = orderedTaskIds.iterator()
        val desired = expected.orderedTaskIds.map {if (it in selected) iterator.next() else it}
        val old = state.items["day_plan:$planId"] ?: return state
        val current = old.content().timelineIds()
        // Only suffix appends are safe: an insertion, deletion or reorder must be reviewed first.
        val suffix = current.drop(expected.orderedTaskIds.size)
        staleIf(current.size < expected.orderedTaskIds.size || suffix.any {it in expected.orderedTaskIds})
        val prefix = current.take(expected.orderedTaskIds.size)
        if (prefix == desired) return state
        staleIf(prefix != expected.orderedTaskIds)
        val content = old.content()!!.toMutableMap()
        content["ordered_task_ids"] = JsonArray((desired + suffix).map(::JsonPrimitive))
        return writePlan(state, planId, content)
    }

    private fun guardSchedule(state: SyncState, taskId: String, planId: String, expected: TimelineScheduleBaseline): TimelineScheduleBaseline {
        staleIf(expected.planId != planId || expected.taskId != taskId)
        guardPlan(state, planId, expected.planningZone, expected.planExists)
        val task = state.items["task:$taskId"] ?: throw StaleContentEditException(StaleEditReason.DELETED)
        guardEntity(task)
        check(timelineActiveTask(task)) { "任务已完成或归档，时间安排未修改" }
        staleIf(!expected.taskExists || task.title() != expected.taskTitle)
        return TimelineScheduleBaseline.from(state, planId, taskId)
    }

    private fun requireScheduleUnchanged(current: TimelineScheduleBaseline, expected: TimelineScheduleBaseline) {
        staleIf(current.scheduledAt != expected.scheduledAt || current.scheduledMinutes != expected.scheduledMinutes ||
            current.reminderLeadMinutes != expected.reminderLeadMinutes || current.included != expected.included)
    }

    /** Older Mac plans can hold a future schedule. Normalize only on an explicit edit, atomically. */
    private fun migrateLegacy(state: SyncState, taskId: String, expected: TimelineScheduleBaseline,
        current: TimelineScheduleBaseline, start: Instant?, durationMinutes: Int?, reminderLeadMinutes: Int?): SyncState {
        val target = requireNotNull(expected.canonical)
        guardPlan(state, target.planId, expected.planningZone, target.planExists)
        val targetEntry = timelineEntry(state, target.planId, taskId)
        val targetIncluded = taskId in state.items["day_plan:${target.planId}"]?.content().timelineIds()
        val capturedPeers = expected.legacyPeers.associateBy {it.planId}
        // A newly discovered snapshot could be newer than the dialog or add history we have
        // not guarded. Do not normalize an outdated source or leave an uncaptured ghost behind.
        val discoveredPeers = timelineLegacyPeers(state, taskId, expected.planId.substringAfter('@'))
            .filter {it.planId != expected.planId}
        staleIf(discoveredPeers.any {it.planId !in capturedPeers})
        val peerEntries = expected.legacyPeers.associate {peer ->
            guardPlan(state, peer.planId, expected.planningZone, expectedExists = true)
            peer.planId to timelineEntry(state, peer.planId, taskId)
        }
        val sourceCleared = current.scheduledAt == null && current.scheduledMinutes == null && current.reminderLeadMinutes == null
        val targetMatches = targetEntry != null && targetIncluded && targetEntry.timelineString("scheduled_at") == start?.toString() &&
            targetEntry.timelineInt("scheduled_minutes") == durationMinutes && targetEntry.timelineInt("schedule_reminder_lead_minutes") == reminderLeadMinutes
        // A persisted success may be retried after rotation/reload. Never write either plan again.
        val peersCleared = peerEntries.values.all {entry -> entry != null && scheduleIsCleared(entry)}
        if (sourceCleared && current.included == expected.included && targetMatches && peersCleared) return state
        requireScheduleUnchanged(current, expected)
        staleIf(targetEntry != target.entry || targetIncluded != target.included)
        expected.legacyPeers.forEach {peer ->
            val entry = peerEntries[peer.planId]
            staleIf(entry?.timelineString("scheduled_at") != peer.scheduledAt || entry.timelineInt("scheduled_minutes") != peer.scheduledMinutes ||
                entry.timelineInt("schedule_reminder_lead_minutes") != peer.reminderLeadMinutes)
        }
        val sourceEntry = requireNotNull(timelineEntry(state, expected.planId, taskId))
        // Target-specific allocation/unknown values take precedence; missing fields are copied.
        val merged = sourceEntry.toMutableMap().apply {targetEntry?.let {putAll(it)}}
        if (start == null) {
            merged.remove("scheduled_at"); merged.remove("scheduled_minutes"); merged.remove("schedule_reminder_lead_minutes")
        } else {
            merged["scheduled_at"] = JsonPrimitive(start.toString())
            merged["scheduled_minutes"] = JsonPrimitive(requireNotNull(durationMinutes))
            if (reminderLeadMinutes == null) merged.remove("schedule_reminder_lead_minutes")
            else merged["schedule_reminder_lead_minutes"] = JsonPrimitive(reminderLeadMinutes)
        }
        var result = updateEntry(state, taskId, target.planId, include = true) {entry -> entry.clear(); entry.putAll(merged)}
        result = updateEntry(result, taskId, expected.planId, include = false) {
            it.remove("scheduled_at"); it.remove("scheduled_minutes"); it.remove("schedule_reminder_lead_minutes")
        }
        expected.legacyPeers.forEach {peer ->
            result = updateEntry(result, taskId, peer.planId, include = false) {
                it.remove("scheduled_at"); it.remove("scheduled_minutes"); it.remove("schedule_reminder_lead_minutes")
            }
        }
        val keys = setOf("day_plan:${expected.planId}", "day_plan:${target.planId}") + expected.legacyPeers.map {"day_plan:${it.planId}"}
        val grouped = StateMachine.group(result, keys)
        val groupId = grouped.items.getValue("day_plan:${expected.planId}").group
        // Include inherited dirty groups, not just the plans edited here. The repository only
        // commits a returned state; throwing now leaves all source, target and peer data intact.
        require(grouped.items.values.count {it.dirty && it.group == groupId} <= 100) { "这次迁移关联的同步内容超过100条，尚未修改任何安排" }
        return grouped
    }

    private fun scheduleIsCleared(entry: JsonObject): Boolean = entry.timelineString("scheduled_at") == null &&
        entry.timelineInt("scheduled_minutes") == null && entry.timelineInt("schedule_reminder_lead_minutes") == null

    private fun guardPlan(state: SyncState, planId: String, planningZone: String, expectedExists: Boolean) {
        val day = TimelineDay.fromPlanId(planId)
        staleIf(timelinePlanningZone(state) != planningZone)
        state.items["profile:profile"]?.let {check(it.conflict == null && it.conflictGroup.isEmpty()) { "请先处理规划时区的同步冲突" }}
        val plan = state.items["day_plan:$planId"]
        if (plan == null) {
            if (expectedExists) throw StaleContentEditException(StaleEditReason.DELETED)
            return
        }
        guardEntity(plan)
        staleIf(plan.content()?.timelineString("day_key") != day.date.toString() || plan.content()?.timelineString("time_zone") != day.zone.id)
    }

    private fun guardEntity(entity: Entity) {
        if (entity.deleted || entity.content() == null) throw StaleContentEditException(StaleEditReason.DELETED)
        check(entity.conflict == null && entity.conflictGroup.isEmpty()) { "请先处理这条内容的同步冲突，时间安排未修改" }
    }

    private fun updateEntry(state: SyncState, taskId: String, planId: String, include: Boolean,
        change: (MutableMap<String, JsonElement>) -> Unit): SyncState {
        val content = state.items["day_plan:$planId"]?.content()?.toMutableMap() ?: mutableMapOf(
            "day_key" to JsonPrimitive(planId.substringBefore('@')), "time_zone" to JsonPrimitive(planId.substringAfter('@')),
            "ordered_task_ids" to JsonArray(emptyList()), "task_plans" to JsonArray(emptyList()))
        if (include) content["ordered_task_ids"] = JsonArray((JsonObject(content).timelineIds() + taskId).distinct().map(::JsonPrimitive))
        val entries = (content["task_plans"] as? JsonArray).orEmpty().toMutableList()
        val index = entries.indexOfFirst {(it as? JsonObject)?.timelineString("task_id") == taskId}
        val entry = if (index < 0) mutableMapOf<String, JsonElement>("task_id" to JsonPrimitive(taskId)) else entries[index].jsonObject.toMutableMap()
        change(entry)
        if (index < 0) entries.add(JsonObject(entry)) else entries[index] = JsonObject(entry)
        content["task_plans"] = JsonArray(entries)
        return writePlan(state, planId, content)
    }

    private fun writePlan(state: SyncState, planId: String, content: Map<String, JsonElement>): SyncState {
        val payload = state.items["day_plan:$planId"]?.payload.orEmpty().toMutableMap().apply {put("content", JsonObject(content))}
        return StateMachine.edit(state, "day_plan", planId, JsonObject(payload))
    }

    private fun staleIf(condition: Boolean) {if (condition) throw StaleContentEditException(StaleEditReason.UPDATED)}
}

private fun JsonObject?.timelineIds(): List<String> = (this?.get("ordered_task_ids") as? JsonArray).orEmpty()
    .mapNotNull {(it as? JsonPrimitive)?.contentOrNull}

private fun timelineEntry(state: SyncState, planId: String, taskId: String): JsonObject? =
    (state.items["day_plan:$planId"]?.takeUnless {it.deleted}?.content()?.get("task_plans") as? JsonArray).orEmpty()
        .filterIsInstance<JsonObject>().firstOrNull {it.timelineString("task_id") == taskId}

private fun timelineLegacyPeers(state: SyncState, taskId: String, zone: String): List<TimelineLegacyPeerBaseline> =
    state.items.values.filter {it.kind == "day_plan" && !it.deleted && it.id.substringAfter('@') == zone}.mapNotNull {plan ->
        val entry = timelineEntry(state, plan.id, taskId) ?: return@mapNotNull null
        val value = entry.timelineString("scheduled_at") ?: return@mapNotNull null
        val sourceDay = runCatching {TimelineDay.fromPlanId(plan.id)}.getOrNull() ?: return@mapNotNull null
        val startDay = runCatching {Instant.parse(value).atZone(sourceDay.zone).toLocalDate()}.getOrNull() ?: return@mapNotNull null
        if (startDay == sourceDay.date) return@mapNotNull null
        TimelineLegacyPeerBaseline(plan.id, value, entry.timelineInt("scheduled_minutes"), entry.timelineInt("schedule_reminder_lead_minutes"))
    }.sortedBy {it.planId}
