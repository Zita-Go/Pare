package com.pare.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

class LibraryDialogModel : ViewModel() {
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var feedback by mutableStateOf<String?>(null)
    var messageEvent by mutableLongStateOf(0L); private set
    var message: String?
        get() = feedback
        set(value) { feedback=value; if(value!=null) messageEvent++ }
    fun consumeMessage(event: Long) { if(messageEvent==event) message=null }
    var deletion by mutableStateOf<Entity?>(null)
    var undo by mutableStateOf<LibraryDeletionUndo?>(null)
    fun edit(onEdit: LibraryEdit, operation: (SyncState) -> SyncState, success: () -> Unit = {}) {
        if (saving) return
        saving = true; error = null; message = null
        onEdit(operation) { result ->
            saving = false
            if (result.isSuccess) success() else error = result.exceptionOrNull()?.message ?: "保存失败，当前内容仍保留"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryDialog(
    state: SyncState, onEdit: LibraryEdit, onDismiss: () -> Unit,
    onOpenTask: (Entity) -> Unit, onOpenIdea: (Entity) -> Unit,
    onOpenEntity: (Entity) -> Unit = {}, initialKind: String? = null
) {
    val model: LibraryDialogModel = viewModel(key = "library:${state.owner}:${state.device}")
    LaunchedEffect(state.localTrash) {
        model.undo?.let { undo ->
            val retained = state.localTrash.firstOrNull { it.before.key == undo.before.key && it.tombstone == undo.tombstone }
            if (retained == null || retained.restoredKey != null) model.undo = null
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var scopeName by rememberSaveable { mutableStateOf(when(initialKind) {
        "task" -> "TASKS"; "inbox" -> "INBOX"; "idea" -> "IDEAS"; "goal" -> "GOALS"; else -> "ALL"
    }) }
    val scope = LibraryScope.valueOf(scopeName)
    var stage by rememberSaveable { mutableStateOf<String?>(null) }
    var scopeMenu by remember { mutableStateOf(false) }
    var stageMenu by remember { mutableStateOf(false) }
    val results = remember(state, query, scope, stage) { LibrarySearch.find(state, query, scope, stage) }
    val open: (Entity) -> Unit = { item -> when(item.kind) { "task" -> onOpenTask(item); "idea" -> onOpenIdea(item); else -> onOpenEntity(item) } }
    Dialog(onDismissRequest = { if (!model.saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("library_dialog"), color = MaterialTheme.colorScheme.surface) {
            Scaffold(topBar = { ParePageHeader("资料库",onDismiss,backModifier=Modifier.testTag("library_close"),enabled=!model.saving) }, snackbarHost={
                ManagementSnackbar(model.message,model.messageEvent,model::consumeMessage,"library_feedback")
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索标题、背景或下一步") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("library_search"))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            TextButton(onClick = { scopeMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("library_scope")) { Text("${scope.label} ▾") }
                            DropdownMenu(expanded = scopeMenu, onDismissRequest = { scopeMenu = false }) {
                                LibraryScope.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { scopeName = option.name; scopeMenu = false }, modifier = Modifier.testTag("library_scope_${option.name.lowercase()}")) }
                            }
                        }
                        if (scope == LibraryScope.IDEAS) Box {
                            TextButton(onClick = { stageMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("library_stage")) { Text("${stage?.let(::ideaStageLabel) ?: "所有阶段"} ▾") }
                            DropdownMenu(expanded = stageMenu, onDismissRequest = { stageMenu = false }) {
                                (listOf<String?>(null) + listOf("seed", "exploring", "building", "parked", "archived")).forEach { value ->
                                    DropdownMenuItem(text = { Text(value?.let(::ideaStageLabel) ?: "所有阶段") }, onClick = { stage = value; stageMenu = false }, modifier = Modifier.testTag("library_stage_${value ?: "all"}"))
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f)); Text("${if (scope == LibraryScope.TRASH) LocalTrashActions.visible(state).size else results.size} 条", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (scope == LibraryScope.DEADLINES) Text("按截止时间排列；提醒时间在任务详情中单独管理。", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp).testTag("library_error")) }
                    model.undo?.let { undo ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("已删除“${LibrarySearch.title(undo.before)}”", modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (state.items[undo.before.key] == undo.tombstone) TextButton(onClick = { model.edit(onEdit, undo::restore) { model.undo = null; model.message = "已恢复" } }, enabled = !model.saving,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("library_undo_delete")) { Text("撤销") }
                            else TextButton(onClick = { scopeName = LibraryScope.TRASH.name }, modifier = Modifier.heightIn(min = 48.dp).testTag("library_open_trash")) { Text("回收站") }
                        }
                    }
                    HorizontalDivider()
                    if (scope == LibraryScope.TRASH) LocalTrashPanel(state, query, onEdit, model, open)
                    else LazyColumn(Modifier.fillMaxSize().testTag("library_results"), contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (results.isEmpty()) item { Text(if (query.isBlank()) "这个分类暂时没有内容" else "没有找到匹配的内容，试试标题、背景或下一步中的词。", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(results, key = { it.key }) { item ->
                            LibraryRow(item, !model.saving, onOpen = { open(item) }, onDelete = { model.deletion = item },
                                onArchive = { model.edit(onEdit, { LibraryActions.archive(it, item.id, LibraryBaseline.from(item)) }) { model.message = "已归档，可在已归档中恢复" } },
                                onRestoreArchive = { model.edit(onEdit, { LibraryActions.restoreArchive(it, item.id, LibraryBaseline.from(item), Instant.now()) }) { model.message = "已恢复为待安排任务" } },
                                onRestoreCompleted = { model.edit(onEdit, { current -> LibraryBaseline.from(item).check(libraryCurrent(current, "task", item.id)); CompletionActions.set(current, item.id, false, Instant.now()) }) },
                                onKeepIdea = {
                                    val id = uid()
                                    model.edit(onEdit, { IdeaManagementActions.keepInbox(it, item.id, id, LibraryBaseline.from(item), Instant.now()) }) { model.message = "已保留为想法，可在想法分类继续探索" }
                                })
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
    model.deletion?.let { item -> AlertDialog(onDismissRequest = { if (!model.saving) model.deletion = null },
        title = { Text("删除${libraryKindLabel(item.kind)}？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("“${LibrarySearch.title(item)}”会从资料库移除，恢复内容保存在本机回收站。其他关联内容仍保留；原删除状态已同步或再次改变后，可从回收站恢复为副本。")
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            var undo: LibraryDeletionUndo? = null
            model.edit(onEdit, { before -> LibraryActions.delete(before, item.kind, item.id, LibraryBaseline.from(item)).also { undo = LibraryActions.deletionUndo(before, it, item.key) } }) {
                model.deletion = null; model.undo = undo
            }
        }, enabled = !model.saving, modifier = Modifier.testTag("library_delete_confirm")) { Text(if (model.saving) "正在删除…" else "删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { model.deletion = null }, enabled = !model.saving) { Text("保留") } }) }
}

private fun libraryKindLabel(kind: String) = when(kind) { "task" -> "任务"; "idea" -> "想法"; "goal" -> "目标"; else -> "随手记" }

@Composable
private fun LibraryRow(item: Entity, enabled: Boolean, onOpen: () -> Unit, onDelete: () -> Unit, onArchive: () -> Unit, onRestoreArchive: () -> Unit, onRestoreCompleted: () -> Unit, onKeepIdea: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val archived = LibrarySearch.archived(item)
    val status = when { archived -> "已归档"; item.kind == "task" && item.completed() -> "已完成"; item.kind == "idea" -> ideaStageLabel((item.content()?.get("stage") as? JsonPrimitive)?.contentOrNull ?: "seed"); else -> libraryKindLabel(item.kind) }
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(enabled = enabled, onClick = onOpen).padding(vertical = 16.dp).testTag("library_item_${item.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(LibrarySearch.title(item), style = MaterialTheme.typography.bodyLarge)
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LibrarySearch.deadline(item)?.let { due -> Text("截止 ${due.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.size(48.dp).testTag("library_menu_${item.id}")) { PareIcon(PareIcons.More, "更多操作：${LibrarySearch.title(item)}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("打开") }, onClick = { menu = false; onOpen() })
                if (item.kind == "task") {
                    if (archived) DropdownMenuItem(text = { Text("恢复为待安排任务") }, onClick = { menu = false; onRestoreArchive() }, modifier = Modifier.testTag("library_restore_archive"))
                    else {
                        if (item.completed()) DropdownMenuItem(text = { Text("恢复未完成") }, onClick = { menu = false; onRestoreCompleted() })
                        DropdownMenuItem(text = { Text("归档") }, onClick = { menu = false; onArchive() }, modifier = Modifier.testTag("library_archive"))
                    }
                }
                if (item.kind == "inbox") DropdownMenuItem(text = { Text("保留为想法") }, onClick = { menu = false; onKeepIdea() }, modifier = Modifier.testTag("library_keep_idea"))
                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() }, modifier = Modifier.testTag("library_delete"))
            }
        }
    }
}
