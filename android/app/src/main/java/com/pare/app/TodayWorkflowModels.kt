package com.pare.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

internal data class SupportReason(val code: String, val label: String, val guidance: String)
internal val supportReasons = listOf(
    SupportReason("unclear", "不知道下一步", "先找出一个能看见结果的动作，例如打开资料并写下一个问题。"),
    SupportReason("tooLarge", "任务太大", "只选一小部分：一段文字、一页资料或一个待验证的问题。写清做到哪里就停。"),
    SupportReason("lowEnergy", "精力不足", "挑一个现在做得动的小动作，例如打开文件、列三点，或整理一份材料。先安排5分钟。"),
    SupportReason("resistance", "有压力 / 抗拒", "先写出让你犹豫的一个问题，再选一个可以撤回、无需一次做好的动作。")
)

internal fun validTodayMinutes(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it in 1..1440 }
internal fun taskCanBePlanned(task: Entity?): Boolean = task != null && !task.deleted && task.content() != null &&
    !task.completed() && task.payload?.get("lifecycle")?.jsonPrimitive?.contentOrNull != "archived"

/** The callback writes into this activity-retained model, never into a disposed dialog. */
class TodayWorkflowModel : ViewModel() {
    internal var initialized = false
    internal var allocationBaseline: DayAllocationBaseline? = null
    internal var smallStepBaseline: SmallStepBaseline? = null
    internal var allocationId = uid()
    internal var blockerEntryId = uid()
    var kind by mutableStateOf("personal")
    var minutes by mutableStateOf("5")
    var reason by mutableStateOf("unclear")
    var nextAction by mutableStateOf("")
    var definitionOfDone by mutableStateOf("")
    var saving by mutableStateOf(false); private set
    var saved by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var reloadRequested by mutableStateOf(false); private set

    internal fun initialize(state: SyncState, taskId: String, now: Instant, support: Boolean) {
        if (initialized) return
        initialized = true
        val baseline = DayAllocationBaseline.from(state, taskId, now)
        allocationBaseline = baseline
        val task = state.items["task:$taskId"]
        smallStepBaseline = task?.let { SmallStepBaseline.from(it) }
        kind = baseline.kind?.takeIf { it == "check" } ?: "personal"
        minutes = if (support) "5" else (baseline.scheduledMinutes.takeIf { baseline.hasCurrentDaySchedule } ?: baseline.plannedMinutes ?: 5).toString()
        nextAction = task?.content()?.get("nextAction")?.jsonPrimitive?.contentOrNull.orEmpty()
        definitionOfDone = task?.content()?.get("definitionOfDone")?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    internal fun allocation(): TodayAllocationDraft? {
        val expected = allocationBaseline ?: return null
        val amount = validTodayMinutes(minutes) ?: return null
        return TodayAllocationDraft(expected.planId, kind, amount, allocationId, expected)
    }

    fun begin(): Boolean {
        if (saving || saved) return false
        saving = true
        error = null
        return true
    }
    fun finish(result: Result<Unit>) {
        saving = false
        saved = result.isSuccess
        error = result.exceptionOrNull()?.message ?: if (result.isFailure) "保存失败，草稿已保留，请重试。" else null
    }
    fun requestReload() { if (!saving) reloadRequested = true }
    fun cancelReload() { reloadRequested = false }
    internal fun confirmReload(state: SyncState, taskId: String, now: Instant, support: Boolean) {
        if (!reloadRequested || saving || !taskCanBePlanned(state.items["task:$taskId"])) return
        clear()
        initialize(state, taskId, now, support)
    }
    internal fun clear() {
        initialized = false; saved = false; error = null; reloadRequested = false
        allocationBaseline = null; smallStepBaseline = null
        allocationId = uid(); blockerEntryId = uid()
        reason = "unclear"
    }
}
