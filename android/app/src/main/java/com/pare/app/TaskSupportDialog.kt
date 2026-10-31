package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskSupportDialog(taskId: String, vm: PareViewModel, onStart: ((String) -> Unit)? = null, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    val now by vm.planningInstant.collectAsState()
    val model: TodayWorkflowModel = viewModel(key = "support:${state.owner}:$taskId")
    model.initialize(state, taskId, now, support = true)
    val task = state.items["task:$taskId"]
    val available = taskCanBePlanned(task)
    var custom by androidx.compose.runtime.saveable.rememberSaveable(taskId) { mutableStateOf(false) }
    LaunchedEffect(model.saved) { if (model.saved) {
        val start=model.consumeStartAfterSave()
        model.clear();onDismiss()
        if(start) onStart?.invoke(taskId)
    } }
    fun save(start:Boolean) {
        val allocation=model.allocation();val expected=model.smallStepBaseline
        if(allocation!=null && expected!=null && model.begin(startAfterSave=start)) vm.saveSmallStep(
            taskId=taskId,nextAction=model.nextAction,definitionOfDone=model.definitionOfDone,
            expected=expected,allocation=allocation,blockerReason=model.reason,blockerEntryId=model.blockerEntryId,onResult=model::finish)
    }
    TodayWorkflowSurface("我卡住了", "support", model, available,
        canSave = model.nextAction.isNotBlank() && model.definitionOfDone.isNotBlank() && model.allocation() != null && model.smallStepBaseline != null,
        onSave = {save(false)}, onDismiss = onDismiss, onReload = {
            model.confirmReload(state, taskId, now, support = true)
            custom = false
        }) {
        val enabled = available && !model.saving
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("当前任务", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(task?.title() ?: "任务已不可用", style = MaterialTheme.typography.titleLarge)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("卡在哪里", style = MaterialTheme.typography.titleSmall)
            WorkflowChoiceGroup { choiceModifier ->
                supportReasons.forEach { reason ->
                    WorkflowChoice(reason.label, model.reason == reason.code, enabled, "support_reason_${reason.code}", choiceModifier) { model.reason = reason.code }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("可以先这样试试", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(supportReasons.first { it.code == model.reason }.guidance, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PareFormField(model.nextAction, { model.nextAction = it }, "最小下一步：现在能做的一件小事", enabled = enabled, minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("support_next_action"))
            PareFormField(model.definitionOfDone, { model.definitionOfDone = it }, "做到哪里就停", enabled = enabled, minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("support_done"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (model.kind == "check") "留几分钟检查结果" else "先给自己一点时间", style = MaterialTheme.typography.titleSmall)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(5, 10, 15).forEach { minutes ->
                    FilterChip(selected = !custom && model.minutes == minutes.toString(), onClick = { custom = false; model.minutes = minutes.toString() },
                        enabled = enabled, label = { Text("$minutes 分钟") }, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.widthIn(min = 88.dp).heightIn(min = 48.dp).testTag("support_minutes_$minutes"))
                }
            }
            TextButton(onClick = { custom = !custom }, enabled = enabled, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("support_custom_minutes")) {
                Text(if (custom) "收起自定时间" else "自定时间")
            }
            if (custom) WorkflowMinutes(model.minutes, { model.minutes = it }, enabled, "support_minutes",
                label = if (model.kind == "check") "今天检查结果要几分钟" else "今天想做几分钟")
            else Text("今天${if (model.kind == "check") "检查" else "投入"} ${model.minutes} 分钟", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("support_minutes"))
        }
        TimeBlockDisclosure(model)
        if(onStart!=null) OutlinedButton(onClick={save(true)},enabled=enabled && model.nextAction.isNotBlank() && model.definitionOfDone.isNotBlank() && model.allocation()!=null,
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("support_save_start")) {Text("保存并开始5分钟")}
        Text(if(onStart==null) "保存会记录下一步和今日投入，计时由你手动开始。" else "保存会记录下一步和今日投入。选择“保存并开始5分钟”才请求启动专注；只保存不会启动。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
