package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Stateless editor boundary allows save failures and large font layouts to be exercised without an account. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun TaskEditorDialog(
    draft: EditorDraft,
    original: EditorDraft,
    saving: Boolean,
    error: String?,
    onDraftChange: (EditorDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    latestVersion: EditorDraft? = null,
    onReloadLatest: (() -> Unit)? = null
) {
    var confirmClose by rememberSaveable(draft.entityId) { mutableStateOf(false) }
    var compareLatest by rememberSaveable(draft.entityId) { mutableStateOf(false) }
    var priorityMenu by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = { if (!saving) { if (draft != original) confirmClose = true else onDiscard() } }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = !saving, dismissOnClickOutside = !saving)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp)) {
                    Text(if (!draft.isNew) "编辑" else when (draft.kind) { "task" -> if (draft.addToToday) "添加今天的任务" else "新建任务"; "idea" -> "记个想法"; else -> "随手记" },
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    if (draft.isNew) Text(if (draft.kind == "task") "先写下一件做得到的事。" else "先记下来，稍后慢慢整理。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(draft.text, { onDraftChange(draft.copy(text = it)) }, label = { Text("内容") }, minLines = 2,
                            enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("editor_text"))
                        if (draft.kind != "inbox") OutlinedTextField(draft.note, { onDraftChange(draft.copy(note = it)) }, label = { Text("备注（可选）") }, minLines = 2,
                            enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("editor_note"))
                        if (draft.kind == "task") {
                            OutlinedTextField(draft.nextAction, { onDraftChange(draft.copy(nextAction = it)) }, label = { Text("下一步（可选）") },
                                enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("editor_next_action"))
                            OutlinedTextField(draft.estimate, { onDraftChange(draft.copy(estimate = it)) }, label = { Text("预计分钟数（可选）") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !saving,
                                isError = draft.estimate.isNotBlank() && draft.estimate.toIntOrNull()?.let { it > 0 } != true,
                                modifier = Modifier.fillMaxWidth().testTag("editor_estimate"))
                            Text("优先级", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box {
                                OutlinedButton(onClick = { priorityMenu = true }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("editor_priority")) {
                                    Text(quadrantLabels.getOrElse(draft.quadrant) { quadrantLabels[1] }, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "选择优先级")
                                }
                                DropdownMenu(expanded = priorityMenu, onDismissRequest = { priorityMenu = false }) {
                                    quadrantLabels.forEachIndexed { index, label ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { onDraftChange(draft.copy(quadrant = index)); priorityMenu = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("priority_$index"))
                                    }
                                }
                            }
                        }

                    }
                    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().testTag("editor_error").semantics { liveRegion = LiveRegionMode.Polite })
                    if (error != null && latestVersion != null) TextButton(onClick = { compareLatest = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("editor_compare_latest")) { Text("查看最新版本") }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = dismiss, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).testTag("editor_cancel")) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onSave, enabled = !saving && draft.text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("editor_save")) {
                            if (saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                            Text(if (saving) "正在保存" else "保存")
                        }
                    }
                }
            }
        }
    }
    if (compareLatest && latestVersion != null) AlertDialog(onDismissRequest = { compareLatest = false }, title = { Text("最新版本") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("你的草稿仍在。只有选择下方的载入操作，才会放弃草稿。", style = MaterialTheme.typography.bodySmall)
                Text("内容", style = MaterialTheme.typography.labelLarge); Text(latestVersion.text)
                if (latestVersion.kind != "inbox") { Text("备注", style = MaterialTheme.typography.labelLarge); Text(latestVersion.note.ifBlank { "无" }) }
                if (latestVersion.kind == "task") {
                    Text("下一步", style = MaterialTheme.typography.labelLarge); Text(latestVersion.nextAction.ifBlank { "未填写" })
                    Text("预计时长", style = MaterialTheme.typography.labelLarge); Text(latestVersion.estimate.takeIf { it.isNotBlank() }?.let { "$it 分钟" } ?: "未填写")
                    Text("优先级", style = MaterialTheme.typography.labelLarge); Text(quadrantLabels.getOrElse(latestVersion.quadrant) { "未分类" })
                }
            }
        },
        confirmButton = { TextButton(onClick = { compareLatest = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("editor_return_draft")) { Text("返回草稿") } },
        dismissButton = { if (onReloadLatest != null) TextButton(onClick = { compareLatest = false; onReloadLatest() }, modifier = Modifier.heightIn(min = 48.dp).testTag("editor_reload_latest")) { Text("放弃草稿并载入最新") } })
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("修改还没有保存") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("可以保留草稿，稍后继续编辑。草稿尚未同步到其他设备。")
                TextButton(onClick = { confirmClose = false; onDiscard() }, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_discard")) { Text("放弃修改", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { confirmClose = false; onDismiss() }, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_keep_close")) { Text("保留草稿并关闭") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_continue")) { Text("继续编辑") } })
}
