package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable fun AccountDialog(vm: PareViewModel, state: SyncState, status: String, session: Session?, busy: Boolean, previewReady: Boolean, onDismiss: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signOut by rememberSaveable { mutableStateOf(false) }
    var pause by rememberSaveable { mutableStateOf(false) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("账号与同步") }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (session == null) {
                Text("本机保存，随时可用", style = MaterialTheme.typography.titleMedium)
                Text("登录后可以让手机与 Mac 自动同步。先查看云端内容，再由你确认是否合并。", style = MaterialTheme.typography.bodyMedium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(email, { email = it }, label = { Text("邮箱") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.authenticate(email.trim(), password, false); password = "" }, enabled = !busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("登录") }
                    TextButton(onClick = { vm.authenticate(email.trim(), password, true); password = "" }, enabled = !busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("注册") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (state.enabled) Icons.Default.CheckCircle else Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary)
                    Text(if (state.enabled) "自动同步已开启" else "自动同步未开启", fontWeight = FontWeight.SemiBold)
                }
                Text(session.email, style = MaterialTheme.typography.bodyMedium)
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (state.enabled) "修改会先保存在本机，联网后自动同步。离开应用后会定期检查，具体时间由系统安排。" else "本机内容会继续保存。开启后，任务、想法、今日计划、背景与规划偏好将与云端合并。", style = MaterialTheme.typography.bodySmall)
                if (!state.enabled) {
                    Button(onClick = { consent = true }, enabled = !busy && previewReady, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sync_enable")) { Text("确认合并并开启自动同步") }
                    if (!previewReady) TextButton(onClick = { vm.sync(true) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("先读取云端预览") }
                }
                TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp).testTag("sync_details")) { Text(if (details) "收起同步详情" else "同步详情与操作") }
                if (details) {
                    HorizontalDivider()
                    Text("本机待上传 ${state.items.values.count { it.dirty }} 项 · 待决定版本 ${state.items.values.count { it.conflict != null }} 项", style = MaterialTheme.typography.bodySmall)
                    Text("AI 密钥与请求历史留在本机。", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.sync(true) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("sync_retry")) { Text("立即重试") }
                        if (state.enabled) TextButton(onClick = { pause = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("暂停同步") }
                        TextButton(onClick = { signOut = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("退出账号") }
                    }
                }
            }
            val conflict = stableEntities(state.items.values.filter { it.conflict != null && it.conflictChoice == null }).firstOrNull()
            if (conflict != null) {
                HorizontalDivider()
                Text("选择要保留的版本", fontWeight = FontWeight.SemiBold)
                Text("下方列出两个版本的内容。选择会用于整条记录；有关联的记录需要全部选择后一起处理。", style = MaterialTheme.typography.bodySmall)
                val taskTitles = state.items.values.filter { it.kind == "task" }.associate { it.id to it.title() }
                val localFields = conflictFields(conflict.kind, conflict.payload, conflict.deleted, taskTitles)
                val cloud = requireNotNull(conflict.conflict)
                val cloudFields = conflictFields(cloud.kind, cloud.payload, cloud.deletedAt != null, taskTitles)
                ConflictVersion("本机版本", localFields, cloudFields)
                ConflictVersion("云端版本", cloudFields, localFields)
                if (state.pending != null) Text("请先重试同步，确认上一笔请求后再选择版本。", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.resolve(conflict.key, true) }, enabled = !busy && state.pending == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("保留本机") }
                    OutlinedButton(onClick = { vm.resolve(conflict.key, false) }, enabled = !busy && state.pending == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("采用云端") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") } })
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("开启自动同步？") },
        text = { Text("已读取云端预览。开启后，本机与云端的任务、想法、今日计划、背景和规划偏好会合并，之后自动同步。不同版本需要你决定，AI 密钥与请求历史不会上传。") },
        confirmButton = { TextButton(onClick = { consent = false; vm.enableSync() }, enabled = !busy && previewReady, modifier = Modifier.heightIn(min = 48.dp)) { Text("确认合并并开启") } },
        dismissButton = { TextButton(onClick = { consent = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("暂不开启") } })
    if (pause) AlertDialog(onDismissRequest = { pause = false }, title = { Text("暂停自动同步？") }, text = { Text("暂停后仍可在本机编辑。待上传内容会保留，重新开启后继续同步。") },
        confirmButton = { TextButton(onClick = { pause = false; vm.pauseSync() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("暂停同步") } },
        dismissButton = { TextButton(onClick = { pause = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    if (signOut) AlertDialog(onDismissRequest = { signOut = false }, title = { Text("退出账号？") }, text = { Text("未上传变更会保留在本机，并继续绑定当前账号。重新登录同一账号可继续同步。") },
        confirmButton = { TextButton(onClick = { signOut = false; vm.signOut() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("退出并保留本机") } },
        dismissButton = { TextButton(onClick = { signOut = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable private fun ConflictVersion(title: String, fields: List<ConflictField>, other: List<ConflictField>) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            fields.forEach { field ->
                val changed = other.none { it == field }
                Text(field.label + if (changed) " · 有差异" else "", style = MaterialTheme.typography.labelMedium, color = if (changed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(field.value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
