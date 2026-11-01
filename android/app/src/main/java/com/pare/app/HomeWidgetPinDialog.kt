package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner

enum class WidgetPinPhase { IDLE, REQUESTED, UNCONFIRMED, UNSUPPORTED, FAILED, ADDED }

/** A supported request is not success. Only a newly bound provider ID confirms addition. */
class HomeWidgetPinModel(private val saved:SavedStateHandle=SavedStateHandle()):ViewModel() {
    var phase by mutableStateOf(saved.get<String>("phase")?.let {name->WidgetPinPhase.entries.firstOrNull {it.name==name}} ?: WidgetPinPhase.IDLE);private set
    private var previousIds:Set<Int> = saved.get<IntArray>("previousIds")?.toSet().orEmpty()
    private fun updatePhase(value:WidgetPinPhase){phase=value;saved["phase"]=value.name}
    fun reset(){updatePhase(WidgetPinPhase.IDLE);previousIds=emptySet();saved["previousIds"]=intArrayOf()}
    fun request(readIds:()->Set<Int>,launch:()->Boolean) {
        runCatching {
            previousIds=readIds()
            saved["previousIds"]=previousIds.toIntArray()
            updatePhase(WidgetPinPhase.REQUESTED)
            if(!launch())updatePhase(WidgetPinPhase.UNSUPPORTED)
        }.onFailure {updatePhase(WidgetPinPhase.FAILED)}
    }
    fun refresh(readIds:()->Set<Int>) {
        if(phase !in setOf(WidgetPinPhase.REQUESTED,WidgetPinPhase.UNCONFIRMED))return
        runCatching {readIds()-previousIds}.onSuccess {
            updatePhase(if(it.isNotEmpty())WidgetPinPhase.ADDED else WidgetPinPhase.UNCONFIRMED)
        }.onFailure {updatePhase(WidgetPinPhase.FAILED)}
    }
}

@Composable fun HomeWidgetPinDialog(model:HomeWidgetPinModel,onDismiss:()->Unit) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    fun ids()=HomeWidgetUpdates.ids(context).toSet()
    fun request()=model.request(::ids){HomeWidgetProvider.requestPin(context)}
    LaunchedEffect(Unit){if(model.phase==WidgetPinPhase.IDLE)request()}
    DisposableEffect(owner,model) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME)model.refresh(::ids)}
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer)}
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("添加桌面小组件")},modifier=Modifier.testTag("widget_pin_dialog"),text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(when(model.phase) {
                WidgetPinPhase.IDLE->"正在请求桌面添加…"
                WidgetPinPhase.REQUESTED->"已向桌面发出请求，请在系统窗口中确认添加。"
                WidgetPinPhase.UNCONFIRMED->"尚未检测到新增组件。若系统窗口没有出现，可以重新请求或手动添加。"
                WidgetPinPhase.UNSUPPORTED->"当前桌面不支持应用内添加，请手动添加。"
                WidgetPinPhase.FAILED->"暂时无法请求桌面添加。可以重试，或按下方方法手动添加。"
                WidgetPinPhase.ADDED->"已确认桌面新增了 Pare 小组件。"
            },modifier=Modifier.testTag("widget_pin_status"))
            if(model.phase!=WidgetPinPhase.ADDED)Text("手动添加：长按手机桌面的空白处 → 小组件 → Pare · 今日与专注。",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.testTag("widget_pin_manual"))
            if(model.phase in setOf(WidgetPinPhase.REQUESTED,WidgetPinPhase.UNCONFIRMED))TextButton(onClick={model.refresh(::ids)},modifier=Modifier.heightIn(min=48.dp).testTag("widget_pin_check")){Text("检查添加结果")}
        }
    },confirmButton={
        if(model.phase==WidgetPinPhase.ADDED)TextButton(onClick=onDismiss,modifier=Modifier.heightIn(min=48.dp)){Text("完成")}
        else TextButton(onClick={request()},modifier=Modifier.heightIn(min=48.dp).testTag("widget_pin_retry")){Text("重新请求")}
    },dismissButton={if(model.phase!=WidgetPinPhase.ADDED)TextButton(onClick=onDismiss,modifier=Modifier.heightIn(min=48.dp).testTag("widget_pin_close")){Text("关闭")}})
}
