package com.pare.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Stateless editor boundary allows save failures and large font layouts to be exercised without an account. */
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
            val compact = maxHeight < 460.dp
            val errorHeight = (maxHeight * 0.24f).coerceAtMost(132.dp)
            Surface(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    PareFormHeader(
                        icon = when (draft.kind) { "task" -> Icons.Default.Check; "idea" -> Icons.Default.Star; else -> Icons.Default.Edit },
                        label = if (compact) null else when (draft.kind) { "task" -> "把重要的事，向前推进"; "idea" -> "给灵感留个位置"; else -> "先记下，稍后整理" },
                        title = if (!draft.isNew) "编辑" else when (draft.kind) { "task" -> if (draft.addToToday) "添加今天的任务" else "新建任务"; "idea" -> "记个想法"; else -> "随手记" }
                    )
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PareFormField(draft.text, { onDraftChange(draft.copy(text = it)) }, label = "内容", minLines = 2,
                            enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("editor_text"))
                        if (draft.kind != "inbox") PareFormField(draft.note, { onDraftChange(draft.copy(note = it)) }, label = "备注（可选）", minLines = 2,
                            enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("editor_note"))
                        if (draft.kind == "task") {
                            PareFormField(draft.nextAction, { onDraftChange(draft.copy(nextAction = it)) }, label = "下一步（可选）",
                                enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("editor_next_action"))
                            PareFormField(draft.estimate, { onDraftChange(draft.copy(estimate = it)) }, label = "预计分钟数（可选）", singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !saving,
                                isError = draft.estimate.isNotBlank() && draft.estimate.toIntOrNull()?.let { it > 0 } != true,
                                modifier = Modifier.fillMaxWidth().testTag("editor_estimate"))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("优先级", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Box {
                                    OutlinedButton(
                                        onClick = { priorityMenu = true }, enabled = !saving,
                                        shape = MaterialTheme.shapes.medium,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface, containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("editor_priority")
                                    ) {
                                        Box(Modifier.size(7.dp).background(pareQuadrantColor(draft.quadrant), CircleShape))
                                        Spacer(Modifier.width(8.dp))
                                        Text(quadrantLabels.getOrElse(draft.quadrant) { quadrantLabels[1] }, modifier = Modifier.weight(1f, fill = false))
                                        Spacer(Modifier.width(8.dp))
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "选择优先级", modifier = Modifier.size(18.dp))
                                    }
                                    DropdownMenu(expanded = priorityMenu, onDismissRequest = { priorityMenu = false }) {
                                        quadrantLabels.forEachIndexed { index, label ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                leadingIcon = { Box(Modifier.size(8.dp).background(pareQuadrantColor(index), CircleShape)) },
                                                trailingIcon = { if (index == draft.quadrant) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) },
                                                onClick = { onDraftChange(draft.copy(quadrant = index)); priorityMenu = false },
                                                modifier = Modifier.heightIn(min = 48.dp).testTag("priority_$index")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Errors and actions remain outside the form scroller, including at large font scales.
                    if (error != null) {
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                            Text(
                                error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().heightIn(max = errorHeight).verticalScroll(rememberScrollState()).padding(12.dp)
                                    .testTag("editor_error").semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                        if (latestVersion != null) TextButton(onClick = { compareLatest = true }, shape = MaterialTheme.shapes.small,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("editor_compare_latest")) { Text("查看最新版本") }
                        Spacer(Modifier.height(12.dp))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = dismiss, enabled = !saving, shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("editor_cancel")
                        ) { Text("取消") }
                        Button(onClick = onSave, enabled = !saving && draft.text.isNotBlank(), shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("editor_save")) {
                            if (saving) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(6.dp)) }
                            Text(if (saving) "正在保存" else "保存")
                        }
                    }
                }
            }
        }
    }
    if (compareLatest && latestVersion != null) AlertDialog(onDismissRequest = { compareLatest = false }, title = { Text("最新版本") },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("你的草稿仍在。只有选择下方的载入操作，才会放弃草稿。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LatestVersionField("内容", latestVersion.text)
                        if (latestVersion.kind != "inbox") LatestVersionField("备注", latestVersion.note.ifBlank { "无" })
                        if (latestVersion.kind == "task") {
                            LatestVersionField("下一步", latestVersion.nextAction.ifBlank { "未填写" })
                            LatestVersionField("预计时长", latestVersion.estimate.takeIf { it.isNotBlank() }?.let { "$it 分钟" } ?: "未填写")
                            LatestVersionField("优先级", quadrantLabels.getOrElse(latestVersion.quadrant) { "未分类" })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { compareLatest = false }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp).testTag("editor_return_draft")) { Text("返回草稿") } },
        dismissButton = { if (onReloadLatest != null) TextButton(onClick = { compareLatest = false; onReloadLatest() }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp).testTag("editor_reload_latest")) { Text("放弃草稿并载入最新") } })
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("修改还没有保存") },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("可以保留草稿，稍后继续编辑。草稿尚未同步到其他设备。")
                TextButton(onClick = { confirmClose = false; onDiscard() }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_discard")) { Text("放弃修改", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { confirmClose = false; onDismiss() }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_keep_close")) { Text("保留草稿并关闭") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_continue")) { Text("继续编辑") } })
}

@Composable private fun LatestVersionField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
