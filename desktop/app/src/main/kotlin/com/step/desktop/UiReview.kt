package com.step.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pare.app.*
import java.time.Instant
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable internal fun ColumnScope.ReviewPane(runtime:DesktopRuntime,onTask:(String)->Unit) {
    val state by runtime.engine.state.collectAsState()
    val now=remember(state){Instant.now()}
    val recent=remember(state,now){MobileReview.calculate(state,now)}
    val trends=remember(state,now){ReviewAnalytics.calculate(state,now)}
    val work=rememberWork(runtime)
    var export by remember {mutableStateOf<String?>(null)}
    Row(verticalAlignment=Alignment.CenterVertically) {
        Text("用已有记录回看，不用完成数量评价自己。",color=StepMuted,modifier=Modifier.weight(1f))
        StepOutlinedButton({export=ReviewAnalytics.export(state,Instant.now())},modifier=Modifier.testTag("review_export")){Text("导出汇总")}
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=16.dp)) {
        item {StepCard(Modifier.fillMaxWidth(),emphasis=true){Row(Modifier.padding(20.dp),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
            Column{Text("${recent.completedTasks.size} 件",style=MaterialTheme.typography.titleLarge);Text("近7天完成",color=StepMuted)}
            Column{Text("${recent.focusMinutes} 分钟",style=MaterialTheme.typography.titleLarge);Text("专注记录",color=StepMuted)}
            Column{Text("${recent.externalMinutes} 分钟",style=MaterialTheme.typography.titleLarge);Text("等待结果",color=StepMuted)}
        }}}
        item {StepCard(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            SectionLabel("最近8周","本周截至现在 · ${trends.zone} · 手工校正用于估时对比")
            val max=trends.weeks.maxOfOrNull {it.focusMinutes}?.coerceAtLeast(1)?:1
            Row(Modifier.fillMaxWidth().height(150.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                trends.weeks.forEach {week->Column(Modifier.weight(1f).fillMaxHeight(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Bottom) {
                    Text("${week.focusMinutes}分",style=MaterialTheme.typography.bodySmall,color=StepMuted)
                    Box(Modifier.padding(vertical=5.dp).fillMaxWidth().height((90f*week.focusMinutes/max).coerceAtLeast(3f).dp).background(if(week.current)StepGold else StepGreen,MaterialTheme.shapes.extraSmall))
                    Text("${week.start.monthValue}/${week.start.dayOfMonth}",style=MaterialTheme.typography.bodySmall)
                    Text("${week.completed} 件",style=MaterialTheme.typography.bodySmall,color=StepMuted)
                }}
            }
            if(!trends.hasRecords)Text("还没有足够记录；完成一步或记录一次投入后，就能逐渐看到自己的节奏。",color=StepMuted)
        }}}
        item {StepCard(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            SectionLabel("估时校准")
            val c=trends.calibration
            if(c==null)Text("需要同时记录预计与实际时长的已完成任务，目前不作推算。",color=StepMuted)
            else Text("${c.pairs} 件完整样本 · 预计 ${c.estimatedMinutes} 分钟 · 实际 ${c.actualMinutes} 分钟\n实际约为预计的 ${String.format(java.util.Locale.ROOT,"%.2f",c.ratio)} 倍。")
            Text("最近8周完成分布："+trends.quadrantCompletions.entries.joinToString(" · "){"象限${it.key+1} ${it.value}件"},style=MaterialTheme.typography.bodySmall,color=StepMuted)
        }}}
        item {SectionLabel("最近完成的任务")}
        if(recent.completedTasks.isEmpty())item {Text("近7天没有完成记录，资料库仍可查看更早的任务。",color=StepMuted)}
        recent.completedTasks.forEach {task->item(key=task.key) {StepCard(Modifier.fillMaxWidth(),onClick={onTask(task.id)}){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Text(task.title(),modifier=Modifier.weight(1f));Text("查看步骤与记录",style=MaterialTheme.typography.bodySmall,color=StepMuted)}}}}
        if(recent.blockerCounts.isNotEmpty())item {SectionLabel("最近遇到的阻力");Text(recent.blockerCounts.entries.joinToString(" · "){"${mapOf("unclear" to "不清楚下一步","tooLarge" to "任务太大","lowEnergy" to "精力不足","resistance" to "有些抗拒")[it.key]} ${it.value}次"},color=StepMuted)}
        val stalled=recent.attention.filter {it.kind=="task"}
        if(stalled.isNotEmpty())item {SectionLabel("值得重新看一眼")}
        stalled.take(8).forEach {a->item(key="attention-${a.key}"){StepCard(Modifier.fillMaxWidth(),onClick={onTask(a.id)}){Column(Modifier.padding(16.dp)){Text(a.title);Text(a.reason,style=MaterialTheme.typography.bodySmall,color=StepMuted)}}}}
    }
    export?.let {value->UiModal("回顾汇总",{export=null},work) {
        Text("仅含统计，不包含任务标题、账号凭据或 AI 原始历史。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
        SelectionContainer {Text(value,modifier=Modifier.testTag("review_export_content"))}
        StepButton({work.run {runCatching {Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value),null);Unit}}}) {Text("复制汇总")}
    }}
}
