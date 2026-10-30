package com.pare.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.*

class MainActivity : ComponentActivity() {
    // The activity owns lifecycle delivery so the first resume cannot precede Compose's VM hookup.
    private val model: PareViewModel by lazy { ViewModelProvider(this)[PareViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PareTheme { PareScreen(model) } }
    }
    override fun onResume() { super.onResume(); model.onForeground() }
    override fun onPause() { model.onBackground(); super.onPause() }
}

private val DraftSaver = listSaver<EditorDraft, Any>(
    save = { listOf(it.entityId, it.kind, it.text, it.note, it.quadrant, it.nextAction, it.estimate, it.addToToday, it.isNew) },
    restore = { EditorDraft(it[0] as String, it[1] as String, it[2] as String, it[3] as String, it[4] as Int, it[5] as String, it[6] as String, it[7] as Boolean, it[8] as Boolean) }
)

@Composable fun PareScreen(vm: PareViewModel) {
    val state by vm.state.collectAsState()
    val planningNow by vm.planningInstant.collectAsState()
    val status by vm.status.collectAsState()
    val session by vm.session.collectAsState()
    val busy by vm.busy.collectAsState()
    val previewReady by vm.previewReady.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var account by rememberSaveable { mutableStateOf(false) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(EditorDraft("", "inbox")) }
    var original by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(EditorDraft("", "inbox")) }
    var pendingDraft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(EditorDraft("", "inbox")) }
    val editorSave: EditorSaveModel = viewModel()
    val saveState = editorSave.state
    fun openEditor(entity: Entity? = null, kind: String = "inbox", today: Boolean = false) {
        val requested = entity?.let(EditorDraft::from) ?: EditorDraft(uid(), kind, addToToday = today)
        when (draftOpenAction(draft, requested)) {
            DraftOpenAction.OPEN_REQUESTED -> {
                draft = requested
                original = requested
                editorSave.reset()
                editorOpen = true
            }
            DraftOpenAction.RESUME_EXISTING -> editorOpen = true
            DraftOpenAction.CONFIRM_SWITCH -> pendingDraft = requested
        }
    }
    fun clearDraft() {
        editorOpen = false
        draft = EditorDraft("", "inbox")
        original = draft
        editorSave.reset()
    }
    LaunchedEffect(saveState.saved) { if (saveState.saved) clearDraft() }
    val plan = vm.todayPlan(state, planningNow)
    val ordered = (plan?.content()?.get("ordered_task_ids") as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    val win = plan?.content()?.get("daily_win_task_id")?.jsonPrimitive?.contentOrNull
    val all = stableEntities(state.items.values.filter { !it.deleted })
    val tasks = all.filter { it.kind == "task" && it.payload?.get("lifecycle")?.jsonPrimitive?.contentOrNull != "archived" }
    val todayTasks = ordered.mapNotNull { id -> tasks.find { it.id == id } }
    val completedTasks = (if (tab == 0) todayTasks else tasks).filter { it.completed() }
    val conflictCount = state.items.values.count { it.conflict != null }
    val capture: () -> Unit = { openEditor(kind = when (tab) { 0 -> "task"; 2 -> "idea"; else -> "inbox" }, today = tab == 0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val icons = listOf(Icons.Default.DateRange, Icons.AutoMirrored.Filled.List, Icons.Default.Create)
                listOf("今天", "整理", "想法").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        modifier = Modifier.testTag(listOf("tab_today", "tab_organize", "tab_ideas")[index]),
                        icon = { Icon(icons[index], contentDescription = null) }, label = { Text(label) })
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = capture, modifier = Modifier.testTag("add_item"),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(when (tab) { 0 -> "添加任务"; 2 -> "记个想法"; else -> "随手记" }) },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("main_list"),
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 104.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("PARE", letterSpacing = 4.sp, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { account = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("account_open")) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp)); Text(if (session == null) "账号" else "同步")
                    }
                }
                Text(listOf("给今天留一点余地", "把事情慢慢理清", "让想法长出来")[tab], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(status, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (draft.entityId.isNotEmpty() && !editorOpen) item {
                OutlinedButton(onClick = { editorOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("resume_draft")) { Text("继续编辑草稿") }
            }
            if (conflictCount > 0) item {
                Card(onClick = { account = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("有 $conflictCount 个版本需要你决定", fontWeight = FontWeight.SemiBold)
                        Text("两个版本都已保留。点此查看差异。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            when (tab) {
                0 -> {
                    item {
                        Text(vm.planningDate(state, planningNow), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(vm.planningZone(state, planningNow), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp)); SectionLabel("今日重点", todayTasks.count { !it.completed() })
                    }
                    val active = todayTasks.filterNot { it.completed() }
                    if (active.isEmpty()) item {
                        EmptyNote(if (completedTasks.isEmpty()) "今天还很轻" else "今天的安排已完成", "从一件做得到的小事开始。") {
                            TextButton(onClick = capture, modifier = Modifier.heightIn(min = 48.dp).testTag("today_add_task")) {
                                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("添加今天的任务")
                            }
                        }
                    }
                    items(active, key = { it.key }) { item -> EntityCard(item, win == item.id, { openEditor(item) }, { vm.complete(item) }, { vm.toggleToday(item) }, { vm.setWin(item) }) }
                }
                1 -> {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SectionLabel("待整理", all.count { it.kind == "inbox" }) }
                            TextButton(onClick = { openEditor(kind = "task") }, modifier = Modifier.heightIn(min = 48.dp).testTag("new_task")) { Text("新建任务") }
                        }
                    }
                    val inbox = all.filter { it.kind == "inbox" }
                    if (inbox.isEmpty()) item { Text("随手记的内容会留在这里。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(inbox, key = { it.key }) { item ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(item.title(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { openEditor(item) }.padding(vertical = 10.dp).testTag("edit_${item.id}"))
                                TextButton(onClick = { vm.organize(item) }, modifier = Modifier.heightIn(min = 48.dp).testTag("organize_${item.id}")) {
                                    Text("整理为任务"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    quadrantLabels.forEachIndexed { q, label ->
                        val group = tasks.filter { (it.content()?.get("quadrant")?.jsonPrimitive?.intOrNull ?: 1) == q && !it.completed() }
                        item { Spacer(Modifier.height(4.dp)); SectionLabel(label, group.size) }
                        if (group.isEmpty()) item { Text("暂无任务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(group, key = { it.key }) { item -> EntityCard(item, win == item.id, { openEditor(item) }, { vm.complete(item) }, { vm.toggleToday(item) }, inToday = item.id in ordered) }
                    }
                }
                2 -> {
                    val ideas = all.filter { it.kind == "idea" }
                    if (ideas.isEmpty()) item { EmptyNote("从一粒种子开始", "记下还没有答案的问题，慢慢探索。") }
                    items(ideas, key = { it.key }) { item ->
                        Card(onClick = { openEditor(item) }, modifier = Modifier.testTag("idea_${item.id}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(ideaStageLabel(item.content()?.get("stage")?.jsonPrimitive?.contentOrNull), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                                Text(item.title(), style = MaterialTheme.typography.titleMedium)
                                item.content()?.get("notes")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            if (tab != 2 && completedTasks.isNotEmpty()) {
                item {
                    TextButton(onClick = { showCompleted = !showCompleted }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("completed_toggle")) {
                        Text(if (showCompleted) "收起已完成 · ${completedTasks.size}" else "查看已完成 · ${completedTasks.size}")
                    }
                }
                if (showCompleted) items(completedTasks, key = { "completed:${it.key}" }) { item ->
                    EntityCard(item, win == item.id, { openEditor(item) }, { vm.complete(item) }, { vm.toggleToday(item) }, inToday = item.id in ordered)
                }
            }
        }
    }
    if (editorOpen) TaskEditorDialog(draft, original, saveState.saving, saveState.error,
        onDraftChange = { draft = it; editorSave.clearError() },
        onSave = {
            if (editorSave.begin(draft)) {
                val submitted = draft
                val entity = state.items["${submitted.kind}:${submitted.entityId}"] ?: Entity(submitted.kind, submitted.entityId)
                vm.save(entity, submitted.kind, submitted.text.trim(), submitted.note.trim(), submitted.quadrant,
                    estimatedMinutes = submitted.estimate.toIntOrNull(), nextAction = submitted.nextAction.trim(), addToToday = submitted.addToToday, expected = original.editBaseline()) { result ->
                    editorSave.finish(result)
                }
            }
        }, onDismiss = { editorOpen = false }, onDiscard = ::clearDraft,
        latestVersion = if (saveState.staleContent) state.items["${draft.kind}:${draft.entityId}"]?.takeUnless { it.deleted }?.let(EditorDraft::from) else null,
        onReloadLatest = {
            state.items["${draft.kind}:${draft.entityId}"]?.takeUnless { it.deleted }?.let { latest ->
                draft = EditorDraft.from(latest); original = draft; editorSave.reset()
            }
        })
    if (pendingDraft.entityId.isNotEmpty()) AlertDialog(
        onDismissRequest = { pendingDraft = EditorDraft("", "inbox") },
        title = { Text("还有一份未保存的草稿") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(draft.text.ifBlank { "尚未填写内容" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("继续原草稿，或放弃它再打开刚才选择的内容。")
        } },
        confirmButton = { TextButton(onClick = { pendingDraft = EditorDraft("", "inbox"); editorOpen = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_switch_continue")) { Text("继续原草稿") } },
        dismissButton = { TextButton(onClick = {
            draft = pendingDraft; original = pendingDraft
            pendingDraft = EditorDraft("", "inbox")
            editorSave.reset(); editorOpen = true
        }, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_switch_discard")) { Text("放弃草稿并打开") } }
    )
    if (account) AccountDialog(vm, state, status, session, busy, previewReady) { account = false }
}

@Composable private fun SectionLabel(title: String, count: Int) {
    Text("$title · $count", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
}

@Composable private fun EmptyNote(title: String, detail: String, action: (@Composable () -> Unit)? = null) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun EntityCard(item: Entity, win: Boolean, onEdit: () -> Unit, onComplete: () -> Unit, onToday: () -> Unit, onWin: (() -> Unit)? = null, inToday: Boolean = true) {
    Card(modifier = Modifier.fillMaxWidth().testTag("task_${item.id}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(item.completed(), onCheckedChange = { onComplete() }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("complete_${item.id}")
                    .semantics { contentDescription = (if (item.completed()) "标为未完成：" else "完成：") + item.title() })
                Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = onEdit).padding(top = 10.dp, bottom = 6.dp, end = 4.dp).testTag("edit_${item.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (win) Text("今天最值得的一件事", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
                    Text(item.title(), style = MaterialTheme.typography.titleMedium, color = if (item.completed()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    item.content()?.get("nextAction")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { Text("下一步 · $it", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    item.content()?.get("note")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    item.content()?.get("estimatedMinutes")?.jsonPrimitive?.intOrNull?.let { Text("预计 $it 分钟", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onToday, modifier = Modifier.heightIn(min = 48.dp).testTag("today_${item.id}")) { Text(if (inToday) "移出今天" else "加入今天") }
                if (onWin != null) TextButton(onClick = onWin, modifier = Modifier.heightIn(min = 48.dp).testTag("win_${item.id}")) {
                    Icon(Icons.Default.Star, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (win) "取消重点" else "设为重点")
                }
            }
        }
    }
}
