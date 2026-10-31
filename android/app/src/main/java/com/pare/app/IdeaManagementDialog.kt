package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import kotlinx.serialization.json.*

class IdeaManagementDraftModel : ViewModel() {
    var baseline by mutableStateOf<IdeaManagementBaseline?>(null)
    var review by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    fun read(idea: Entity) {
        baseline = IdeaManagementBaseline.from(idea)
        review = (idea.content()?.get("reviewAt") as? JsonPrimitive)?.contentOrNull
        error = null
    }
    fun edit(onEdit: LibraryEdit, operation: (SyncState) -> SyncState, success: () -> Unit = {}) {
        if (saving) return
        saving = true; error = null; message = null
        onEdit(operation) { result ->
            saving = false
            if (result.isSuccess) success() else error = result.exceptionOrNull()?.message ?: "保存失败，草稿仍保留"
        }
    }
}

@Composable
internal fun IdeaManagementDialog(ideaId: String, state: SyncState, onEdit: LibraryEdit, onDismiss: () -> Unit, onOpenTask: (Entity) -> Unit = {}) {
    val model: IdeaManagementDraftModel = viewModel(key = "idea_management:${state.owner}:${state.device}:$ideaId")
    val idea = state.items["idea:$ideaId"]?.takeUnless { it.deleted }
    LaunchedEffect(ideaId, idea) { if (model.baseline == null && idea != null) model.read(idea) }
    var query by rememberSaveable(ideaId) { mutableStateOf("") }
    var limit by rememberSaveable(ideaId) { mutableIntStateOf(20) }
    var showCandidates by rememberSaveable(ideaId) { mutableStateOf(false) }
    var linkLimit by rememberSaveable(ideaId) { mutableIntStateOf(20) }
    val links = idea?.let { IdeaLinkedTasks.project(state, it) }.orEmpty()
    val linkedIDs = links.map { it.id }.toSet()
    val candidates = LibrarySearch.find(state, query).filter { it.kind == "task" && it.id !in linkedIDs }
    val available = idea != null && idea.conflict == null && idea.conflictGroup.isEmpty()
    val link: (String, Boolean) -> Unit = { taskId, shouldLink ->
        if (idea != null) model.edit(onEdit, { IdeaManagementActions.link(it, ideaId, taskId, shouldLink, IdeaManagementBaseline.from(idea), Instant.now()) }) {
            model.message = if (shouldLink) "已关联行动" else "已解除关联，任务仍保留"
        }
    }
    LibraryFormDialog("关联行动与回看", "idea_management", model.saving, onDismiss) {
        Text(idea?.title() ?: "想法已不可用", style = MaterialTheme.typography.titleMedium)
        LibraryDateField("回看时间", model.review, available && !model.saving, "idea_review") { model.review = it }
        Text("到了这一天，在回看中重新看看这个想法。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = {
            model.baseline?.let { expected ->
                val at = model.review?.let(Instant::parse)
                model.edit(onEdit, { IdeaManagementActions.review(it, ideaId, at, expected, Instant.now()) }) {
                    model.baseline = expected.copy(reviewAt = at?.let { JsonPrimitive(it.toString()) }); model.message = "已保存回看时间"
                }
            }
        }, enabled = available && !model.saving && model.baseline != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("idea_review_save")) { Text("保存回看时间") }
        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("idea_management_error")) }
        model.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (!available) Text("想法不可用或存在冲突，草稿仍保留。", color = MaterialTheme.colorScheme.error)
        if (model.error != null && idea != null) TextButton(onClick = { model.read(idea) }, enabled = !model.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("放弃回看草稿，读取最新值") }
        HorizontalDivider()
        Text("已关联行动 · ${links.size}", style = MaterialTheme.typography.titleSmall)
        if (links.isEmpty()) Text("把已有任务接到想法上，继续积累探索与行动。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        links.take(linkLimit).forEach { task ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge)
                Text(when { task.missing -> "历史关联，行动已不可用"; task.archived -> "已归档"; task.completed -> "已完成"; else -> "进行中" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!task.missing) TextButton(onClick = { state.items["task:${task.id}"]?.let(onOpenTask) }, enabled = !model.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开任务") }
                    TextButton(onClick = { link(task.id, false) }, enabled = available && !model.saving, modifier = Modifier.heightIn(min = 48.dp).testTag("idea_unlink_${task.id}")) { Text("解除关联") }
                }
            }
        }
        if (links.size > linkLimit) TextButton(onClick = { linkLimit += 20 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("显示更多关联行动") }
        OutlinedButton(onClick = { showCandidates = !showCandidates }, enabled = available && !model.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("idea_link_existing")) { Text(if (showCandidates) "收起已有任务" else "关联已有任务") }
        if (showCandidates) {
            OutlinedTextField(value = query, onValueChange = { query = it; limit = 20 }, label = { Text("搜索已有任务") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("idea_link_search"))
            if (candidates.isEmpty()) Text("没有找到可关联的任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
            candidates.take(limit).forEach { task ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title(), style = MaterialTheme.typography.bodyLarge)
                        if (LibrarySearch.archived(task) || task.completed()) Text(if (LibrarySearch.archived(task)) "已归档" else "已完成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { link(task.id, true) }, enabled = available && !model.saving && task.conflict == null,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("idea_link_${task.id}")) { Text("关联") }
                }
            }
            if (candidates.size > limit) TextButton(onClick = { limit += 20 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("显示更多任务") }
        }
    }
}
