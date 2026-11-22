package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun AIHistoryUi(runtime:DesktopRuntime,onClose:()->Unit) {
    val history by runtime.aiHistory.collectAsState()
    val context by runtime.aiContext.collectAsState()
    val work=rememberWork(runtime)
    var expanded by remember {mutableStateOf<String?>(null)}
    var deletion by remember {mutableStateOf<Set<String>?>(null)}
    UiModal("本机 AI 请求与反馈",onClose,work,width=860) {
        Text("原始请求、响应与反馈只保存在本机加密资料中，不随账号同步，也不进入内容备份。",color=StepMuted)
        if(history.isEmpty())EmptyState("还没有请求记录","每次明确点击生成后，才能在这里查看实际请求与响应。")
        else Row(verticalAlignment=Alignment.CenterVertically) {
            Text("${history.size} 条请求",modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            StepTextButton({deletion=emptySet()},enabled=!work.busy){Text("删除全部本机请求")}
        }
        history.asReversed().forEach {trace->
            StepCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {Text(trace.at,style=MaterialTheme.typography.titleMedium);Text("${trace.kind} · ${if(trace.success)"已收到建议"else"请求失败"}",style=MaterialTheme.typography.bodySmall,color=StepMuted)}
                    StepTextButton({expanded=if(expanded==trace.id)null else trace.id}){Text(if(expanded==trace.id)"收起"else"查看原始内容")}
                    StepTextButton({deletion=setOf(trace.id)},enabled=!work.busy,modifier=Modifier.testTag("delete_trace_${trace.id}")){Text("删除")}
                }
                if(expanded==trace.id) {
                    Text("原始请求",style=MaterialTheme.typography.titleMedium)
                    SelectionContainer {Text(trace.input,style=MaterialTheme.typography.bodySmall)}
                    HorizontalDivider(color=StepLine)
                    Text("原始响应",style=MaterialTheme.typography.titleMedium)
                    SelectionContainer {Text(trace.output,style=MaterialTheme.typography.bodySmall)}
                }
            }}
        }
        if(context.feedback.isNotEmpty()) {
            HorizontalDivider(color=StepLine);SectionLabel("你记录的建议反馈","只有下次明确选择参考这些反馈时，才会进入模型请求。")
            context.feedback.asReversed().forEach {feedback->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {Text(feedbackLabel(feedback.kind));Text(feedback.at,style=MaterialTheme.typography.bodySmall,color=StepMuted)}
                    StepTextButton({work.run {runtime.deleteAIFeedback(feedback.id)}},enabled=!work.busy){Text("删除反馈")}
                }
            }
        }
    }
    deletion?.let {ids->AlertDialog(onDismissRequest={if(!work.busy)deletion=null},title={Text(if(ids.isEmpty())"删除全部本机请求？"else"删除这条本机请求？")},text={Column {Text("删除后无法恢复原始输入与输出。任务、已采纳的下一步和单独保存的反馈会保留。");if(work.failed)WorkFeedback(work)}},confirmButton={StepButton({work.run({deletion=null;expanded=null;work.message="本机请求记录已删除"}) {if(ids.isEmpty())runtime.clearAIHistory()else runtime.deleteAIHistory(ids)}},enabled=!work.busy){Text("确认删除")}},dismissButton={StepTextButton({deletion=null},enabled=!work.busy){Text("取消")}})}
}
