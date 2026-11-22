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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable fun MobileAIDialog(
    state:SyncState,
    taskId:String,
    onEdit:((SyncState)->SyncState,(Result<Unit>)->Unit)->Unit,
    onDismiss:()->Unit,
    model:MobileAIModel=viewModel()
) {
    val draft=model.draft(taskId)
    var settings by rememberSaveable {mutableStateOf(false)}
    var history by rememberSaveable {mutableStateOf(false)}
    var showScope by rememberSaveable(taskId) {mutableStateOf(false)}
    var showRaw by rememberSaveable(taskId) {mutableStateOf(false)}
    var showInstructions by rememberSaveable {mutableStateOf(false)}
    var confirmClear by remember {mutableStateOf(false)}
    val scroll=rememberScrollState()
    LaunchedEffect(model.saving,model.settingsStatus) {
        if(!model.saving && model.settingsStatus=="已加密保存在这台手机") settings=false
    }
    LaunchedEffect(draft.input?.id) {showRaw=false;showInstructions=false}
    LaunchedEffect(draft.proposal?.requestId) {
        if(draft.proposal!=null) {
            settings=false;showScope=false;showRaw=false;showInstructions=false;history=false
            scroll.scrollTo(0)
        }
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize().testTag("ai_dialog"),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                ParePageHeader(title="找一个可开始的下一步",onBack=onDismiss)
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f).verticalScroll(scroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    AITargetContext("当前任务",draft.input?.taskContent.planningText("text") ?: state.items["task:$taskId"]?.title() ?: "任务不可用",
                        PareIcons.Note,Modifier.testTag("ai_task_context"))
                    draft.proposal?.let {p ->
                        var fullReason by rememberSaveable(p.requestId) {mutableStateOf(false)}
                        Surface(color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().testTag("ai_proposal_card")) {
                            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    AIProposalIdentity()
                                    AITimeLabel("先投入 ${p.minutes} 分钟",Modifier.testTag("ai_minutes"))
                                }
                                SelectionContainer { Text(p.nextAction,style=MaterialTheme.typography.titleLarge,modifier=Modifier.testTag("ai_proposal")) }
                                AIStopCriterion(p.stopCriterion,Modifier.testTag("ai_stop"))
                                AISectionRow(title="建议依据",expanded=fullReason,onClick={fullReason=!fullReason},
                                    modifier=Modifier.testTag("ai_reason_toggle"),icon=PareIcons.Note)
                                if(fullReason) SelectionContainer {
                                    Text("参考依据：${p.reason}",style=MaterialTheme.typography.bodyLarge,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("ai_reason"))
                                }

                            }
                        }
                        Text("采纳仅更新下一步和停止标准，保留今日投入和总预估。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    } ?: Text("先预览本次参考内容，再手动生成一个可开始的建议。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    // The proposal and explicit adoption button already communicate this transient state.
                    // Saving, undo, and error messages remain visible even while a proposal is present.
                    if(draft.status.isNotBlank() && !(draft.proposal!=null && draft.status=="建议已生成，确认后才更新下一步"))
                        Text(draft.status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("ai_status"))
                    if(draft.undo!=null) {
                        Text("这里只保留最近一次采纳的撤销入口。继续下一轮后，上一次采纳会保留，无法再从这里撤销。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick={model.beginNextRound(state,taskId)},enabled=!draft.busy,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("ai_next_round")) {
                            Text("保留本次采纳，继续建议")
                        }
                    }
                    val scopeSummary=listOfNotNull("当前任务",if(draft.options.ideas) "关联想法" else null,if(draft.options.goals) "目标" else null,
                        if(draft.options.preferences) "偏好" else null,if(draft.options.history) "历史" else null).joinToString("、")
                    AISupportPanel {
                        AISectionRow(
                            title="参考范围",expanded=showScope,icon=PareIcons.Link,
                            onClick={showScope=!showScope},modifier=Modifier.testTag("ai_scope_toggle"),
                            supporting={Text(scopeSummary,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        )
                        val editable=!draft.busy && draft.proposal==null && draft.undo==null
                        if(showScope) Column(Modifier.testTag("ai_context_options"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("默认只含当前任务的标题、说明、四象限与下一步。",style=MaterialTheme.typography.bodySmall)
                            MobileAIOption("关联想法的背景与问题",draft.options.ideas,editable) {model.setOptions(taskId,draft.options.copy(ideas=it))}
                            MobileAIOption("与当前任务关联的长期目标",draft.options.goals,editable) {model.setOptions(taskId,draft.options.copy(goals=it))}
                            MobileAIOption("已声明及确认的相关偏好",draft.options.preferences,editable) {model.setOptions(taskId,draft.options.copy(preferences=it))}
                            MobileAIOption("当前任务历史、勾选想法的记录及最近 3 次本任务 AI 输出",draft.options.history,editable) {model.setOptions(taskId,draft.options.copy(history=it))}
                        }
                        if(draft.undo==null) OutlinedButton(onClick={model.prepare(state,taskId)},enabled=!draft.busy && model.ready,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("ai_preview")) {
                            Text(if(draft.input==null) "预览将发送的内容" else "重新读取并预览")
                        }
                        draft.input?.let {input ->
                            Text(if(model.endpoint.isBlank() || model.modelName.isBlank()) "请先设置本机服务与模型" else "使用本机已配置的服务与模型",style=MaterialTheme.typography.bodySmall)
                            AISectionRow(
                                title="完整发送内容",
                                expanded=showRaw,onClick={showRaw=!showRaw},modifier=Modifier.testTag("ai_full_context_toggle")
                            )
                            if(showRaw) {
                                Text("完整任务上下文，最多 32 KB。",style=MaterialTheme.typography.bodySmall)
                                Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium) {
                                    SelectionContainer {Text(input.preview,Modifier.fillMaxWidth().padding(12.dp).testTag("ai_context_preview"),style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace)}
                                }
                                TextButton(onClick={showInstructions=!showInstructions},shape=MaterialTheme.shapes.medium,modifier=Modifier.heightIn(min=48.dp)) {Text(if(showInstructions) "收起固定指令" else "查看同时发送的固定指令")}
                                if(showInstructions) Text(MobileAIClient.instructions,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        AISectionRow(
                            title="本机服务设置",icon=PareIcons.More,
                            expanded=settings,onClick={settings=!settings},modifier=Modifier.testTag("ai_settings_toggle"),
                            supporting={
                                if(model.settingsStatus.isNotBlank()) Text(model.settingsStatus,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("ai_settings_status"))
                            }
                        )
                        if(settings) {
                            Text("连接你选择的兼容服务",style=MaterialTheme.typography.titleSmall)
                            Text("填写完整的 HTTPS Chat Completions 接口地址。密钥和原始请求记录只加密保存在这台手机。",style=MaterialTheme.typography.bodySmall)
                            PareFormField(model.endpoint,{model.endpoint=it},"HTTPS 接口地址",Modifier.fillMaxWidth().testTag("ai_endpoint"),enabled=!draft.busy && model.ready && !model.saving,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
                            PareFormField(model.modelName,{model.modelName=it},"模型名称",Modifier.fillMaxWidth().testTag("ai_model"),enabled=!draft.busy && model.ready && !model.saving,singleLine=true)
                            PareFormField(model.apiKey,{model.apiKey=it},"API 密钥",Modifier.fillMaxWidth().testTag("ai_key"),enabled=!draft.busy && model.ready && !model.saving,singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
                            OutlinedButton(onClick=model::saveSettings,enabled=model.ready && !model.saving && !draft.busy,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("ai_settings_save")) {Text("保存本机设置")}
                            TextButton(onClick={confirmClear=true},enabled=!draft.busy && !model.saving,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),shape=MaterialTheme.shapes.medium,modifier=Modifier.heightIn(min=48.dp)) {Text("清除本机 AI 设置与记录")}
                        }
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        AISectionRow(
                            title="本机请求记录（${model.history.count {it.taskId==taskId}}）",icon=PareIcons.Clock,
                            expanded=history,onClick={history=!history},modifier=Modifier.testTag("ai_history_toggle")
                        )
                        if(history) {
                            Text("记录仅保存在这台手机，最多 12 条并限制总大小。打开或删除记录不会调用 AI。",style=MaterialTheme.typography.bodySmall)
                            model.history.filter {it.taskId==taskId}.asReversed().forEach {trace ->
                                var expanded by rememberSaveable(trace.id) {mutableStateOf(false)}
                                AISectionRow(title="${trace.at} · ${if(trace.success) "收到建议" else "请求未完成"}",expanded=expanded,
                                    onClick={expanded=!expanded},modifier=Modifier.testTag("ai_trace_${trace.id}"))
                                if(expanded) {
                                    SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Text("发送内容",style=MaterialTheme.typography.labelLarge);Text(trace.input,style=MaterialTheme.typography.bodySmall)
                                        Text("原始返回",style=MaterialTheme.typography.labelLarge);Text(trace.output.ifBlank {"服务未返回内容"},style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("ai_trace_output_${trace.id}"))
                                    }}
                                    TextButton(onClick={model.deleteTrace(trace.id)},enabled=!draft.busy && !model.saving,shape=MaterialTheme.shapes.medium,modifier=Modifier.heightIn(min=48.dp).testTag("ai_trace_delete_${trace.id}")) {Text("删除这条记录")}
                                }
                            }
                            if(model.history.isNotEmpty()) TextButton(onClick=model::clearHistory,enabled=!draft.busy && !model.saving,shape=MaterialTheme.shapes.medium,modifier=Modifier.heightIn(min=48.dp)) {Text("删除本机所有 AI 请求记录")}
                        }
                    }
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    when {
                        draft.undo!=null -> OutlinedButton(onClick={model.undo(taskId,onEdit)},enabled=!draft.busy,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("ai_undo")) {Text("撤销本次采纳")}
                        draft.proposal!=null -> Button(onClick={model.adopt(taskId,onEdit)},enabled=!draft.busy,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("ai_adopt")) {Text("采纳为最小下一步")}
                        else -> Button(onClick={model.generate(state,taskId)},enabled=draft.input!=null && !draft.busy && model.ready && !model.saving,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("ai_generate")) {
                            if(draft.busy) {CircularProgressIndicator(Modifier.size(18.dp),color=MaterialTheme.colorScheme.onPrimary,strokeWidth=2.dp);Spacer(Modifier.width(8.dp))}
                            Text(if(draft.busy) "正在生成" else "发送以上内容并生成")
                        }
                    }
                }
            }
        }
    }
    if(confirmClear) AlertDialog(onDismissRequest={confirmClear=false},title={Text("清除本机 AI 资料？")},text={Text("删除这台手机保存的服务地址、密钥和所有原始请求记录。已采纳的任务内容保留。")},
        confirmButton={TextButton(modifier=Modifier.heightIn(min=48.dp),onClick={confirmClear=false;model.clearPrivateData()}) {Text("清除")}},dismissButton={TextButton(modifier=Modifier.heightIn(min=48.dp),onClick={confirmClear=false}) {Text("取消")}})
}

@Composable private fun MobileAIOption(label:String,checked:Boolean,enabled:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked,onChange,enabled=enabled);Text(label,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f))
    }
}

@Composable internal fun AISupportPanel(content:@Composable ColumnScope.()->Unit) {
    Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
    }
}

@Composable internal fun AISectionRow(
    title:String,expanded:Boolean,onClick:()->Unit,modifier:Modifier=Modifier,
    icon:Int?=null,
    supporting:(@Composable ()->Unit)?=null
) {
    TextButton(
        onClick=onClick,shape=MaterialTheme.shapes.medium,
        colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSurface),
        contentPadding=PaddingValues(vertical=10.dp),modifier=modifier.fillMaxWidth().heightIn(min=48.dp)
            .semantics {stateDescription=if(expanded) "已展开" else "已收起"}
    ) {
        if(icon!=null) {
            PareIcon(icon,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.labelLarge)
            supporting?.invoke()
        }
        Spacer(Modifier.width(8.dp))
        PareIcon(if(expanded) PareIcons.Up else PareIcons.Down,null,modifier=Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun AIProposalIdentity() {
    Row(Modifier.heightIn(min=28.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        PareIcon(PareIcons.Sparkle,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.primary)
        Text("AI 建议",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
    }
}

@Composable internal fun AITargetContext(label:String,value:String,icon:Int,modifier:Modifier=Modifier) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants=true) {},verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        PareIcon(icon,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$label · $value",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun AITimeLabel(text:String,modifier:Modifier=Modifier) {
    Row(modifier.heightIn(min=28.dp).semantics(mergeDescendants=true) {},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        PareIcon(PareIcons.Clock,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun AIStopCriterion(text:String,modifier:Modifier=Modifier) {
    Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                PareIcon(PareIcons.Check,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("做到这里就可以停",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectionContainer {Text(text,modifier=modifier,style=MaterialTheme.typography.bodyLarge)}
        }
    }
}
