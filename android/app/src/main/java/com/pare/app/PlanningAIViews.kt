package com.pare.app

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PlanningAIDialog(state:SyncState,planId:String,onEdit:((SyncState)->SyncState,(Result<Unit>)->Unit)->Unit,onDismiss:()->Unit,
    ideaId:String?=null,goalId:String?=null,inboxId:String?=null,model:MobileAIModel=viewModel()) {
    val initial=when {ideaId!=null->PlanningAIKind.IDEA;goalId!=null->PlanningAIKind.GOAL;inboxId!=null->PlanningAIKind.INBOX;else->PlanningAIKind.DAY}
    var kind by rememberSaveable(initial) {mutableStateOf(initial)}
    val subject=when(kind) {PlanningAIKind.IDEA->ideaId;PlanningAIKind.GOAL->goalId;PlanningAIKind.INBOX->inboxId;else->null}
    val d=model.planningDraft(state,kind,subject,planId)
    var selector by remember {mutableStateOf(false)}
    var scope by rememberSaveable {mutableStateOf(false)}
    var raw by rememberSaveable {mutableStateOf(false)}
    var settings by rememberSaveable {mutableStateOf(false)}
    var history by rememberSaveable {mutableStateOf(false)}
    var confirmClear by remember {mutableStateOf(false)}
    val editable=!d.busy && d.undo==null
    val scroll=rememberScrollState()
    LaunchedEffect(d.proposal?.requestId) {if(d.proposal!=null) {scope=false;raw=false;settings=false;history=false;scroll.scrollTo(0)}}
    LaunchedEffect(model.settingsStatus) {if(model.settingsStatus=="已加密保存在这台手机") settings=false}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize().testTag("planning_ai_dialog"),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                ParePageHeader(title=kind.title,onBack=onDismiss)
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(scroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    val targetLabel=when(kind) {
                        PlanningAIKind.DAY->"安排日期";PlanningAIKind.IDEA->"当前想法";PlanningAIKind.GOAL->"当前目标";PlanningAIKind.INBOX->"当前收件"
                    }
                    val targetIcon=when(kind) {
                        PlanningAIKind.DAY->PareIcons.Calendar;PlanningAIKind.IDEA->PareIcons.Idea;PlanningAIKind.GOAL->PareIcons.Decision;PlanningAIKind.INBOX->PareIcons.Note
                    }
                    AITargetContext(targetLabel,planningTargetTitle(d,state),targetIcon,Modifier.testTag("planning_target_context"))
                    if(initial!=PlanningAIKind.DAY) Box {
                        TextButton(modifier=Modifier.heightIn(min=48.dp),onClick={selector=true},enabled=!d.busy) {
                            Text("当前协助 · ${kind.title}",Modifier.weight(1f,false))
                            Spacer(Modifier.width(8.dp));PareIcon(PareIcons.Down,null,Modifier.size(20.dp))
                        }
                        DropdownMenu(selector,{selector=false}) {
                            listOf(initial,PlanningAIKind.DAY).distinct().forEach {choice->DropdownMenuItem(text={Text(choice.title)},onClick={kind=choice;selector=false})}
                        }
                    }
                    if(d.proposal==null) Text(when(kind) {
                        PlanningAIKind.DAY->"根据有限时间与精力安排。保留已有时间块和个人 / 检查类型。"
                        PlanningAIKind.IDEA->"探索记录可慢慢积累，关联行动由你确认后创建。"
                        PlanningAIKind.GOAL->"为目标找到一件能开始的小行动；也可选择已有任务继续推进。"
                        PlanningAIKind.INBOX->"建议归为任务或想法，采纳时保留原文。"
                    },style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(d.proposal==null && kind==PlanningAIKind.DAY) {
                        var capacityText by rememberSaveable(d) {mutableStateOf(d.options.capacity.toString())}
                        PareFormField(capacityText,{value->capacityText=value;d.change(d.options.copy(capacity=value.toIntOrNull() ?: 0))},"这一天的总投入容量（5–480 分钟）",Modifier.fillMaxWidth().testTag("planning_capacity"),enabled=editable,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("低","一般","充足").forEach {energy->FilterChip(d.options.energy==energy,{d.change(d.options.copy(energy=energy))},label={Text("精力$energy")},enabled=editable,colors=pareChoiceColors(),modifier=Modifier.heightIn(min=48.dp))}}
                    }
                    d.proposal?.let {p->
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            AIProposalIdentity()
                            Text(p.summary,style=MaterialTheme.typography.titleLarge,modifier=Modifier.testTag("planning_summary"))
                            if(d.undo==null) Text("勾选要采纳的行动，确认后才修改资料",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        p.actions.forEach {a->
                            Surface(color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    var reasonExpanded by rememberSaveable(p.requestId,a.id) {mutableStateOf(false)}
                                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                                PareIcon(planningActionIcon(a),null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.primary)
                                                Text(planningActionKind(a),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                                            }
                                            if(a.minutes>0) AITimeLabel(
                                                if(a.kind=="allocate") "${if(a.allocationKind=="check") "检查" else "个人"} ${a.minutes} 分钟" else "从 ${a.minutes} 分钟开始",
                                                Modifier.testTag("planning_time_${a.id}"))
                                        }
                                        Checkbox(a.id in d.selected,{checked->d.selected=if(checked) d.selected+a.id else d.selected-a.id},enabled=editable,
                                            modifier=Modifier.testTag("planning_select_${a.id}").semantics {contentDescription="采纳${planningActionKind(a)}：${planningActionTitle(a,state)}"})
                                    }
                                    SelectionContainer {
                                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                            Text(planningActionTitle(a,state),style=MaterialTheme.typography.titleMedium,
                                                modifier=Modifier.testTag("planning_action_${a.id}"))
                                            if(a.kind in setOf("goal_task","linked_task","classify_task","classify_idea"))
                                                Text("象限 ${a.quadrant+1}",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier=Modifier.testTag("planning_quadrant_${a.id}"))
                                            if(a.nextAction.isNotBlank()) Text("下一步：${a.nextAction}",style=MaterialTheme.typography.bodyLarge,
                                                modifier=Modifier.testTag("planning_next_${a.id}"))
                                        }
                                    }
                                    if(a.stopCriterion.isNotBlank()) AIStopCriterion(a.stopCriterion,Modifier.testTag("planning_stop_${a.id}"))
                                    AISectionRow(title="建议依据",expanded=reasonExpanded,onClick={reasonExpanded=!reasonExpanded},
                                        modifier=Modifier.testTag("planning_reason_toggle_${a.id}"),icon=PareIcons.Note)
                                    if(reasonExpanded) SelectionContainer {
                                        Text("依据：${a.reason}",style=MaterialTheme.typography.bodyLarge,
                                            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("planning_reason_${a.id}"))
                                    }
                                }
                            }
                        }
                        Text(when(kind) {PlanningAIKind.DAY->"按上方顺序采纳勾选任务；未勾选的已有安排保留，容量在保存前再次核对。";PlanningAIKind.IDEA->"发现和问题追加到探索记录；关联行动不自动排入今天。";PlanningAIKind.INBOX->"确认后原收件会移入建议类型；此操作不会重复创建。";PlanningAIKind.GOAL->"勾选创建项会新建任务并关联目标，不自动排入今天；已有任务只更新下一步与停止标准。"},style=MaterialTheme.typography.bodySmall)
                    }
                    if(d.status.isNotBlank() && !(d.proposal!=null && d.status=="勾选要采纳的行动，确认后才修改资料"))
                        Text(d.status,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.testTag("planning_status"))
                    if(d.undo!=null) {
                        Text("开始下一轮会保留本次采纳，并结束这次撤销入口。",style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(shape=MaterialTheme.shapes.medium,onClick={model.nextPlanningRound(state,d)},enabled=!d.busy,modifier=Modifier.heightIn(min=48.dp)) {Text("保留采纳，开始下一轮")}
                    }
                    AISupportPanel {
                        AISectionRow(title="参考范围",expanded=scope,onClick={scope=!scope},icon=PareIcons.Link,
                            modifier=Modifier.testTag("planning_scope_toggle"),supporting={
                                Text(planningScopeSummary(kind,d.options),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            })
                        if(scope) {
                            if(kind in setOf(PlanningAIKind.DAY,PlanningAIKind.GOAL)) {
                                Text(if(kind==PlanningAIKind.GOAL) "默认只发送当前目标。可选已有的关联任务；尚无任务时也能提议一件新行动。" else "最多选择 8 个候选任务；仅发送勾选任务的内容。",style=MaterialTheme.typography.bodySmall)
                                PlanningAIContext.eligible(state,kind,subject).forEach {task->PlanningOption(task.title(),task.id in d.options.taskIds,editable) {checked->
                                    if(!checked || d.options.taskIds.size<8) d.change(d.options.copy(taskIds=if(checked) d.options.taskIds+task.id else d.options.taskIds-task.id))
                                }}
                            }
                            if(kind!=PlanningAIKind.INBOX) PlanningOption(if(kind==PlanningAIKind.IDEA) "关联任务的内容" else "关联想法的背景",d.options.extra.ideas,editable) {d.change(d.options.copy(extra=d.options.extra.copy(ideas=it)))}
                            if(kind!=PlanningAIKind.INBOX) PlanningOption("相关目标（默认不发送）",d.options.extra.goals,editable) {d.change(d.options.copy(extra=d.options.extra.copy(goals=it)))}
                            PlanningOption("已声明和确认的相关偏好",d.options.extra.preferences,editable) {d.change(d.options.copy(extra=d.options.extra.copy(preferences=it)))}
                            PlanningOption("相关历史及本范围最近 3 次 AI 输出",d.options.extra.history,editable) {d.change(d.options.copy(extra=d.options.extra.copy(history=it)))}
                        }
                        if(d.undo==null) OutlinedButton(shape=MaterialTheme.shapes.medium,onClick={model.preparePlanning(state,d)},enabled=editable && model.ready,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("planning_preview")) {Text(if(d.input==null) "预览将发送的内容" else "重新读取并预览")}
                        d.input?.let {input->
                            Text(if(model.endpoint.isBlank() || model.modelName.isBlank()) "请先设置本机服务与模型" else "使用本机已配置的服务与模型",style=MaterialTheme.typography.bodySmall)
                            AISectionRow(title="完整发送内容与指令",expanded=raw,onClick={raw=!raw},modifier=Modifier.testTag("planning_raw_toggle"))
                            if(raw) SelectionContainer {Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(runCatching {PlanningAIWire.body(MobileAIConfig(model.endpoint,model.modelName),input)}.getOrDefault(input.preview),style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace,modifier=Modifier.testTag("planning_payload"))
                                Text("固定指令",style=MaterialTheme.typography.labelLarge);Text(PlanningAIWire.instructions,style=MaterialTheme.typography.bodySmall)
                            }}
                        }
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        AISectionRow(title="本机服务设置",expanded=settings,onClick={settings=!settings},icon=PareIcons.More,
                            modifier=Modifier.testTag("planning_settings_toggle"),supporting={
                                if(model.settingsStatus.isNotBlank()) Text(model.settingsStatus,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            })
                        if(settings) {
                            Text("使用已保存的兼容服务。密钥与原始请求记录仅在这台手机加密保存。",style=MaterialTheme.typography.bodySmall)
                            PareFormField(model.endpoint,{model.endpoint=it},"完整 HTTPS 接口地址",Modifier.fillMaxWidth().testTag("planning_endpoint"),enabled=!d.busy && model.ready && !model.saving,singleLine=true)
                            PareFormField(model.modelName,{model.modelName=it},"模型名称",Modifier.fillMaxWidth().testTag("planning_model"),enabled=!d.busy && model.ready && !model.saving,singleLine=true)
                            PareFormField(model.apiKey,{model.apiKey=it},"API 密钥",Modifier.fillMaxWidth().testTag("planning_key"),enabled=!d.busy && model.ready && !model.saving,singleLine=true,visualTransformation=PasswordVisualTransformation())
                            OutlinedButton(shape=MaterialTheme.shapes.medium,onClick=model::saveSettings,enabled=model.ready && !model.saving && !d.busy,modifier=Modifier.heightIn(min=48.dp).testTag("planning_settings_save")) {Text("保存本机设置")}
                            TextButton(modifier=Modifier.heightIn(min=48.dp),onClick={confirmClear=true},enabled=!d.busy && !model.saving) {Text("清除本机 AI 设置与记录")}
                        }
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        AISectionRow(title="本机请求记录（${model.history.size}）",expanded=history,onClick={history=!history},icon=PareIcons.Clock,
                            modifier=Modifier.testTag("planning_history_toggle"))
                        if(history) {
                            Text("全部流程共保留最多 12 条记录，打开、删除不会调用 AI。",style=MaterialTheme.typography.bodySmall)
                            model.history.asReversed().forEach {trace->
                                var expanded by rememberSaveable(trace.id) {mutableStateOf(false)}
                                AISectionRow(title="${trace.at} · ${PlanningAIKind.entries.firstOrNull {it.name==trace.kind}?.title ?: "最小下一步"} · ${if(trace.success) "已返回" else "未完成"}",
                                    expanded=expanded,onClick={expanded=!expanded})
                                if(expanded) {
                                    SelectionContainer {Text("发送内容\n${trace.input}\n原始返回\n${trace.output}",style=MaterialTheme.typography.bodySmall)}
                                    TextButton(modifier=Modifier.heightIn(min=48.dp),onClick={model.deleteTrace(trace.id)},enabled=!d.busy && !model.saving) {Text("删除这条记录")}
                                }
                            }
                            if(model.history.isNotEmpty()) TextButton(modifier=Modifier.heightIn(min=48.dp),onClick=model::clearHistory,enabled=!d.busy && !model.saving) {Text("删除全部本机请求记录")}
                        }
                    }
                }
                HorizontalDivider()
                Button(shape=MaterialTheme.shapes.medium,onClick={when {d.undo!=null->model.undoPlanning(d,onEdit);d.proposal!=null->model.adoptPlanning(d,onEdit);else->model.generatePlanning(state,d)}},
                    enabled=!d.busy && model.ready && !model.saving && (d.undo!=null || (d.proposal!=null && d.selected.isNotEmpty()) || (d.proposal==null && d.input!=null)),
                    modifier=Modifier.fillMaxWidth().padding(16.dp).heightIn(min=48.dp).testTag(when {d.undo!=null->"planning_undo";d.proposal!=null->"planning_adopt";else->"planning_generate"})) {
                    Text(when {d.busy->"正在处理…";d.undo!=null->"撤销本次采纳";d.proposal!=null->"确认采纳 ${d.selected.size} 项行动";else->"发送以上内容并生成"})
                }
            }
        }
    }
    if(confirmClear) AlertDialog(onDismissRequest={confirmClear=false},title={Text("清除本机 AI 资料？")},text={Text("清除服务设置、密钥和全部请求记录。已采纳的内容保留。")},confirmButton={TextButton(modifier=Modifier.heightIn(min=48.dp),onClick={confirmClear=false;model.clearPrivateData()}) {Text("清除")}},dismissButton={TextButton(modifier=Modifier.heightIn(min=48.dp),onClick={confirmClear=false}) {Text("取消")}})
}
@Composable private fun PlanningOption(label:String,checked:Boolean,enabled:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {Checkbox(checked,onChange,enabled=enabled);Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)}
}
private fun planningTargetTitle(draft:PlanningAIDraft,state:SyncState):String {
    if(draft.kind==PlanningAIKind.DAY) {
        val planId=draft.input?.planId ?: draft.planId
        return "${planId.substringBefore('@')} · ${planId.substringAfter('@')}"
    }
    val kind=when(draft.kind) {PlanningAIKind.IDEA->"idea";PlanningAIKind.GOAL->"goal";else->"inbox"}
    val key="$kind:${draft.subjectId}"
    val subject=draft.input?.baselines?.get(key) ?: state.items[key]
    return subject?.content().planningText(if(draft.kind==PlanningAIKind.GOAL) "title" else "text") ?: "资料不可用"
}
private fun planningActionKind(a:PlanningAIAction):String = when(a.kind) {
    "allocate"->"安排今日投入";"goal_task"->"创建并关联到目标";"next_step"->"更新最小下一步"
    "finding"->"追加发现";"question"->"追加探索问题";"linked_task"->"创建关联行动"
    "classify_task"->"整理为任务";else->"整理为想法"
}
private fun planningActionTitle(a:PlanningAIAction,state:SyncState):String = when(a.kind) {
    "allocate","next_step"->state.items["task:${a.sourceId}"]?.title().orEmpty()
    "goal_task","linked_task"->a.title
    "finding","question"->a.text
    "classify_task","classify_idea"->state.items["inbox:${a.sourceId}"]?.title().orEmpty()
    else->planningActionKind(a)
}
private fun planningActionIcon(a:PlanningAIAction):Int = when(a.kind) {
    "allocate"->PareIcons.Today;"goal_task","linked_task"->PareIcons.Link;"next_step"->PareIcons.Play
    "finding"->PareIcons.Finding;"question","classify_idea"->PareIcons.Idea;else->PareIcons.Note
}
private fun planningScopeSummary(kind:PlanningAIKind,options:PlanningAIOptions):String {
    val base=when(kind) {
        PlanningAIKind.DAY->"${options.taskIds.size} 个候选任务"
        PlanningAIKind.IDEA->"当前想法"
        PlanningAIKind.GOAL->"当前目标 · ${options.taskIds.size} 个关联任务"
        PlanningAIKind.INBOX->"当前收件"
    }
    return listOfNotNull(base,if(options.extra.ideas && kind!=PlanningAIKind.INBOX) if(kind==PlanningAIKind.IDEA) "关联任务" else "关联想法" else null,
        if(options.extra.goals && kind!=PlanningAIKind.INBOX) "相关目标" else null,if(options.extra.preferences) "偏好" else null,if(options.extra.history) "历史" else null).joinToString("、")
}
