package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A task-scoped control surface; opening it never starts a timer or writes history. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ExecutionDialog(
    task:Entity,
    snapshot:ExecutionSnapshot,
    onCommand:(ExecutionCommand,(Result<Unit>)->Unit)->Unit,
    onDismiss:()->Unit
) {
    var external by rememberSaveable(task.id) {mutableStateOf(false)}
    var minutes by rememberSaveable(task.id) {mutableStateOf("5")}
    var checkMinutes by rememberSaveable(task.id) {mutableStateOf("15")}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var confirm by remember {mutableStateOf<ExecutionAction?>(null)}
    val run=snapshot.runFor(task.id)
    val isExternal=snapshot.external.any {it.taskId==task.id}
    val context=LocalContext.current
    val clock=remember(context) {AndroidExecutionClock(context)}
    var now by remember {mutableStateOf(clock.now())}
    LaunchedEffect(run?.id) {while(true) {now=clock.now();delay(1000)}}
    fun submit(action:ExecutionAction,value:Int?=null,close:Boolean=false) {
        if(busy) return
        error=null;busy=true
        onCommand(ExecutionCommand(task.id,action,value)) {result ->
            busy=false
            result.fold(onSuccess={if(close) onDismiss()},onFailure={error=it.message ?: "尚未保存，请重试"})
        }
    }
    AlertDialog(
        onDismissRequest={if(!busy) onDismiss()},
        title={Text(if(run==null) "推进这一步" else if(isExternal) "外部推进" else "个人专注")},
        text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(task.title(),style=MaterialTheme.typography.titleMedium)
            task.content()?.get("nextAction")?.let {element ->
                val action=(element as? kotlinx.serialization.json.JsonPrimitive)?.content
                if(!action.isNullOrBlank()) Text("下一步：$action")
            }
            Text("运行状态仅保存在这台手机；结束记录后，历史会随任务同步。",style=MaterialTheme.typography.bodySmall)
            if(run!=null) {
                val elapsed=run.elapsedMillis(now)/1000
                Text("${elapsed/60} 分 ${elapsed%60} 秒 · ${when {run.pendingFinish!=null -> "等待保存";run.due -> "已到点，等待确认";run.paused -> "已暂停";else -> "进行中"}}",style=MaterialTheme.typography.titleMedium)
                run.recoveryNotice?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                if(!isExternal) {
                    Text("目标 ${run.targetMinutes ?: 0} 分钟。到点会停下计时，任务是否完成由你决定。",style=MaterialTheme.typography.bodySmall)
                } else {
                    Text("这是你手动记录的外部工作状态；等待时长不计入个人投入。",style=MaterialTheme.typography.bodySmall)
                    run.nextCheckAtMillis?.let {at ->
                        Text("检查节点：${DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))}",style=MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled=!busy,onClick={submit(ExecutionAction.SET_CHECK,15)}) {Text("15 分钟后检查")}
                        TextButton(enabled=!busy,onClick={submit(ExecutionAction.SET_CHECK,null)}) {Text("取消检查节点")}
                    }
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(!run.due && run.pendingFinish==null) OutlinedButton(enabled=!busy,onClick={submit(if(run.paused) ExecutionAction.RESUME else ExecutionAction.PAUSE)}) {Text(if(run.paused) "继续" else "暂停")}
                    TextButton(enabled=!busy && run.pendingFinish==null,onClick={confirm=ExecutionAction.CANCEL}) {Text("取消这段记录")}
                }
            } else {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=!external,onClick={external=false},label={Text("自己专注")},enabled=!busy)
                    FilterChip(selected=external,onClick={external=true},label={Text("外部推进")},enabled=!busy)
                }
                if(external) {
                    Text("外部 AI、另一台机器或他人可以同时推进多个任务。Pare 记录你选择的状态，不连接或控制它们。",style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value=checkMinutes,onValueChange={checkMinutes=it},label={Text("多少分钟后检查（可留空）")},singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=!busy,modifier=Modifier.fillMaxWidth())
                    Text("检查节点会交给手机提醒设置处理；能否通知取决于系统权限和提醒设置。",style=MaterialTheme.typography.bodySmall)
                } else {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(5,10,15,25).forEach {value ->
                        FilterChip(selected=minutes==value.toString(),onClick={minutes=value.toString()},label={Text("$value 分")},enabled=!busy)
                    }}
                    OutlinedTextField(value=minutes,onValueChange={minutes=it},label={Text("专注分钟")},singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=!busy,modifier=Modifier.fillMaxWidth())
                    if(snapshot.focus!=null) Text("本机已有另一项个人专注，请先结束或取消那段记录。",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                }
            }
            MobileNotificationSettings()
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }},
        confirmButton={Button(enabled=!busy && (run!=null || external || snapshot.focus==null),onClick={
            if(run!=null) confirm=ExecutionAction.FINISH
            else if(external) {
                val value=checkMinutes.trim().takeIf {it.isNotEmpty()}?.toIntOrNull()
                if(checkMinutes.isNotBlank() && (value==null || value !in 1..10080)) error="请填写 1–10080 分钟，或留空"
                else submit(ExecutionAction.START_EXTERNAL,value,close=true)
            } else {
                val value=minutes.toIntOrNull()
                if(value==null || value !in 1..1440) error="请填写 1–1440 分钟"
                else submit(ExecutionAction.START_FOCUS,value,close=true)
            }
        }) {Text(if(busy) "正在保存" else if(run!=null) "结束并记录" else if(external) "记录开始" else "开始专注")}},
        dismissButton={TextButton(enabled=!busy,onClick=onDismiss) {Text("关闭")}}
    )
    confirm?.let {action ->
        AlertDialog(onDismissRequest={if(!busy) confirm=null},
            title={Text(if(action==ExecutionAction.CANCEL) "取消这段记录？" else "结束并记录？")},
            text={Text(if(action==ExecutionAction.CANCEL) "这段尚未记录的时长将丢弃，任务内容保留。" else if(isExternal) "将记录外部推进时长，任务继续保留为未完成。" else "将记录这段个人投入，任务继续保留为未完成。")},
            confirmButton={TextButton(enabled=!busy,onClick={confirm=null;submit(action,close=true)}) {Text("确认")}},
            dismissButton={TextButton(enabled=!busy,onClick={confirm=null}) {Text("返回")}})
    }
}
