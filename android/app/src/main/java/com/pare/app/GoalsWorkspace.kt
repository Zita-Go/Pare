package com.pare.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

class GoalsWorkspaceModel : ViewModel() {
    var draft: GoalDraft? by mutableStateOf(null); private set
    var baseline: GoalBaseline? by mutableStateOf(null); private set
    var original: Entity? by mutableStateOf(null); private set
    var saving by mutableStateOf(false); private set
    var error: String? by mutableStateOf(null); private set
    var message: String? by mutableStateOf(null); private set
    var messageEvent by mutableLongStateOf(0L); private set
    fun consumeMessage(event: Long) { if(messageEvent==event) message=null }
    var confirmDelete by mutableStateOf(false)
    var confirmDiscard by mutableStateOf(false)
    private var initialDraft: GoalDraft? = null
    fun isDirty() = draft!=null && draft!=initialDraft
    fun open(entity: Entity?) {
        if (saving || draft != null) return
        original=entity; baseline=GoalBaseline.from(entity); draft=entity?.let(GoalDraft::from) ?: GoalDraft()
        initialDraft=draft
        error=null; message=null
    }
    fun change(value: GoalDraft) { if (!saving) { draft=value; error=null } }
    fun begin(deleting: Boolean = false): Boolean {
        if (saving) return false
        if (!deleting) draft?.validationError()?.let { error=it; return false }
        error=null; saving=true; return true
    }
    fun finish(result: Result<Unit>) {
        saving=false
        if (result.isSuccess) { clear(); messageEvent++; message="已保存" }
        else error=result.exceptionOrNull()?.message ?: "保存失败，草稿已保留，请重试。"
    }
    fun clear() {
        if (saving) return
        draft=null; baseline=null; original=null; error=null; confirmDelete=false; confirmDiscard=false
    }
}

@Composable
fun GoalsWorkspace(state: SyncState, onEdit: ((SyncState)->SyncState,(Result<Unit>)->Unit)->Unit,
    onDismiss: ()->Unit, onOpenTask: ((Entity)->Unit)? = null, initialGoalId:String?=null, onPlanGoal:((String)->Unit)?=null) {
    val model: GoalsWorkspaceModel = viewModel(key="goals-workspace:${state.owner}")
    LaunchedEffect(initialGoalId) {initialGoalId?.let {state.items["goal:$it"]?.takeUnless {e->e.deleted}?.let(model::open)}}
    val draft=model.draft
    val close = { if (!model.saving) { if (draft != null) {if(model.isDirty()) model.confirmDiscard=true else model.clear()} else onDismiss() } }
    GoalsPreferencesFrame(if (draft == null) "长期目标" else if (model.baseline?.isNew == true) "新建长期目标" else "编辑长期目标",
        "goals_workspace",model.saving,model.error,close,
        editing=draft!=null,message=model.message,messageEvent=model.messageEvent,onMessageConsumed=model::consumeMessage,messageTag="goal_saved",
        actions={
            if (draft != null) Button(shape=MaterialTheme.shapes.medium,onClick={
                val expected=model.baseline ?: return@Button
                if (model.begin()) onEdit({ GoalsActions.save(it,draft,expected,Instant.now()) },model::finish)
            },enabled=!model.saving && draft.validationError()==null,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("goal_save")) {
                Text(if(model.saving) "正在保存…" else "保存目标")
            }
        }
    ) {
        if (draft == null) {
            Text("让今天的行动连接到更长远的方向。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(shape=MaterialTheme.shapes.medium,onClick={ model.open(null) },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("goal_add")) { Text("新建目标") }
            val goals=state.items.values.filter { it.kind=="goal" && !it.deleted && it.payload!=null }.sortedWith(
                compareBy<Entity> { when(it.content()?.stringValue("status")) { "active"->0; "paused"->1; else->2 } }
                    .thenByDescending { it.content()?.stringValue("updatedAt") }.thenBy { it.id })
            if (goals.isEmpty()) Text("还没有长期目标。写下一个值得慢慢推进的方向，再关联一个能开始的行动。",style=MaterialTheme.typography.bodyLarge)
            goals.forEach { goal ->
                val c=goal.content()!!
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier=Modifier.fillMaxWidth().clickable { model.open(goal) }.testTag("goal_${goal.id}")) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(c.stringValue("title").orEmpty(),style=MaterialTheme.typography.titleMedium)
                        Text(goalStatusLabel(c.stringValue("status")),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        c.stringValue("motivation")?.takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodyMedium) }
                        val ids=(c["taskIDs"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                        val complete=ids.count { state.items["task:$it"]?.completed()==true }
                        Text("${ids.size} 个关联行动 · $complete 个已完成",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        c.stringValue("targetDate")?.let { Text("目标日期：${goalsDateText(it,PreferencesActions.planningZone(state))}",style=MaterialTheme.typography.bodySmall) }
                        onPlanGoal?.let {action -> TextButton(onClick={action(goal.id)},modifier=Modifier.heightIn(min=48.dp).testTag("goal_ai_${goal.id}")){Text("AI 帮我找下一步")}}
                        if(goal.conflict!=null || goal.conflictGroup.isNotEmpty()) Text("存在同步冲突，请先处理后保存",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            GoalEditorFields(state,draft,!model.saving,model::change,onOpenTask)
            if(model.original!=null) TextButton(onClick={ model.confirmDelete=true },enabled=!model.saving,
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("goal_delete")) { Text("删除目标",color=MaterialTheme.colorScheme.error) }
        }
    }
    if(model.confirmDelete) AlertDialog(onDismissRequest={ model.confirmDelete=false },title={Text("删除这个目标？")},
        text={Text("目标将从资料库删除，关联任务会保留。恢复内容会保存在资料库的本机回收站；删除已同步后，可恢复为副本。")},
        confirmButton={TextButton(onClick={
            val expected=model.original ?: return@TextButton
            model.confirmDelete=false
            if(model.begin(deleting=true)) onEdit({GoalsActions.delete(it,expected)},model::finish)
        },modifier=Modifier.testTag("goal_delete_confirm")) {Text("删除目标")}},
        dismissButton={TextButton(onClick={model.confirmDelete=false}) {Text("取消")}})
    if(model.confirmDiscard) AlertDialog(onDismissRequest={model.confirmDiscard=false},title={Text("放弃这份草稿？")},
        text={Text("尚未保存的修改会被丢弃。")},confirmButton={TextButton(onClick=model::clear) {Text("放弃草稿")}},
        dismissButton={TextButton(onClick={model.confirmDiscard=false}) {Text("继续编辑")}})
}

internal fun goalStatusLabel(value: String?) = when(value) { "paused"->"暂停"; "achieved"->"已达成"; else->"进行中" }

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun GoalEditorFields(state: SyncState,draft: GoalDraft,enabled: Boolean,onChange: (GoalDraft)->Unit,onOpenTask: ((Entity)->Unit)?) {
    PareFormField(draft.title,{onChange(draft.copy(title=it))},"想达成什么？ · 最多150字",enabled=enabled,
        modifier=Modifier.fillMaxWidth().testTag("goal_title"),minLines=2)
    PareFormField(draft.motivation,{onChange(draft.copy(motivation=it))},"为什么想做 / 怎样算达成 · 可选",enabled=enabled,
        modifier=Modifier.fillMaxWidth().testTag("goal_motivation"),minLines=2)
    Text("当前状态",style=MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("active","paused","achieved").forEach { value -> FilterChip(colors=pareChoiceColors(),selected=draft.status==value,
            onClick={onChange(draft.copy(status=value))},enabled=enabled,label={Text(goalStatusLabel(value))},modifier=Modifier.heightIn(min=48.dp).testTag("goal_status_$value")) }
    }
    GoalsPreferenceDateButton("目标日期",draft.targetDate,PreferencesActions.planningZone(state),false,enabled,"goal_target_date") { onChange(draft.copy(targetDate=it)) }
    HorizontalDivider()
    Text("关联行动",style=MaterialTheme.typography.titleMedium)
    Text("包括已完成和已归档任务，最多100条。新增下一步会与目标一起保存。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    var showTasks by rememberSaveable(draft.id) { mutableStateOf(false) }
    var query by rememberSaveable(draft.id) { mutableStateOf("") }
    val selected=draft.taskIds.toSet()
    selected.forEach { id ->
        val task=state.items["task:$id"]?.takeUnless { it.deleted }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task?.title() ?: "任务已删除或不可用",style=MaterialTheme.typography.bodyMedium)
                if(task!=null && onOpenTask!=null) TextButton(onClick={onOpenTask(task)},enabled=enabled,modifier=Modifier.heightIn(min=48.dp)) {Text("查看任务")}
            }
            TextButton(onClick={onChange(draft.copy(taskIds=draft.taskIds.filterNot {it==id}))},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("goal_unlink_$id")) {Text("解除关联")}
        }
    }
    TextButton(onClick={showTasks=!showTasks},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("goal_link_tasks")) {Text(if(showTasks) "收起任务选择" else "关联已有任务")}
    if(showTasks) {
        PareFormField(query,{query=it},"搜索任务",enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("goal_task_search"),singleLine=true)
        val tasks=stableEntities(state.items.values.filter {it.kind=="task" && !it.deleted && it.payload!=null && it.title().contains(query,true) && it.id !in selected})
        if(tasks.isEmpty()) Text("没有可关联的任务",style=MaterialTheme.typography.bodySmall)
        tasks.forEach { task ->
            val available=enabled && task.conflict==null && task.conflictGroup.isEmpty() && selected.size<100
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=available) {onChange(draft.copy(taskIds=draft.taskIds+task.id))}.testTag("goal_link_${task.id}"),verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=false,onCheckedChange={onChange(draft.copy(taskIds=draft.taskIds+task.id))},enabled=available)
                Column(Modifier.weight(1f)) {
                    Text(task.title(),style=MaterialTheme.typography.bodyMedium)
                    val status=if(task.payload?.stringValue("lifecycle")=="archived") "已归档" else if(task.completed()) "已完成" else "未完成"
                    Text(if(task.conflict!=null) "同步冲突" else status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    PareFormField(draft.newStep,{onChange(draft.copy(newStep=it))},"新增一个最小下一步 · 可选",enabled=enabled,
        modifier=Modifier.fillMaxWidth().testTag("goal_new_step"),minLines=2)
    draft.validationError()?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
}

@Composable internal fun GoalsPreferencesFrame(title: String,tag: String,busy: Boolean,error: String?,onDismiss: ()->Unit,
    editing: Boolean = true, message: String? = null, messageEvent: Long = 0L,
    onMessageConsumed: (Long)->Unit = {}, messageTag: String = "management_saved",
    actions: @Composable RowScope.()->Unit,content: @Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,dismissOnBackPress=!busy,dismissOnClickOutside=!busy)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag(tag),color=MaterialTheme.colorScheme.surface) {
            Column {
                ParePageHeader(title,onDismiss,backLabel=if(editing) "返回" else "关闭",enabled=!busy,backModifier=Modifier.testTag("${tag}_close"))
                HorizontalDivider()
                Box(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
                    ManagementSnackbar(message,messageEvent,onMessageConsumed,messageTag,Modifier.align(Alignment.BottomCenter))
                }
                if(error!=null) Text(error,modifier=Modifier.fillMaxWidth().heightIn(max=140.dp).verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp).testTag("${tag}_error"),
                    style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.error)
                if(editing) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),content=actions)
                }
            }
        }
    }
}

/** Consumed events prevent rotation/recomposition from replaying an old success. */
@Composable internal fun ManagementSnackbar(message: String?, event: Long, onConsumed: (Long)->Unit,
    tag: String, modifier: Modifier = Modifier) {
    val host = remember { SnackbarHostState() }
    val consume by rememberUpdatedState(onConsumed)
    LaunchedEffect(message,event) {
        if(message!=null) {
            host.showSnackbar(message,duration=SnackbarDuration.Short)
            consume(event)
        }
    }
    SnackbarHost(host,modifier.padding(16.dp)) { data ->
        Snackbar(data,modifier=Modifier.testTag(tag))
    }
}

internal fun goalsDateText(at: String,zone: ZoneId,time: Boolean=false): String = runCatching {
    Instant.parse(at).atZone(zone).format(DateTimeFormatter.ofPattern(if(time) "yyyy年M月d日 HH:mm" else "yyyy年M月d日"))
}.getOrDefault(at)

@Composable internal fun GoalsPreferenceDateButton(label: String,value: String?,zone: ZoneId,withTime: Boolean,enabled: Boolean,tag: String,onChange: (String?)->Unit) {
    val context=LocalContext.current
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        OutlinedButton(shape=MaterialTheme.shapes.medium,onClick={
            val selected=value?.let {runCatching {Instant.parse(it)}.getOrNull()}?.atZone(zone) ?: ZonedDateTime.now(zone).plusDays(7)
            DatePickerDialog(context,{_,year,month,day ->
                val date=LocalDate.of(year,month+1,day)
                if(withTime) TimePickerDialog(context,{_,hour,minute -> onChange(date.atTime(hour,minute).atZone(zone).toInstant().toString()) },selected.hour,selected.minute,true).show()
                else onChange(date.atStartOfDay(zone).toInstant().toString())
            },selected.year,selected.monthValue-1,selected.dayOfMonth).show()
        },enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag)) {
            Text("$label · ${value?.let {goalsDateText(it,zone,withTime)} ?: "未设置"}")
        }
        if(value!=null) TextButton(onClick={onChange(null)},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("${tag}_clear")) {Text("清除$label")}
        if(withTime) Text("时区：${zone.id}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
