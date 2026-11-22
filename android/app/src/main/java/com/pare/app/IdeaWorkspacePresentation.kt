package com.pare.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.serialization.json.*
import java.time.Instant

/** Callbacks update a lifecycle-owned holder, so rotation cannot strand a saving form. */
internal class IdeaWorkspaceDraftViewModel : ViewModel() {
    var form by mutableStateOf("")
    var context by mutableStateOf<IdeaContextDraft?>(null)
    var entry by mutableStateOf<IdeaEntryDraft?>(null)
    var action by mutableStateOf<IdeaActionDraft?>(null)
    var pendingActionReload by mutableStateOf<IdeaActionDraft?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    fun open(which: String) { if (!saving) { form = which; error = null } }
    fun back() { if (!saving) { form = ""; error = null } }
    fun requestActionReload(state: SyncState, now: Instant): Boolean {
        val draft = action ?: return false
        if (saving) return false
        if (state.items.containsKey("task:${draft.id}")) {
            error = "这个行动标识已存在，请返回工作台查看关联行动。草稿仍保留。"
            return false
        }
        pendingActionReload = draft.copy(baseline = DayAllocationBaseline.from(state, draft.id, now))
        return true
    }
    fun cancelActionReload() { pendingActionReload = null }
    fun confirmActionReload(state: SyncState): Boolean {
        val refreshed = pendingActionReload ?: return false
        pendingActionReload = null
        if (saving) return false
        if (state.items.containsKey("task:${refreshed.id}")) {
            error = "这个行动标识已存在，请返回工作台查看关联行动。草稿仍保留。"
            return false
        }
        action = refreshed
        error = null
        return true
    }
    fun begin(validation: String? = null): Boolean {
        if (saving) return false
        error = validation
        if (validation != null) return false
        saving = true
        return true
    }
    fun finish(result: Result<Unit>) {
        saving = false
        if (result.isFailure) {
            error = (result.exceptionOrNull()?.message ?: "暂时无法保存") + "。草稿已保留。"
            return
        }
        when (form) { "context" -> context = null; "entry" -> entry = null; "action" -> action = null }
        form = ""
        error = null
    }
}
