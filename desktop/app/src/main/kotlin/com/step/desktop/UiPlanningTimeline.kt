package com.step.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pare.app.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Layout lanes account for minimum readable paint height, without altering saved times. */
internal data class TimelineDisplayBlock(val block:TimelineBlock,val lane:Int,val lanes:Int,val visualMinutes:Double)
internal fun timelineDisplayBlocks(blocks:List<TimelineBlock>,minimumMinutes:Double):List<TimelineDisplayBlock> {
    val result=mutableListOf<TimelineDisplayBlock>()
    var cluster=mutableListOf<TimelineBlock>();var clusterEnd=Double.NEGATIVE_INFINITY
    fun finishCluster() {
        if(cluster.isEmpty())return
        val ends=mutableListOf<Double>()
        val packed=cluster.map {block->
            val start=block.startMinute.coerceAtLeast(0.0)
            val duration=maxOf(block.endMinute-block.startMinute,minimumMinutes)
            val lane=ends.indexOfFirst {it<=start}.let {if(it<0){ends.add(start);ends.lastIndex}else it}
            ends[lane]=start+duration
            Triple(block,lane,duration)
        }
        result+=packed.map {(block,lane,minutes)->TimelineDisplayBlock(block,lane,ends.size,minutes)}
        cluster=mutableListOf();clusterEnd=Double.NEGATIVE_INFINITY
    }
    blocks.sortedBy {it.startMinute}.forEach {block->
        val start=block.startMinute.coerceAtLeast(0.0)
        if(start>=clusterEnd)finishCluster()
        cluster.add(block);clusterEnd=maxOf(clusterEnd,start+maxOf(block.endMinute-block.startMinute,minimumMinutes))
    }
    finishCluster();return result
}

internal class TimelineGesture(private val work:UiWork,val day:TimelineDay) {
    data class Draft(val shown:SyncState,val task:Entity,val block:TimelineBlock?,val baseline:TimelineScheduleBaseline,
        val resize:Boolean,val pointer:Offset,val grabMinutes:Double,val start:Instant,val minutes:Int,val target:TimelineDay,val valid:Boolean=false)
    var active by mutableStateOf<Draft?>(null);private set
    var grid by mutableStateOf(Rect.Zero);var viewport by mutableStateOf(Rect.Zero)
    val dateZones=mutableStateMapOf<Int,Rect>()
    var pixelsPerMinute=1f
    var gutterPixels=60f
    var undo by mutableStateOf<TimelineUndo?>(null);private set
    fun begin(state:SyncState,task:Entity,block:TimelineBlock?,resize:Boolean,pointer:Offset):Boolean {
        if(work.busy)return false
        return runCatching {
            require(!task.completed()&&task.conflict==null&&task.conflictGroup.isEmpty()){ "请先处理任务状态" }
            val baseline=TimelineScheduleBaseline.from(state,block?.sourcePlanId?:day.planId,task.id)
            val start=block?.start?:day.start
            val grab=if(block!=null)(pointer.y-grid.top)/pixelsPerMinute-(if(resize)block.startMinute+block.durationMinutes else block.startMinute)else 0.0
            active=Draft(state,task,block,baseline,resize,pointer,grab,start,block?.durationMinutes?:task.content().number("estimatedMinutes")?:30,day)
        }.onFailure {work.message=it.message}.isSuccess
    }
    fun move(point:Offset) {
        val old=active?:return
        val dateOffset=dateZones.entries.firstOrNull {it.value.contains(point)}?.key
        active=runCatching {
            if(dateOffset!=null&&old.block!=null&&!old.resize) {
                val target=TimelineDay(day.date.plusDays(dateOffset.toLong()),day.zone)
                old.copy(pointer=point,start=TimelineTime.onDate(old.block.start,target),target=target,valid=true)
            } else {
                val raw=(point.y-grid.top)/pixelsPerMinute
                val valid=viewport.contains(point)&&point.x>grid.left+gutterPixels
                if(old.resize) {
                    val startMinute=java.time.Duration.between(day.start,old.block!!.start).toMinutes().toInt()
                    val minutes=(TimelineTime.snapMinute(raw-old.grabMinutes)-startMinute).coerceIn(15,1440)
                    old.copy(pointer=point,start=old.block.start,minutes=minutes,target=day,valid=valid)
                } else {
                    val minute=TimelineTime.snapMinute(raw-old.grabMinutes).coerceIn(0,day.minutes-15)
                    old.copy(pointer=point,start=TimelineTime.instantAt(day,minute),target=day,valid=valid)
                }
            }
        }.getOrElse {old.copy(pointer=point,valid=false)}
    }
    fun cancel(){active=null}
    fun finish() {val draft=active?:return;active=null;if(!draft.valid)return;commit(draft)}
    private fun commit(draft:Draft) {
        var receipt:TimelineUndo?=null
        work.edit({undo=receipt}) {current->
            val originalDay=draft.block?.start?.atZone(day.zone)?.toLocalDate()
            val next=if(draft.block!=null&&originalDay!=draft.target.date) {
                val expected=TimelineMoveBaseline.capture(draft.shown,draft.baseline,draft.target.planId)
                TimelineActions.moveToDay(current,draft.task.id,draft.start,draft.minutes,expected)
            } else TimelineActions.setSchedule(current,draft.task.id,draft.block?.sourcePlanId?:day.planId,draft.start,draft.minutes,draft.baseline)
            receipt=TimelineUndo.capture(current,next);next
        }
    }
    fun edit(change:(SyncState)->SyncState) {
        var receipt:TimelineUndo?=null
        work.edit({undo=receipt}) {before->change(before).also {receipt=TimelineUndo.capture(before,it)}}
    }
    fun recordUndo(receipt:TimelineUndo?){undo=receipt}
    fun undo(){val receipt=undo?:return;work.edit({undo=null}){receipt.apply(it)}}
}

@Composable internal fun PlanningTimeline(runtime:DesktopRuntime,planId:String,onOpenTask:(String)->Unit,onDate:(LocalDate)->Unit) {
    val state by runtime.engine.state.collectAsState()
    val day=remember(planId){TimelineDay.fromPlanId(planId)}
    val work=rememberWork(runtime)
    val gesture=remember(planId,work){TimelineGesture(work,day)}
    val projection=TimelineProjection.project(state,day)
    val density=LocalDensity.current
    var expanded by remember {mutableStateOf(true)}
    var scaleAnchor by remember {mutableStateOf<Float?>(null)}
    val minuteHeight=if(expanded)2.dp else 1.dp
    val ppm=with(density){minuteHeight.toPx()};gesture.pixelsPerMinute=ppm;gesture.gutterPixels=with(density){60.dp.toPx()}
    val scroll=rememberScrollState(with(density){(minuteHeight*(7*60)).roundToPx()})
    val displayBlocks=timelineDisplayBlocks(projection.blocks,26.0/minuteHeight.value)
    LaunchedEffect(expanded){scaleAnchor?.let {anchor->withFrameNanos {};scroll.scrollTo((anchor*ppm).roundToInt());scaleAnchor=null}}
    var createMinute by remember(planId){mutableStateOf<Int?>(null)}
    val active=gesture.active
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            StepTextButton({onDate(day.date.minusDays(1))},Modifier.testTag("timeline_day_previous").onGloballyPositioned {gesture.dateZones[-1]=it.boundsInRoot()}){Text("前一天")}
            StepTextButton({onDate(day.date.plusDays(1))},Modifier.testTag("timeline_day_next").onGloballyPositioned {gesture.dateZones[1]=it.boundsInRoot()}){Text("后一天")}
            Text(if(active!=null&&active.valid)"松开：${active.start.atZone(day.zone).format(DateTimeFormatter.ofPattern("M/d HH:mm"))} · ${active.minutes}分钟"else"拖动手柄安排 · 底边调整时长 · Esc取消",style=MaterialTheme.typography.bodySmall,color=StepMuted,modifier=Modifier.weight(1f))
            StepTextButton({scaleAnchor=scroll.value/ppm;expanded=!expanded},modifier=Modifier.testTag("timeline_scale_toggle")){Text(if(expanded)"紧凑刻度"else"展开刻度")}
            if(gesture.undo!=null)StepTextButton(gesture::undo,Modifier.testTag("timeline_undo")){Text("撤销时间调整")}
        }
        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            StepCard(Modifier.width(205.dp).fillMaxHeight()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("未安排时间",style=MaterialTheme.typography.titleMedium)
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(projection.unscheduled,key={it.id}) {task->
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(24.dp,42.dp).stepDragHandle("timeline_unscheduled_${task.id}",
                                    onBegin={gesture.begin(state,task,null,false,it)},onMove=gesture::move,onFinish=gesture::finish,onCancel=gesture::cancel)) {
                                    StepIcon(Icons.Outlined.DragIndicator,color=StepMuted)
                                }
                                Text(task.title(),style=MaterialTheme.typography.bodyMedium,maxLines=3,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f).clickable {onOpenTask(task.id)})
                            }
                        }
                        if(projection.unscheduled.isEmpty())item {Text("点击时间轴空白处，可新建或选择其他行动。",style=MaterialTheme.typography.bodySmall,color=StepMuted)}
                    }
                }
            }
            StepCard(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxSize().onGloballyPositioned {gesture.viewport=it.boundsInRoot()}) {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(end=8.dp).verticalScroll(scroll)) {
                        val gutter=60.dp;val area=maxWidth-gutter-8.dp
                        Box(Modifier.fillMaxWidth().height(minuteHeight*day.minutes+26.dp)) {
                        Box(Modifier.fillMaxWidth().height(minuteHeight*day.minutes).testTag("timeline_grid").onGloballyPositioned {gesture.grid=Rect(it.positionInRoot(),Size(it.size.width.toFloat(),it.size.height.toFloat()))}) {
                            Column {
                                for(minute in 0 until day.minutes step 30)Row(Modifier.fillMaxWidth().height(minuteHeight*30).testTag("timeline_slot_$minute").clickable {createMinute=minute}) {
                                    Text(if(minute%60==0)TimelineTime.instantAt(day,minute).atZone(day.zone).format(DateTimeFormatter.ofPattern("HH:mm"))else"",style=MaterialTheme.typography.labelSmall,color=StepMuted,modifier=Modifier.width(gutter).padding(start=10.dp,top=2.dp))
                                    HorizontalDivider(color=StepLine,modifier=Modifier.weight(1f))
                                }
                            }
                            displayBlocks.forEach {display->val block=display.block;key(block.key) {
                                val width=(area-6.dp*(display.lanes-1))/display.lanes
                                val y=minuteHeight*block.startMinute.toFloat().coerceAtLeast(0f)
                                val height=minuteHeight*display.visualMinutes.toFloat()
                                val accent=LocalStepPalette.current.quadrants[(block.task.content().number("quadrant")?:1).coerceIn(0,3)]
                                val baseline=TimelineScheduleBaseline.from(state,block.sourcePlanId,block.task.id)
                                Box(Modifier.offset(x=gutter+(width+6.dp)*display.lane,y=y).width(width).height(height).testTag("timeline_block_${block.task.id}")) {
                                ContextMenuArea(items={
                                    listOf(ContextMenuItem("打开任务"){onOpenTask(block.task.id)})+if(block.task.completed())emptyList()else listOf(
                                        ContextMenuItem("提前15分钟"){gesture.edit {TimelineActions.setSchedule(it,block.task.id,block.sourcePlanId,block.start.minusSeconds(900),block.durationMinutes,baseline)}},
                                        ContextMenuItem("延后15分钟"){gesture.edit {TimelineActions.setSchedule(it,block.task.id,block.sourcePlanId,block.start.plusSeconds(900),block.durationMinutes,baseline)}},
                                        ContextMenuItem("延长15分钟"){gesture.edit {TimelineActions.setSchedule(it,block.task.id,block.sourcePlanId,block.start,block.durationMinutes+15,baseline)}},
                                        ContextMenuItem("移到后一天"){gesture.edit {val target=TimelineDay(day.date.plusDays(1),day.zone);TimelineActions.moveToDay(it,block.task.id,TimelineTime.onDate(block.start,target),block.durationMinutes,TimelineMoveBaseline.capture(state,baseline,target.planId))}},
                                        ContextMenuItem("移除时间块"){gesture.edit {TimelineActions.clearSchedule(it,block.task.id,block.sourcePlanId,baseline)}}
                                    )
                                }) {
                                    Box(Modifier.fillMaxSize().clip(MaterialTheme.shapes.small)
                                        .background(accent.copy(alpha=.20f)).border(1.dp,accent.copy(alpha=.45f),MaterialTheme.shapes.small)) {
                                        Row(Modifier.fillMaxSize().stepDragHandle("timeline_drag_${block.task.id}",enabled=!block.task.completed(),
                                            onBegin={gesture.begin(state,block.task,block,false,it)},onMove=gesture::move,onFinish=gesture::finish,onCancel=gesture::cancel)
                                            .clickable {onOpenTask(block.task.id)}.padding(start=7.dp,end=36.dp,top=2.dp,bottom=2.dp),verticalAlignment=Alignment.Top) {
                                            Text(block.task.title(),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,
                                                modifier=Modifier.weight(1f).testTag("timeline_label_${block.task.id}"))
                                            if(width>180.dp)Text("${block.durationMinutes}分",style=MaterialTheme.typography.labelSmall,color=StepMuted)
                                        }
                                        Box(Modifier.align(Alignment.BottomEnd).width(30.dp).height(8.dp).stepDragHandle("timeline_resize_${block.task.id}",enabled=!block.task.completed(),
                                            onBegin={gesture.begin(state,block.task,block,true,it)},onMove=gesture::move,onFinish=gesture::finish,onCancel=gesture::cancel),contentAlignment=Alignment.Center) {
                                            Box(Modifier.width(20.dp).height(2.dp).background(accent,MaterialTheme.shapes.small))
                                        }
                                    }
                                }
                                }
                            }}
                            if(active!=null&&active.valid&&active.target.planId==planId) {
                                val minute=java.time.Duration.between(day.start,active.start).toMinutes().toInt().coerceAtLeast(0)
                                Box(Modifier.offset(x=gutter,y=minuteHeight*minute).width(area).height(minuteHeight*active.minutes).border(2.dp,StepGold,MaterialTheme.shapes.small))
                            }
                        }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(scroll),Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp))
                }
            }
        }
        WorkFeedback(work)
    }
    LaunchedEffect(active!=null) {
        while(gesture.active!=null) {
            val p=gesture.active!!.pointer;val bounds=gesture.viewport
            if(bounds.contains(p)) {val delta=when {p.y<bounds.top+30->-12f;p.y>bounds.bottom-30->12f;else->0f};if(delta!=0f)scroll.scrollTo((scroll.value+delta.roundToInt()).coerceIn(0,scroll.maxValue))}
            delay(30)
        }
    }
    createMinute?.let {minute->TimelineCreateSheet(runtime,day,minute,{createMinute=null},gesture::recordUndo)}
}

@Composable private fun TimelineCreateSheet(runtime:DesktopRuntime,day:TimelineDay,minute:Int,onClose:()->Unit,onCommitted:(TimelineUndo?)->Unit) {
    val state by runtime.engine.state.collectAsState();val shown=remember {state}
    val id=remember {uid()};val work=rememberWork(runtime)
    var mode by remember {mutableStateOf("new")};var title by remember {mutableStateOf("")};var taskId by remember {mutableStateOf<String?>(null)}
    var time by remember {mutableStateOf(TimelineTime.instantAt(day,minute).atZone(day.zone).format(DateTimeFormatter.ofPattern("HH:mm")))}
    val originalTime=remember{time}
    var minutes by remember {mutableStateOf("30")}
    UiModal("留出一段时间",onClose,work,width=680,dirty=title.isNotBlank()||taskId!=null||time!=originalTime||minutes!="30") {
        Text("${day.date} · ${day.zone.id}",color=StepMuted)
        ChoiceRow(listOf("new" to "新建行动","existing" to "选择已有行动"),mode,!work.busy){mode=it}
        if(mode=="new")Field(title,{title=it},"行动名称",enabled=!work.busy,inputModifier=Modifier.testTag("timeline_new_title"))
        else activeTasks(state).forEach {task->Row(verticalAlignment=Alignment.CenterVertically) {RadioButton(taskId==task.id,{taskId=task.id},enabled=!work.busy);Text(task.title(),modifier=Modifier.weight(1f))}}
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Field(time,{time=it},"开始 HH:mm",Modifier.weight(1f),enabled=!work.busy);Field(minutes,{minutes=it},"分钟",Modifier.weight(1f),enabled=!work.busy)}
        StepButton({val value=title;val selected=taskId;val clock=time;val amount=minutes;val kind=mode;var receipt:TimelineUndo?=null;work.edit({onCommitted(receipt);onClose()}) {before->
            val parsed=LocalTime.parse(clock);val at=TimelineTime.resolveLocal(day,parsed.hour*60+parsed.minute)
            val duration=amount.toIntOrNull()?:error("请输入整数分钟")
            val next=if(kind=="new")TimelineActions.createScheduledTask(before,id,value,1,day.planId,at,duration,TimelineScheduleBaseline.from(shown,day.planId,id),Instant.now())
            else TimelineActions.setSchedule(before,requireNotNull(selected),day.planId,at,duration,TimelineScheduleBaseline.from(shown,day.planId,selected))
            receipt=TimelineUndo.capture(before,next);next
        }},modifier=Modifier.testTag("timeline_create"),enabled=!work.busy&&(if(mode=="new")title.isNotBlank()else taskId!=null)){Text(if(mode=="new")"新建并安排"else"安排选中行动")}
    }
}
