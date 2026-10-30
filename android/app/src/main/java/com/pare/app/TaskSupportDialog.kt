package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun TaskSupportDialog(taskId: String, vm: PareViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    val now by vm.planningInstant.collectAsState()
    val model: TodayWorkflowModel = viewModel(key = "support:${state.owner}:$taskId")
    model.initialize(state, taskId, now, support = true)
    val task = state.items["task:$taskId"]
    val available = taskCanBePlanned(task)
    var custom by androidx.compose.runtime.saveable.rememberSaveable(taskId) { mutableStateOf(false) }
    LaunchedEffect(model.saved) { if (model.saved) { model.clear(); onDismiss() } }
    TodayWorkflowSurface("我卡住了", "support", model, available,
        canSave = model.nextAction.isNotBlank() && model.definitionOfDone.isNotBlank() && model.allocation() != null && model.smallStepBaseline != null,
        onSave = {
            val allocation = model.allocation()
            val expected = model.smallStepBaseline
            if (allocation != null && expected != null && model.begin()) vm.saveSmallStep(
                taskId = taskId, nextAction = model.nextAction, definitionOfDone = model.definitionOfDone,
                expected = expected, allocation = allocation, blockerReason = model.reason,
                blockerEntryId = model.blockerEntryId, onResult = model::finish)
        }, onDismiss = onDismiss, onReload = {
            model.confirmReload(state, taskId, now, support = true)
            custom = false
        }) {
        val enabled = available && !model.saving
        Text(task?.title() ?: "任务已不可用", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            supportReasons.forEach { reason ->
                WorkflowChoice(reason.label, model.reason == reason.code, enabled, "support_reason_${reason.code}") { model.reason = reason.code }
            }
        }
        Text("本地引导", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(supportReasons.first { it.code == model.reason }.guidance, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PareFormField(model.nextAction, { model.nextAction = it }, "一个具体的下一步", enabled = enabled, minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("support_next_action"))
        PareFormField(model.definitionOfDone, { model.definitionOfDone = it }, "做到哪里就停", enabled = enabled, minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("support_done"))
        Text(if (model.kind == "check") "这次安排一点检查时间" else "先给自己一点时间", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(5, 10, 15).forEach { minutes ->
                FilterChip(selected = !custom && model.minutes == minutes.toString(), onClick = { custom = false; model.minutes = minutes.toString() },
                    enabled = enabled, label = { Text("$minutes 分钟") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("support_minutes_$minutes"))
            }
        }
        TextButton(onClick = { custom = !custom }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("support_custom_minutes")) {
            Text(if (custom) "收起自定时间" else "自定时间")
        }
        if (custom) WorkflowMinutes(model.minutes, { model.minutes = it }, enabled, "support_minutes")
        else Text("今天${if (model.kind == "check") "检查" else "投入"} ${model.minutes} 分钟", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("support_minutes"))
        TimeBlockDisclosure(model)
        Text("保存后记录这次卡点、更新下一步与停止标准，并安排今天。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
