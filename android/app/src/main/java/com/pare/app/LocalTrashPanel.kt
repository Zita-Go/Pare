package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LocalTrashDialogModel : ViewModel() {
    var removing by mutableStateOf<List<LocalTrashEntry>?>(null)
}

@Composable
internal fun LocalTrashPanel(state: SyncState, query: String, onEdit: LibraryEdit, model: LibraryDialogModel, onOpen: (Entity) -> Unit) {
    val dialog: LocalTrashDialogModel = viewModel(key = "local_trash:${state.owner}:${state.device}")
    val entries = LocalTrashActions.visible(state)
    val filtered = remember(entries, query) {
        entries.filter { LibrarySearch.find(SyncState(items = mapOf(it.before.key to it.before)), query).isNotEmpty() }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("library_trash"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本机回收站 · ${entries.size} / ${LocalTrashActions.MAX_ENTRIES} 项", style = MaterialTheme.typography.titleMedium)
                Text("删除的恢复内容只保存在这台手机，重启后仍可找回。超过 30 天会提示清理，不会自动删除。回收站上限为 100 项或 2 MB。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entries.isNotEmpty()) TextButton(onClick = { dialog.removing = entries }, enabled = !model.saving,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("trash_clear")) { Text("清空本机回收站", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (filtered.isEmpty()) item { Text(if (query.isBlank()) "回收站为空" else "没有匹配的回收记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(filtered, key = { it.id }) { entry ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("trash_item_${entry.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LibrarySearch.title(entry.before), style = MaterialTheme.typography.titleSmall)
                    Text("删除于 ${trashDate(entry.deletedAt)}${if (entry.canReviewForCleanup(Instant.now())) " · 已超过 30 天，可检查后清理" else ""}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.restoredKey != null) {
                        Text(if (entry.restoredKey == entry.before.key) "已恢复原内容" else "已恢复为副本", color = MaterialTheme.colorScheme.primary)
                        state.items[entry.restoredKey]?.takeUnless { it.deleted }?.let { restored ->
                            TextButton(onClick = { onOpen(restored) }, enabled = !model.saving, modifier = Modifier.heightIn(min = 48.dp).testTag("trash_open_${entry.id}")) { Text("打开恢复内容") }
                        }
                        Text("恢复内容会继续保存；清理此回收记录不会删除它。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (entry.canRestoreOriginal(state)) {
                        OutlinedButton(onClick = { model.edit(onEdit, { LocalTrashActions.restoreOriginal(it, entry.id) }) { model.undo = null; model.message = "已恢复原内容" } },
                            enabled = !model.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("trash_restore_original_${entry.id}")) { Text("恢复原内容") }
                    } else {
                        Text("原删除状态已同步或再次改变，可以恢复一份副本，保留已有的新版本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("副本保留可恢复的内容与记录。其他内容中指向原记录的关联不会自动改动，需要时可重新关联。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { model.edit(onEdit, { LocalTrashActions.restoreCopy(it, entry.id) }) { model.undo = null; model.message = "已恢复为副本，原有内容与关联未覆盖" } },
                            enabled = !model.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("trash_restore_copy_${entry.id}")) { Text("恢复为副本") }
                    }
                    TextButton(onClick = { dialog.removing = listOf(entry) }, enabled = !model.saving,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trash_forget_${entry.id}")) { Text("清理这项恢复记录", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    dialog.removing?.let { selected ->
        AlertDialog(onDismissRequest = { if (!model.saving) dialog.removing = null }, title = { Text("清理 ${selected.size} 项回收记录？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("会永久移除这台手机保存的恢复内容。已恢复的原内容或副本会保留，云端记录不会因清理回收站而改变。")
                model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = {
                model.edit(onEdit, { LocalTrashActions.clear(it, selected) }) { dialog.removing = null; model.undo = null; model.message = "已清理本机回收记录" }
            }, enabled = !model.saving, modifier = Modifier.testTag("trash_clear_confirm")) { Text("永久清理", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { dialog.removing = null }, enabled = !model.saving) { Text("保留") } })
    }
}

private fun trashDate(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))
}.getOrDefault("未知日期")
