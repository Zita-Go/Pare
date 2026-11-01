package com.pare.app

import kotlinx.serialization.json.JsonObject

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalLayoutApi::class)
@Composable fun AccountDialog(vm: PareViewModel, state: SyncState, status: String, session: Session?, busy: Boolean, previewReady: Boolean, onDismiss: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signOut by rememberSaveable { mutableStateOf(false) }
    var pause by rememberSaveable { mutableStateOf(false) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Surface(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight().testTag("account_surface"),
                color = MaterialTheme.colorScheme.background
            ) {
                Column {
                    ParePageHeader("账号与同步",onDismiss,backModifier=Modifier.testTag("account_close"),backLabel="关闭")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (session == null) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("在设备之间接着推进", style = MaterialTheme.typography.titleMedium)
                                Text("本机内容已保存在这台设备。登录可连接其他设备。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AccountSection {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PareFormField(email, { email = it }, label = "邮箱", singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                        modifier = Modifier.fillMaxWidth().testTag("account_email"))
                                    PareFormField(password, { password = it }, label = "密码", visualTransformation = PasswordVisualTransformation(), singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                        modifier = Modifier.fillMaxWidth().testTag("account_password"))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = { vm.authenticate(email.trim(), password, false); password = "" },
                                        enabled = !busy && email.isNotBlank() && password.isNotEmpty(), shape = MaterialTheme.shapes.medium,
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("account_login")
                                    ) { Text("登录") }
                                    OutlinedButton(
                                        onClick = { vm.authenticate(email.trim(), password, true); password = "" },
                                        enabled = !busy && email.isNotBlank() && password.isNotEmpty(), shape = MaterialTheme.shapes.medium,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("account_register")
                                    ) { Text("注册") }
                                }
                                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("account_status").semantics { liveRegion = LiveRegionMode.Polite })
                            }
                            Text("先查看云端内容，再由你确认是否合并。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            AccountSection {
                                Text("当前账号", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(session.email, style = MaterialTheme.typography.titleMedium)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PareIcon(if (state.enabled) PareIcons.CheckCircle else PareIcons.Refresh, null,
                                        modifier = Modifier.size(20.dp), tint = if (state.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (state.enabled) "自动同步已开启" else "自动同步未开启", style = MaterialTheme.typography.titleSmall)
                                }
                                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("account_status").semantics { liveRegion = LiveRegionMode.Polite })
                                Text(
                                    if (state.enabled) "修改先保存在本机，联网后自动同步。离开应用后，系统会安排定期检查。" else "本机内容会继续保存。开启同步后，先合并手机与云端的内容。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!state.enabled) {
                                    Button(onClick= { consent = true }, enabled = !busy && previewReady, shape = MaterialTheme.shapes.medium,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sync_enable")) { Text("确认合并并开启自动同步") }
                                    if (!previewReady) TextButton(onClick = { vm.sync(true) }, enabled = !busy, shape = MaterialTheme.shapes.medium,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("先读取云端预览") }
                                }
                            }
                            AccountSection {
                                TextButton(
                                    onClick = { details = !details }, shape = MaterialTheme.shapes.medium,
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sync_details")
                                ) {
                                    Text(if (details) "收起同步详情" else "同步详情与操作", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    PareIcon(if (details) PareIcons.Up else PareIcons.Down, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (details) {
                                    Text("本机待上传 ${state.items.values.count { it.dirty }} 项 · 待决定版本 ${state.items.values.count { it.conflict != null }} 项", style = MaterialTheme.typography.bodySmall)
                                    Text("AI 密钥与请求历史留在本机。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    OutlinedButton(onClick= { vm.sync(true) }, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sync_retry")) { Text("立即重试") }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (state.enabled) TextButton(onClick = { pause = true }, shape = MaterialTheme.shapes.medium,
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant), modifier = Modifier.heightIn(min = 48.dp)) { Text("暂停同步") }
                                        TextButton(onClick = { signOut = true }, shape = MaterialTheme.shapes.medium,
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.heightIn(min = 48.dp)) { Text("退出账号") }
                                    }
                                }
                            }
                        }
                        val conflict = stableEntities(state.items.values.filter { it.conflict != null && it.conflictChoice == null }).firstOrNull()
                        if (conflict != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("选择要保留的版本", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Text("选择会用于整条记录；有关联的记录，需要全部选择后一起处理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                            val taskTitles = state.items.values.filter { it.kind == "task" }.associate { it.id to it.title() }
                            val localFields = conflictFields(conflict.kind, conflict.payload, conflict.deleted, taskTitles)
                            val cloud = requireNotNull(conflict.conflict)
                            val cloudFields = conflictFields(cloud.kind, cloud.payload, cloud.deletedAt != null, taskTitles)
                            ConflictVersion("本机版本", localFields, cloudFields, raw = conflict.payload)
                            ConflictVersion("云端版本", cloudFields, localFields, cloudVersion = true, raw = cloud.payload)
                            if (state.pending != null) Text("请先重试同步，确认上一笔请求后再选择版本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick= { vm.resolve(conflict.key, true) }, enabled = !busy && state.pending == null, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) { Text("保留本机") }
                                OutlinedButton(onClick= { vm.resolve(conflict.key, false) }, enabled = !busy && state.pending == null, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) { Text("采用云端") }
                            }
                        }
                    }

                }
            }
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("开启自动同步？") },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        text = { Text("已读取云端预览。开启后，本机与云端的任务、想法、今日计划、背景和规划偏好会合并，之后自动同步。不同版本需要你决定，AI 密钥与请求历史不会上传。") },
        confirmButton = { TextButton(onClick = { consent = false; vm.enableSync() }, enabled = !busy && previewReady, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) { Text("确认合并并开启") } },
        dismissButton = { TextButton(onClick = { consent = false }, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) { Text("暂不开启") } })
    if (pause) AlertDialog(onDismissRequest = { pause = false }, title = { Text("暂停自动同步？") }, text = { Text("暂停后仍可在本机编辑。待上传内容会保留，重新开启后继续同步。") },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        confirmButton = { TextButton(onClick = { pause = false; vm.pauseSync() }, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) { Text("暂停同步") } },
        dismissButton = { TextButton(onClick = { pause = false }, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    if (signOut) AlertDialog(onDismissRequest = { signOut = false }, title = { Text("退出账号？") }, text = { Text("未上传变更会保留在本机，并继续绑定当前账号。重新登录同一账号可继续同步。") },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        confirmButton = { TextButton(onClick = { signOut = false; vm.signOut() }, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) { Text("退出并保留本机") } },
        dismissButton = { TextButton(onClick = { signOut = false }, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable private fun ConflictVersion(title: String, fields: List<ConflictField>, other: List<ConflictField>, cloudVersion: Boolean = false, raw: JsonObject? = null) {
    var expanded by remember(raw) { mutableStateOf(false) }
    val accent = if (cloudVersion) pareQuadrantColor(2) else MaterialTheme.colorScheme.primary
    Surface(
        color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = accent)
            HorizontalDivider(color = accent.copy(alpha = 0.20f))
            fields.forEach { field ->
                val changed = other.none { it == field }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(field.label + if (changed) " · 有差异" else "", style = MaterialTheme.typography.labelLarge, color = if (changed) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(field.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (raw != null) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (expanded) "收起完整内容" else "查看完整版本内容")
                }
                if (expanded) androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(raw.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable private fun AccountSection(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
