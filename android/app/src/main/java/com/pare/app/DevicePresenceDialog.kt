package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePresenceDialog(state: DevicePresenceState, content: SyncState, onRefresh: () -> Unit, onDismiss: () -> Unit,
    sharingEnabled: Boolean? = null, onSharingChange: ((Boolean) -> Unit)? = null, sharingError: String? = null) {
    val peers = if (sharingEnabled == false || state.owner != content.owner || state.device != content.device) emptyList() else state.peers
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("device_presence"), color = MaterialTheme.colorScheme.surface) {
            Scaffold(topBar = { ParePageHeader("其他设备的运行",onDismiss,backModifier=Modifier.testTag("device_presence_close"),backLabel="关闭") }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text("同一账号下其他设备最近报告的专注与等待记录。这里的状态仅供参考，各设备仍分别控制自己的计时。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (sharingEnabled != null) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("共享这台设备的运行状态", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Switch(checked = sharingEnabled, onCheckedChange = onSharingChange, enabled = onSharingChange != null && content.owner != null,
                                    modifier = Modifier.testTag("device_presence_sharing"))
                            }
                            Text("只有你开启后才会共享任务标识、运行状态与时间。这类报告不含任务标题、备注和 AI 记录，有效期为 90 秒。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (content.owner == null) Text("请先登录账号，再选择是否共享。", style = MaterialTheme.typography.bodySmall)
                            else if (!content.enabled) Text("内容同步暂停时不会发送运行状态。", style = MaterialTheme.typography.bodySmall)
                            if(sharingError!=null)Text(sharingError,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(state.status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("device_presence_status"))
                            if (sharingEnabled != false) state.lastConfirmedAt?.let { Text("最近确认：${presenceDate(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (sharingEnabled != false || state.status.contains("暂未确认清空")) OutlinedButton(shape=MaterialTheme.shapes.medium,onClick= onRefresh, enabled = !state.refreshing, modifier = Modifier.heightIn(min = 48.dp).testTag("device_presence_refresh")) {
                                Text(if (state.refreshing) "正在更新…" else if (sharingEnabled == false) "重试确认清空" else "更新状态")
                            }
                        }
                    }
                    if (peers.isEmpty() && sharingEnabled != false) item { Text("暂时没有其他设备的报告。设备关闭或离线后，之前的状态会标记为过期。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(peers, key = { it.deviceId }) { peer ->
                        OutlinedCard(Modifier.fillMaxWidth().testTag("device_presence_peer_${peer.deviceId}")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${peer.label} · ${peer.deviceId.takeLast(4)}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    Text(if (peer.fresh) "最近报告" else "状态已过期", style = MaterialTheme.typography.labelMedium,
                                        color = if (peer.fresh) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("报告时间：${presenceDate(peer.lastSeen)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!peer.fresh) Text("无法确认这台设备现在是否仍在运行。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                if (peer.runs.isEmpty()) Text(if (peer.fresh) "最近报告中没有运行记录" else "最后一次报告中没有运行记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                peer.runs.forEach { run ->
                                    val task = content.items["task:${run.taskId}"]?.takeUnless { it.deleted }
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(task?.title() ?: "任务已删除或尚未同步", style = MaterialTheme.typography.bodyLarge)
                                        val kind = if (run.kind == "personal") "个人专注" else "等待结果"
                                        val status = if (!peer.fresh) "最后记录 · $kind" else if (run.state == "paused") "$kind · 已暂停" else "$kind · 运行中"
                                        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.testTag("device_presence_run_${run.runId}"))
                                        Text("报告时已记录 ${run.elapsedSeconds / 60} 分钟${run.targetSeconds?.let { "，目标 ${it / 60} 分钟" }.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun presenceDate(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm:ss"))
}.getOrDefault("时间不可用")
