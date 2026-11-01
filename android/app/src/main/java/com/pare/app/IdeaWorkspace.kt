package com.pare.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.*

/** Resolves the source and linked tasks from current state on every update. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun IdeaWorkspaceDialog(
    ideaId: String, vm: PareViewModel, onDismiss: () -> Unit,
    onEditIdea: (Entity) -> Unit, onOpenTask: (Entity) -> Unit, onManage: (() -> Unit)? = null, onAI: (() -> Unit)? = null
) {
    val state by vm.state.collectAsState()
    val now by vm.planningInstant.collectAsState()
    var visibleActions by rememberSaveable(ideaId) { mutableIntStateOf(20) }
    var visibleEntries by rememberSaveable(ideaId) { mutableIntStateOf(3) }
    val drafts: IdeaWorkspaceDraftViewModel = viewModel(key = "idea_workspace_${state.owner}:$ideaId")
    val idea = state.items["idea:$ideaId"]?.takeUnless { it.deleted }
    val canGrow = ideaCanGrow(idea)
    val goBack = { if (drafts.form.isNotEmpty()) drafts.back() else if (!drafts.saving) onDismiss() }
    Dialog(onDismissRequest = goBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = goBack)
        Surface(Modifier.fillMaxSize().testTag("idea_workspace"), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = { ParePageHeader(
                    title = when (drafts.form) {
                        "context" -> "阶段与探索问题"
                        "entry" -> "添加探索记录"
                        "action" -> "拆出一个行动"
                        else -> "想法工作台"
                    }, onBack = goBack, enabled = !drafts.saving,
                    modifier = Modifier.statusBarsPadding(),
                    backModifier = Modifier.testTag(if (drafts.form.isEmpty()) "idea_workspace_close" else "idea_workspace_back"),
                    actions = { if (drafts.form.isEmpty() && idea != null) IconButton(
                        onClick = { onEditIdea(idea) }, modifier = Modifier.size(48.dp).testTag("idea_edit")) {
                        PareIcon(PareIcons.Edit, "编辑标题与背景", Modifier.size(20.dp))
                    } }
                ) },
                containerColor = MaterialTheme.colorScheme.surface
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (idea == null) {
                        Text("这个想法已删除或暂不可用", style = MaterialTheme.typography.titleMedium)
                        Text("已有草稿仍保留，可以返回查看其他想法。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (drafts.form.isNotEmpty()) {
                        if (!canGrow && idea != null && drafts.form != "context") Text("这个想法已归档。恢复阶段后可继续探索，草稿仍保留。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (drafts.form) {
                            "context" -> drafts.context?.let { draft ->
                                Text("选择当前阶段，留一个值得探索的问题。", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("seed", "exploring", "building", "parked", "archived").forEach { stage ->
                                        FilterChip(colors = pareChoiceColors(), selected = draft.stage == stage,
                                            onClick = { drafts.context = draft.copy(stage = stage) },
                                            enabled = !drafts.saving && idea != null,
                                            label = { Text(ideaStageLabel(stage)) },
                                            leadingIcon = { PareIcon(ideaStageIcon(stage), null, Modifier.size(20.dp)) },
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("idea_stage_$stage"))
                                    }
                                }
                                PareFormField(value = draft.question, onValueChange = { drafts.context = draft.copy(question = it) },
                                    label = "正在探索的问题", minLines = 3,
                                    enabled = !drafts.saving && idea != null,
                                    modifier = Modifier.fillMaxWidth().testTag("idea_question"))
                                WorkspaceSave(drafts, "保存", "idea_context_save", idea != null) {
                                    if (drafts.begin()) vm.saveIdeaContext(ideaId, draft.stage, draft.question, draft.baseline, drafts::finish)
                                }
                                if (drafts.error != null && idea != null) TextButton(onClick = {
                                    drafts.context = IdeaContextDraft(
                                        idea.content()?.get("stage")?.jsonPrimitive?.contentOrNull ?: "seed",
                                        idea.content()?.get("nextQuestion")?.jsonPrimitive?.contentOrNull.orEmpty(), IdeaContextBaseline.from(idea))
                                    drafts.open("context")
                                }, enabled = !drafts.saving, modifier = Modifier.heightIn(min = 48.dp).testTag("idea_context_reload")) {
                                    Text("放弃这份草稿，读取最新阶段与问题")
                                }
                            }
                            "entry" -> drafts.entry?.let { draft ->
                                Text("留住探索过程中的记录、发现和决定。", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("note", "finding", "decision").forEach { kind ->
                                        FilterChip(colors = pareChoiceColors(), selected = draft.kind == kind,
                                            onClick = { drafts.entry = draft.copy(kind = kind) },
                                            enabled = !drafts.saving && canGrow,
                                            label = { Text(ideaEntryKindLabel(kind)) },
                                            leadingIcon = { PareIcon(ideaEntryIcon(kind), null, Modifier.size(20.dp)) },
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("idea_entry_kind_$kind"))
                                    }
                                }
                                PareFormField(value = draft.text, onValueChange = { drafts.entry = draft.copy(text = it) },
                                    label = "写下这次探索", minLines = 5, enabled = !drafts.saving && canGrow,
                                    modifier = Modifier.fillMaxWidth().testTag("idea_entry_text"))
                                WorkspaceSave(drafts, "保存记录", "idea_entry_save", canGrow) {
                                    if (drafts.begin(if (draft.text.isBlank()) "先写下一点探索内容" else null))
                                        vm.appendIdeaEntry(ideaId, draft.id, draft.text, draft.kind, drafts::finish)
                                }
                            }
                            "action" -> drafts.action?.let { draft ->
                                Text("先拆出能开始的一步，保留与想法的联系。",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                PareFormField(value = draft.title, onValueChange = { drafts.action = draft.copy(title = it) },
                                    label = "具体行动", enabled = !drafts.saving && canGrow,
                                    modifier = Modifier.fillMaxWidth().testTag("idea_action_title"))
                                PareFormField(value = draft.next, onValueChange = { drafts.action = draft.copy(next = it) },
                                    label = "最小下一步（可选）", minLines = 2, enabled = !drafts.saving && canGrow,
                                    modifier = Modifier.fillMaxWidth().testTag("idea_action_next"))
                                PareFormField(value = draft.done, onValueChange = { drafts.action = draft.copy(done = it) },
                                    label = "完成标准（可选）", minLines = 2, enabled = !drafts.saving && canGrow,
                                    modifier = Modifier.fillMaxWidth().testTag("idea_action_dod"))
                                Text("分清轻重", style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    quadrantLabels.forEachIndexed { index, label ->
                                        FilterChip(colors = pareChoiceColors(), selected = draft.quadrant == index,
                                            onClick = { drafts.action = draft.copy(quadrant = index) },
                                            enabled = !drafts.saving && canGrow, label = { Text(label) },
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("idea_action_quadrant_$index"))
                                    }
                                }
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("加入今天", Modifier.weight(1f))
                                    Switch(checked = draft.today, onCheckedChange = { drafts.action = draft.copy(today = it) },
                                        enabled = !drafts.saving && canGrow, modifier = Modifier.testTag("idea_action_today"))
                                }
                                if (draft.today) {
                                    Text("安排到 ${draft.baseline.planId.substringBefore('@')} · ${draft.baseline.planId.substringAfter('@')}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    PareFormField(value = draft.minutes, onValueChange = { drafts.action = draft.copy(minutes = it) },
                                        label = "今天自己投入（分钟）", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        enabled = !drafts.saving && canGrow,
                                        modifier = Modifier.fillMaxWidth().testTag("idea_action_minutes"))
                                }
                                WorkspaceSave(drafts, "创建行动", "idea_action_create", canGrow) {
                                    if (drafts.begin(draft.validationError())) vm.createIdeaAction(
                                        ideaId, draft.id, draft.title, draft.next, draft.done, draft.quadrant,
                                        if (draft.today) TodayAllocationDraft(draft.baseline.planId, "personal", draft.minutes.toInt(),
                                            draft.allocationId, draft.baseline) else null, drafts::finish)
                                }
                                if (drafts.error != null || draft.baseline.planId != ContentActions.planId(state, now)) {
                                    TextButton(onClick = { drafts.requestActionReload(state, now) },
                                        enabled = !drafts.saving && canGrow,
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("idea_action_reload")) {
                                        Text("保留行动文字，重新读取今天安排")
                                    }
                                }
                            }
                        }
                    } else if (idea != null) {
                        val content = idea.content()
                        val stage = content?.get("stage")?.jsonPrimitive?.contentOrNull
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(idea.title(), style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.testTag("idea_workspace_title").semantics { heading() })
                            Surface(shape = MaterialTheme.shapes.small,
                                color = if (canGrow) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (canGrow) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PareIcon(ideaStageIcon(stage), null, Modifier.size(20.dp))
                                    Text(ideaStageLabel(stage), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(
                                role = Role.Button, onClickLabel = "调整阶段与问题"
                            ) {
                                if (drafts.context == null) drafts.context = IdeaContextDraft(
                                    content?.get("stage")?.jsonPrimitive?.contentOrNull ?: "seed",
                                    content?.get("nextQuestion")?.jsonPrimitive?.contentOrNull.orEmpty(), IdeaContextBaseline.from(idea))
                                drafts.open("context")
                            }.padding(16.dp).testTag("idea_context"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PareIcon(PareIcons.Finding, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text("正在探索的问题", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    PareIcon(PareIcons.Edit, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(content?.get("nextQuestion")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                    ?: "先留一个问题，给探索一个方向。", style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        val entries = ideaTimeline(idea)
                        WorkspaceSection(PareIcons.Note, "探索时间线", entries.size)
                        if (entries.isEmpty()) WorkspaceEmpty(PareIcons.Seed, "还没有探索记录。一点发现、一次决定，都可以留在这里。")
                        if (entries.isNotEmpty()) Column {
                            entries.take(visibleEntries).forEachIndexed { index, entry ->
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.width(32.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Surface(Modifier.size(32.dp), shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = if (entry.kind == "note") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary) {
                                            Box(contentAlignment = Alignment.Center) { PareIcon(ideaEntryIcon(entry.kind), null, Modifier.size(20.dp)) }
                                        }
                                        if (index < minOf(visibleEntries, entries.size) - 1) {
                                            Spacer(Modifier.height(4.dp))
                                            Box(Modifier.width(1.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
                                            Spacer(Modifier.height(4.dp))
                                        }
                                    }
                                    Column(Modifier.weight(1f).padding(top = 4.dp,
                                        bottom = if (index < minOf(visibleEntries, entries.size) - 1) 16.dp else 0.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(listOfNotNull(ideaEntryKindLabel(entry.kind), ideaDateLabel(entry.createdAt)).joinToString(" · "),
                                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        SelectionContainer { Text(entry.text, style = MaterialTheme.typography.bodyLarge) }
                                    }
                                }
                            }
                        }
                        if (visibleEntries < entries.size) TextButton(onClick = { visibleEntries += 20 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看更多记录（还有 ${entries.size - visibleEntries} 条）") }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = { if (drafts.entry == null) drafts.entry = IdeaEntryDraft(); drafts.open("entry") },
                                enabled = canGrow, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp).testTag("idea_add_entry")) {
                                PareIcon(PareIcons.Add, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (drafts.entry != null) "继续记录" else "记录探索")
                            }
                            onAI?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp).testTag("idea_ai")) {
                                PareIcon(PareIcons.Sparkle, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("和 AI 一起探索")
                            } }
                        }
                        if (drafts.entry != null || drafts.action != null) Text("有未保存的草稿，可以继续填写。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!canGrow) Text("想法已归档，探索记录与关联行动仍然保留。", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val linked = IdeaLinkedTasks.project(state, idea)
                        WorkspaceSection(PareIcons.Link, "关联行动", linked.size)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = {
                                if (drafts.action == null) {
                                    val id = uid()
                                    drafts.action = IdeaActionDraft(id = id, baseline = DayAllocationBaseline.from(state, id, now))
                                }
                                drafts.open("action")
                            }, enabled = canGrow, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp).testTag("idea_create_action")) {
                                PareIcon(PareIcons.Next, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (drafts.action != null) "继续行动" else "拆出行动")
                            }
                            onManage?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp).testTag("idea_manage")) {
                                Text("关联行动与回看")
                            } }
                        }
                        if (linked.isEmpty()) WorkspaceEmpty(PareIcons.Link, "行动准备好时，再从这个想法中拆出第一步。")
                        linked.take(visibleActions).forEachIndexed { index, task ->
                            val entity = state.items["task:${task.id}"]?.takeUnless { it.deleted }
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .clickable(enabled = entity != null, role = Role.Button) { entity?.let(onOpenTask) }
                                .padding(vertical = 8.dp).testTag("linked_task_${task.id}"),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PareIcon(when {
                                    task.missing -> PareIcons.Unlink
                                    task.archived -> PareIcons.Archive
                                    task.completed -> PareIcons.Check
                                    else -> PareIcons.Note
                                }, null, Modifier.size(20.dp),
                                    tint = if (task.completed && !task.archived && !task.missing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(task.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(when { task.missing -> "行动已删除或暂不可用"; task.archived -> "已归档"; task.completed -> "已完成"; else -> "待推进" },
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (entity != null) PareIcon(PareIcons.Next, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (index < minOf(visibleActions, linked.size) - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        if (visibleActions < linked.size) TextButton(onClick = { visibleActions += 20 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看更多行动（还有 ${linked.size - visibleActions} 项）") }

                        content?.get("notes")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { notes ->
                            var backgroundExpanded by rememberSaveable(ideaId) { mutableStateOf(false) }
                            TextButton(onClick = { backgroundExpanded = !backgroundExpanded },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("idea_background_toggle")) {
                                PareIcon(PareIcons.Note, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (backgroundExpanded) "收起想法背景" else "查看想法背景")
                                Spacer(Modifier.width(8.dp))
                                PareIcon(if (backgroundExpanded) PareIcons.Up else PareIcons.Down, null, Modifier.size(20.dp))
                            }
                            if (backgroundExpanded) SelectionContainer {
                                Text(notes, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("idea_background"))
                            }
                        }

                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    drafts.pendingActionReload?.let { refreshed ->
        AlertDialog(onDismissRequest = drafts::cancelActionReload,
            title = { Text("重新读取今天安排？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("行动文字、下一步、完成标准和当天投入会保留。确认后，请再点创建行动保存。")
                    drafts.action?.baseline?.planId?.let { Text("原安排：${it.replace("@", " · ")}") }
                    Text("重新安排：${refreshed.baseline.planId.replace("@", " · ")}")
                }
            },
            confirmButton = { TextButton(onClick = { drafts.confirmActionReload(state) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("idea_action_reload_confirm")) { Text("确认重新读取") } },
            dismissButton = { TextButton(onClick = drafts::cancelActionReload,
                modifier = Modifier.heightIn(min = 48.dp).testTag("idea_action_reload_cancel")) { Text("保留原草稿") } })
    }
}

@Composable
private fun WorkspaceSection(icon: Int, title: String, count: Int) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PareIcon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkspaceEmpty(icon: Int, message: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        PareIcon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun ideaEntryIcon(kind: String): Int = when (kind) {
    "finding" -> PareIcons.Finding
    "decision" -> PareIcons.Decision
    else -> PareIcons.Note
}

private fun ideaStageIcon(stage: String?): Int = when (stage) {
    "exploring" -> PareIcons.Finding
    "building" -> PareIcons.Next
    "parked" -> PareIcons.Clock
    "archived" -> PareIcons.Archive
    else -> PareIcons.Seed
}

@Composable
private fun WorkspaceSave(drafts: IdeaWorkspaceDraftViewModel, label: String, tag: String, available: Boolean, onClick: () -> Unit) {
    drafts.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("idea_save_error").semantics { liveRegion = LiveRegionMode.Polite }) }
    Button(onClick = onClick, enabled = !drafts.saving && available, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        if (drafts.saving) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
        }
        Text(if (drafts.saving) "正在保存…" else label)
    }
    Text("返回工作台会保留未保存的草稿。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
