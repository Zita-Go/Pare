package com.step.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pare.app.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Kept mounted while its native window is hidden, including drafts and any child editor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun StepCaptureUi(runtime:DesktopRuntime,onClose:()->Unit,visible:Boolean=true,onOrganize:()->Unit={}) {
    DesktopTheme {
        QuickActionHost(runtime) {
            val state by runtime.engine.state.collectAsState()
            val initialized by runtime.engine.initialized.collectAsState()
            val execution by runtime.execution.state.collectAsState()
            val work=rememberWork(runtime)
            var draft by remember {mutableStateOf(TextFieldValue())}
            var id by remember {mutableStateOf(uid())}
            var captured by remember {mutableStateOf(false)}
            var expanded by remember {mutableStateOf(true)}
            // Freeze the date with the editor; crossing midnight or changing the planning
            // zone must not move a still-open task draft into another day's plan.
            var detail by remember {mutableStateOf<Pair<String,String>?>(null)}
            var ai by remember {mutableStateOf<AiTarget?>(null)}
            var now by remember {mutableStateOf(Instant.now())}
            LaunchedEffect(visible) {while(visible){now=Instant.now();delay(30_000)}}
            val day=TimelineDay.today(state,now)
            val today=TodayOverviewProjection.project(state,day)
            val selected=(today.scheduled+today.unscheduled+today.completed).map {it.task}.distinctBy {it.id}
            val running=(listOfNotNull(execution.focus)+execution.external).mapNotNull {state.items["task:${it.taskId}"]}
                .filter {it.kind=="task"&&!it.deleted&&!it.completed()&&it.content()!=null}
            val tasks=(running+selected.sortedByDescending {planWin(state,day.planId,it.id)}).distinctBy {it.id}
            val done=selected.count {it.completed()}
            val focus=remember {FocusRequester()}
            val windowFocused=LocalWindowInfo.current.isWindowFocused
            ReportUnsavedEdits(draft.text.isNotBlank())
            // Re-focus after every show, native activation and closing a task detail. Child
            // windows own their input while open; no WindowFocusLost handler hides this panel.
            LaunchedEffect(visible,windowFocused,detail,ai,work.busy) {
                if(visible&&windowFocused&&detail==null&&ai==null&&!work.busy)focus.requestFocus()
            }
            val save:()->Unit={
                if(initialized&&draft.text.isNotBlank()&&draft.composition==null&&!work.busy) {
                    val submitted=draft.text;val creationId=id
                    work.edit({draft=TextFieldValue();id=uid();captured=true}) {
                        checkedContentSave(it,null,"inbox",creationId,submitted,"",1,ContentEditBaseline.from(null))
                    }
                }
            }
            Surface(Modifier.fillMaxSize().testTag("capture_root").onKeyEvent {event->
                // Bubble after controls/popups have handled Escape. A composing IME gets
                // first chance to cancel its candidate rather than losing the whole panel.
                if(event.type==KeyEventType.KeyDown&&event.key==Key.Escape&&draft.composition==null&&detail==null&&ai==null) {
                    onClose();true
                }else false
            }) {
                Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    // One scroll owner includes header/input/list: fitting to a very short
                    // work area cannot clip tasks or the input. Footer actions stay reachable.
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text("随手记",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                            Text(day.date.format(DateTimeFormatter.ofPattern("M月d日 · EEEE",Locale.SIMPLIFIED_CHINESE)),style=MaterialTheme.typography.bodySmall,color=StepMuted)
                        }
                        Text(if(selected.isEmpty())"把想法先放下来"else"今天 ${selected.size} 件 · 已完成 $done 件",
                            style=MaterialTheme.typography.bodyMedium,color=StepMuted,modifier=Modifier.testTag("capture_progress"))
                        if(selected.isNotEmpty())LinearProgressIndicator(progress={done.toFloat()/selected.size},modifier=Modifier.fillMaxWidth(),color=StepGreen,trackColor=StepLine)
                        val inputSource=remember {MutableInteractionSource()}
                        OutlinedTextField(draft,{draft=it;captured=false;work.message=null},
                            modifier=Modifier.fillMaxWidth().focusRequester(focus).testTag("capture_input")
                                .semantics {text=AnnotatedString("此刻想到什么？");contentDescription="随手记输入框"}
                                .stepInputSurface(inputSource,initialized)
                                .onPreviewKeyEvent {event->
                                    if(event.type==KeyEventType.KeyDown&&event.key==Key.Enter&&
                                        !event.isAltPressed&&!event.isMetaPressed&&!event.isShiftPressed&&draft.composition==null) {
                                        save();true
                                    }else false
                                },
                            singleLine=true,readOnly=work.busy,enabled=initialized,
                            placeholder={Text("此刻想到什么？")},textStyle=MaterialTheme.typography.bodyLarge,
                            shape=MaterialTheme.shapes.small,interactionSource=inputSource)
                        Text(when {work.failed->"未保存，输入已保留";captured->"已记下 · 待整理";else->"回车记下 · Ctrl + Enter 也可以 · Esc 关闭"},
                            style=MaterialTheme.typography.bodySmall,color=if(work.failed)MaterialTheme.colorScheme.error else if(captured)StepGreen else StepMuted,
                            modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                        if(work.busy)LinearProgressIndicator(Modifier.fillMaxWidth().testTag("ui_saving"))
                        if(work.failed)work.message?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                        if(!initialized)Text("正在读取本机内容",color=StepMuted)
                        else {
                            HorizontalDivider(color=StepLine)
                            StepTextButton({expanded=!expanded},modifier=Modifier.fillMaxWidth().testTag("capture_today_toggle"),contentPadding=PaddingValues(0.dp)) {
                                Text("今日任务 · ${selected.size}"+(if(running.any {run->selected.none {it.id==run.id}})" · 含进行中"else""),modifier=Modifier.weight(1f))
                                StepIcon(if(expanded)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,if(expanded)"收起今日任务"else"展开今日任务")
                            }
                            if(expanded) {
                                if(tasks.isEmpty())Text("今天还没有任务，去整理选一件。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
                                tasks.forEach {task->key(task.id) {
                                    CaptureTaskRow(runtime,task,execution.runFor(task.id),execution.focus?.taskId==task.id) {detail=task.id to day.planId}
                                }}
                            }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        StepTextButton(onClose){Text("取消")}
                        StepTextButton({onClose();onOrganize()},modifier=Modifier.testTag("capture_organize")){Text("整理")}
                        StepButton(save,modifier=Modifier.testTag("quick_capture_save"),enabled=initialized&&draft.text.isNotBlank()&&draft.composition==null&&!work.busy){Text("保存到收件箱")}
                    }
                }
            }
            detail?.let {(taskId,planId)->key(taskId,planId) {
                TaskEditor(runtime,taskId,planId,{detail=null},{ai=AiTarget(null,it,planId)})
            }}
            ai?.let {target->AiUi(runtime,target,{ai=null})}
        }
    }
}

@Composable private fun CaptureTaskRow(runtime:DesktopRuntime,task:Entity,run:ExecutionRun?,personal:Boolean,onDetail:()->Unit) {
    val work=rememberWork(runtime)
    val ready by runtime.executionReady.collectAsState()
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Checkbox(task.completed(),{work.complete(task.id,it)},enabled=ready&&!work.busy&&task.conflict==null&&task.conflictGroup.isEmpty(),
                modifier=Modifier.testTag("capture_complete_${task.id}").semantics {contentDescription=(if(task.completed())"恢复任务："else"完成任务：")+task.title()})
            Text(task.title(),style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis,
                textDecoration=if(task.completed())TextDecoration.LineThrough else null,color=if(task.completed())StepMuted else MaterialTheme.colorScheme.onSurface,
                modifier=Modifier.weight(1f).clickable(onClick=onDetail))
            IconButton(onDetail,Modifier.testTag("capture_detail_${task.id}")) {StepIcon(Icons.Outlined.MoreHoriz,"任务详情")}
        }
        if(run!=null)Text("${if(personal)"个人专注"else"并行进行"} · ${if(run.due)"已到点"else if(run.paused)"已暂停"else"进行中"}",
            color=StepGreen,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(start=12.dp))
        val next=task.content().str("nextAction")
        if(next.isNotBlank()&&!task.completed())Text("下一步：$next",style=MaterialTheme.typography.bodySmall,color=StepMuted,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(horizontal=12.dp))
        QuickTaskActions(runtime,task,prefix="capture",compact=true)
        WorkFeedback(work)
    }
}
