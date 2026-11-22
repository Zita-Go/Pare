package com.pare.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Save acknowledgement survives activity recreation while the editor draft is restored by Compose. */
class EditorSaveModel : ViewModel() {
    var state by mutableStateOf(EditorSaveState())
        private set

    fun begin(draft: EditorDraft): Boolean {
        if (state.saving) return false
        state = state.begin(draft)
        return state.saving
    }
    fun finish(result: Result<Unit>) { state = state.finish(result) }
    fun clearError() { state = state.copy(error = null) }
    fun reset() { state = EditorSaveState() }
}
