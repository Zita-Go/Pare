package com.pare.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

class TaskReminderModel:ViewModel() {
    var baseline:TaskReminderBaseline? by mutableStateOf(null)
    var at:Instant? by mutableStateOf(null)
    var recurrence:String? by mutableStateOf(null)
    var saving by mutableStateOf(false)
    var saved by mutableStateOf(false)
    var reload by mutableStateOf(false)
    var error:String? by mutableStateOf(null)
    fun initialize(task:Entity) {if(baseline==null) read(task)}
    fun read(task:Entity) {
        baseline=TaskReminderBaseline.from(task)
        at=task.content()?.get("remindAt")?.jsonPrimitive?.contentOrNull?.let {runCatching {Instant.parse(it)}.getOrNull()}
        recurrence=task.content()?.get("recurrence")?.jsonPrimitive?.contentOrNull
        error=null;reload=false
    }
    fun finish(result:Result<Unit>) {saving=false;saved=result.isSuccess;error=result.exceptionOrNull()?.message}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun TaskReminderDialog(taskId:String,vm:PareViewModel,onDismiss:()->Unit) {
    val state by vm.state.collectAsState()
    val task=state.items["task:$taskId"]
    val model:TaskReminderModel=viewModel(key="reminder:${state.owner}:$taskId")
    task?.let(model::initialize)
    val context=LocalContext.current
    val zone=ZoneId.systemDefault()
    val available=taskCanBePlanned(task) && task?.conflict==null
    var repeatMenu by remember {mutableStateOf(false)}
    LaunchedEffect(model.saved) {if(model.saved) {model.baseline=null;model.saved=false;onDismiss()}}
    Dialog(onDismissRequest={if(!model.saving)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("task_reminder_dialog"),color=MaterialTheme.colorScheme.background) {
            Column {
                Text("提醒我",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(horizontal=24.dp,vertical=20.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text(task?.title() ?: "任务已不可用",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface)
                    Surface(color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("提醒时间",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(model.at?.atZone(zone)?.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) ?: "尚未安排提醒",
                                style=MaterialTheme.typography.headlineSmall,color=if(model.at!=null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text("手机时区：${zone.id}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {listOf(10,30,60).forEach {minutes ->
                        OutlinedButton(onClick={model.at=Instant.now().plusSeconds(minutes*60L);model.error=null},enabled=available && !model.saving,
                            modifier=Modifier.heightIn(min=48.dp).testTag("reminder_after_$minutes"),contentPadding=PaddingValues(horizontal=12.dp,vertical=12.dp),shape=MaterialTheme.shapes.small) {Text("${minutes}分钟后")}
                    }}
                    OutlinedButton(onClick={
                        val date=(model.at ?: Instant.now().plusSeconds(600)).atZone(zone)
                        DatePickerDialog(context,{_,year,month,day ->
                            TimePickerDialog(context,{_,hour,minute ->
                                model.at=LocalDate.of(year,month+1,day).atTime(hour,minute).atZone(zone).toInstant();model.error=null
                            },date.hour,date.minute,true).show()
                        },date.year,date.monthValue-1,date.dayOfMonth).show()
                    },enabled=available && !model.saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=MaterialTheme.shapes.small) {Text("选择日期与时间")}
                    if(model.at!=null) {
                        val repeatOptions=listOf(null to "不重复","daily" to "每天","weekly" to "每周","monthly" to "每月")
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Box {
                                OutlinedButton(onClick={repeatMenu=true},enabled=available && !model.saving,
                                    modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("reminder_repeat").semantics {stateDescription=if(repeatMenu) "已展开" else "已收起"},
                                    shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp)) {
                                    Text("重复",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface,modifier=Modifier.weight(1f))
                                    Text(repeatOptions.firstOrNull {it.first==model.recurrence}?.second ?: "其他重复规则",style=MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Default.KeyboardArrowDown,null,Modifier.size(20.dp))
                                }
                                DropdownMenu(expanded=repeatMenu,onDismissRequest={repeatMenu=false}) {
                                    repeatOptions.forEach {(value,label) ->
                                        DropdownMenuItem(text={Text(label)},onClick={model.recurrence=value;repeatMenu=false},enabled=available && !model.saving,
                                            trailingIcon={if(model.recurrence==value) Icon(Icons.Default.Check,null,Modifier.size(18.dp))},
                                            modifier=Modifier.heightIn(min=48.dp).testTag("reminder_repeat_${value ?: "none"}").semantics {selected=model.recurrence==value})
                                    }
                                }
                            }
                            Text("重复提醒跟随手机当地时间",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick={model.at=null;model.recurrence=null},enabled=!model.saving,modifier=Modifier.heightIn(min=48.dp).testTag("reminder_clear")) {Text("清除提醒")}
                    }
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    MobileNotificationSettings()
                    if(!available) Text("任务已完成、存在冲突或不可用，提醒草稿仍保留。",color=MaterialTheme.colorScheme.error)
                    model.error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("reminder_error"))}
                    if(model.error!=null && available) TextButton(onClick={model.reload=true},enabled=!model.saving,modifier=Modifier.heightIn(min=48.dp)) {Text("重新读取已保存的提醒")}
                    Spacer(Modifier.height(8.dp))
                }
                Surface(color=MaterialTheme.colorScheme.surface) {
                    Column {
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            TextButton(onClick=onDismiss,enabled=!model.saving,modifier=Modifier.heightIn(min=48.dp).testTag("reminder_close")) {Text("关闭")}
                            Button(onClick={
                                val expected=model.baseline
                                if(expected!=null) {model.saving=true;vm.saveReminder(taskId,expected,model.at,model.recurrence,model::finish)}
                            },enabled=available && !model.saving,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("reminder_save"),shape=MaterialTheme.shapes.small) {Text(if(model.saving) "保存中…" else "保存提醒")}
                        }
                    }
                }
            }
        }
    }
    if(model.reload) AlertDialog(onDismissRequest={model.reload=false},title={Text("重新读取提醒？")},text={Text("会放弃当前提醒草稿，读取任务最新保存的提醒时间。")},
        confirmButton={TextButton(onClick={task?.let(model::read)}) {Text("重新读取")}},dismissButton={TextButton(onClick={model.reload=false}) {Text("保留草稿")}})
}
