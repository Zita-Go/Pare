package com.pare.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.*

class MainActivity : ComponentActivity() {
    // The activity owns lifecycle delivery so the first resume cannot precede Compose's VM hookup.
    private val model: PareViewModel by lazy { ViewModelProvider(this)[PareViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
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
        bottomBar = { PareDock(tab, onTab = { tab = it }, onAdd = capture) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("main_list"),
            contentPadding = PaddingValues(start = 22.dp, top = 12.dp, end = 22.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PareHeader(tab, status, busy, session != null, onAccount = { account = true }) }
            if (draft.entityId.isNotEmpty() && !editorOpen) item {
                OutlinedButton(onClick = { editorOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("resume_draft")) { Text("继续编辑草稿") }
            }
            if (conflictCount > 0) item {
                Card(onClick = { account = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("有 $conflictCount 个版本需要你决定", style = MaterialTheme.typography.titleSmall)
                        Text("两个版本都已保留。点此查看差异。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            when (tab) {
                0 -> {
                    item {
                        Column(Modifier.padding(top = 5.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(planningDayLabel(vm.planningDate(state, planningNow)), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary)
                            planningZoneLabel(vm.planningZone(state, planningNow), planningNow)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (todayTasks.isNotEmpty()) item { TodayOverview(todayTasks) }
                    val active = todayTasks.filterNot { it.completed() }
                    if (active.isNotEmpty()) item { SectionLabel("今日清单", active.size) }
                    if (active.isEmpty()) item {
                        EmptyNote(if (completedTasks.isEmpty()) "今天还很轻" else "给自己一点闲暇",
                            if (completedTasks.isEmpty()) "留一点空间，从一件做得到的小事开始。" else "安排都完成了，接下来的时间留给自己。") {
                            TextButton(onClick = capture, modifier = Modifier.heightIn(min = 48.dp).testTag("today_add_task")) {
                                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("添加今天的任务")
                            }
                        }
                    }
                    items(active, key = { it.key }) { item ->
                        EntityCard(item, win == item.id, { openEditor(item) }, { vm.complete(item) }, { vm.toggleToday(item) }, { vm.setWin(item) })
                    }
                }
                1 -> {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SectionLabel("待整理", all.count { it.kind == "inbox" }) }
                            Spacer(Modifier.width(12.dp))
                            TextButton(onClick = { openEditor(kind = "task") }, modifier = Modifier.heightIn(min = 48.dp).testTag("new_task")) {
                                Icon(Icons.Default.Add, null, Modifier.size(15.dp)); Spacer(Modifier.width(5.dp))
                                Text("新建任务", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    val inbox = all.filter { it.kind == "inbox" }
                    if (inbox.isEmpty()) item {
                        Text("随手记下的事，会在这里等你整理。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    items(inbox, key = { it.key }) { item -> InboxCard(item, { openEditor(item) }, { vm.organize(item) }) }
                    quadrantLabels.forEachIndexed { q, label ->
                        val group = tasks.filter { (it.content()?.get("quadrant")?.jsonPrimitive?.intOrNull ?: 1) == q && !it.completed() }
                        item {
                            Column(Modifier.padding(top = 6.dp)) {
                                SectionLabel(label, group.size, pareQuadrantColor(q))
                                if (group.isEmpty()) HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                        items(group, key = { it.key }) { item ->
                            EntityCard(item, win == item.id, { openEditor(item) }, { vm.complete(item) }, { vm.toggleToday(item) }, inToday = item.id in ordered)
                        }
                    }
                }
                2 -> {
                    val ideas = all.filter { it.kind == "idea" }
                    item { SectionLabel("想法花园", ideas.size, MaterialTheme.colorScheme.secondary) }
                    if (ideas.isEmpty()) item { EmptyNote("从一粒种子开始", "记下还没有答案的问题，慢慢探索。", idea = true) }
                    items(ideas, key = { it.key }) { item -> IdeaCard(item, onEdit = { openEditor(item) }) }
                }
            }
            if (tab != 2 && completedTasks.isNotEmpty()) {
                item {
                    TextButton(onClick = { showCompleted = !showCompleted }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("completed_toggle")) {
                        Text(if (showCompleted) "收起已完成 · ${completedTasks.size}" else "查看已完成 · ${completedTasks.size}",
                            style = MaterialTheme.typography.labelLarge)
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
