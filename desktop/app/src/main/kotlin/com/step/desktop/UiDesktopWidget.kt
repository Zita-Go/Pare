package com.step.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pare.app.*
import java.time.Instant
import kotlinx.coroutines.delay

@Composable fun DesktopWidgetUi(runtime:DesktopRuntime,onOpenTask:(String)->Unit,onCapture:()->Unit) {
    DesktopTheme {
        QuickActionHost(runtime) {
        val state by runtime.engine.state.collectAsState()
        val initialized by runtime.engine.initialized.collectAsState()
        val execution by runtime.execution.state.collectAsState()
        var now by remember{mutableStateOf(Instant.now())}
        var moment by remember{mutableStateOf(runtime.now())}
        LaunchedEffect(Unit){while(true){delay(1_000);now=Instant.now();moment=runtime.now()}}
        val day=TimelineDay.today(state,now)
        val overview=TodayOverviewProjection.project(state,day)
        val tasks=(overview.scheduled+overview.unscheduled).map {it.task}.distinctBy {it.id}
        val currentRun=execution.focus?:execution.external.firstOrNull()
        val runningTask=currentRun?.let {state.items["task:${it.taskId}"]}?.takeUnless {it.deleted||it.completed()}
        val focus=runningTask?:tasks.firstOrNull {planWin(state,day.planId,it.id)}?:tasks.firstOrNull()
        Column(Modifier.fillMaxSize().stepWindowBackground().padding(16.dp).testTag("widget_root"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row {Text("Step · 今天",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));StepTextButton(onCapture){IconLabel(Icons.Outlined.Add,"记下")}}
            if(!initialized)Text("正在读取本机内容",color=StepMuted)
            else if(focus==null)EmptyState("今天还留有空间","打开Step选出今天要推进的行动。")
            else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(if(runningTask!=null)"${if(execution.focus!=null)"个人专注"else"外部等待"} · ${if(currentRun!!.due)"已到点"else if(currentRun.paused)"已暂停"else"进行中"}"else if(planWin(state,day.planId,focus.id))"今日重点"else"下一件行动",style=MaterialTheme.typography.labelMedium,color=StepGold)
                Text(focus.title(),style=MaterialTheme.typography.titleLarge,modifier=Modifier.clickable {onOpenTask(focus.id)},maxLines=3,overflow=TextOverflow.Ellipsis)
                val next=focus.content().str("nextAction")
                Text(next.ifBlank {"先写一个能够开始的小动作"},style=MaterialTheme.typography.bodyMedium,color=StepMuted)
                if(runningTask!=null)Text("${currentRun!!.elapsedMillis(moment)/60_000} 分钟",style=MaterialTheme.typography.titleLarge)
                QuickTaskActions(runtime,focus,prefix="widget",compact=true)
                StepTextButton({onOpenTask(focus.id)}){Text("打开完整任务")}
            }
        }
        }
    }
}
