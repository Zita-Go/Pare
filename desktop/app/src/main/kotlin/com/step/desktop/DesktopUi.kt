package com.step.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                Column(Modifier.width(196.dp).fillMaxHeight().background(Color(0xFFF0F3EC)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Step",style=MaterialTheme.typography.headlineLarge,color=StepGreen,modifier=Modifier.padding(start=12.dp,top=14.dp))
                    Text("让下一步变得清楚",style=MaterialTheme.typography.bodySmall,color=StepMuted,modifier=Modifier.padding(start=12.dp,bottom=28.dp))
                    listOf("今天" to "◷", "四象限" to "▦", "想法" to "✧", "目标" to "◎").forEach {(name,symbol)->
                        Surface(onClick={page=name},modifier=Modifier.testTag("nav_$name"),shape=MaterialTheme.shapes.small,color=if(page==name) Color(0xFFDDE7DC) else Color.Transparent) {
                            Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                                Text(symbol,color=StepGreen);Text(name,fontWeight=if(page==name)FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp));HorizontalDivider(color=StepLine)
                    TextButton({page="收件箱"},modifier=Modifier.fillMaxWidth()) {Text("收件箱");Spacer(Modifier.weight(1f));Text(state.items.values.count {it.kind=="inbox" && !it.deleted}.toString())}
                    Spacer(Modifier.weight(1f))
                    Text("先选重要的事，\n再迈出小小一步。",style=MaterialTheme.typography.bodySmall,color=StepMuted,modifier=Modifier.padding(10.dp))
                    TextButton({settings=true}) {Text(if(state.enabled) "●  同步已开启" else "○  本机优先")}
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height(76.dp).padding(horizontal=28.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("我的空间 / $page",style=MaterialTheme.typography.bodyMedium,color=StepMuted,modifier=Modifier.weight(1f))
                        OutlinedButton(onQuickCapture,enabled=initialized){Text("＋ 随手记")}
                        TextButton({ai=AiTarget(PlanningAIKind.DAY,null,planId)},enabled=initialized){Text("AI 助手")}
                        TextButton({settings=true}){Text("设置")}
                        TextButton(onDesktopOptions){Text("桌面选项")}
                    }
                    HorizontalDivider(color=StepLine)
                    if(initialized)ExecutionStrip(runtime){editor="task" to it}
                    if(!initialized) {
                        Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){CircularProgressIndicator();Text(syncStatus)}}
                    } else {
                        Column(Modifier.weight(1f).padding(horizontal=28.dp,vertical=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f)) {Text(page,style=MaterialTheme.typography.headlineLarge);Text(when(page){"今天"->"给有限的精力，留出明确的位置";"四象限"->"分清轻重，再决定今天要做什么";"想法"->"保留背景、发现与决定，让想法慢慢长大";"目标"->"记住方向，把它连接到具体行动";else->"先接住，再整理。每一条都可以成为行动或想法。"},color=StepMuted,style=MaterialTheme.typography.bodyMedium)}
                                if(page=="今天") {OutlinedButton({picker=true}){Text("选择任务")};Button({ai=AiTarget(PlanningAIKind.DAY,null,planId)}){Text("规划这一天")}}
                                else Button({editor=(when(page){"想法"->"idea";"目标"->"goal";"收件箱"->"inbox";else->"task"}) to null}){Text("＋ 新建")}
                            }
                            when(page) {
                                "今天" -> TodayPane(state,planId,date,{date=it},{editor="task" to it},work,{ai=AiTarget(PlanningAIKind.DAY,null,planId)})
                                "四象限" -> QuadrantPane(state,planId,work,{editor="task" to it},{editor="task" to null})
                                "想法","目标" -> CollectionPane(state,if(page=="想法")"idea" else "goal",{editor=it.kind to it.id})
                                else -> InboxPane(state,work,{editor="inbox" to it},{ai=AiTarget(PlanningAIKind.INBOX,it,planId)})
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        WorkFeedback(work)
                        error?.let {message->Row(verticalAlignment=Alignment.CenterVertically){Text(message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f));TextButton({runtime.error.value=null}){Text("知道了")}}}
                        Row {Text(syncStatus,style=MaterialTheme.typography.bodySmall,color=StepMuted,modifier=Modifier.weight(1f));Text("更改先保存到本机",style=MaterialTheme.typography.bodySmall,color=StepMuted)}
                    }
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
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedButton({onDate(date.minusDays(1))}){Text("‹")};Text(date.format(DateTimeFormatter.ofPattern("M月d日 · EEEE",java.util.Locale.CHINESE)),style=MaterialTheme.typography.titleMedium);OutlinedButton({onDate(date.plusDays(1))}){Text("›")}
        TextButton({onDate(Instant.now().atZone(day.zone).toLocalDate())}){Text("今天")}
        Spacer(Modifier.weight(1f));Text("${tasks.count {!it.completed()}} 件待推进  ·  ${projection.blocks.sumOf {it.durationMinutes}} 分钟已排程",style=MaterialTheme.typography.bodySmall,color=StepMuted)
    }
    Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1.15f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SectionLabel("今天的行动","★ 只选一件最重要的事")
            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(tasks.isEmpty())item {EmptyState("今天，还留有空间","从四象限选出值得推进的事，再安排一个能够开始的小动作。")}
                items(tasks,key={it.id}) {TaskRow(it,state,planId,work,onTask)}
            }
        }
        Column(Modifier.weight(.85f).fillMaxHeight().background(MaterialTheme.colorScheme.surface,MaterialTheme.shapes.medium).border(1.dp,StepLine,MaterialTheme.shapes.medium).padding(20.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
            SectionLabel("时间安排",day.zone.id+" · 安排与实际专注分别记录")
            HorizontalDivider(color=StepLine)
            LazyColumn(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                if(projection.blocks.isEmpty())item {EmptyState("还没有时间块","打开任务的「安排与提醒」，为重要行动留出一段时间。")}
                items(projection.blocks,key={it.key}) {block->
                    Row(Modifier.fillMaxWidth().clickable {onTask(block.task.id)},horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.width(54.dp)) {Text(block.start.atZone(day.zone).format(DateTimeFormatter.ofPattern("HH:mm")),color=StepGreen,fontWeight=FontWeight.SemiBold);Text(block.end.atZone(day.zone).format(DateTimeFormatter.ofPattern("HH:mm")),style=MaterialTheme.typography.bodySmall,color=StepMuted)}
                        Box(Modifier.width(3.dp).height(60.dp).background(if(planWin(state,planId,block.task.id))StepGold else StepGreen))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {Text(block.task.title(),style=MaterialTheme.typography.titleMedium);Text("${block.durationMinutes} 分钟"+(if(block.laneCount>1)" · 与其他安排重叠" else ""),style=MaterialTheme.typography.bodySmall,color=StepMuted)}
                    }
                }
            }
        }
    }
}
@Composable private fun ColumnScope.QuadrantPane(state:SyncState,planId:String,work:UiWork,onTask:(String)->Unit,onNew:()->Unit) {
    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        repeat(2) {row-> Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            repeat(2) {column->val q=row*2+column;val tasks=OrganizeActions.tasks(state,q)
                Column(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface,MaterialTheme.shapes.medium).border(1.dp,StepLine,MaterialTheme.shapes.medium).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {Text("0${q+1}",color=if(q==0)StepGold else StepGreen,style=MaterialTheme.typography.titleMedium);Spacer(Modifier.width(10.dp));Text(quadrantNames[q],style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));Text(tasks.size.toString(),color=StepMuted)}
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
@Composable internal fun TaskRow(task:Entity,state:SyncState,planId:String,work:UiWork,onTask:(String)->Unit) {
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
        Surface(onClick={onTask(task.id)},modifier=Modifier.testTag("task_${task.id}"),color=if(win)Color(0xFFF8F1DE)else Color(0xFFF5F6F1),shape=MaterialTheme.shapes.small) {
            Row(Modifier.fillMaxWidth().padding(vertical=10.dp,horizontal=8.dp),verticalAlignment=Alignment.Top) {
                Checkbox(task.completed(),{value->work.edit {CompletionActions.set(it,task.id,value,Instant.now())}},enabled=!work.busy,modifier=Modifier.size(32.dp))
                Column(Modifier.weight(1f).padding(start=5.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(task.title(),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                    val next=task.content().str("nextAction")
                    if(next.isNotBlank())Text("下一步 · $next",maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=StepMuted)
                    val effort=TodayEffort.forTask(state,TimelineDay.fromPlanId(planId),task.id)
                    if(effort.minutes!=null)Text(effort.label,style=MaterialTheme.typography.bodySmall,color=StepGreen)
                    if(task.conflict!=null)Text("存在同步冲突 · 在设置中处理",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                }
                TextButton({work.edit {DayTaskActions.setWin(it,task.id,!win,planId,Instant.now())}},enabled=!work.busy && !task.completed(),contentPadding=PaddingValues(4.dp),modifier=Modifier.width(34.dp)){Text(if(win)"★"else"☆",color=if(win)StepGold else StepMuted)}
            }
        }
    }
}
@Composable private fun ColumnScope.CollectionPane(state:SyncState,kind:String,onOpen:(Entity)->Unit) {
    var filter by remember(kind) {mutableStateOf("")}
    Field(filter,{filter=it},if(kind=="idea")"搜索想法与背景"else"搜索目标",Modifier.widthIn(max=520.dp))
    val entities=state.items.values.filter {it.kind==kind && !it.deleted && (filter.isBlank() || it.displayTitle().contains(filter,true) || it.content().str(if(kind=="idea")"notes"else"motivation").contains(filter,true))}.sortedByDescending {it.content().str("updatedAt")}
    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
        if(entities.isEmpty())item {EmptyState(if(kind=="idea")"给未成形的想法一个位置"else"从一个值得投入的方向开始",if(kind=="idea")"记录背景与问题，积累发现，准备好时再连接行动。"else"写下为什么，再关联能够推进它的行动。")}
        items(entities,key={it.id}) {e->Surface(onClick={onOpen(e)},modifier=Modifier.testTag("${e.kind}_${e.id}"),color=MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(20.dp),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(if(kind=="idea")"✧"else"◎",style=MaterialTheme.typography.headlineMedium,color=StepGreen)
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
                    Row(verticalAlignment=Alignment.CenterVertically) {Text(entry.title(),modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton({onEdit(entry.id)}){Text("编辑")};TextButton({onAI(entry.id)}){Text("AI 整理")}}
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)){ChoiceRow(quadrantNames.mapIndexed {i,s->i.toString() to s},q,!work.busy){q=it}}
                        OutlinedButton({val id=uid();work.edit {IdeaManagementActions.keepInbox(it,entry.id,id,LibraryBaseline.from(entry),Instant.now())}},enabled=!work.busy){Text("留作想法")}
                        Button({val id=uid();work.edit {organizeInbox(it,entry,q.toInt(),false,id,Instant.now())}},enabled=!work.busy){Text("转为行动")}
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
        }) {Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("先接住这个想法",style=MaterialTheme.typography.headlineMedium)
            Text("保存到收件箱，稍后再分清轻重。",color=StepMuted)
            Field(text,{text=it},"此刻想到什么？",Modifier.focusRequester(focus),lines=4,enabled=!work.busy)
            Text("Ctrl + Enter 保存",style=MaterialTheme.typography.bodySmall,color=StepMuted)
            WorkFeedback(work)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {TextButton(onClose,enabled=!work.busy){Text("取消")};Button(save,enabled=text.isNotBlank()&&!work.busy){Text("保存到收件箱")}}
        }}
    }
}
