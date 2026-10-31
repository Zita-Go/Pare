package com.pare.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.*

data class TodayOverviewEntry(val task:Entity,val blocks:List<TimelineBlock>)
data class TodayOverviewProjection(val scheduled:List<TodayOverviewEntry>,val unscheduled:List<TodayOverviewEntry>,val completed:List<TodayOverviewEntry>) {
    companion object {
        fun project(state:SyncState,day:TimelineDay):TodayOverviewProjection {
            val timeline=TimelineProjection.project(state,day)
            val grouped=timeline.blocks.groupBy {it.task.id}
            val scheduled=grouped.values.map {TodayOverviewEntry(it.first().task,it)}
            val ordered=(state.items["day_plan:${day.planId}"]?.takeUnless {it.deleted}?.content()?.get("ordered_task_ids") as? JsonArray).orEmpty()
                .mapNotNull {(it as? JsonPrimitive)?.contentOrNull}.distinct()
            val selected=ordered.mapNotNull {state.items["task:$it"]}.filter {
                it.kind=="task" && !it.deleted && it.content()!=null && it.payload?.timelineString("lifecycle")!="archived"
            }
            val done=(scheduled+selected.filter {it.id !in grouped}.map {TodayOverviewEntry(it,emptyList())})
                .filter {it.task.completed()}.distinctBy {it.task.id}
            return TodayOverviewProjection(scheduled.filterNot {it.task.completed()},
                timeline.unscheduled.map {TodayOverviewEntry(it,emptyList())},done)
        }
    }
}

@Composable
internal fun TodayDateHeader(day:TimelineDay,now:Instant,onSelectDate:(LocalDate)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        TextButton(onClick={onSelectDate(day.date.minusDays(1))},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("timeline_previous_day").semantics {contentDescription="前一天"}) {Text("‹",style=MaterialTheme.typography.headlineSmall)}
        Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
            Text(day.date.format(DateTimeFormatter.ofPattern("M月d日 EEEE",Locale.SIMPLIFIED_CHINESE)),style=MaterialTheme.typography.titleMedium)
            Text(day.zone.id,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick={onSelectDate(day.date.plusDays(1))},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("timeline_next_day").semantics {contentDescription="后一天"}) {Text("›",style=MaterialTheme.typography.headlineSmall)}
        if(day.date!=now.atZone(day.zone).toLocalDate())TextButton(onClick={onSelectDate(now.atZone(day.zone).toLocalDate())},modifier=Modifier.heightIn(min=48.dp).testTag("timeline_today")) {Text("今天")}
    }
}

/** An agenda uses the exact same dated occurrences as the timeline, without empty hour space. */
@Composable
fun TodayOverview(state:SyncState,selectedDate:LocalDate,now:Instant,onSelectDate:(LocalDate)->Unit,
    onOpenTask:(Entity)->Unit,onComplete:(Entity)->Unit,onScheduleTask:(Entity,TimelineDay)->Unit,
    onEditSchedule:(TimelineBlock)->Unit,onExecution:(Entity)->Unit,executionLabel:(String)->String?,executionPaused:(String)->Boolean,
    modifier:Modifier=Modifier) {
    val day=remember(state,selectedDate) {TimelineDay.from(state,selectedDate)}
    val projection=remember(state,day) {TodayOverviewProjection.project(state,day)}
    val win=state.items["day_plan:${day.planId}"]?.takeUnless {it.deleted}?.content()?.timelineString("daily_win_task_id")
    var completedExpanded by rememberSaveable(day.planId) {mutableStateOf(false)}
    val remaining=projection.scheduled.size+projection.unscheduled.size
    Column(modifier.fillMaxSize().testTag("today_overview")) {
        TodayDateHeader(day,now,onSelectDate)
        Text("待办 $remaining 件 · 已完成 ${projection.completed.size} 件",style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp).testTag("today_overview_summary"))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("today_overview_list"),contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=96.dp)) {
            if(projection.scheduled.isNotEmpty())item {OverviewSection("已安排时间",projection.scheduled.size)}
            items(projection.scheduled,key={"scheduled:${it.task.id}"}) {entry ->
                OverviewRow(entry,day,entry.task.id==win,onOpenTask,onComplete,onScheduleTask,onEditSchedule,onExecution,executionLabel(entry.task.id),executionPaused(entry.task.id))
            }
            if(projection.unscheduled.isNotEmpty())item {OverviewSection("未定时间",projection.unscheduled.size)}
            items(projection.unscheduled,key={"unscheduled:${it.task.id}"}) {entry ->
                OverviewRow(entry,day,entry.task.id==win,onOpenTask,onComplete,onScheduleTask,onEditSchedule,onExecution,executionLabel(entry.task.id),executionPaused(entry.task.id))
            }
            if(remaining==0)item {
                EmptyNote(if(projection.completed.isEmpty()) "这一天还没有任务" else "这一天的任务已完成",
                    if(projection.completed.isEmpty()) "点下方添加任务；想安排具体时刻时，切到时间轴。" else "已完成的内容保留在下方，可以随时回看。")
            }
            if(projection.completed.isNotEmpty()) {
                item {TextButton(onClick={completedExpanded=!completedExpanded},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("today_completed_toggle")) {Text("已完成 ${projection.completed.size} 件 · ${if(completedExpanded) "收起" else "展开"}")}}
                if(completedExpanded)items(projection.completed,key={"completed:${it.task.id}"}) {entry ->
                    OverviewRow(entry,day,false,onOpenTask,onComplete,onScheduleTask,onEditSchedule,onExecution,null,false)
                }
            }
        }
    }
}

@Composable private fun OverviewSection(title:String,count:Int) {
    Text("$title · $count",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
        modifier=Modifier.padding(start=8.dp,top=10.dp,bottom=4.dp))
}

@Composable private fun OverviewRow(entry:TodayOverviewEntry,day:TimelineDay,win:Boolean,
    onOpen:(Entity)->Unit,onComplete:(Entity)->Unit,onSchedule:(Entity,TimelineDay)->Unit,
    onEditSchedule:(TimelineBlock)->Unit,onExecution:(Entity)->Unit,executionLabel:String?,paused:Boolean) {
    val task=entry.task
    val completed=task.completed()
    val clock=remember {DateTimeFormatter.ofPattern("HH:mm")}
    var chooseOccurrence by remember {mutableStateOf(false)}
    fun timeLabel(block:TimelineBlock):String {
        val start=if(block.start<day.start)"前一天 ${block.start.atZone(day.zone).format(clock)}" else block.start.atZone(day.zone).format(clock)
        val end=if(block.end>=day.end)"次日 ${block.end.atZone(day.zone).format(clock)}" else block.end.atZone(day.zone).format(clock)
        return "$start–$end · ${block.durationMinutes}分"
    }
    Surface(color=if(win)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().padding(vertical=3.dp).testTag("overview_task_${task.id}")) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).toggleable(completed,role=Role.Checkbox,onValueChange={onComplete(task)}).testTag("overview_complete_${task.id}")
                .semantics {contentDescription=(if(completed)"标为未完成：" else "完成：")+task.title()},contentAlignment=Alignment.Center) {
                Box(Modifier.size(23.dp).then(if(completed)Modifier.background(MaterialTheme.colorScheme.primary,CircleShape)
                    else Modifier.border(1.5.dp,MaterialTheme.colorScheme.outline,CircleShape)),contentAlignment=Alignment.Center) {
                    if(completed)Icon(Icons.Default.Check,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onPrimary)
                }
            }
            Column(Modifier.weight(1f).heightIn(min=64.dp).clickable {onOpen(task)}.padding(vertical=10.dp).testTag("overview_open_${task.id}"),verticalArrangement=Arrangement.Center) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    if(win) {Icon(Icons.Default.Star,"今日重点",Modifier.size(16.dp),tint=MaterialTheme.colorScheme.secondary);Spacer(Modifier.width(4.dp))}
                    Text(task.title(),style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=TextOverflow.Ellipsis,
                        textDecoration=if(completed)TextDecoration.LineThrough else null,
                        color=if(completed)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
                val times=entry.blocks.map {timeLabel(it)}
                Text(listOfNotNull(executionLabel,if(times.isEmpty())"未定时间" else times.joinToString("；")).joinToString(" · "),
                    style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(!completed)Box {
                IconButton(onClick={when(entry.blocks.size){0->onSchedule(task,day);1->onEditSchedule(entry.blocks.single());else->chooseOccurrence=true}},modifier=Modifier.size(48.dp).testTag("overview_schedule_${task.id}")) {Icon(Icons.Default.DateRange,if(entry.blocks.isEmpty())"安排时间" else "调整时间",tint=MaterialTheme.colorScheme.onSurfaceVariant)}
                DropdownMenu(expanded=chooseOccurrence,onDismissRequest={chooseOccurrence=false}) {
                    entry.blocks.forEach {block->DropdownMenuItem(text={Text(timeLabel(block))},onClick={chooseOccurrence=false;onEditSchedule(block)})}
                }
            }
            if(!completed)IconButton(onClick={onExecution(task)},modifier=Modifier.size(48.dp).testTag("overview_execution_${task.id}")) {
                val active=executionLabel in setOf("本机专注中","等待结果中")
                if(active)Row(Modifier.size(24.dp).padding(horizontal=6.dp,vertical=4.dp).semantics {contentDescription="暂停"},horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    repeat(2) {Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.primary))}
                } else Icon(if(executionLabel==null || paused)Icons.Default.PlayArrow else Icons.Default.MoreVert,
                    if(paused)"继续" else executionLabel ?: "开始专注",tint=MaterialTheme.colorScheme.primary)
            }
        }
    }
}
