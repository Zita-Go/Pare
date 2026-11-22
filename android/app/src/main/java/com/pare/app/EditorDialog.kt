package com.pare.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
    onReloadLatest: (() -> Unit)? = null,
    taskActions: (@Composable () -> Unit)? = null,
    plannedDayLabel:String? = null,
    inboxActions:(@Composable ()->Unit)?=null
) {
    var confirmClose by rememberSaveable(draft.entityId) { mutableStateOf(false) }
    var compareLatest by rememberSaveable(draft.entityId) { mutableStateOf(false) }
    var expanded by rememberSaveable(draft.entityId) { mutableStateOf(!draft.isNew && draft.kind != "task") }
    var focusedOnce by rememberSaveable(draft.entityId) { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val dismiss: () -> Unit = { if (!saving) { if (draft != original) confirmClose = true else onDiscard() } }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = !saving, dismissOnClickOutside = !saving)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = if (draft.isNew) Alignment.BottomCenter else Alignment.TopCenter) {
            if (draft.isNew) Box(Modifier.matchParentSize().clickable(enabled = !saving, indication = null, interactionSource = remember { MutableInteractionSource() }) { dismiss() })
            val errorHeight = (maxHeight * 0.24f).coerceAtMost(132.dp)
            Surface(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().then(if (draft.isNew) Modifier.heightIn(max = maxHeight) else Modifier.fillMaxHeight()).testTag("editor_surface"),
                shape = if (draft.isNew) RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp) else RectangleShape,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column {
                    ParePageHeader(
                        title = if (draft.isNew) when (draft.kind) { "task" -> "添加任务"; "idea" -> "记录想法"; else -> "随手记" }
                            else when (draft.kind) { "idea" -> "想法详情"; "inbox" -> "随手记详情"; else -> "任务详情" },
                        onBack = dismiss, backLabel = if (draft.isNew) "取消" else "返回", enabled = !saving,
                        backModifier = Modifier.testTag("editor_cancel")
                    )
                    Column(
                        Modifier.weight(1f, fill = !draft.isNew).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        BasicTextField(
                            value = draft.text, onValueChange = { onDraftChange(draft.copy(text = it)) }, enabled = !saving,
                            textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(titleFocus).testTag("editor_text"),
                            decorationBox = { field -> Box(Modifier.padding(vertical = 12.dp)) {
                                if (draft.text.isEmpty()) Text(if (draft.kind == "idea") "记下你的想法" else "准备做什么？", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                field()
                            } }
                        )
                        if (draft.kind == "task") EditorPriority(draft, saving, onDraftChange)
                        if(draft.isNew && plannedDayLabel!=null)Text(plannedDayLabel,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(!draft.isNew && draft.kind=="task") {
                            EditorNextAction(draft, saving, onDraftChange)
                            taskActions?.invoke()
                        }
                        if(!draft.isNew && draft.kind=="inbox")inboxActions?.invoke()
                        if (draft.isNew && draft.kind == "task" && !expanded) TextButton(
                            onClick = { expanded = true }, enabled = !saving,
                            contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("editor_estimate_entry")
                        ) { Text("预计时长 · ${draft.estimate.takeIf { it.isNotBlank() }?.let { "$it 分钟" } ?: "未填写"}") }
                        if (draft.kind != "inbox" && (draft.isNew || draft.kind == "task")) TextButton(
                            onClick = { expanded = !expanded }, enabled = !saving,
                            contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("editor_details")
                        ) {
                            PareIcon(if (expanded) PareIcons.Up else PareIcons.Down, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (expanded) "收起更多信息" else "更多信息")
                        }
                        if (expanded && draft.kind != "inbox") {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            if (draft.isNew && draft.kind == "task") EditorNextAction(draft, saving, onDraftChange)
                            PareFormField(draft.note, { onDraftChange(draft.copy(note = it)) }, label = "备注（可选）", minLines = if (draft.isNew) 2 else 3,
                                enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("editor_note"))
                            if (draft.kind == "task") {
                                PareFormField(draft.estimate, { onDraftChange(draft.copy(estimate = it)) }, label = "预计分钟数（可选）", singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !saving,
                                    isError = draft.estimate.isNotBlank() && draft.estimate.toIntOrNull()?.let { it > 0 } != true,
                                    modifier = Modifier.fillMaxWidth().testTag("editor_estimate"))
                            }
                        }
                    }
                    // Keep errors outside the scroller so a failed save is visible from every field.
                    if (error != null) Column(Modifier.padding(horizontal = 16.dp)) {
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().heightIn(max = errorHeight).verticalScroll(rememberScrollState()).padding(12.dp)
                                    .testTag("editor_error").semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        if (latestVersion != null) TextButton(onClick = { compareLatest = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("editor_compare_latest")) { Text("查看最新版本") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) { EditorSaveButton(draft, saving, onSave) }
                }
            }
            LaunchedEffect(draft.entityId) {
                if (draft.isNew && !focusedOnce) {
                    withFrameNanos { }
                    titleFocus.requestFocus()
                    keyboard?.show()
                    focusedOnce = true
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
        confirmButton = { TextButton(onClick = { compareLatest = false }, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp).testTag("editor_return_draft")) { Text("返回草稿") } },
        dismissButton = { if (onReloadLatest != null) TextButton(onClick = { compareLatest = false; onReloadLatest() }, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp).testTag("editor_reload_latest")) { Text("放弃草稿并载入最新") } })
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("修改还没有保存") },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("可以保留草稿，稍后继续编辑。草稿尚未同步到其他设备。")
                TextButton(onClick = { confirmClose = false; onDiscard() }, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_discard")) { Text("放弃修改", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { confirmClose = false; onDismiss() }, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_keep_close")) { Text("保留草稿并关闭") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_continue")) { Text("继续编辑") } })
}

@Composable private fun EditorNextAction(draft: EditorDraft, saving: Boolean, onDraftChange: (EditorDraft) -> Unit) {
    PareFormField(draft.nextAction, { onDraftChange(draft.copy(nextAction = it)) }, label = "最小下一步（可选）",
        enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("editor_next_action"))
}

@Composable private fun LatestVersionField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun EditorSaveButton(draft: EditorDraft, saving: Boolean, onSave: () -> Unit) {
    Button(onClick = onSave, enabled = !saving && draft.text.isNotBlank(), shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("editor_save")) {
        if (saving) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(6.dp)) }
        Text(if (saving) "正在保存" else "保存")
    }
}

@Composable private fun EditorPriority(draft: EditorDraft, saving: Boolean, onDraftChange: (EditorDraft) -> Unit) {
    var priorityMenu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { priorityMenu = true }, enabled = !saving, contentPadding = PaddingValues(horizontal = 0.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("editor_priority")) {
            Text("优先级", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Box(Modifier.size(7.dp).background(pareQuadrantColor(draft.quadrant), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(quadrantLabels.getOrElse(draft.quadrant) { quadrantLabels[1] }, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            PareIcon(PareIcons.Down, "选择优先级", modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = priorityMenu, onDismissRequest = { priorityMenu = false }) {
            quadrantLabels.forEachIndexed { index, label ->
                DropdownMenuItem(text = { Text(label) },
                    leadingIcon = { Box(Modifier.size(8.dp).background(pareQuadrantColor(index), CircleShape)) },
                    trailingIcon = { if (index == draft.quadrant) PareIcon(PareIcons.Check, null, modifier = Modifier.size(18.dp)) },
                    onClick = { onDraftChange(draft.copy(quadrant = index)); priorityMenu = false },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("priority_$index"))
            }
        }
    }
}
