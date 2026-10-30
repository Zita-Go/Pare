package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun TodayAllocationDialog(taskId: String, vm: PareViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    val now by vm.planningInstant.collectAsState()
    val model: TodayWorkflowModel = viewModel(key = "allocation:${state.owner}:$taskId")
    model.initialize(state, taskId, now, support = false)
    val task = state.items["task:$taskId"]
    val available = taskCanBePlanned(task)
    LaunchedEffect(model.saved) { if (model.saved) { model.clear(); onDismiss() } }
    TodayWorkflowSurface("今天怎么推进", "allocation", model, available,
        canSave = model.allocation() != null,
        onSave = {
            val allocation = model.allocation()
            if (allocation != null && model.begin()) vm.allocateToday(taskId, allocation, model::finish)
        }, onDismiss = onDismiss, onReload = { model.confirmReload(state, taskId, now, support = false) }) {
        Text(task?.title() ?: "任务已不可用", style = MaterialTheme.typography.titleMedium)
        task?.content()?.get("estimatedMinutes")?.jsonPrimitive?.intOrNull?.let {
            Text("任务总预估 $it 分钟", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.selectableGroup()) {
            WorkflowChoice("自己推进", model.kind == "personal", !model.saving && available, "allocation_personal") { model.kind = "personal" }
            WorkflowChoice("等待结果后检查", model.kind == "check", !model.saving && available, "allocation_check") { model.kind = "check" }
        }
        if (model.kind == "check") Text("任务在别处推进，今天只安排检查结果所需时间", style = MaterialTheme.typography.bodyMedium)
        WorkflowMinutes(model.minutes, { model.minutes = it }, !model.saving && available, "allocation_minutes")
        Text("只调整今天的投入，任务总预估单独保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TimeBlockDisclosure(model)
    }
}

@Composable
internal fun WorkflowChoice(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun WorkflowMinutes(value: String, onChange: (String) -> Unit, enabled: Boolean, tag: String) {
    PareFormField(value, onChange, "今天投入 / 检查的分钟数", enabled = enabled, singleLine = true,
        isError = validTodayMinutes(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag(tag))
    if (validTodayMinutes(value) == null) Text("请输入1–1440分钟", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun TimeBlockDisclosure(model: TodayWorkflowModel) {
    if (model.allocationBaseline?.hasCurrentDaySchedule == true) Text(
        "今天已有日历时间块。保存会调整它的时长，开始时间和提醒设置保持不变。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Pinned actions and error; only the form scrolls, including with IME and large text. */
@Composable
internal fun TodayWorkflowSurface(
    title: String, tag: String, model: TodayWorkflowModel, available: Boolean,
    canSave: Boolean, onSave: () -> Unit, onDismiss: () -> Unit, onReload: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = { if (!model.saving) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        dismissOnBackPress = !model.saving, dismissOnClickOutside = !model.saving)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight().testTag("${tag}_dialog"), color = MaterialTheme.colorScheme.surface) {
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
                    val error = if (!available) "任务已完成、归档或不可用，无法保存。草稿仍保留。" else model.error
                    if (error != null) Text(error, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(16.dp)
                            .testTag("${tag}_error").semantics { liveRegion = LiveRegionMode.Polite })
                    if (model.error != null) TextButton(onClick = model::requestReload, enabled = !model.saving && available,
                        modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("${tag}_reload_latest")) {
                        Text("读取最新内容")
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onDismiss, enabled = !model.saving, modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_cancel")) { Text("关闭") }
                        Button(onClick = onSave, enabled = available && !model.saving && canSave,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("${tag}_save"), shape = MaterialTheme.shapes.small) {
                            if (model.saving) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                            Text(if (model.saving) "正在保存…" else "保存")
                        }
                    }
                }
            }
        }
    }
    if (model.reloadRequested) AlertDialog(onDismissRequest = model::cancelReload,
        title = { Text("放弃当前草稿？") },
        text = { Text("将读取最新任务和今天的安排，当前草稿不会保存。") },
        confirmButton = {
            TextButton(onClick = onReload, enabled = !model.saving && available,
                modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_reload_confirm")) { Text("放弃草稿并读取最新内容") }
        }, dismissButton = {
            TextButton(onClick = model::cancelReload, modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_reload_cancel")) { Text("保留草稿") }
        })
}
