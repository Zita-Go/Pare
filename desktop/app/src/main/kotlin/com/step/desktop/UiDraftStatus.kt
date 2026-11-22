package com.step.desktop

import androidx.compose.runtime.*
import com.pare.app.MobileAIConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only booleans are aggregated; no draft content or credentials leave their editor. */
object DesktopDraftStatus {
    private val entries=mutableMapOf<Any,Boolean>()
    private val unsaved=MutableStateFlow(false)
    val hasUnsavedEdits:StateFlow<Boolean> = unsaved.asStateFlow()

    @Synchronized internal fun update(token:Any,dirty:Boolean) {
        entries[token]=dirty
        unsaved.value=entries.values.any {it}
    }
    @Synchronized internal fun remove(token:Any) {
        entries.remove(token)
        unsaved.value=entries.values.any {it}
    }
}

@Composable internal fun ReportUnsavedEdits(dirty:Boolean) {
    val token=remember {Any()}
    DisposableEffect(token) {
        DesktopDraftStatus.update(token,dirty)
        onDispose {DesktopDraftStatus.remove(token)}
    }
    SideEffect {DesktopDraftStatus.update(token,dirty)}
}

internal class UiAIServiceDraft(config:MobileAIConfig) {
    var baseline by mutableStateOf(config)
    var endpoint by mutableStateOf(config.endpoint)
    var model by mutableStateOf(config.model)
    var key by mutableStateOf("")
    val dirty:Boolean get() = endpoint!=baseline.endpoint || model!=baseline.model || key.isNotEmpty()
    fun saved(config:MobileAIConfig) {
        baseline=config;endpoint=config.endpoint;model=config.model;key=""
    }
}
