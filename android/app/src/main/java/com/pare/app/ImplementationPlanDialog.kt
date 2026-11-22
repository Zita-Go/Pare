package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.*

class ImplementationPlanModel:ViewModel() {
    var baseline:ImplementationPlanBaseline? by mutableStateOf(null);private set
    var enabled by mutableStateOf(false)
    var hour by mutableStateOf("9")
    var minute by mutableStateOf("0")
    var action by mutableStateOf("")
    var unsupported by mutableStateOf(false);private set
    var saving by mutableStateOf(false);private set
    var saved by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null);private set
    var confirmDiscard by mutableStateOf(false)
    private var originalInput = emptyList<String>()
    private fun input() = listOf(enabled.toString(),hour,minute,action)
    fun isDirty() = !unsupported && baseline!=null && input()!=originalInput
    fun initialize(state:SyncState,taskId:String) {
        if(baseline!=null)return
        baseline=ImplementationPlanBaseline.from(state,taskId)
        val plan=state.items["task:$taskId"]?.content()?.get("implementationPlan") as? JsonObject
        enabled=plan!=null;unsupported=plan!=null && plan.timelineString("kind")!="time"
        hour=(plan?.timelineInt("hour") ?: 9).toString();minute=(plan?.timelineInt("minute") ?: 0).toString();action=plan?.timelineString("action").orEmpty()
        originalInput=input()
    }
    fun draft():ImplementationPlanDraft? {
        if(unsupported)return null
        if(!enabled)return ImplementationPlanDraft(false)
        val h=hour.toIntOrNull()?.takeIf {it in 0..23} ?: return null
        val m=minute.toIntOrNull()?.takeIf {it in 0..59} ?: return null
        return ImplementationPlanDraft(true,h,m,action).takeIf {action.trim().length in 1..500}
    }
    fun save(taskId:String,onEdit:((SyncState)->SyncState,(Result<Unit>)->Unit)->Unit) {
        val expected=baseline ?: return;val input=draft() ?: return
        if(saving || saved)return;saving=true;error=null
        onEdit({ImplementationPlanActions.save(it,taskId,expected,input)}) {result->saving=false;saved=result.isSuccess;error=result.exceptionOrNull()?.message ?: if(result.isFailure) "保存失败，草稿已保留" else null}
    }
    fun reset() {if(!saving) {baseline=null;saved=false;error=null;confirmDiscard=false}}
}

@Composable fun ImplementationPlanDialog(state:SyncState,taskId:String,onEdit:((SyncState)->SyncState,(Result<Unit>)->Unit)->Unit,onDismiss:()->Unit,
    model:ImplementationPlanModel=viewModel(key="implementation:${state.owner}:${state.device}:$taskId")) {
    model.initialize(state,taskId)
    val task=state.items["task:$taskId"]
    val available=taskCanBePlanned(task) && task?.conflict==null && task?.conflictGroup.orEmpty().isEmpty()
    LaunchedEffect(model.saved) {if(model.saved){model.reset();onDismiss()}}
    val close = { if(!model.saving) {if(model.isDirty()) model.confirmDiscard=true else {model.reset();onDismiss()}} }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,dismissOnBackPress=!model.saving,dismissOnClickOutside=!model.saving)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("implementation_dialog"),color=MaterialTheme.colorScheme.background) {
            Column {
                ParePageHeader("到点开始一个小行动",close,enabled=!model.saving,backModifier=Modifier.testTag("implementation_close"))
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text(task?.title() ?: "任务不可用",style=MaterialTheme.typography.titleMedium)
                    if(model.unsupported) {
                        Text("这项计划使用返回电脑或打开应用等触发方式。手机无法监听这些事件，原计划保留，请在支持它的设备编辑。")
                        if(model.action.isNotBlank())Text("原行动：${model.action}")
                    } else {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                            Text("按时刻提醒行动",Modifier.weight(1f));Switch(model.enabled,{model.enabled=it},enabled=available && !model.saving,modifier=Modifier.testTag("implementation_enabled"))
                        }
                        Text("只有当天已选入计划且未完成的任务才提醒。规划时区：${timelinePlanningZone(state)}。当天已过的时刻不补发；夏令时无效或重复时刻不自动调整。",style=MaterialTheme.typography.bodySmall)
                        if(model.enabled) {
                            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                PareFormField(model.hour,{model.hour=it},"时（0–23）",Modifier.weight(1f).testTag("implementation_hour"),enabled=available && !model.saving,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                                PareFormField(model.minute,{model.minute=it},"分（0–59）",Modifier.weight(1f).testTag("implementation_minute"),enabled=available && !model.saving,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                            }
                            PareFormField(model.action,{model.action=it},"如果到这个时刻，那么先做…",Modifier.fillMaxWidth().testTag("implementation_action"),enabled=available && !model.saving,minLines=2)
                        }
                        Text("这是独立的行动提示，不修改截止日期、普通提醒或时间块，也不会自动启动计时或 AI。",style=MaterialTheme.typography.bodySmall)
                        MobileNotificationSettings()
                    }
                    if(!available)Text("任务已完成、删除或存在冲突，草稿已保留。",color=MaterialTheme.colorScheme.error)
                    model.error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("implementation_error"))}
                }
                if(!model.unsupported) Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Button(shape=MaterialTheme.shapes.medium,onClick={model.save(taskId,onEdit)},enabled=available && !model.saving && model.draft()!=null,
                        modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("implementation_save")) {Text(if(model.saving) "正在保存…" else "保存")}
                }
            }
        }
    }
    ManagementDiscardConfirmation(model.confirmDiscard,{model.confirmDiscard=false}) {model.reset();onDismiss()}

}
