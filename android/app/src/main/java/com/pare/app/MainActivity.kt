package com.pare.app

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
    var workspaceId by rememberSaveable { mutableStateOf("") }
    var allocationTaskId by rememberSaveable { mutableStateOf("") }
    var supportTaskId by rememberSaveable { mutableStateOf("") }
    var originTaskId by rememberSaveable { mutableStateOf("") }
    var organizeId by rememberSaveable { mutableStateOf("") }
    val organizeSave: OrganizeSaveModel = viewModel()
    LaunchedEffect(organizeSave.saved) { if (organizeSave.saved) { organizeId = ""; organizeSave.reset() } }
    var account by rememberSaveable { mutableStateOf(false) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    val editorStateHolder = rememberSaveableStateHolder()
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
        editorStateHolder.removeState(draft.entityId)
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
    fun openOrigin(taskId: String) {
        val origins = relatedIdeas(state, taskId)
        if (origins.size == 1) workspaceId = origins.first().id else if (origins.isNotEmpty()) originTaskId = taskId
    }
    val capture: () -> Unit = { openEditor(kind = when (tab) { 0 -> "task"; 2 -> "idea"; else -> "inbox" }, today = tab == 0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Column(Modifier.statusBarsPadding()) { PareHeader(tab, status, busy, session != null, onAccount = { account = true }) } },
        bottomBar = { PareNavigation(tab, onTab = { tab = it }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = capture, modifier = Modifier.heightIn(min = 56.dp).testTag("add_item"),
                icon = { Icon(Icons.Default.Add, null) }, text = { Text(listOf("添加任务", "随手记", "记录想法")[tab]) })
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("main_list"),
            contentPadding = PaddingValues(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 88.dp)) {
            if (draft.entityId.isNotEmpty() && !editorOpen) item {
                OutlinedButton(onClick = { editorOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("resume_draft")) { Text("继续编辑草稿") }
            }
            if (listOf("失败", "超时", "失效", "拒绝", "已停止", "等待联网", "等待重试", "未完成").any { it in status }) item {
                TextButton(onClick = { account = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sync_attention")) {
                    Text(status, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (conflictCount > 0) item {
                Card(onClick = { account = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("有 $conflictCount 个版本需要你决定", style = MaterialTheme.typography.titleSmall)
                        Text("两个版本都已保留。点此查看差异。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            when (tab) {
                0 -> {
                    val active = todayTasks.filterNot { it.completed() }
                    item {
                        val load = TodayLoad.calculate(state, planningNow)
                        val summary = listOfNotNull(planningDayLabel(vm.planningDate(state, planningNow)),
                            if (load.personalMinutes > 0) "自己投入 ${load.personalMinutes} 分钟" else null,
                            if (load.checkMinutes > 0) "检查 ${load.checkMinutes} 分钟" else null,
                            if (load.unplannedCount > 0) "${load.unplannedCount} 件未安排投入" else null).joinToString(" · ")
                        Text(summary, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp).testTag("today_summary"))
                        planningZoneLabel(vm.planningZone(state, planningNow), planningNow)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (active.isEmpty()) item {
                        EmptyNote(if (completedTasks.isEmpty()) "今天还没有任务" else "今天的任务已完成",
                            if (completedTasks.isEmpty()) "添加一件今天要做的事。" else "你可以继续添加任务，或回顾已完成的内容。") {
                            TextButton(onClick = capture, modifier = Modifier.heightIn(min = 48.dp).testTag("today_add_task")) {
                                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("添加今天的任务")
                            }
                        }
                    }
                    items(active, key = { it.key }) { item ->
                        EntityCard(item, win == item.id, { openEditor(item) }, { vm.complete(item) }, { vm.toggleToday(item) }, { vm.setWin(item) },
                            planLabel = todayPlanLabel(state, item.id, planningNow), onPlan = { allocationTaskId = item.id },
                            onSupport = { supportTaskId = item.id }, onOrigin = if (relatedIdeas(state,item.id).isEmpty()) null else ({ openOrigin(item.id) }))
                    }
                }
                1 -> {
                    val inbox = all.filter { it.kind == "inbox" }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                if (inbox.isEmpty()) Text("待整理 · 暂无内容", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else SectionLabel("待整理", inbox.size)
                            }
                            TextButton(onClick = { openEditor(kind = "task") }, modifier = Modifier.heightIn(min = 48.dp).testTag("new_task")) {
                                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                                Text("新建任务", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    items(inbox, key = { it.key }) { item -> InboxCard(item, { openEditor(item) }, { organizeSave.reset(); organizeId = item.id }) }
                    quadrantLabels.forEachIndexed { q, label ->
                        val group = tasks.filter { (it.content()?.get("quadrant")?.jsonPrimitive?.intOrNull ?: 1) == q && !it.completed() }
                        if (group.isNotEmpty()) item { SectionLabel(label, group.size, pareQuadrantColor(q)) }
                        items(group, key = { it.key }) { item ->
                            EntityCard(item, win == item.id, { openEditor(item) }, { vm.complete(item) }, { vm.toggleToday(item) }, { vm.setWin(item) }, inToday = item.id in ordered,
                                planLabel = if (item.id in ordered) todayPlanLabel(state,item.id,planningNow) else null,
                                onPlan = { allocationTaskId = item.id }, onSupport = { supportTaskId = item.id },
                                onOrigin = if (relatedIdeas(state,item.id).isEmpty()) null else ({ openOrigin(item.id) }))
                        }
                    }
                }
                2 -> {
                    val ideas = all.filter { it.kind == "idea" }

                    if (ideas.isEmpty()) item { EmptyNote("还没有想法", "记录一个想法，之后可以继续补充。") }
                    items(ideas, key = { it.key }) { item -> IdeaCard(item, onEdit = { workspaceId = item.id }) }
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
                    EntityCard(item, win == item.id, { openEditor(item) }, { vm.complete(item) }, { vm.toggleToday(item) }, { vm.setWin(item) }, inToday = item.id in ordered,
                                planLabel = if (item.id in ordered) todayPlanLabel(state,item.id,planningNow) else null,
                                onPlan = { allocationTaskId = item.id }, onSupport = { supportTaskId = item.id },
                                onOrigin = if (relatedIdeas(state,item.id).isEmpty()) null else ({ openOrigin(item.id) }))
                }
            }
        }
    }
    if (workspaceId.isNotBlank()) IdeaWorkspaceDialog(workspaceId, vm, onDismiss = { workspaceId = "" },
        onEditIdea = { openEditor(it) }, onOpenTask = { openEditor(it) })
    if (allocationTaskId.isNotBlank()) TodayAllocationDialog(allocationTaskId, vm) { allocationTaskId = "" }
    if (supportTaskId.isNotBlank()) TaskSupportDialog(supportTaskId, vm) { supportTaskId = "" }
    if (originTaskId.isNotBlank()) AlertDialog(onDismissRequest = { originTaskId = "" }, title = { Text("关联想法") },
        text = { Column { relatedIdeas(state, originTaskId).forEach { idea ->
            TextButton(onClick = { workspaceId = idea.id; originTaskId = "" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(idea.title()) }
        } } }, confirmButton = { TextButton(onClick = { originTaskId = "" }) { Text("关闭") } })
    if (organizeId.isNotBlank()) {
        val inbox = state.items["inbox:$organizeId"]
        OrganizeDialog(inbox, organizeSave.saving, organizeSave.error,
            onConfirm = { quadrant, today ->
                if (inbox != null && organizeSave.begin()) vm.organize(inbox, quadrant, today, organizeSave::finish)
            }, onDismiss = { if (!organizeSave.saving) { organizeId = ""; organizeSave.reset() } })
    }
    if (editorOpen) editorStateHolder.SaveableStateProvider(draft.entityId) {
        TaskEditorDialog(draft, original, saveState.saving, saveState.error,
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
    }
    if (pendingDraft.entityId.isNotEmpty()) AlertDialog(
        onDismissRequest = { pendingDraft = EditorDraft("", "inbox") },
        title = { Text("还有一份未保存的草稿") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(draft.text.ifBlank { "尚未填写内容" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("继续原草稿，或放弃它再打开刚才选择的内容。")
        } },
        confirmButton = { TextButton(onClick = { pendingDraft = EditorDraft("", "inbox"); editorOpen = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_switch_continue")) { Text("继续原草稿") } },
        dismissButton = { TextButton(onClick = {
            editorStateHolder.removeState(draft.entityId)
            draft = pendingDraft; original = pendingDraft
            pendingDraft = EditorDraft("", "inbox")
            editorSave.reset(); editorOpen = true
        }, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_switch_discard")) { Text("放弃草稿并打开") } }
    )
    if (account) AccountDialog(vm, state, status, session, busy, previewReady) { account = false }
}
