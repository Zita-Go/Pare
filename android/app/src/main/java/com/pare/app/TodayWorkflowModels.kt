package com.pare.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/** The callback writes into this activity-retained model, never into a disposed dialog. */
class TodayWorkflowModel : ViewModel() {
    internal var initialized = false
    internal var allocationBaseline: DayAllocationBaseline? = null
    internal var smallStepBaseline: SmallStepBaseline? = null
    internal var allocationId = uid()
    internal var blockerEntryId = uid()
    internal var completionId = uid()
    var kind by mutableStateOf("personal")
    var minutes by mutableStateOf("5")
    var reason by mutableStateOf("unclear")
    var nextAction by mutableStateOf("")
    var definitionOfDone by mutableStateOf("")
    var saving by mutableStateOf(false); private set
    var saved by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var reloadRequested by mutableStateOf(false); private set
    private var startAfterSave = false
    private var startConsumed = false

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

    fun begin(startAfterSave:Boolean=false): Boolean {
        if (saving || saved) return false
        saving = true
        error = null
        this.startAfterSave=startAfterSave;startConsumed=false
        return true
    }
    /** Retained with the successful save across rotation, and claimed exactly once by the UI. */
    fun consumeStartAfterSave():Boolean {
        if(!saved || !startAfterSave || startConsumed) return false
        startConsumed=true
        return true
    }
    fun finish(result: Result<Unit>) {
        saving = false
        saved = result.isSuccess
        error = result.exceptionOrNull()?.message ?: if (result.isFailure) "保存失败，草稿已保留，请重试。" else null
    }
    internal fun finishStepChange(result:Result<Unit>,task:Entity?,submitted:Pair<String,String>) {
        if(result.isSuccess && task!=null) {
            smallStepBaseline=SmallStepBaseline.from(task)
            if(submitted==nextAction to definitionOfDone) {
                nextAction=smallStepBaseline!!.nextAction;definitionOfDone=smallStepBaseline!!.definitionOfDone
            }
            completionId=uid()
        }
        saving=false;error=result.exceptionOrNull()?.message
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
        startAfterSave=false;startConsumed=false
        allocationBaseline = null; smallStepBaseline = null
        allocationId = uid(); blockerEntryId = uid()
        reason = "unclear"
    }
}
