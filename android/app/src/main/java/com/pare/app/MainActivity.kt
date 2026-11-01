package com.pare.app

import android.os.Bundle
import android.content.Intent
import java.time.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        receiveReminder(intent)
        setContent { PareTheme { PareScreen(model) } }
    }
    private fun receiveReminder(value:Intent?) {
        model.receiveHomeWidget(value)
        if(value?.hasExtra(MobileNotificationRuntime.TASK_ID_EXTRA)==true) {
            model.openNotificationTask(value.getStringExtra(MobileNotificationRuntime.TASK_ID_EXTRA))
            value.removeExtra(MobileNotificationRuntime.TASK_ID_EXTRA)
        }
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);receiveReminder(intent)}
    override fun onResume() { super.onResume(); model.onForeground() }
    override fun onPause() { model.onBackground(); super.onPause() }
}

private val DraftSaver = listSaver<EditorDraft, Any>(
    save = { listOf(it.entityId, it.kind, it.text, it.note, it.quadrant, it.nextAction, it.estimate, it.addToToday, it.isNew) },
    restore = { EditorDraft(it[0] as String, it[1] as String, it[2] as String, it[3] as String, it[4] as Int, it[5] as String, it[6] as String, it[7] as Boolean, it[8] as Boolean) }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PareScreen(vm: PareViewModel) {
    val state by vm.state.collectAsState()
    val initialized by vm.initialized.collectAsState()
    val execution by vm.execution.collectAsState()
    val executionReady by vm.executionReady.collectAsState()
    val deviceError by vm.deviceError.collectAsState()
    val reminderError by vm.reminderError.collectAsState()
    val notificationTask by vm.notificationTask.collectAsState()
    val widgetRequest by vm.widgetRequest.collectAsState()
    var executionTaskId by rememberSaveable {mutableStateOf("")}
    var reminderTaskId by rememberSaveable {mutableStateOf("")}
    var aiTaskId by rememberSaveable {mutableStateOf("")}
    var aiChooser by rememberSaveable {mutableStateOf(false)}
    var executionChooser by rememberSaveable {mutableStateOf(false)}
    var reviewOpen by rememberSaveable {mutableStateOf(false)}
    var moreOpen by rememberSaveable {mutableStateOf(false)}
    var widgetPinOpen by rememberSaveable {mutableStateOf(false)}
    val widgetPinModel:HomeWidgetPinModel=viewModel(key="widget-pin")
    var libraryOpen by rememberSaveable {mutableStateOf(false)}
    var goalsOpen by rememberSaveable {mutableStateOf(false)}
    var preferencesOpen by rememberSaveable {mutableStateOf(false)}
    var devicesOpen by rememberSaveable {mutableStateOf(false)}
    var initialGoalId by rememberSaveable {mutableStateOf<String?>(null)}
    var ideaManagementId by rememberSaveable {mutableStateOf("")}
    var metadataTaskId by rememberSaveable {mutableStateOf("")}
    var implementationTaskId by rememberSaveable {mutableStateOf("")}
    var planningOpen by rememberSaveable {mutableStateOf(false)}
    var planningKind by rememberSaveable {mutableStateOf("day")}
    var planningSubject by rememberSaveable {mutableStateOf("")}
    var planningPlanId by rememberSaveable {mutableStateOf("")}
    var timelineReveal by rememberSaveable {mutableStateOf<String?>(null)}
    val devicePresence by vm.presence.collectAsState()
    val presenceSharingEnabled by vm.presenceSharingEnabled.collectAsState()
    val presenceSharingError by vm.presenceSharingError.collectAsState()
    val context=LocalContext.current
    val hintPreferences=remember(context) {MobileHintPreferences(context)}
    val localScope=rememberCoroutineScope()
    var hintVersion by rememberSaveable {mutableIntStateOf(0)}
    var hintsEnabled by remember {mutableStateOf(hintPreferences.enabled())}
    val planningNow by vm.planningInstant.collectAsState()
    val status by vm.status.collectAsState()
    val session by vm.session.collectAsState()
    val busy by vm.busy.collectAsState()
    val previewReady by vm.previewReady.collectAsState()
    val snackbars=remember {SnackbarHostState()}
    var timelineMode by rememberSaveable {mutableStateOf(false)}
    val todayViewStates=rememberSaveableStateHolder()
    var calendarDate by rememberSaveable {mutableStateOf<String?>(null)}
    var chooseDate by rememberSaveable {mutableStateOf(false)}
    val selectedDate=calendarDate?.let(LocalDate::parse) ?: LocalDate.parse(vm.planningDate(state,planningNow))
    val scheduleModel:TimelineScheduleModel=viewModel(key="timeline-form:${state.owner}")
    var scheduleOpen by rememberSaveable {mutableStateOf(false)}
    var pendingEditorAction by rememberSaveable {mutableStateOf("")}
    var capturePlanId by rememberSaveable {mutableStateOf<String?>(null)}
    var pendingCapturePlanId by rememberSaveable {mutableStateOf<String?>(null)}
    var revealUnscheduled by rememberSaveable {mutableIntStateOf(0)}
    var organizeMoving by remember { mutableStateOf(false) }
    var choosingTaskPlanId by rememberSaveable {mutableStateOf<String?>(null)}
    val choosingTaskSave:OrganizeSaveModel=viewModel(key="choose-existing-task")
    LaunchedEffect(choosingTaskSave.saved) {if(choosingTaskSave.saved){choosingTaskPlanId=null;choosingTaskSave.reset()}}
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
                capturePlanId=if(requested.isNew && requested.kind=="task" && today)TimelineDay.from(state,selectedDate).planId else null
                original = requested
                editorSave.reset()
                editorOpen = true
            }
            DraftOpenAction.RESUME_EXISTING -> editorOpen = true
            DraftOpenAction.CONFIRM_SWITCH -> {pendingDraft=requested;pendingCapturePlanId=if(requested.isNew && requested.kind=="task" && today)TimelineDay.from(state,selectedDate).planId else null}
        }
    }
    fun clearDraft() {
        editorStateHolder.removeState(draft.entityId)
        editorOpen = false
        draft = EditorDraft("", "inbox")
        original = draft
        editorSave.reset()
        pendingEditorAction=""
        capturePlanId=null
    }
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
    fun openSchedule(task:Entity?,day:TimelineDay=TimelineDay.from(state,selectedDate),at:Instant?=null,sourcePlanId:String=day.planId) {
        if(scheduleModel.saving)return
        scheduleModel.clear();scheduleModel.initialize(state,day,task,at,sourcePlanId);scheduleOpen=true
    }
    fun saveTimeline(label:String,onSaved:()->Unit={},undoReveal:String?=timelineReveal,onResult:((Result<Unit>)->Unit)?=null,change:(SyncState)->SyncState) {
        val originalDate=selectedDate.toString()
        var undo:TimelineUndo?=null
        vm.editTimeline({before ->change(before).also {after ->undo=TimelineUndo.capture(before,after)}}) {result ->
            onResult?.invoke(result)
            localScope.launch {
                snackbars.currentSnackbarData?.dismiss()
                if(result.isFailure)snackbars.showSnackbar(result.exceptionOrNull()?.message ?: "安排没有保存，请重试")
                else { onSaved();if(snackbars.showSnackbar(label,actionLabel=if(undo!=null) "撤销" else null,withDismissAction=true,duration=SnackbarDuration.Short)==SnackbarResult.ActionPerformed) {
                    undo?.let {receipt ->vm.editTimeline(receipt::apply) {back ->
                        if(back.isSuccess) {calendarDate=originalDate;timelineReveal=undoReveal}
                        localScope.launch {snackbars.showSnackbar(if(back.isSuccess) "已撤销这次安排" else back.exceptionOrNull()?.message ?: "撤销未保存")}
                    }}
                }}
            }
        }
    }
    fun addExistingTask(task:Entity,planId:String,fromPicker:Boolean=false) {
        if(fromPicker && !choosingTaskSave.begin())return
        vm.editWorkspace({DayTaskSelection.add(it,task.id,planId,Instant.now())}) {result->
            if(fromPicker)choosingTaskSave.finish(result)
            localScope.launch {
                snackbars.currentSnackbarData?.dismiss()
                val message=if(result.isSuccess) "已加入 ${planId.substringBefore('@')}" else result.exceptionOrNull()?.message ?: "没有保存，请重试"
                if(snackbars.showSnackbar(message,actionLabel=if(result.isSuccess && !fromPicker) "查看" else null,withDismissAction=true,duration=SnackbarDuration.Short)==SnackbarResult.ActionPerformed){
                    tab=0;calendarDate=planId.substringBefore('@');timelineMode=false
                }
            }
        }
    }
    fun openPlanning(kind:String="day",subject:String="") {
        planningKind=kind;planningSubject=subject;planningPlanId=TimelineDay.from(state,selectedDate).planId;planningOpen=true
    }
    fun performTaskAction(action:String,id:String) {
        if(action=="inbox_ai") {openPlanning("inbox",id);return}
        val task=state.items["task:$id"] ?: return
        when(action) {
            "schedule"->openSchedule(task)
            "run"->executionTaskId=id
            "ai"->aiTaskId=id
            "support"->supportTaskId=id
            "remind"->reminderTaskId=id
            "plan"->allocationTaskId=id
            "metadata"->metadataTaskId=id
            "implementation"->implementationTaskId=id
            "origin"->openOrigin(id)
            "today"->vm.toggleToday(task)
            "win"->vm.setWin(task)
            "complete"->vm.complete(task)
        }
    }
    fun saveEditor(afterAction:String="") {
        if(editorSave.begin(draft)) {
            pendingEditorAction=afterAction
            val submitted=draft
            val entity=state.items["${submitted.kind}:${submitted.entityId}"] ?: Entity(submitted.kind,submitted.entityId)
            vm.save(entity,submitted.kind,submitted.text.trim(),submitted.note.trim(),submitted.quadrant,
                estimatedMinutes=submitted.estimate.toIntOrNull(),nextAction=submitted.nextAction.trim(),addToToday=submitted.addToToday,
                expected=original.editBaseline(),targetPlanId=capturePlanId,onResult=editorSave::finish)
        }
    }
    LaunchedEffect(saveState.saved) {
        if(saveState.saved) {
            val id=draft.entityId;val action=pendingEditorAction
            val newPlan=capturePlanId.takeIf {draft.isNew && draft.kind=="task" && draft.addToToday}
            clearDraft()
            if(newPlan!=null) {val date=newPlan.substringBefore('@');calendarDate=if(date==vm.planningDate(state,Instant.now()))null else date;revealUnscheduled++;localScope.launch {snackbars.showSnackbar(if(timelineMode) "已放入待安排，可拖到时间轴" else "已加入这一天的清单")}}
            if(action.isNotEmpty())performTaskAction(action,id)
        }
    }
    LaunchedEffect(scheduleModel.saved) {if(scheduleModel.saved){
        scheduleModel.day?.let {calendarDate=if(it.date==LocalDate.parse(vm.planningDate(state,planningNow)))null else it.date.toString()}
        timelineReveal=scheduleModel.startInstant()?.toString();scheduleOpen=false;scheduleModel.clear()
    }}
    LaunchedEffect(notificationTask,initialized,state) {
        if(initialized && notificationTask!=null) {
            val target=state.items["task:$notificationTask"]?.takeUnless {it.deleted}
            if(target!=null)openEditor(target) else vm.deviceError.value="这项提醒对应的任务已不可用。"
            vm.consumeNotificationTask()
        }
    }
    LaunchedEffect(widgetRequest,initialized,executionReady,deviceError) {
        val request=widgetRequest
        if(request!=null && initialized && (executionReady || request.action in setOf(HomeWidgetAction.TODAY,HomeWidgetAction.CAPTURE) || deviceError!=null)) {
            vm.widgetRequest.value=null
            if(!executionReady && request.action !in setOf(HomeWidgetAction.TODAY,HomeWidgetAction.CAPTURE)) {
                vm.deviceError.value="专注资料暂不可用，请先查看错误提示；任务内容保留。"
                return@LaunchedEffect
            }
            when(request.action) {
                HomeWidgetAction.TODAY -> {tab=0;calendarDate=null;timelineMode=false}
                HomeWidgetAction.CAPTURE -> openEditor(kind="inbox")
                HomeWidgetAction.START_FOCUS,HomeWidgetAction.REVIEW_FOCUS -> executionTaskId=request.taskId.orEmpty()
                HomeWidgetAction.PAUSE_FOCUS,HomeWidgetAction.RESUME_FOCUS -> vm.runWidgetCommand(request) {result->
                    if(result.getOrNull()!=true)executionTaskId=request.taskId.orEmpty()
                    if(result.isFailure)vm.deviceError.value=result.exceptionOrNull()?.message ?: "专注操作未完成"
                }
            }
        }
    }
    val localReview by produceState<MobileReviewSummary?>(null,state,planningNow) {
        value=withContext(Dispatchers.Default) {MobileReview.calculate(state,planningNow)}
    }
    val gentleHint=remember(localReview,hintsEnabled,hintVersion,planningNow,execution) {
        if(hintsEnabled)localReview?.attention?.firstOrNull {execution.runFor(it.id)==null && hintPreferences.visible(it,planningNow)} else null
    }
    fun openReviewItem(hint:ReviewAttention) {
        reviewOpen=false
        if(hint.kind=="idea")workspaceId=hint.id else state.items["task:${hint.id}"]?.let {openEditor(it)}
    }
    fun executionLabel(id:String):String? {
        val run=execution.runFor(id) ?: return null
        return when {run.pendingFinish!=null->"记录待保存";run.due->"专注到点";run.paused->"已暂停";execution.focus?.id==run.id->"本机专注中";else->"等待结果中"}
    }
    fun toggleExecution(id:String) {
        val run=execution.runFor(id) ?: return
        if(run.due || run.pendingFinish!=null)executionTaskId=id
        else vm.runCommand(ExecutionCommand(id,if(run.paused)ExecutionAction.RESUME else ExecutionAction.PAUSE))
    }
    val capture: () -> Unit = { openEditor(kind = when (tab) { 0 -> "task"; 2 -> "idea"; else -> "inbox" }, today = tab == 0) }
    val localRuns=listOfNotNull(execution.focus)+execution.external
    val runningAction=if(localRuns.isEmpty())null else PareRunningAction(
        execution.focus?.let {when {it.pendingFinish!=null || it.due->"待记录";it.paused->"已暂停";else->"专注中"}} ?: "等待 ${execution.external.size} 项"
    ) {if(localRuns.size==1)executionTaskId=localRuns.single().taskId else executionChooser=true}
    CompositionLocalProvider(LocalPareRunningAction provides runningAction) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost={SnackbarHost(snackbars) {data ->Snackbar(data,modifier=Modifier.testTag("workspace_snackbar"))}},
        topBar = { Column(Modifier.statusBarsPadding()) { PareHeader(tab, status, busy, session != null, onAccount = { moreOpen = true }, onAI={openPlanning()},onReview={reviewOpen=true},day=if(tab==0)TimelineDay.from(state,selectedDate) else null,onChooseDate={chooseDate=true}) } },
        bottomBar = { Column {
            if(runningAction!=null) Surface(color=MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=runningAction.onClick,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("execution_running"),contentPadding=PaddingValues(horizontal=8.dp)) {
                        Text(runningAction.label+(execution.focus?.let {" · "+(state.items["task:${it.taskId}"]?.title() ?: "任务")} ?: ""),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth(),style=MaterialTheme.typography.labelLarge)
                    }
                    execution.focus?.let {run->
                        TextButton(onClick={toggleExecution(run.taskId)},modifier=Modifier.heightIn(min=48.dp).testTag("execution_global_toggle")) {Text(if(run.due || run.pendingFinish!=null)"查看" else if(run.paused)"继续" else "暂停")}
                    }
                }
            }
            PareNavigation(tab, onTab = { tab = it })
        } },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = capture, shape=MaterialTheme.shapes.large,
                containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary,
                elevation=FloatingActionButtonDefaults.elevation(defaultElevation=2.dp,pressedElevation=4.dp), modifier = Modifier.heightIn(min = 56.dp).testTag("add_item"),
                icon = { PareIcon(PareIcons.Add, null) }, text = { Text(listOf("添加任务", "随手记", "记录想法")[tab]) })
        }
    ) { padding ->
        if(tab==0)Column(Modifier.fillMaxSize().padding(padding)) {
            if(draft.entityId.isNotEmpty() && !editorOpen)TextButton(onClick={editorOpen=true},modifier=Modifier.heightIn(min=48.dp).testTag("resume_draft")){Text("继续编辑草稿")}
            if(listOf("失败","超时","失效","拒绝","已停止","等待联网","等待重试","未完成").any {it in status})TextButton(onClick={account=true},modifier=Modifier.heightIn(min=48.dp).testTag("sync_attention")){Text(status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
            if(conflictCount>0)TextButton(onClick={account=true},modifier=Modifier.heightIn(min=48.dp)){Text("$conflictCount 个同步冲突待处理")}
            if(deviceError!=null || reminderError!=null)Text(deviceError ?: reminderError.orEmpty(),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(horizontal=16.dp).testTag("device_error"))
            if(gentleHint!=null)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={openReviewItem(gentleHint)},modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text("值得回看：${gentleHint.title}",maxLines=1,overflow=TextOverflow.Ellipsis)}
                TextButton(onClick={localScope.launch {try {withContext(Dispatchers.IO){hintPreferences.defer(gentleHint,planningNow)};hintVersion++}catch(_:Exception){vm.deviceError.value="暂放没有保存，请重试。"}}},modifier=Modifier.heightIn(min=48.dp)){Text("暂放")}
            }
            TodayViewControls(TimelineDay.from(state,selectedDate),planningNow,timelineMode,{timelineMode=it}) {date ->
                timelineReveal=null;calendarDate=if(date==LocalDate.parse(vm.planningDate(state,planningNow)))null else date.toString()
            }
            todayViewStates.SaveableStateProvider(if(timelineMode) "timeline" else "list") {
            if(!timelineMode) TodayOverview(state,selectedDate,planningNow,
                onChooseTask={choosingTaskSave.reset();choosingTaskPlanId=TimelineDay.from(state,selectedDate).planId},
                onSelectDate={date->timelineReveal=null;calendarDate=if(date==LocalDate.parse(vm.planningDate(state,planningNow)))null else date.toString()},
                onOpenTask={openEditor(it)},onComplete=vm::complete,
                onScheduleTask={task,day->openSchedule(task,day)},
                onEditSchedule={block->openSchedule(block.task,TimelineDay.from(state,block.start.atZone(TimelineDay.from(state,selectedDate).zone).toLocalDate()),block.start,block.sourcePlanId)},
                onExecution={task->if(execution.runFor(task.id)!=null)toggleExecution(task.id) else executionTaskId=task.id},
                executionLabel=::executionLabel,executionPaused={id->execution.runFor(id)?.let {it.paused && !it.due && it.pendingFinish==null}==true},modifier=Modifier.weight(1f),showDateHeader=false)
            else TodayTimeline(state,selectedDate,planningNow,
                onSelectDate={date ->timelineReveal=null;calendarDate=if(date==LocalDate.parse(vm.planningDate(state,planningNow)))null else date.toString()},
                onOpenTask={openEditor(it)},onComplete={vm.complete(it)},
                onCreateAt={day,at->openSchedule(null,day,at)},
                onScheduleTask={task,day,at,baseline ->
                    if(at==null || baseline==null)openSchedule(task,day,at)
                    else saveTimeline("已安排到 ${at.atZone(day.zone).format(DateTimeFormatter.ofPattern("HH:mm"))}") {TimelineActions.setSchedule(it,task.id,day.planId,at,30,baseline)}
                },
                onEditSchedule={block->openSchedule(block.task,TimelineDay.from(state,block.start.atZone(TimelineDay.from(state,selectedDate).zone).toLocalDate()),block.start,block.sourcePlanId)},
                onChangeSchedule={block,start,minutes,expected->saveTimeline("时间已调整") {TimelineActions.setSchedule(it,block.task.id,block.sourcePlanId,start,minutes,expected)}},
                onReorderUnscheduled={day,ids,expected->saveTimeline("顺序已调整") {TimelineActions.reorder(it,day.planId,ids,expected)}},
                revealUnscheduledKey=revealUnscheduled,modifier=Modifier.weight(1f),
                onMoveToDay={block,start,expected->
                    val date=start.atZone(TimelineDay.from(state,selectedDate).zone).toLocalDate()
                    saveTimeline("已移到 ${date.monthValue}月${date.dayOfMonth}日",onSaved={calendarDate=date.toString();timelineReveal=start.toString()},undoReveal=block.start.toString()) {
                        TimelineActions.moveToDay(it,block.task.id,start,block.durationMinutes,expected)
                    }
                },revealAt=timelineReveal?.let(Instant::parse),showDateHeader=false)
            }
        } else OrganizeDragLayout(state,enabled=tab==1 && !organizeMoving,modifier=Modifier.fillMaxSize().padding(padding),onMove={drop->
            organizeMoving=true
            var receipt:OrganizeUndo?=null
            vm.editWorkspace({before->OrganizeActions.move(before,drop.taskId,drop.quadrant,drop.beforeId,drop.baseline,Instant.now()).also {receipt=OrganizeUndo.capture(before,it)}}) {result->
                organizeMoving=false
                localScope.launch {
                    snackbars.currentSnackbarData?.dismiss()
                    val message=when {result.isFailure->result.exceptionOrNull()?.message ?: "调整未保存，请重试"
                        receipt==null->"位置未改变"
                        drop.baseline.sourceQuadrant==drop.quadrant->"任务顺序已调整"
                        else->"已移至${quadrantLabels[drop.quadrant]}"}
                    if(snackbars.showSnackbar(message,actionLabel=if(result.isSuccess && receipt!=null)"撤销" else null,withDismissAction=true,duration=SnackbarDuration.Short)==SnackbarResult.ActionPerformed)
                        receipt?.let {undo->vm.editWorkspace(undo::apply) {back->localScope.launch {snackbars.showSnackbar(if(back.isSuccess)"已撤销移动" else back.exceptionOrNull()?.message ?: "无法撤销",withDismissAction=true,duration=SnackbarDuration.Short)}}}
                }
            }
        }) { drag -> LazyColumn(Modifier.fillMaxSize().testTag("main_list"),state=drag.listState,
            contentPadding = PaddingValues(start = 12.dp, top = 0.dp, end = 12.dp, bottom = 88.dp)) {
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
            if(deviceError!=null || reminderError!=null) item {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(deviceError ?: reminderError.orEmpty(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error,modifier=Modifier.weight(1f).testTag("device_error"))
                    TextButton(onClick={vm.deviceError.value=null;vm.reminderError.value=null}) {Text("知道了")}
                }
            }
            when (tab) {
                1 -> {
                    val inbox = all.filter { it.kind == "inbox" }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                if (inbox.isEmpty()) Text("待整理 · 暂无内容", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else SectionLabel("待整理", inbox.size)
                            }
                            TextButton(onClick={libraryOpen=true},modifier=Modifier.heightIn(min=48.dp).testTag("library_open")){Text("查找 / 归档")}
                            TextButton(onClick = { openEditor(kind = "task") }, modifier = Modifier.heightIn(min = 48.dp).testTag("new_task")) {
                                PareIcon(PareIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                                Text("新建任务", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    items(inbox, key = { it.key }) { item -> InboxCard(item, { openEditor(item) }, { organizeSave.reset(); organizeId = item.id }) }
                    (0..3).sortedBy {OrganizeActions.tasks(state,it).isEmpty()}.forEach { q ->
                        val group = OrganizeActions.tasks(state,q)
                        item(key="quadrant:$q") { drag.Header(q,group.size) }
                        itemsIndexed(group, key = { _,item -> item.key }) { index,item ->
                            drag.Task(item) { EntityCard(item, win == item.id, { openEditor(item) }, { vm.complete(item) }, {
                                val planId=ContentActions.planId(state,planningNow)
                                if(DayTaskSelection.contains(state,item.id,planId)){tab=0;calendarDate=null;timelineMode=false}else addExistingTask(item,planId)
                            }, { vm.setWin(item) }, inToday = DayTaskSelection.contains(state,item.id,ContentActions.planId(state,planningNow)),showTodayAction=true,
                                planLabel = if (item.id in ordered) todayPlanLabel(state,item.id,planningNow) else null,
                                onPlan = { allocationTaskId = item.id }, onSupport = { supportTaskId = item.id },
                                onOrigin = if (relatedIdeas(state,item.id).isEmpty()) null else ({ openOrigin(item.id) }),
                                onExecution=if(executionReady) ({executionTaskId=item.id}) else null,onAI={aiTaskId=item.id},onReminder={reminderTaskId=item.id},
                                executionLabel=executionLabel(item.id),executionPaused=execution.runFor(item.id)?.paused==true,onExecutionToggle={toggleExecution(item.id)},firstInGroup=index==0,lastInGroup=index==group.lastIndex,onSchedule={openSchedule(item)}) }
                        }
                        item(key="quadrant_end:$q") {drag.End(q,group.isEmpty())}
                    }
                }
                2 -> {
                    val ideas = all.filter { it.kind == "idea" }

                    if (ideas.isEmpty()) item { EmptyNote("还没有想法", "记录一个想法，之后可以继续补充。",icon=PareIcons.Seed) }
                    item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={initialGoalId=null;goalsOpen=true},modifier=Modifier.heightIn(min=48.dp).testTag("goals_open")){Text("长期目标")}
                        TextButton(onClick={libraryOpen=true},modifier=Modifier.heightIn(min=48.dp)){Text("查找想法")}
                    }}
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
                if (showCompleted) itemsIndexed(completedTasks, key = { _,item -> "completed:${item.key}" }) { index,item ->
                    EntityCard(item, win == item.id, { openEditor(item) }, { vm.complete(item) }, { vm.toggleToday(item) }, { vm.setWin(item) }, inToday = item.id in ordered,
                                planLabel = if (item.id in ordered) todayPlanLabel(state,item.id,planningNow) else null,
                                onPlan = { allocationTaskId = item.id }, onSupport = { supportTaskId = item.id },
                                onOrigin = if (relatedIdeas(state,item.id).isEmpty()) null else ({ openOrigin(item.id) }),
                                onExecution=if(executionReady) ({executionTaskId=item.id}) else null,onAI={aiTaskId=item.id},onReminder={reminderTaskId=item.id},
                                executionLabel=executionLabel(item.id),executionPaused=execution.runFor(item.id)?.paused==true,onExecutionToggle={toggleExecution(item.id)},firstInGroup=index==0,lastInGroup=index==completedTasks.lastIndex,onSchedule={openSchedule(item)})
                }
            }
        } }
    }
    if(chooseDate) {
        val picker=rememberDatePickerState(initialSelectedDateMillis=selectedDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={chooseDate=false},modifier=Modifier.testTag("today_date_picker"),confirmButton={
            TextButton(onClick={picker.selectedDateMillis?.let {millis ->
                val date=Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                calendarDate=if(date==LocalDate.parse(vm.planningDate(state,planningNow)))null else date.toString();timelineReveal=null
            };chooseDate=false},enabled=picker.selectedDateMillis!=null,modifier=Modifier.heightIn(min=48.dp).testTag("today_date_confirm")) {Text("查看这一天")}
        },dismissButton={TextButton(onClick={chooseDate=false},modifier=Modifier.heightIn(min=48.dp)) {Text("取消")}}) {
            Column {DatePicker(picker);Text("规划时区：${TimelineDay.from(state,selectedDate).zone.id}",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(horizontal=24.dp,vertical=8.dp))}
        }
    }
    if(moreOpen)MoreWorkspaceDialog(onSelect={key->moreOpen=false;when(key) {
        "library"->libraryOpen=true
        "goals"->{initialGoalId=null;goalsOpen=true}
        "preferences"->preferencesOpen=true
        "devices"->{devicesOpen=true;vm.refreshPresence()}
        "account"->account=true
        "widget"->{widgetPinModel.reset();widgetPinOpen=true}
    }},onDismiss={moreOpen=false})
    choosingTaskPlanId?.let {planId->ChooseTaskForDayDialog(state,planId,onChoose={addExistingTask(it,planId,true)},saving=choosingTaskSave.saving,error=choosingTaskSave.error,onDismiss={if(!choosingTaskSave.saving)choosingTaskPlanId=null})}
    if(widgetPinOpen)HomeWidgetPinDialog(widgetPinModel,onDismiss={widgetPinOpen=false})
    if(libraryOpen)LibraryDialog(state,vm::editWorkspace,onDismiss={libraryOpen=false},onOpenTask={openEditor(it)},onOpenIdea={workspaceId=it.id},
        onOpenEntity={if(it.kind=="goal"){initialGoalId=it.id;goalsOpen=true}else openEditor(it)})
    if(goalsOpen)GoalsWorkspace(state,vm::editWorkspace,onDismiss={goalsOpen=false;initialGoalId=null},onOpenTask={openEditor(it)},initialGoalId=initialGoalId,onPlanGoal={openPlanning("goal",it)})
    if(preferencesOpen)PreferencesWorkspace(state,vm::editWorkspace,onDismiss={preferencesOpen=false})
    if(devicesOpen)DevicePresenceDialog(devicePresence,state,onRefresh=vm::refreshPresence,onDismiss={devicesOpen=false},
        sharingEnabled=presenceSharingEnabled,onSharingChange=vm::setPresenceSharing,sharingError=presenceSharingError)
    if(scheduleOpen) {
        val day=scheduleModel.day
        val expected=scheduleModel.expected
        val task=state.items["task:${scheduleModel.taskId}"]
        val available=scheduleModel.isNewTask || (taskCanBePlanned(task) && task?.conflict==null)
        TimelineScheduleDialog(scheduleModel,available,onSave={
            if(day!=null && expected!=null && scheduleModel.begin()) {
                val start=scheduleModel.startInstant()!!;val minutes=scheduleModel.durationMinutes()!!
                val id=scheduleModel.taskId;val title=scheduleModel.title;val isNew=scheduleModel.isNewTask;val reminder=scheduleModel.reminderLeadMinutes()
                val move=scheduleModel.moveExpected
                saveTimeline("安排已保存",undoReveal=move?.source?.scheduledAt ?: expected.scheduledAt,onResult=scheduleModel::finish) {s->if(isNew) TimelineActions.createScheduledTask(s,id,title,1,expected.planId,start,minutes,expected,Instant.now(),reminderLeadMinutes=reminder)
                    else if(move!=null)TimelineActions.moveToDay(s,id,start,minutes,move,reminder)
                    else TimelineActions.setSchedule(s,id,expected.planId,start,minutes,expected,reminder)}
            }
        },onUnschedule=if(!scheduleModel.isNewTask && expected?.scheduledAt!=null) ({
            if(scheduleModel.beginUnschedule()) {val id=scheduleModel.taskId;saveTimeline("已取消时间安排",undoReveal=expected.scheduledAt,onResult=scheduleModel::finish) {TimelineActions.clearSchedule(it,id,expected.planId,expected)}}
        }) else null,onDismiss={scheduleOpen=false;scheduleModel.clear()},onSelectDate={scheduleModel.selectDate(state,it)})
    }
    if(reminderTaskId.isNotBlank())TaskReminderDialog(reminderTaskId,vm) {reminderTaskId=""}
    if(aiChooser)AlertDialog(onDismissRequest={aiChooser=false},title={Text("让 AI 帮哪件事？")},text={
        val choices=tasks.filterNot {it.completed()}.sortedBy {if(it.id==win)0 else if(it.id in ordered)1 else 2}
        if(choices.isEmpty())Text("先记录一件任务，再让 AI 帮你找到下一步。")
        else LazyColumn(Modifier.heightIn(max=400.dp)) {items(choices,key={it.id}) {task ->
            TextButton(onClick={aiTaskId=task.id;aiChooser=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(task.title(),modifier=Modifier.fillMaxWidth())}
        }}
    },confirmButton={TextButton(onClick={aiChooser=false}){Text("关闭")}})
    if(reviewOpen)MobileReviewDialog(state,planningNow,hintsEnabled,onHintsChange={enabled ->
        localScope.launch {try {withContext(Dispatchers.IO) {hintPreferences.setEnabled(enabled)};hintsEnabled=enabled} catch(_:Exception) {vm.deviceError.value="轻提示设置未保存，请重试。"}}
    },onOpen=::openReviewItem,onAI={reviewOpen=false;aiTaskId=it},onDismiss={reviewOpen=false})
    if (workspaceId.isNotBlank()) IdeaWorkspaceDialog(workspaceId, vm, onDismiss = { workspaceId = "" },
        onEditIdea = { openEditor(it) }, onOpenTask = { openEditor(it) },onManage={ideaManagementId=workspaceId},onAI={openPlanning("idea",workspaceId)})
    if (allocationTaskId.isNotBlank()) TodayAllocationDialog(allocationTaskId, vm) { allocationTaskId = "" }
    if (supportTaskId.isNotBlank()) TaskSupportDialog(supportTaskId, vm,onStart={id->
        supportTaskId=""
        if(vm.peerFocusLabels()!=null)executionTaskId=id else vm.runCommand(ExecutionCommand(id,ExecutionAction.START_FOCUS,5))
    },onDismiss={supportTaskId=""})
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
        onSave = {saveEditor()}, onDismiss = { editorOpen = false }, onDiscard = ::clearDraft,
        latestVersion = if (saveState.staleContent) state.items["${draft.kind}:${draft.entityId}"]?.takeUnless { it.deleted }?.let(EditorDraft::from) else null,
        onReloadLatest = {
            state.items["${draft.kind}:${draft.entityId}"]?.takeUnless { it.deleted }?.let { latest ->
                draft = EditorDraft.from(latest); original = draft; editorSave.reset()
            }
        }, inboxActions={
            TextButton(onClick={val id=draft.entityId;if(draft!=original)saveEditor("inbox_ai")else{clearDraft();openPlanning("inbox",id)}},enabled=!saveState.saving,modifier=Modifier.heightIn(min=48.dp).testTag("inbox_ai")){Text(if(draft!=original) "保存后让 AI 整理" else "让 AI 帮我整理")}
        }, plannedDayLabel=if(draft.isNew && capturePlanId!=null) "加入 ${capturePlanId!!.substringBefore('@')} 的待安排，之后可拖到时间轴" else null, taskActions={
            state.items["task:${draft.entityId}"]?.let {task ->
                TaskDetailActions(task,draft!=original,saveState.saving,task.id in ordered,task.id==win,
                    execution.runFor(task.id)!=null,relatedIdeas(state,task.id).isNotEmpty()) {action ->
                    if(draft!=original)saveEditor(action) else {val id=task.id;clearDraft();performTaskAction(action,id)}
                }
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
            capturePlanId=pendingCapturePlanId;pendingCapturePlanId=null
            pendingDraft = EditorDraft("", "inbox")
            editorSave.reset(); editorOpen = true
        }, modifier = Modifier.heightIn(min = 48.dp).testTag("draft_switch_discard")) { Text("放弃草稿并打开") } }
    )
    if(ideaManagementId.isNotBlank())IdeaManagementDialog(ideaManagementId,state,vm::editWorkspace,onDismiss={ideaManagementId=""},onOpenTask={ideaManagementId="";openEditor(it)})
    if(metadataTaskId.isNotBlank())TaskMetadataDialog(metadataTaskId,state,vm::editWorkspace,onDismiss={metadataTaskId=""})
    if(implementationTaskId.isNotBlank())ImplementationPlanDialog(state,implementationTaskId,vm::editWorkspace,onDismiss={implementationTaskId=""})
    if(aiTaskId.isNotBlank())MobileAIDialog(state,aiTaskId,vm::editMobileAI,onDismiss={aiTaskId=""})
    if(planningOpen)PlanningAIDialog(state,planningPlanId,vm::editWorkspace,onDismiss={planningOpen=false},
        ideaId=planningSubject.takeIf {planningKind=="idea"},goalId=planningSubject.takeIf {planningKind=="goal"},inboxId=planningSubject.takeIf {planningKind=="inbox"})
    if (account) AccountDialog(vm, state, status, session, busy, previewReady) { account = false }
    if(executionChooser)AlertDialog(onDismissRequest={executionChooser=false},title={Text("本机正在推进")},text={
        LazyColumn(Modifier.heightIn(max=400.dp)) {items(listOfNotNull(execution.focus)+execution.external,key={it.id}) {run ->
            TextButton(onClick={executionTaskId=run.taskId;executionChooser=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Text("${state.items["task:${run.taskId}"]?.title() ?: "任务已不可用"} · ${executionLabel(run.taskId).orEmpty()}")
            }
        }}
    },confirmButton={TextButton(onClick={executionChooser=false}){Text("关闭")}})
    if(executionTaskId.isNotBlank()) {
        val task=state.items["task:$executionTaskId"]?.takeUnless {it.deleted}
        if(task!=null)ExecutionDialog(task,execution,vm::runCommand,onDismiss={executionTaskId=""},otherFocusLabel=vm.peerFocusLabels())
        else AlertDialog(onDismissRequest={executionTaskId=""},title={Text("任务已不可用")},confirmButton={TextButton(onClick={executionTaskId=""}){Text("关闭")}})
    }
    }
}
