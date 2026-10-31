package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.viewmodel.compose.viewModel

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
                Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp).heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(kind.title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    IconButton(onClick=onDismiss) {Icon(Icons.Default.Close,"关闭")}
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(scroll).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(initial!=PlanningAIKind.DAY) Box {
                        TextButton(onClick={selector=true},enabled=!d.busy) {Text("当前协助 · ${kind.title} ▾")}
                        DropdownMenu(selector,{selector=false}) {
                            listOf(initial,PlanningAIKind.DAY).distinct().forEach {choice->DropdownMenuItem(text={Text(choice.title)},onClick={kind=choice;selector=false})}
                        }
                    }
                    Text(when(kind) {
                        PlanningAIKind.DAY->"${planId.substringBefore('@')} · 根据有限时间与精力安排。保留已有时间块和个人 / 检查类型。"
                        PlanningAIKind.IDEA->"${state.items["idea:$subject"]?.title().orEmpty()} · 探索记录可慢慢积累，关联行动由你确认后创建。"
                        PlanningAIKind.GOAL->"${state.items["goal:$subject"]?.content().planningText("title").orEmpty()} · 为目标找到一件能开始的小行动；也可选择已有任务继续推进。"
                        PlanningAIKind.INBOX->"${state.items["inbox:$subject"]?.title().orEmpty()} · 建议归为任务或想法，采纳时保留原文。"
                    },style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(d.proposal==null && kind==PlanningAIKind.DAY) {
                        var capacityText by rememberSaveable(d) {mutableStateOf(d.options.capacity.toString())}
                        PareFormField(capacityText,{value->capacityText=value;d.change(d.options.copy(capacity=value.toIntOrNull() ?: 0))},"这一天的总投入容量（5–480 分钟）",Modifier.fillMaxWidth().testTag("planning_capacity"),enabled=editable,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("低","一般","充足").forEach {energy->FilterChip(d.options.energy==energy,{d.change(d.options.copy(energy=energy))},label={Text("精力$energy")},enabled=editable)}}
                    }
                    d.proposal?.let {p->
                        Text(p.summary,style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("planning_summary"))
                        p.actions.forEach {a->
                            Surface(color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.Top) {
                                    Checkbox(a.id in d.selected,{checked->d.selected=if(checked) d.selected+a.id else d.selected-a.id},enabled=editable,modifier=Modifier.testTag("planning_select_${a.id}"))
                                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                        Text(planningActionLabel(a,state),style=MaterialTheme.typography.titleSmall)
                                        if(a.text.isNotBlank()) Text(a.text)
                                        if(a.nextAction.isNotBlank()) Text("下一步：${a.nextAction}")
                                        if(a.stopCriterion.isNotBlank()) Text("做到这里可停：${a.stopCriterion}",style=MaterialTheme.typography.bodyMedium)
                                        Text("依据：${a.reason}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Text(when(kind) {PlanningAIKind.DAY->"按上方顺序采纳勾选任务；未勾选的已有安排保留，容量在保存前再次核对。";PlanningAIKind.IDEA->"发现和问题追加到探索记录；关联行动不自动排入今天。";PlanningAIKind.INBOX->"确认后原收件会移入建议类型；此操作不会重复创建。";PlanningAIKind.GOAL->"勾选创建项会新建任务并关联目标，不自动排入今天；已有任务只更新下一步与停止标准。"},style=MaterialTheme.typography.bodySmall)
                    }
                    if(d.status.isNotBlank()) Text(d.status,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.testTag("planning_status"))
                    if(d.undo!=null) {
                        Text("开始下一轮会保留本次采纳，并结束这次撤销入口。",style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick={model.nextPlanningRound(state,d)},enabled=!d.busy) {Text("保留采纳，开始下一轮")}
                    }
                    TextButton(onClick={scope=!scope},modifier=Modifier.testTag("planning_scope_toggle")) {Text("参考范围 · ${d.options.taskIds.size} 个任务${if(scope) " ▴" else " ▾"}")}
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
                    if(d.undo==null) OutlinedButton(onClick={model.preparePlanning(state,d)},enabled=editable && model.ready,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("planning_preview")) {Text(if(d.input==null) "预览将发送的内容" else "重新读取并预览")}
                    d.input?.let {input->
                        Text("发送给：${model.endpoint.ifBlank {"请先设置服务地址"}}",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={raw=!raw},modifier=Modifier.testTag("planning_raw_toggle")) {Text(if(raw) "收起完整发送内容" else "查看完整发送内容与指令")}
                        if(raw) SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(runCatching {PlanningAIWire.body(MobileAIConfig(model.endpoint,model.modelName),input)}.getOrDefault(input.preview),style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace,modifier=Modifier.testTag("planning_payload"))
                            Text("固定指令",style=MaterialTheme.typography.labelLarge);Text(PlanningAIWire.instructions,style=MaterialTheme.typography.bodySmall)
                        }}
                    }
                    TextButton(onClick={settings=!settings},modifier=Modifier.testTag("planning_settings_toggle")) {Text(if(settings) "收起本机服务设置" else "本机服务设置")}
                    if(settings) {
                        Text("使用已保存的兼容服务。密钥与原始请求记录仅在这台手机加密保存。",style=MaterialTheme.typography.bodySmall)
                        PareFormField(model.endpoint,{model.endpoint=it},"完整 HTTPS 接口地址",Modifier.fillMaxWidth().testTag("planning_endpoint"),enabled=!d.busy && model.ready && !model.saving,singleLine=true)
                        PareFormField(model.modelName,{model.modelName=it},"模型名称",Modifier.fillMaxWidth().testTag("planning_model"),enabled=!d.busy && model.ready && !model.saving,singleLine=true)
                        PareFormField(model.apiKey,{model.apiKey=it},"API 密钥",Modifier.fillMaxWidth().testTag("planning_key"),enabled=!d.busy && model.ready && !model.saving,singleLine=true,visualTransformation=PasswordVisualTransformation())
                        OutlinedButton(onClick=model::saveSettings,enabled=model.ready && !model.saving && !d.busy,modifier=Modifier.testTag("planning_settings_save")) {Text("保存本机设置")}
                        TextButton(onClick={confirmClear=true},enabled=!d.busy && !model.saving) {Text("清除本机 AI 设置与记录")}
                    }
                    if(model.settingsStatus.isNotBlank()) Text(model.settingsStatus,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={history=!history},modifier=Modifier.testTag("planning_history_toggle")) {Text("本机请求记录（${model.history.size}）")}
                    if(history) {
                        Text("全部流程共保留最多 12 条记录，打开、删除不会调用 AI。",style=MaterialTheme.typography.bodySmall)
                        model.history.asReversed().forEach {trace->
                            var expanded by rememberSaveable(trace.id) {mutableStateOf(false)}
                            TextButton(onClick={expanded=!expanded}) {Text("${trace.at} · ${PlanningAIKind.entries.firstOrNull {it.name==trace.kind}?.title ?: "最小下一步"} · ${if(trace.success) "已返回" else "未完成"}")}
                            if(expanded) {
                                SelectionContainer {Text("发送内容\n${trace.input}\n原始返回\n${trace.output}",style=MaterialTheme.typography.bodySmall)}
                                TextButton(onClick={model.deleteTrace(trace.id)},enabled=!d.busy && !model.saving) {Text("删除这条记录")}
                            }
                        }
                        if(model.history.isNotEmpty()) TextButton(onClick=model::clearHistory,enabled=!d.busy && !model.saving) {Text("删除全部本机请求记录")}
                    }
                }
                HorizontalDivider()
                Button(onClick={when {d.undo!=null->model.undoPlanning(d,onEdit);d.proposal!=null->model.adoptPlanning(d,onEdit);else->model.generatePlanning(state,d)}},
                    enabled=!d.busy && model.ready && !model.saving && (d.undo!=null || (d.proposal!=null && d.selected.isNotEmpty()) || (d.proposal==null && d.input!=null)),
                    modifier=Modifier.fillMaxWidth().padding(16.dp).heightIn(min=48.dp).testTag(when {d.undo!=null->"planning_undo";d.proposal!=null->"planning_adopt";else->"planning_generate"})) {
                    Text(when {d.busy->"正在处理…";d.undo!=null->"撤销本次采纳";d.proposal!=null->"确认采纳 ${d.selected.size} 项行动";else->"发送以上内容并生成"})
                }
            }
        }
    }
    if(confirmClear) AlertDialog(onDismissRequest={confirmClear=false},title={Text("清除本机 AI 资料？")},text={Text("清除服务设置、密钥和全部请求记录。已采纳的内容保留。")},confirmButton={TextButton(onClick={confirmClear=false;model.clearPrivateData()}) {Text("清除")}},dismissButton={TextButton(onClick={confirmClear=false}) {Text("取消")}})
}
@Composable private fun PlanningOption(label:String,checked:Boolean,enabled:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {Checkbox(checked,onChange,enabled=enabled);Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)}
}
private fun planningActionLabel(a:PlanningAIAction,state:SyncState):String = when(a.kind) {
    "allocate"->"${state.items["task:${a.sourceId}"]?.title().orEmpty()} · ${if(a.allocationKind=="check") "检查" else "个人"} ${a.minutes} 分钟"
    "goal_task"->"创建并关联到目标 · ${a.title} · 象限 ${a.quadrant+1} · 从 ${a.minutes} 分钟开始"
    "next_step"->"${state.items["task:${a.sourceId}"]?.title().orEmpty()} · 从 ${a.minutes} 分钟开始"
    "finding"->"追加发现";"question"->"追加探索问题";"linked_task"->"创建关联行动 · ${a.title} · 象限 ${a.quadrant+1}"
    "classify_task"->"整理为任务 · 象限 ${a.quadrant+1}";else->"整理为想法"
}
