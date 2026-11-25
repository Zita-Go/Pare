package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pare.app.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LocalQuickSupportRequest=staticCompositionLocalOf<((Entity)->Unit)?> {null}

/** Each window keeps its draft independently of task ordering or the currently running task. */
@Composable internal fun QuickActionHost(runtime:DesktopRuntime,content:@Composable ()->Unit) {
    var subject by remember {mutableStateOf<Entity?>(null)}
    CompositionLocalProvider(LocalQuickSupportRequest provides {subject=it}) {
        content()
        subject?.let {task->key(task.id){QuickSupportSheet(runtime,task.id,{subject=null},task)}}
    }
}

/** Shared by ordinary rows, quick capture and the small desktop window. */
@OptIn(ExperimentalLayoutApi::class,ExperimentalMaterial3Api::class)
@Composable internal fun QuickTaskActions(runtime:DesktopRuntime,task:Entity,prefix:String="quick",compact:Boolean=false,featured:Boolean=false) {
    if(task.completed() || task.deleted)return
    val execution by runtime.execution.state.collectAsState()
    val ready by runtime.executionReady.collectAsState()
    val work=rememberWork(runtime)
    var focusMenu by remember(task.id){mutableStateOf(false)}
    var checkMenu by remember(task.id){mutableStateOf(false)}
    var support by remember(task.id){mutableStateOf(false)}
    val requestSupport=LocalQuickSupportRequest.current
    val baseline=SmallStepBaseline.from(task)
    val completionId=remember(task.id,baseline){uid()}
    val control=Modifier.defaultMinSize(minWidth=1.dp,minHeight=if(compact)28.dp else 30.dp)
    val padding=PaddingValues(horizontal=if(compact)4.dp else 6.dp,vertical=3.dp)
    val parallel=execution.external.firstOrNull {it.taskId==task.id}
    val run=execution.focus?.takeIf {it.taskId==task.id}?:parallel
    // These mouse/keyboard controls keep their natural text height and wrap even in a narrow quadrant.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            if(run==null) {
                if(featured)StepButton({work.execute {it.startFocus(task.id,5)}},modifier=control.testTag("featured_start_${task.id}"),enabled=ready&&!work.busy&&execution.focus==null,contentPadding=padding){IconLabel(Icons.Outlined.PlayArrow,"开始 5 分钟")}
                Box {
                StepTextButton({focusMenu=true},modifier=control.testTag("${prefix}_focus_${task.id}"),enabled=ready&&!work.busy&&execution.focus==null,contentPadding=padding) {IconLabel(Icons.Outlined.PlayArrow,if(featured)"时长"else"专注")}
                DropdownMenu(focusMenu,{focusMenu=false}) {listOf(5,15,30,60).forEach {minutes->
                    DropdownMenuItem(text={Text("$minutes 分钟")},onClick={focusMenu=false;work.execute {it.startFocus(task.id,minutes)}})
                }}
                }
                StepTextButton({work.execute {it.startExternal(task.id)}},modifier=control.testTag("${prefix}_parallel_${task.id}"),enabled=ready&&!work.busy,contentPadding=padding) {IconLabel(Icons.Outlined.CallSplit,"并行")}
            } else {
                if(!run.due)StepTextButton({work.execute {if(run.paused)it.resume(task.id)else it.pause(task.id)}},modifier=control.testTag("${prefix}_${if(run.paused)"resume"else"pause"}_${task.id}"),enabled=!work.busy,contentPadding=padding) {
                    IconLabel(if(run.paused)Icons.Outlined.PlayArrow else Icons.Outlined.Pause,if(run.paused)"继续"else"暂停")
                }
                StepTextButton({work.execute {it.finish(task.id)}},modifier=control.testTag("${prefix}_finish_${task.id}"),enabled=!work.busy,contentPadding=padding){IconLabel(Icons.Outlined.Stop,"结束")}
                if(parallel!=null)Box {
                    val checkAt=parallel.nextCheckAtMillis?.let {Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))}
                    StepTextButton({checkMenu=true},modifier=control.testTag("${prefix}_check_${task.id}"),enabled=!work.busy,contentPadding=padding) {IconLabel(Icons.Outlined.Schedule,checkAt?.let {"$it 检查"}?:"检查时间")}
                    DropdownMenu(checkMenu,{checkMenu=false}) {
                        listOf(5,15,30,60).forEach {minutes->
                            DropdownMenuItem(text={Text("$minutes 分钟后检查")},modifier=Modifier.testTag("${prefix}_check_${minutes}_${task.id}"),onClick={checkMenu=false;work.execute {it.setCheck(task.id,minutes)}})
                        }
                        if(parallel.nextCheckAtMillis!=null)DropdownMenuItem(text={Text("取消检查时间")},modifier=Modifier.testTag("${prefix}_check_cancel_${task.id}"),onClick={checkMenu=false;work.execute {it.setCheck(task.id,null)}})
                    }
                }
            }
            if(baseline.nextAction.isNotBlank())StepTextButton({work.edit {NextStepActions.complete(it,task.id,completionId,baseline,Instant.now())}},modifier=control.testTag("${prefix}_next_${task.id}"),enabled=!work.busy&&baseline.nextAction.isNotBlank()&&task.conflict==null,contentPadding=padding) {
                if(compact)Text("完成这一步")else IconLabel(Icons.Outlined.Check,"完成这一步")
            }
            StepTextButton({if(requestSupport!=null)requestSupport(task)else support=true},modifier=control.testTag("${prefix}_support_${task.id}"),enabled=!work.busy,contentPadding=padding){val label=if(baseline.nextAction.isBlank())"写下一步"else"卡住了";if(compact)Text(label)else IconLabel(Icons.Outlined.Lightbulb,label)}
        }
        WorkFeedback(work)
    }
    }
    if(support)QuickSupportSheet(runtime,task.id,{support=false})
}

/** One captured draft is committed before a timer command is allowed to run. */
@Composable internal fun QuickSupportSheet(runtime:DesktopRuntime,taskId:String,onClose:()->Unit,initial:Entity?=null) {
    val state by runtime.engine.state.collectAsState()
    val original=remember(taskId){initial?:state.items["task:$taskId"]}
    if(original==null||original.content()==null) {
        UiModal("任务已不可用",onClose){Text("这项任务已经移除，请关闭后查看最新资料。")}
        return
    }
    var baseline by remember(taskId){mutableStateOf(SmallStepBaseline.from(original))}
    var allocationBaseline by remember(taskId){mutableStateOf(DayAllocationBaseline.from(state,taskId,Instant.now()))}
    var blockerId by remember(taskId){mutableStateOf(uid())};val allocationId=remember(taskId){uid()}
    var next by remember(taskId){mutableStateOf(baseline.nextAction)}
    var stop by remember(taskId){mutableStateOf(baseline.definitionOfDone)}
    var reason by remember(taskId){mutableStateOf("")}
    var savedReason by remember(taskId){mutableStateOf("")}
    var savedOnce by remember(taskId){mutableStateOf(false)}
    val work=rememberWork(runtime)
    val current=state.items["task:$taskId"]
    val available=taskCanBePlanned(current)&&current?.conflict==null&&current?.conflictGroup.orEmpty().isEmpty()
    val enabled=available&&!work.busy
    val dirty=next!=baseline.nextAction||stop!=baseline.definitionOfDone||reason!=savedReason
    fun startSaved() {work.run(onClose){runtime.execute {it.startFocus(taskId,5)}}}
    fun save(start:Boolean) {
        if(savedOnce&&!dirty){if(start)startSaved()else onClose();return}
        val action=next;val criterion=stop;val chosenReason=reason;val blocker=chosenReason.takeIf {it.isNotBlank()}
        val expected=baseline;val allocation=allocationBaseline;val entryId=blockerId
        work.editSnapshot({snapshot->
            baseline=SmallStepBaseline.from(snapshot.items.getValue("task:$taskId"))
            allocationBaseline=DayAllocationBaseline.from(snapshot,taskId,Instant.now())
            next=baseline.nextAction;stop=baseline.definitionOfDone;savedReason=chosenReason;savedOnce=true;blockerId=uid()
            if(start)startSaved()else onClose()
        }) {SmallStepActions.save(it,taskId,action,criterion,expected,
            if(start)TodayAllocationDraft(allocation.planId,"personal",5,allocationId,allocation)else null,blocker,if(blocker==null)null else entryId,Instant.now())}
    }
    UiModal(if(original.content().str("nextAction").isBlank())"写下最小下一步"else"把开始缩小一点",onClose,work,width=720,subtitle=original.title(),dirty=dirty) {
        if(!available)Text("任务已完成、移除或存在冲突；这份草稿仍然保留。",color=MaterialTheme.colorScheme.error)
        SectionLabel("先找一个可以开始的动作","卡点记录可选；只保存不改变日计划。选择启动5分钟才安排今天投入并开始计时。")
        ChoiceRow(listOf("" to "只写下一步")+supportReasons.map {it.code to it.label},reason,enabled){reason=it}
        supportReasons.firstOrNull {it.code==reason}?.let {Text(it.guidance,color=StepMuted)}
        if(savedOnce)Text("下一步已保存；启动失败时可直接重试启动。",color=StepGreen,style=MaterialTheme.typography.bodySmall)
        Field(next,{next=it},"一个现在能开始的小动作",lines=2,enabled=enabled,inputModifier=Modifier.testTag("quick_next_action"))
        Field(stop,{stop=it},"做到哪里就可以停",lines=2,enabled=enabled,inputModifier=Modifier.testTag("quick_stop_criterion"))
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            StepOutlinedButton({save(false)},modifier=Modifier.testTag("quick_save_only"),enabled=enabled&&next.isNotBlank()&&stop.isNotBlank()){Text("只保存小一步")}
            StepButton({save(true)},modifier=Modifier.testTag("quick_save_start"),enabled=enabled&&next.isNotBlank()&&stop.isNotBlank()){Text(if(savedOnce&&!dirty)"开始5分钟"else"保存并开始5分钟")}
        }
    }
}
