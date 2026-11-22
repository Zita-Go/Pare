package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pare.app.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable internal fun ContentEditor(runtime:DesktopRuntime,kind:String,existingId:String?,onClose:()->Unit) {
    val id=remember {existingId?:uid()};val original=remember {runtime.engine.state.value.items["$kind:$id"]}
    val baseline=remember {ContentEditBaseline.from(original)}
    var title by remember {mutableStateOf(original?.title().orEmpty())};var note by remember {mutableStateOf(original?.content().str(if(kind=="idea")"notes"else"note"))}
    val work=rememberWork(runtime)
    UiModal(if(existingId==null)"新建收件"else"编辑收件",onClose,work,dirty=title!=baseline.text) {
        Field(title,{title=it},"内容",lines=3,enabled=!work.busy)
        StepButton({val text=title;val n=note;work.edit(onClose){checkedContentSave(it,original,kind,id,text,n,1,baseline)}},enabled=title.isNotBlank()&&!work.busy){Text("保存")}
    }
}

@Composable internal fun TaskEditor(runtime:DesktopRuntime,existingId:String?,planId:String,onClose:()->Unit,onAI:(String)->Unit) {
    val state by runtime.engine.state.collectAsState();val id=remember {existingId?:uid()}
    val initial=remember {state.items["task:$id"]};val current=state.items["task:$id"]
    var baseline by remember {mutableStateOf(ContentEditBaseline.from(initial))}
    var title by remember {mutableStateOf(initial?.title().orEmpty())};var note by remember {mutableStateOf(initial?.content().str("note"))}
    var quadrant by remember {mutableStateOf((initial?.content().number("quadrant")?:1).toString())}
    var tab by remember {mutableStateOf("step")};val work=rememberWork(runtime)
    val stepDraft=remember(id){initial?.let {UiStepDraft(it)}}
    val scheduleDraft=remember(id,planId){initial?.let {UiScheduleDraft(state,it,planId)}}
    val metadataDraft=remember(id){initial?.let {UiMetadataDraft(it,PreferencesActions.planningZone(state))}}
    var estimate by remember {mutableStateOf("")}
    UiModal(if(current==null)"新建行动"else"推进这件事",onClose,work,width=840,dirty=title!=baseline.text || note!=baseline.note || quadrant!=(baseline.quadrant.toString()) || stepDraft?.dirty==true || scheduleDraft?.dirty==true || metadataDraft?.dirty==true || (initial==null&&estimate.isNotBlank()),subtitle=current?.title()) {
        if(current==null) {
            Field(title,{title=it},"任务名称",enabled=!work.busy)
            ChoiceRow(quadrantNames.mapIndexed {i,s->i.toString() to s},quadrant,!work.busy){quadrant=it}
            Field(estimate,{estimate=it},"任务总预估（分钟，可留空）",enabled=!work.busy)
            Field(note,{note=it},"背景与备注",lines=3,enabled=!work.busy)
            StepButton({val t=title;val n=note;val q=quadrant.toInt();val e=estimate;work.edit(onClose){state->
                val created=checkedContentSave(state,initial,"task",id,t,n,q,baseline)
                val minutes=e.trim().takeIf {it.isNotEmpty()}?.let {it.toIntOrNull()?:error("请输入整数分钟")}
                DesktopTaskMetadata.save(created,id,DesktopMetadataBaseline.from(created.items.getValue("task:$id")),minutes,null,null,Instant.now())
            }},enabled=title.isNotBlank()&&!work.busy){Text("创建行动")}
        } else {
            ChoiceRow(listOf("step" to "最小下一步","detail" to "任务详情","schedule" to "安排与提醒","focus" to "专注 / 等待"),tab,!work.busy){tab=it}
            when(tab) {
                "step" -> StepPanel(runtime,current,requireNotNull(stepDraft),work,onAI){saved->val value=ContentEditBaseline.from(saved);baseline=baseline.copy(nextAction=value.nextAction,lastCompletedStepId=value.lastCompletedStepId)}
                "detail" -> {
                    Field(title,{title=it},"任务名称",enabled=!work.busy)
                    ChoiceRow(quadrantNames.mapIndexed {i,s->i.toString() to s},quadrant,!work.busy){quadrant=it}
                    Field(note,{note=it},"背景与备注",lines=4,enabled=!work.busy)
                    StepButton({val t=title;val n=note;val q=quadrant.toInt();work.editSnapshot({snapshot->val saved=snapshot.items.getValue("task:$id");baseline=ContentEditBaseline.from(saved);stepDraft?.let {it.baseline.value=it.baseline.value.copy(title=saved.title())}} ){checkedContentSave(it,initial,"task",id,t,n,q,baseline)}},enabled=title.isNotBlank()&&!work.busy){Text("保存任务详情")}
                    HorizontalDivider()
                    TaskMetadataPanel(runtime,current,requireNotNull(metadataDraft),work){saved->baseline=baseline.copy(estimatedMinutes=ContentEditBaseline.from(saved).estimatedMinutes)}
                    HorizontalDivider()
                    val included=DayTaskSelection.contains(state,id,planId);val win=planWin(state,planId,id)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        StepOutlinedButton({work.edit {DayTaskActions.setIncluded(it,id,!included,planId,Instant.now())}},enabled=!work.busy){Text(if(included)"移出所选日期"else"加入所选日期")}
                        StepOutlinedButton({work.edit {DayTaskActions.setWin(it,id,!win,planId,Instant.now())}},enabled=!work.busy&&!current.completed()){Text(if(win)"取消当日重点"else"设为当日重点")}
                        StepTextButton({val done=!current.completed();work.complete(id,done)},enabled=!work.busy){Text(if(current.completed())"恢复整个任务"else"完成整个任务")}
                    }
                }
                "schedule" -> SchedulePanel(runtime,current,planId,requireNotNull(scheduleDraft),work)
                else -> ExecutionPanel(runtime,current,work)
            }
        }
    }
}
@Composable private fun StepPanel(runtime:DesktopRuntime,task:Entity,draft:UiStepDraft,work:UiWork,onAI:(String)->Unit,onStepChange:(Entity)->Unit) {
    var baseline by draft.baseline
    var next by draft.next;var done by draft.done
    var completionId by draft.completionId;var history by draft.history
    fun refresh(snapshot:SyncState) {snapshot.items[task.key]?.let {baseline=SmallStepBaseline.from(it);next=baseline.nextAction;done=baseline.definitionOfDone;onStepChange(it)};completionId=uid()}
    SectionLabel("把开始缩小一点","完成这一步会留下记录，整个任务仍然可以继续。")
    Field(next,{next=it},"下一步具体做什么？",lines=2,enabled=!work.busy&&!task.completed())
    Field(done,{done=it},"做到什么就可以停下来？",lines=2,enabled=!work.busy&&!task.completed())
    val changed=next!=baseline.nextAction || done!=baseline.definitionOfDone
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        StepButton({val n=next;val d=done;val b=baseline;work.editSnapshot({refresh(it)}) {NextStepActions.save(it,task.id,n,d,b,Instant.now())}},enabled=next.isNotBlank()&&!work.busy&&!task.completed()){Text(if(baseline.nextAction.isBlank())"写下新的一步"else"保存下一步")}
        StepOutlinedButton({val b=baseline;val record=completionId;work.editSnapshot({refresh(it)}) {NextStepActions.complete(it,task.id,record,b,Instant.now())}},enabled=baseline.nextAction.isNotBlank()&&!changed&&!work.busy&&!task.completed()){IconLabel(Icons.Outlined.Check,"完成这一步")}
        StepTextButton({onAI(task.id)},enabled=!work.busy&&!task.completed()){Text("AI 帮我找下一步")}
    }
    if(changed)Text("先保存修改，再完成这一步。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
    val entries=task.content().array("completedSteps").filterIsInstance<JsonObject>()
    HorizontalDivider(color=StepLine);StepTextButton({history=!history},modifier=Modifier.testTag("step_history_toggle")){IconLabel(if(history)Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,"已完成的步骤 · ${entries.size}")}
    if(history) {
        if(entries.isEmpty())Text("还没有完成步骤。迈出一步后，这里会留下进展。",color=StepMuted)
        entries.asReversed().forEachIndexed {index,e->
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){StepIcon(Icons.Outlined.Check,color=StepGreen);Text(e.str("text"),style=MaterialTheme.typography.titleMedium)}
                if(e.str("definitionOfDone").isNotBlank())Text(e.str("definitionOfDone"),style=MaterialTheme.typography.bodySmall,color=StepMuted)
                Text(e.str("completedAt").take(16).replace('T',' '),style=MaterialTheme.typography.bodySmall,color=StepMuted)
                if(index==0) StepTextButton({val cid=e.str("id");work.editSnapshot({refresh(it)}){NextStepActions.undo(it,task.id,cid,Instant.now())}},enabled=!work.busy&&!task.completed()&&baseline.nextAction.isBlank()&&!changed){Text("撤销最近这一步")}
            }
        }
    }
}
@Composable private fun SchedulePanel(runtime:DesktopRuntime,task:Entity,planId:String,draft:UiScheduleDraft,work:UiWork) {
    val state by runtime.engine.state.collectAsState();val zone=ZoneId.of(planId.substringAfter('@'))
    var scheduleBaseline by draft.scheduleBaseline
    var start by draft.start;var duration by draft.duration;var scheduleRemind by draft.scheduleRemind
    var reminderBaseline by draft.reminderBaseline
    var reminder by draft.reminder;var recurrence by draft.recurrence
    if(planId==ContentActions.planId(state,Instant.now())) {
        var allocationBaseline by draft.allocationBaseline
        var effortKind by draft.effortKind;var effortMinutes by draft.effortMinutes
        val allocationId=draft.allocationId
        SectionLabel("今天准备投入多少？","个人投入和检查结果分开计算；等待时长不算个人投入。")
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            ChoiceRow(listOf("personal" to "个人投入","check" to "检查结果"),effortKind,!work.busy){effortKind=it}
            Field(effortMinutes,{effortMinutes=it},"分钟",Modifier.width(120.dp),enabled=!work.busy)
            StepOutlinedButton({val kind=effortKind;val minutes=effortMinutes;val expected=allocationBaseline;work.editSnapshot({snapshot->draft.acceptAllocation(DayAllocationBaseline.from(snapshot,task.id,Instant.now()))}) {DayPlanActions.allocate(it,task.id,TodayAllocationDraft(planId,kind,minutes.toIntOrNull()?:error("请输入有效分钟数"),allocationId,expected),Instant.now())}},enabled=!work.busy&&!task.completed()){Text("保存投入")}
        }
        HorizontalDivider()
    }
    SectionLabel("安排时间","${planId.substringBefore('@')} · ${zone.id}；安排不会自动开始专注。")
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {Field(start,{start=it},"开始时间 HH:mm",Modifier.weight(1f),enabled=!work.busy);Field(duration,{duration=it},"时长（分钟）",Modifier.weight(1f),enabled=!work.busy);Field(scheduleRemind,{scheduleRemind=it},"提前提醒（分钟，留空关闭）",Modifier.weight(1.5f),enabled=!work.busy)}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        StepButton({val time=start;val mins=duration;val lead=scheduleRemind;val expected=scheduleBaseline;work.editSnapshot({snapshot->draft.acceptSchedule(TimelineScheduleBaseline.from(snapshot,planId,task.id))}) {
            val at=LocalDate.parse(planId.substringBefore('@')).atTime(LocalTime.parse(time)).atZone(zone).toInstant()
            TimelineActions.setSchedule(it,task.id,planId,at,mins.toIntOrNull()?:error("请输入有效时长"),expected,lead.takeIf {it.isNotBlank()}?.let {it.toIntOrNull()?:error("提醒提前量无效")})
        }},enabled=!work.busy&&!task.completed()){Text("保存时间块")}
        if(scheduleBaseline.scheduledAt!=null) StepTextButton({val expected=scheduleBaseline;work.editSnapshot({snapshot->draft.acceptSchedule(TimelineScheduleBaseline.from(snapshot,planId,task.id))}){TimelineActions.clearSchedule(it,task.id,planId,expected)}},enabled=!work.busy){Text("移除时间块")}
    }
    HorizontalDivider()
    SectionLabel("任务提醒","运行或托盘驻留时提醒；退出后的系统提醒可在“设置 → Windows”中开启。")
    Field(reminder,{reminder=it},"提醒日期与时间 yyyy-MM-dd HH:mm（留空清除）",enabled=!work.busy)
    ChoiceRow(listOf("" to "不重复","daily" to "每天","weekly" to "每周","monthly" to "每月"),recurrence,!work.busy){recurrence=it}
    StepButton({val value=reminder;val cycle=recurrence;val expected=reminderBaseline;work.editSnapshot({snapshot->draft.acceptReminder(TaskReminderBaseline.from(snapshot.items.getValue(task.key)))}) {
        val at=value.takeIf {it.isNotBlank()}?.let {LocalDateTime.parse(it,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(zone).toInstant()}
        TaskReminderActions.save(it,task.id,expected,at,cycle.takeIf {it.isNotBlank()},Instant.now())
    }},enabled=!work.busy&&!task.completed()){Text(if(reminder.isBlank())"清除任务提醒"else"保存任务提醒")}
}
@Composable private fun ExecutionPanel(runtime:DesktopRuntime,task:Entity,work:UiWork) {
    val execution by runtime.execution.state.collectAsState();val ready by runtime.executionReady.collectAsState()
    var moment by remember {mutableStateOf(runtime.now())}
    LaunchedEffect(Unit){while(true){delay(1000);moment=runtime.now()}}
    val run=execution.focus?.takeIf {it.taskId==task.id}?:execution.external.firstOrNull {it.taskId==task.id}
    SectionLabel("个人专注","只记录你的投入；计时结束后确认保存，不会自动完成任务。")
    if(run==null) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(5,15,30,60).forEach {minutes->StepOutlinedButton({work.execute {it.startFocus(task.id,minutes)}},enabled=ready&&!work.busy&&!task.completed()&&execution.focus==null){Text("$minutes 分钟")}}}
        execution.focus?.let {Text("本机已有另一段专注，请先结束或取消它。",color=StepMuted)}
        HorizontalDivider();SectionLabel("等待其他人或 AI","这是你主动记录的等待；Step 不会监测外部工具。")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {StepButton({work.execute {it.startExternal(task.id,30)}},enabled=ready&&!work.busy&&!task.completed()){Text("开始等待 · 30 分钟后检查")};StepTextButton({work.execute {it.startExternal(task.id,null)}},enabled=ready&&!work.busy&&!task.completed()){Text("等待，不设检查时间")}}
    } else {
        val total=run.elapsedMillis(moment)/1000
        Text("${total/60}:${(total%60).toString().padStart(2,'0')}",style=MaterialTheme.typography.headlineLarge)
        Text(if(execution.focus?.id==run.id) "个人专注 · ${if(run.due)"已到点"else if(run.paused)"已暂停"else"进行中"}" else "外部等待 · ${if(run.paused)"已暂停"else"进行中"}",color=StepMuted)
        run.recoveryNotice?.let {Text(it,color=StepGold)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(!run.due)StepOutlinedButton({work.execute {if(run.paused)it.resume(task.id)else it.pause(task.id)}},enabled=!work.busy){Text(if(run.paused)"继续"else"暂停")}
            StepButton({work.execute {it.finish(task.id)}},enabled=!work.busy){Text("结束并记录")}
            StepTextButton({work.execute {it.cancel(task.id)}},enabled=!work.busy){Text("取消本段")}
        }
        if(execution.external.any {it.id==run.id})Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(5,15,30,60).forEach {m->StepTextButton({work.execute {it.setCheck(task.id,m)}},enabled=!work.busy){Text("$m 分钟后检查")}}}
    }
    val sessions=task.content().array("progressSessions");val waits=task.content().array("parallelRunSessions")
    Text("已记录 ${sessions.size} 段个人投入 · ${waits.size} 段外部等待",style=MaterialTheme.typography.bodySmall,color=StepMuted)
}

@Composable internal fun IdeaEditor(runtime:DesktopRuntime,existingId:String?,planId:String,onClose:()->Unit,onAI:(String)->Unit) {
    val state by runtime.engine.state.collectAsState();val id=remember{existingId?:uid()};val initial=remember {state.items["idea:$id"]};val current=state.items["idea:$id"]
    var title by remember{mutableStateOf(initial?.title().orEmpty())};var notes by remember{mutableStateOf(initial?.content().str("notes"))}
    var contentBaseline by remember{mutableStateOf(ContentEditBaseline.from(initial))}
    var stage by remember{mutableStateOf(initial?.content().str("stage").ifBlank {"seed"})};var question by remember{mutableStateOf(initial?.content().str("nextQuestion"))}
    var contextBaseline by remember{mutableStateOf(initial?.let {IdeaContextBaseline.from(it)})}
    var entry by remember{mutableStateOf("")};var entryKind by remember{mutableStateOf("note")};var entryId by remember{mutableStateOf(uid())}
    var action by remember{mutableStateOf("")};var actionId by remember{mutableStateOf(uid())};var tab by remember{mutableStateOf("context")};var showLink by remember{mutableStateOf(false)}
    val work=rememberWork(runtime)
    UiModal(if(current==null)"种下一个想法"else"想法孵化",onClose,work,width=840,
        dirty=title!=contentBaseline.text || notes!=contentBaseline.note || stage!=(contextBaseline?.stage?:"seed") || question!=(contextBaseline?.nextQuestion?:"") || entry.isNotBlank() || action.isNotBlank(),subtitle=current?.title()) {
        if(current!=null)ChoiceRow(listOf("context" to "背景与问题","explore" to "探索记录","actions" to "关联行动"),tab,!work.busy){tab=it}
        if(tab=="context" || current==null) {
            Field(title,{title=it},"想法",enabled=!work.busy);Field(notes,{notes=it},"为什么想到它？已有的背景与线索",lines=3,enabled=!work.busy)
            if(current!=null) {
                ChoiceRow(stageNames.entries.map {it.key to it.value},stage,!work.busy){stage=it}
                Field(question,{question=it},"下一次想弄清的问题",lines=2,enabled=!work.busy)
            }
            StepButton({val t=title;val n=notes;val chosen=stage;val q=question;val cb=contentBaseline;val ib=contextBaseline;work.editSnapshot({snapshot->val saved=snapshot.items.getValue("idea:$id");contentBaseline=ContentEditBaseline.from(saved);contextBaseline=IdeaContextBaseline.from(saved);stage=contextBaseline!!.stage;question=contextBaseline!!.nextQuestion}) {
                var next=checkedContentSave(it,initial,"idea",id,t,n,1,cb)
                if(ib!=null)next=IdeaActions.saveContext(next,id,chosen,q,ib,Instant.now())
                next
            }},enabled=title.isNotBlank()&&!work.busy){Text(if(current==null)"保存想法"else"保存背景与阶段")}
        } else if(tab=="explore") {
            SectionLabel("积累探索","不用立刻变成任务。先留下观察、发现或决定。")
            Field(entry,{entry=it},"这次记下什么？",lines=3,enabled=!work.busy)
            ChoiceRow(listOf("note" to "笔记","finding" to "发现","decision" to "决定"),entryKind,!work.busy){entryKind=it}
            StepButton({val text=entry;val kind=entryKind;val eid=entryId;work.edit({entry="";entryId=uid()}){IdeaActions.appendEntry(it,id,eid,text,kind,Instant.now())}},enabled=entry.isNotBlank()&&!work.busy){Text("加入探索记录")}
            current.content().array("entries").asReversed().filterIsInstance<JsonObject>().forEach {e->
                HorizontalDivider();Text(mapOf("note" to "笔记","finding" to "发现","decision" to "决定")[e.str("kind")]?:"记录",style=MaterialTheme.typography.bodySmall,color=StepGold);Text(e.str("text"));Text(e.str("createdAt").take(10),style=MaterialTheme.typography.bodySmall,color=StepMuted)
            }
        } else {
            val links=current.content().array("linkedTaskIDs").map {it.jsonPrimitive.content}.toSet()
            links.forEach {taskId->val task=state.items["task:$taskId"];Row(verticalAlignment=Alignment.CenterVertically){Text(task?.title()?:"已移除的行动",modifier=Modifier.weight(1f));StepTextButton({val expected=IdeaManagementBaseline.from(current);work.edit {IdeaManagementActions.link(it,id,taskId,false,expected,Instant.now())}},enabled=!work.busy){Text("解除关联")}}}
            Field(action,{action=it},"从想法中拆出一个具体行动",enabled=!work.busy)
            StepButton({val text=action;val aid=actionId;work.edit({action="";actionId=uid()}){IdeaActions.createLinkedTask(it,id,aid,text,"","",1,null,Instant.now())}},enabled=action.isNotBlank()&&!work.busy){Text("创建并关联行动")}
            StepTextButton({showLink=!showLink}){Text(if(showLink)"收起已有行动"else"关联已有行动")}
            if(showLink) activeTasks(state).filter {it.id !in links}.forEach {task->StepTextButton({val expected=IdeaManagementBaseline.from(current);work.edit {IdeaManagementActions.link(it,id,task.id,true,expected,Instant.now())}},enabled=!work.busy){IconLabel(Icons.Outlined.Add,task.title())}}
        }
        if(current!=null)StepTextButton({onAI(id)},enabled=!work.busy){Text("AI 帮我探索这个想法")}
    }
}
@Composable internal fun GoalEditor(runtime:DesktopRuntime,existingId:String?,onClose:()->Unit,onAI:(String)->Unit) {
    val state by runtime.engine.state.collectAsState();val initial=remember{existingId?.let {state.items["goal:$it"]}}
    var draft by remember{mutableStateOf(initial?.let {GoalDraft.from(it)}?:GoalDraft())};val baseline=remember{GoalBaseline.from(initial)}
    val targetZone=remember{ZoneId.of(ContentActions.planId(state,Instant.now()).substringAfter('@'))}
    var targetDate by remember{mutableStateOf(draft.targetDate?.let {Instant.parse(it).atZone(targetZone).toLocalDate().toString()}.orEmpty())};val work=rememberWork(runtime)
    val originalDraft=remember{draft};val originalDate=remember{targetDate}
    UiModal(if(initial==null)"设定一个方向"else"编辑目标",onClose,work,width=800,dirty=draft!=originalDraft || targetDate!=originalDate,subtitle=initial?.displayTitle()) {
        Field(draft.title,{draft=draft.copy(title=it)},"目标",enabled=!work.busy)
        Field(draft.motivation,{draft=draft.copy(motivation=it)},"为什么值得投入？",lines=3,enabled=!work.busy)
        ChoiceRow(listOf("active" to "进行中","paused" to "暂停","achieved" to "已达成"),draft.status,!work.busy){draft=draft.copy(status=it)}
        Field(targetDate,{targetDate=it},"目标日期 yyyy-MM-dd（${targetZone.id}，可留空）",enabled=!work.busy)
        SectionLabel("关联行动","保留目标与日常行动之间的联系")
        activeTasks(state).forEach {task->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(task.id in draft.taskIds,{yes->draft=draft.copy(taskIds=if(yes)(draft.taskIds+task.id).distinct()else draft.taskIds-task.id)},enabled=!work.busy);Text(task.title(),modifier=Modifier.weight(1f))}}
        Field(draft.newStep,{draft=draft.copy(newStep=it)},"新增一件推进目标的行动（可留空）",enabled=!work.busy)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            StepButton({val value=draft;val date=targetDate;work.edit(onClose){GoalsActions.save(it,value.copy(targetDate=date.takeIf {it.isNotBlank()}?.let {LocalDate.parse(it).atStartOfDay(targetZone).toInstant().toString()}),baseline,Instant.now())}},enabled=draft.title.isNotBlank()&&!work.busy){Text("保存目标")}
            if(initial!=null)StepTextButton({onAI(initial.id)},enabled=!work.busy){Text("AI 帮我推进这个目标")}
        }
    }
}

@Composable internal fun ExecutionStrip(runtime:DesktopRuntime,onTask:(String)->Unit) {
    val runs by runtime.execution.state.collectAsState();val state by runtime.engine.state.collectAsState();val work=rememberWork(runtime)
    val active=listOfNotNull(runs.focus)+runs.external
    if(active.isEmpty())return
    var selected by remember {mutableStateOf(false)}
    Surface(color=MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            val focus=runs.focus
            StepIcon(if(focus!=null)Icons.Outlined.Timer else Icons.Outlined.Schedule,color=StepGold)
            Text(if(focus!=null)"专注${if(focus.paused)"已暂停"else"进行中"}"else"外部等待",style=MaterialTheme.typography.labelLarge,color=StepGold)
            Text(state.items["task:${(focus?:active.first()).taskId}"]?.title().orEmpty(),modifier=Modifier.weight(1f),maxLines=1)
            if(runs.external.isNotEmpty())Text("${runs.external.size} 项等待",style=MaterialTheme.typography.bodySmall)
            StepTextButton({selected=true}){Text("查看推进状态")}
        }
    }
    if(selected)UiModal("本机正在推进",{selected=false},work) {
        active.forEach {run->
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {Text(state.items["task:${run.taskId}"]?.title().orEmpty(),style=MaterialTheme.typography.titleMedium);Text(if(runs.focus?.id==run.id)"个人专注"else"外部等待",style=MaterialTheme.typography.bodySmall,color=StepMuted)}
                if(!run.due)StepTextButton({work.execute {if(run.paused)it.resume(run.taskId)else it.pause(run.taskId)}},enabled=!work.busy){Text(if(run.paused)"继续"else"暂停")}
                StepOutlinedButton({work.execute {it.finish(run.taskId)}},enabled=!work.busy){Text("结束并记录")}
                StepTextButton({selected=false;onTask(run.taskId)}){Text("打开任务")}
            }
        }
    }
}

/** Tab changes preserve both typed values and the version they were based on. */
private class UiStepDraft(task:Entity) {
    val baseline=mutableStateOf(SmallStepBaseline.from(task))
    val next=mutableStateOf(baseline.value.nextAction)
    val done=mutableStateOf(baseline.value.definitionOfDone)
    val completionId=mutableStateOf(uid())
    val history=mutableStateOf(false)
    val dirty:Boolean get() = next.value!=baseline.value.nextAction || done.value!=baseline.value.definitionOfDone
}
private class UiScheduleDraft(state:SyncState,task:Entity,planId:String) {
    private val zone=ZoneId.of(planId.substringAfter('@'))
    val scheduleBaseline=mutableStateOf(TimelineScheduleBaseline.from(state,planId,task.id))
    val start=mutableStateOf(scheduleBaseline.value.scheduledAt?.let {Instant.parse(it).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))}?:"09:00")
    val duration=mutableStateOf((scheduleBaseline.value.scheduledMinutes?:30).toString())
    val scheduleRemind=mutableStateOf(scheduleBaseline.value.reminderLeadMinutes?.toString().orEmpty())
    val reminderBaseline=mutableStateOf(TaskReminderBaseline.from(task))
    val reminder=mutableStateOf(task.content().str("remindAt").takeIf {it.isNotBlank()}?.let {Instant.parse(it).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}.orEmpty())
    val recurrence=mutableStateOf(task.content().str("recurrence"))
    val allocationBaseline=mutableStateOf(DayAllocationBaseline.from(state,task.id,Instant.now()))
    val effortKind=mutableStateOf(allocationBaseline.value.kind?:"personal")
    val effortMinutes=mutableStateOf((allocationBaseline.value.plannedMinutes?:5).toString())
    val allocationId=uid()
    fun acceptSchedule(value:TimelineScheduleBaseline) {
        scheduleBaseline.value=value
        start.value=value.scheduledAt?.let {Instant.parse(it).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))}?:"09:00"
        duration.value=(value.scheduledMinutes?:30).toString()
        scheduleRemind.value=value.reminderLeadMinutes?.toString().orEmpty()
    }
    fun acceptReminder(value:TaskReminderBaseline) {
        reminderBaseline.value=value
        reminder.value=(value.remindAt as? JsonPrimitive)?.contentOrNull?.let {Instant.parse(it).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}.orEmpty()
        recurrence.value=(value.recurrence as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
    fun acceptAllocation(value:DayAllocationBaseline) {
        allocationBaseline.value=value;effortKind.value=value.kind?:"personal";effortMinutes.value=(value.plannedMinutes?:5).toString()
    }
    val dirty:Boolean get() {
        val scheduled=scheduleBaseline.value
        val scheduledTime=scheduled.scheduledAt?.let {Instant.parse(it).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))}?:"09:00"
        val at=reminderBaseline.value.remindAt?.let {(it as? JsonPrimitive)?.contentOrNull}?.let {Instant.parse(it).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}.orEmpty()
        val cycle=(reminderBaseline.value.recurrence as? JsonPrimitive)?.contentOrNull.orEmpty()
        return start.value!=scheduledTime || duration.value.toIntOrNull()!=(scheduled.scheduledMinutes?:30) ||
            scheduleRemind.value!=scheduled.reminderLeadMinutes?.toString().orEmpty() ||
            reminder.value!=at || recurrence.value!=cycle ||
            effortKind.value!=(allocationBaseline.value.kind?:"personal") ||
            effortMinutes.value.toIntOrNull()!=(allocationBaseline.value.plannedMinutes?:5)
    }
}
