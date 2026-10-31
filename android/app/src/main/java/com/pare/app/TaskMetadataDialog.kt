package com.pare.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

class TaskMetadataDraftModel : ViewModel() {
    var baseline by mutableStateOf<TaskMetadataBaseline?>(null)
    var due by mutableStateOf<String?>(null)
    var actual by mutableStateOf("")
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    fun read(task: Entity) {
        baseline = TaskMetadataBaseline.from(task)
        due = (task.content()?.get("dueAt") as? JsonPrimitive)?.contentOrNull
        actual = (task.content()?.get("actualMinutes") as? JsonPrimitive)?.contentOrNull.orEmpty()
        error = null
    }
}

@Composable
internal fun TaskMetadataDialog(taskId: String, state: SyncState, onEdit: LibraryEdit, onDismiss: () -> Unit) {
    val model: TaskMetadataDraftModel = viewModel(key = "task_metadata:${state.owner}:${state.device}:$taskId")
    val task = state.items["task:$taskId"]?.takeUnless { it.deleted }
    LaunchedEffect(taskId, task) { if (model.baseline == null && task != null) model.read(task) }
    val available = task != null && task.conflict == null && task.conflictGroup.isEmpty()
    LibraryFormDialog("截止与实际投入", "task_metadata", model.saving, onDismiss) {
        Text(task?.title() ?: "任务已不可用", style = MaterialTheme.typography.titleMedium)
        LibraryDateField("截止时间", model.due, available && !model.saving, "task_due") { model.due = it }
        Text("截止时间表示最晚完成时间。通知请在“提醒”中单独安排。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        OutlinedTextField(value = model.actual, onValueChange = { model.actual = it }, label = { Text("已投入的个人时间（分钟）") },
            supportingText = { Text("手动校正累计实际投入；留空可清除。专注记录和等待时长会继续保留。") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = available && !model.saving,
            modifier = Modifier.fillMaxWidth().testTag("task_actual_minutes"))
        if (!available) Text("任务不可用或存在冲突，草稿仍保留。", color = MaterialTheme.colorScheme.error)
        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("task_metadata_error")) }
        if (model.error != null && task != null) TextButton(onClick = { model.read(task) }, enabled = !model.saving,
            modifier = Modifier.heightIn(min = 48.dp).testTag("task_metadata_reload")) { Text("放弃草稿，读取最新值") }
        Button(onClick = {
            val expected = model.baseline
            val actual = model.actual.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
            if (model.actual.isNotBlank() && actual == null) model.error = "请填写整数分钟，或留空清除"
            else if (expected != null) {
                val due = model.due?.let(Instant::parse)
                model.saving = true; model.error = null
                onEdit({ TaskMetadataActions.save(it, taskId, expected, due, actual, Instant.now()) }) { result ->
                    model.saving = false
                    if (result.isSuccess) { model.baseline = null; onDismiss() }
                    else model.error = result.exceptionOrNull()?.message ?: "保存失败，草稿仍保留"
                }
            }
        }, enabled = available && !model.saving && model.baseline != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_metadata_save")) { Text(if (model.saving) "保存中…" else "保存") }
    }
}

@Composable
internal fun LibraryDateField(label: String, value: String?, enabled: Boolean, tag: String, onChange: (String?) -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val instant = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(instant?.atZone(zone)?.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")) ?: "未设置", style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = {
            val date = (instant ?: Instant.now()).atZone(zone)
            DatePickerDialog(context, { _, year, month, day ->
                TimePickerDialog(context, { _, hour, minute ->
                    onChange(LocalDate.of(year, month + 1, day).atTime(hour, minute).atZone(zone).toInstant().toString())
                }, date.hour, date.minute, true).show()
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("${tag}_choose")) { Text("选择日期与时间") }
        if (instant != null) TextButton(onClick = { onChange(null) }, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_clear")) { Text("清除$label") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryFormDialog(title: String, tag: String, saving: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag(tag), color = MaterialTheme.colorScheme.surface) {
            Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = {
                IconButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.size(48.dp).testTag("${tag}_close")) { Icon(Icons.Default.ArrowBack, "返回") }
            }) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
    }
}
