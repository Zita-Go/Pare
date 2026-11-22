package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel

/** Keeps an in-flight local acknowledgement across activity recreation. */
class OrganizeSaveModel : ViewModel() {
    var saving by mutableStateOf(false); private set
    var saved by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    fun begin(): Boolean {
        if (saving) return false
        saving = true; error = null
        return true
    }
    fun finish(result: Result<Unit>) {
        saving = false; saved = result.isSuccess
        error = result.exceptionOrNull()?.message ?: if (result.isFailure) "整理失败，请重试" else null
    }
    fun reset() { saving = false; saved = false; error = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrganizeDialog(item: Entity?, saving: Boolean, error: String?, onConfirm: (Int, Boolean) -> Unit, onDismiss: () -> Unit) {
    var quadrant by rememberSaveable(item?.id) { mutableIntStateOf(-1) }
    var today by rememberSaveable(item?.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = !saving, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("整理为任务", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(item?.title() ?: "这条内容已不可用", style = MaterialTheme.typography.bodyLarge)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选择象限", style = MaterialTheme.typography.titleSmall)
                    WorkflowChoiceGroup { choiceModifier ->
                        quadrantLabels.forEachIndexed { index, label ->
                            WorkflowChoice(label, quadrant == index, !saving, "organize_priority_$index", choiceModifier) { quadrant = index }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = today, enabled = !saving,
                    role = Role.Checkbox, onValueChange = { today = it }).testTag("organize_add_today"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = today, onCheckedChange = null, enabled = !saving)
                    Spacer(Modifier.width(12.dp)); Text("加入今天", style = MaterialTheme.typography.bodyLarge)
                }
            }
            (error ?: if (item == null || item.deleted) "这条内容已不可用，请关闭后重试。" else null)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp).testTag("organize_error"))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).testTag("organize_cancel")) { Text("取消") }
                Button(onClick = { onConfirm(quadrant, today) }, enabled = !saving && quadrant in 0..3 && item != null && !item.deleted,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("organize_confirm")) {
                    Text(if (saving) "正在整理…" else "确认整理")
                }
            }
        }
    }
}
