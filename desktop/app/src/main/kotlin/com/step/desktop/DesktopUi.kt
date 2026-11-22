package com.step.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pare.app.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun DesktopUi(runtime:DesktopRuntime,onQuickCapture:()->Unit,onRequestClose:()->Unit,onDesktopOptions:()->Unit={}) {
    val state by runtime.engine.state.collectAsState()
    val initialized by runtime.engine.initialized.collectAsState()
    val syncStatus by runtime.engine.status.collectAsState()
    val error by runtime.error.collectAsState()
    val work=rememberWork(runtime)
    var page by remember {mutableStateOf("今天")}
    var editor by remember {mutableStateOf<Pair<String,String?>?>(null)}
    var settings by remember {mutableStateOf(false)}
    var ai by remember {mutableStateOf<AiTarget?>(null)}
    var picker by remember {mutableStateOf(false)}
    var now by remember {mutableStateOf(Instant.now())}
    LaunchedEffect(Unit) {while(true) {delay(30_000);now=Instant.now()}}
    val todayId=ContentActions.planId(state,now)
    var date by remember(todayId) {mutableStateOf(LocalDate.parse(todayId.substringBefore('@')))}
    val planId="$date@${todayId.substringAfter('@')}"
    DesktopTheme {
        Surface(Modifier.fillMaxSize().testTag("desktop_root"),color=MaterialTheme.colorScheme.background) {
            Row {
                Column(Modifier.width(184.dp).fillMaxHeight().background(LocalStepPalette.current.sidebar).padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Step",style=MaterialTheme.typography.headlineLarge,color=MaterialTheme.colorScheme.onSurface,modifier=Modifier.padding(start=12.dp,top=10.dp))
                    Text("让下一步变得清楚",style=MaterialTheme.typography.bodySmall,color=StepMuted,modifier=Modifier.padding(start=12.dp,bottom=24.dp))
                    listOf("今天" to Icons.Outlined.CalendarToday,"四象限" to Icons.Outlined.Dashboard,"想法" to Icons.Outlined.Lightbulb,"目标" to Icons.Outlined.Flag).forEach {(name,symbol)->
                        Surface(onClick={page=name},modifier=Modifier.testTag("nav_$name"),shape=MaterialTheme.shapes.small,color=if(page==name) LocalStepPalette.current.selection else Color.Transparent) {
                            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                StepIcon(symbol,color=if(page==name)StepGold else StepMuted);Text(name,fontWeight=FontWeight.Medium,color=if(page==name)StepGold else StepMuted)
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp));HorizontalDivider(color=StepLine)
                    StepTextButton({page="收件箱"},modifier=Modifier.fillMaxWidth()) {StepIcon(Icons.Outlined.Inbox);Spacer(Modifier.width(10.dp));Text("收件箱");Spacer(Modifier.weight(1f));Text(state.items.values.count {it.kind=="inbox" && !it.deleted}.toString())}
                    Spacer(Modifier.weight(1f))
                    Text("先选重要的事，\n再迈出小小一步。",style=MaterialTheme.typography.bodySmall,color=StepMuted,modifier=Modifier.padding(10.dp))
                    StepTextButton({settings=true}) {StepIcon(if(state.enabled)Icons.Outlined.CloudDone else Icons.Outlined.CloudOff);Spacer(Modifier.width(8.dp));Text(if(state.enabled)"同步已开启"else"本机优先")}
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("我的空间",style=MaterialTheme.typography.bodyMedium,color=StepMuted,modifier=Modifier.weight(1f))
                        StepOutlinedButton(onQuickCapture,enabled=initialized){IconLabel(Icons.Outlined.Add,"随手记")}
                        StepTextButton({ai=AiTarget(PlanningAIKind.DAY,null,planId)},enabled=initialized){IconLabel(Icons.Outlined.AutoAwesome,"AI 助手")}
                        StepTextButton({settings=true}){IconLabel(Icons.Outlined.Settings,"设置")}
                        StepTextButton(onDesktopOptions){IconLabel(Icons.Outlined.Tune,"桌面选项")}
                    }
                    HorizontalDivider(color=StepLine)
                    if(initialized)ExecutionStrip(runtime){editor="task" to it}
                    if(!initialized) {
                        Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){CircularProgressIndicator();Text(syncStatus)}}
                    } else {
                        Column(Modifier.weight(1f).padding(horizontal=24.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f)) {Text(page,style=MaterialTheme.typography.headlineLarge);Text(when(page){"今天"->"给有限的精力，留出明确的位置";"四象限"->"分清轻重，再决定今天要做什么";"想法"->"保留背景、发现与决定，让想法慢慢长大";"目标"->"记住方向，把它连接到具体行动";else->"先接住，再整理。每一条都可以成为行动或想法。"},color=StepMuted,style=MaterialTheme.typography.bodyMedium)}
                                if(page=="今天") {StepOutlinedButton({picker=true}){Text("选择任务")};StepButton({ai=AiTarget(PlanningAIKind.DAY,null,planId)}){IconLabel(Icons.Outlined.AutoAwesome,"规划这一天")}}
                                else StepButton({editor=(when(page){"想法"->"idea";"目标"->"goal";"收件箱"->"inbox";else->"task"}) to null}){IconLabel(Icons.Outlined.Add,"新建")}
                            }
                            when(page) {
                                "今天" -> TodayPane(state,planId,date,{date=it},{editor="task" to it},work,{ai=AiTarget(PlanningAIKind.DAY,null,planId)})
                                "四象限" -> QuadrantPane(state,planId,work,{editor="task" to it},{editor="task" to null})
                                "想法","目标" -> CollectionPane(state,if(page=="想法")"idea" else "goal",{editor=it.kind to it.id})
                                else -> InboxPane(state,work,{editor="inbox" to it},{ai=AiTarget(PlanningAIKind.INBOX,it,planId)})
                            }
                        }
                    }
                    if(work.failed || error!=null || work.busy) Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(work.failed || work.busy)WorkFeedback(work)
                        error?.let {message->Row(verticalAlignment=Alignment.CenterVertically){Text(message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f));StepTextButton({runtime.error.value=null}){Text("知道了")}}}
                    }
                    if(state.enabled && syncStatus!="已保存到本机")Text(syncStatus,style=MaterialTheme.typography.bodySmall,color=StepMuted,modifier=Modifier.padding(horizontal=24.dp,vertical=8.dp))
                }
            }
        }
        editor?.let {(kind,id)->key(kind,id) {
            when(kind) {
                "task" -> TaskEditor(runtime,id,planId,{editor=null},{ai=AiTarget(null,it,planId)})
                "idea" -> IdeaEditor(runtime,id,planId,{editor=null},{ai=AiTarget(PlanningAIKind.IDEA,it,planId)})
                "goal" -> GoalEditor(runtime,id,{editor=null},{ai=AiTarget(PlanningAIKind.GOAL,it,planId)})
                else -> ContentEditor(runtime,"inbox",id,{editor=null})
            }
        }}
        if(settings) SettingsUi(runtime,{settings=false},onRequestClose)
        ai?.let {AiUi(runtime,it,{ai=null})}
        if(picker) UiModal("选择这一天要推进的任务",{picker=false},work) {
            Text("${date} · 选择不会自动开始计时或发送提醒",color=StepMuted)
            activeTasks(state).forEach {task->
                val selected=DayTaskSelection.contains(state,task.id,planId)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(selected,{desired->work.edit {DayTaskActions.setIncluded(it,task.id,desired,planId,Instant.now())}},enabled=!work.busy)
                    Text(task.title(),modifier=Modifier.weight(1f));Text(quadrantNames[task.content().number("quadrant")?:1],style=MaterialTheme.typography.bodySmall,color=StepMuted)
                }
            }
            if(activeTasks(state).isEmpty()) EmptyState("还没有可选择的任务","到四象限新建任务，或把收件箱内容整理为行动。")
        }
    }
}

@Composable private fun ColumnScope.TodayPane(state:SyncState,planId:String,date:LocalDate,onDate:(LocalDate)->Unit,onTask:(String)->Unit,work:UiWork,onAI:()->Unit) {
    val day=TimelineDay.fromPlanId(planId)
    val projection=TimelineProjection.project(state,day)
    val overview=TodayOverviewProjection.project(state,day)
    val tasks=(overview.scheduled+overview.unscheduled+overview.completed).map {it.task}.distinctBy {it.id}
    val focus=tasks.firstOrNull {planWin(state,planId,it.id)&&!it.completed()}
    val effort=TodayEffort.summary(state,day,overview)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        StepTextButton({onDate(date.minusDays(1))},contentPadding=PaddingValues(6.dp)){StepIcon(Icons.Outlined.ChevronLeft,"前一天")}
        Text(date.format(DateTimeFormatter.ofPattern("M月d日 · EEEE",java.util.Locale.CHINESE)),style=MaterialTheme.typography.titleMedium)
        StepTextButton({onDate(date.plusDays(1))},contentPadding=PaddingValues(6.dp)){StepIcon(Icons.Outlined.ChevronRight,"后一天")}
        StepTextButton({onDate(Instant.now().atZone(day.zone).toLocalDate())}){Text("今天")}
        Spacer(Modifier.weight(1f))
        Text("${tasks.count {!it.completed()}} 件待推进 · ${tasks.count {it.completed()}} 件已完成",style=MaterialTheme.typography.bodySmall,color=StepMuted)
    }
    Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(22.dp)) {
        LazyColumn(Modifier.weight(1.12f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(focus!=null)item {TaskRow(focus,state,planId,work,onTask,featured=true)}
            item {Row(Modifier.padding(top=if(focus!=null)8.dp else 0.dp,bottom=2.dp),verticalAlignment=Alignment.CenterVertically) {Text(if(focus!=null)"今天的其他行动"else"今天的行动",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));Text("${tasks.size-(if(focus!=null)1 else 0)} 项",style=MaterialTheme.typography.bodySmall,color=StepMuted)}}
            if(tasks.isEmpty())item {EmptyState("今天，还留有空间","从四象限选出值得推进的事，再安排一个能够开始的小动作。")}
            items(tasks.filterNot {it.id==focus?.id},key={it.id}) {TaskRow(it,state,planId,work,onTask)}
        }
        LazyColumn(Modifier.weight(.88f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {StepIcon(Icons.Outlined.Schedule,color=StepGold);Text("时间安排",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));Text("${projection.blocks.sumOf {it.durationMinutes}} 分钟",style=MaterialTheme.typography.bodySmall,color=StepMuted)}
            }
            if(projection.blocks.isEmpty())item {
                Surface(color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium) {EmptyState("还没有时间块","打开任务的「安排与提醒」，为重要行动留出一段时间。")}
            }
            items(projection.blocks,key={it.key}) {block->
                val accent=LocalStepPalette.current.quadrants[(block.task.content().number("quadrant")?:1).coerceIn(0,3)]
                Surface(onClick={onTask(block.task.id)},color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.width(48.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {Text(block.start.atZone(day.zone).format(DateTimeFormatter.ofPattern("HH:mm")),color=accent,style=MaterialTheme.typography.titleMedium);Text(block.end.atZone(day.zone).format(DateTimeFormatter.ofPattern("HH:mm")),style=MaterialTheme.typography.bodySmall,color=StepMuted)}
                        Box(Modifier.width(2.dp).height(58.dp).background(accent,MaterialTheme.shapes.small))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {Text(block.task.title(),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis);Text("${block.durationMinutes} 分钟"+(if(block.laneCount>1)" · 有时间重叠"else""),style=MaterialTheme.typography.bodySmall,color=StepMuted)}
                    }
                }
            }
            if(projection.unscheduled.isNotEmpty()) {
                item {Text("还未安排时间",style=MaterialTheme.typography.labelLarge,color=StepMuted,modifier=Modifier.padding(top=8.dp))}
                items(projection.unscheduled,key={"unscheduled-${it.id}"}) {task->
                    Row(Modifier.fillMaxWidth().clickable {onTask(task.id)}.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {StepIcon(Icons.Outlined.Schedule,color=StepMuted);Text(task.title(),style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);StepIcon(Icons.Outlined.ChevronRight,color=StepMuted)}
                }
            }
            item {
                HorizontalDivider(Modifier.padding(top=8.dp,bottom=14.dp),color=StepLine)
                Text("今天的计划投入",style=MaterialTheme.typography.labelLarge,color=StepMuted)
                Row(Modifier.padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                    Column {Text("${effort.personalMinutes} 分钟",style=MaterialTheme.typography.titleMedium);Text("个人投入",style=MaterialTheme.typography.bodySmall,color=StepMuted)}
                    Column {Text("${effort.checkMinutes} 分钟",style=MaterialTheme.typography.titleMedium);Text("检查结果",style=MaterialTheme.typography.bodySmall,color=StepMuted)}
                }
                if(effort.unplannedCount>0)Text("${effort.unplannedCount} 件行动尚未设置投入",style=MaterialTheme.typography.bodySmall,color=StepMuted,modifier=Modifier.padding(top=10.dp))
                Text(day.zone.id+" · 安排与实际专注分别记录",style=MaterialTheme.typography.labelSmall,color=StepMuted,modifier=Modifier.padding(top=12.dp))
            }
        }
    }
}
@Composable private fun ColumnScope.QuadrantPane(state:SyncState,planId:String,work:UiWork,onTask:(String)->Unit,onNew:()->Unit) {
    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        repeat(2) {row-> Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            repeat(2) {column->val q=row*2+column;val tasks=OrganizeActions.tasks(state,q);val accent=LocalStepPalette.current.quadrants[q]
                Column(Modifier.weight(1f).fillMaxHeight().background(accent.copy(alpha=.055f).compositeOver(MaterialTheme.colorScheme.surface),MaterialTheme.shapes.medium).border(1.dp,StepLine,MaterialTheme.shapes.medium).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {Text("0${q+1}",color=accent,style=MaterialTheme.typography.titleMedium);Spacer(Modifier.width(10.dp));Text(quadrantNames[q],style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));Text(tasks.size.toString(),color=StepMuted)}
                    HorizontalDivider(color=StepLine)
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(tasks.isEmpty())item {Text(listOf("优先处理真正有时限的事","给长期价值留出空间","考虑委托、简化或稍后处理","允许自己暂时放下")[q],color=StepMuted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(10.dp))}
                        items(tasks,key={it.id}) {TaskRow(it,state,planId,work,onTask)}
                    }
                }
            }
        }}
    }
}
@Composable internal fun TaskRow(task:Entity,state:SyncState,planId:String,work:UiWork,onTask:(String)->Unit,featured:Boolean=false) {
    val included=DayTaskSelection.contains(state,task.id,planId);val win=planWin(state,planId,task.id)
    ContextMenuArea(items = {
        buildList {
            add(ContextMenuItem("打开 / 编辑") { onTask(task.id) })
            if(!task.completed() && task.conflict==null && task.conflictGroup.isEmpty()) {
                add(ContextMenuItem(if(included) "移出这一天" else "加入这一天") {
                    work.edit { DayTaskActions.setIncluded(it,task.id,!included,planId,Instant.now()) }
                })
                quadrantNames.forEachIndexed { q,label ->
                    add(ContextMenuItem("移至 · $label") {
                        work.edit { current ->
                            val baseline=OrganizeMoveBaseline.from(state,task.id)
                            OrganizeActions.move(current,task.id,q,null,baseline,Instant.now())
                        }
                    })
                }
            }
        }
    }) {
        Surface(onClick={onTask(task.id)},modifier=Modifier.testTag("task_${task.id}"),color=if(win)LocalStepPalette.current.warm else LocalStepPalette.current.field,shape=MaterialTheme.shapes.medium,
            border=if(featured)BorderStroke(1.dp,StepGold.copy(alpha=.20f))else null) {
            Column {
            if(featured)Row(Modifier.padding(start=16.dp,end=16.dp,top=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {StepIcon(Icons.Outlined.StarBorder,color=StepGold);Text("今日重点",style=MaterialTheme.typography.labelLarge,color=StepGold);Spacer(Modifier.weight(1f));Text("先推进这一件",style=MaterialTheme.typography.bodySmall,color=StepMuted)}
            Row(Modifier.fillMaxWidth().padding(vertical=12.dp,horizontal=10.dp),verticalAlignment=Alignment.Top) {
                Checkbox(task.completed(),{value->work.edit {CompletionActions.set(it,task.id,value,Instant.now())}},enabled=!work.busy,modifier=Modifier.size(32.dp))
                Column(Modifier.weight(1f).padding(start=5.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(task.title(),maxLines=2,overflow=TextOverflow.Ellipsis,style=if(featured)MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                    val next=task.content().str("nextAction")
                    if(next.isNotBlank())Text("下一步 · $next",maxLines=2,overflow=TextOverflow.Ellipsis,style=if(featured)MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,color=if(featured)MaterialTheme.colorScheme.onSurface else StepMuted)
                    if(featured && task.content().str("definitionOfDone").isNotBlank())Text(task.content().str("definitionOfDone"),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=StepMuted)
                    val effort=TodayEffort.forTask(state,TimelineDay.fromPlanId(planId),task.id)
                    if(effort.minutes!=null)Text(effort.label,style=MaterialTheme.typography.bodySmall,color=StepGreen)
                    if(task.conflict!=null)Text("存在同步冲突 · 在设置中处理",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                }
                StepTextButton({work.edit {DayTaskActions.setWin(it,task.id,!win,planId,Instant.now())}},enabled=!work.busy && !task.completed(),contentPadding=PaddingValues(4.dp),modifier=Modifier.width(34.dp)){StepIcon(if(win)Icons.Outlined.Star else Icons.Outlined.StarBorder,if(win)"取消当日重点"else"设为当日重点",if(win)StepGold else StepMuted)}
            }
            }
        }
    }
}
@Composable private fun ColumnScope.CollectionPane(state:SyncState,kind:String,onOpen:(Entity)->Unit) {
    var filter by remember(kind) {mutableStateOf("")}
    Field(filter,{filter=it},if(kind=="idea")"搜索想法与背景"else"搜索目标",Modifier.widthIn(max=520.dp))
    val entities=state.items.values.filter {it.kind==kind && !it.deleted && (filter.isBlank() || it.displayTitle().contains(filter,true) || it.content().str(if(kind=="idea")"notes"else"motivation").contains(filter,true))}.sortedByDescending {it.content().str("updatedAt")}
    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(entities.isEmpty())item {EmptyState(if(kind=="idea")"给未成形的想法一个位置"else"从一个值得投入的方向开始",if(kind=="idea")"记录背景与问题，积累发现，准备好时再连接行动。"else"写下为什么，再关联能够推进它的行动。")}
        items(entities,key={it.id}) {e->Surface(onClick={onOpen(e)},modifier=Modifier.testTag("${e.kind}_${e.id}"),color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(20.dp),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.CenterVertically) {
                StepIcon(if(kind=="idea")Icons.Outlined.Lightbulb else Icons.Outlined.Flag,color=StepGold)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {Text(e.displayTitle(),style=MaterialTheme.typography.titleLarge);Text(e.content().str(if(kind=="idea")"notes"else"motivation").ifBlank {if(kind=="idea")"还没有补充背景"else"还没有补充目标说明"},color=StepMuted,maxLines=2,overflow=TextOverflow.Ellipsis)}
                Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(6.dp)) {Text(if(kind=="idea")stageNames[e.content().str("stage")]?:"种子"else mapOf("active" to "进行中","paused" to "暂停","achieved" to "已达成")[e.content().str("status")]?:"进行中",color=StepGreen);Text("${e.content().array(if(kind=="idea")"linkedTaskIDs"else"taskIDs").size} 个关联行动",style=MaterialTheme.typography.bodySmall,color=StepMuted)}
            }
        }}
    }
}
@Composable private fun ColumnScope.InboxPane(state:SyncState,work:UiWork,onEdit:(String)->Unit,onAI:(String)->Unit) {
    val inbox=state.items.values.filter {it.kind=="inbox" && !it.deleted}.sortedByDescending {it.content().str("createdAt")}
    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(inbox.isEmpty())item {EmptyState("收件箱已清空","用顶部「随手记」快速接住脑中的事，再在这里归类。")}
        items(inbox,key={it.id}) {entry->
            var q by remember(entry.id){mutableStateOf("1")}
            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,StepLine)) {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {Text(entry.title(),modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);StepTextButton({onEdit(entry.id)}){Text("编辑")};StepTextButton({onAI(entry.id)}){Text("AI 整理")}}
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)){ChoiceRow(quadrantNames.mapIndexed {i,s->i.toString() to s},q,!work.busy){q=it}}
                        StepOutlinedButton({val id=uid();work.edit {IdeaManagementActions.keepInbox(it,entry.id,id,LibraryBaseline.from(entry),Instant.now())}},enabled=!work.busy){Text("留作想法")}
                        StepButton({val id=uid();work.edit {organizeInbox(it,entry,q.toInt(),false,id,Instant.now())}},enabled=!work.busy){Text("转为行动")}
                    }
                }
            }
        }
    }
}
@Composable fun QuickCaptureUi(runtime:DesktopRuntime,onClose:()->Unit,visible:Boolean=true) {
    DesktopTheme {
        val work=rememberWork(runtime);var text by remember {mutableStateOf("")};var id by remember {mutableStateOf(uid())}
        ReportUnsavedEdits(text.isNotBlank())
        val focus=remember {FocusRequester()}
        val save:()->Unit={if(text.isNotBlank()&&!work.busy){val submitted=text;val creationId=id;work.edit({text="";id=uid();onClose()}){checkedContentSave(it,null,"inbox",creationId,submitted,"",1,ContentEditBaseline.from(null))}}}
        LaunchedEffect(visible){if(visible)focus.requestFocus()}
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent {event->
            if(event.type==KeyEventType.KeyUp&&event.key==Key.Enter&&event.isCtrlPressed){save();true}else false
        }) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("先接住这个想法",style=MaterialTheme.typography.headlineMedium)
            Text("保存到收件箱，稍后再分清轻重。",color=StepMuted)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Field(text,{text=it},"此刻想到什么？",Modifier.focusRequester(focus),lines=3,enabled=!work.busy)
                Text("Ctrl + Enter 保存",style=MaterialTheme.typography.bodySmall,color=StepMuted)
                WorkFeedback(work)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {StepTextButton(onClose,enabled=!work.busy){Text("取消")};StepButton(save,modifier=Modifier.testTag("quick_capture_save"),enabled=text.isNotBlank()&&!work.busy){Text("保存到收件箱")}}
        }}
    }
}
