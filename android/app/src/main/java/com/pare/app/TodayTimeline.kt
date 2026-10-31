package com.pare.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private val timelineClock = DateTimeFormatter.ofPattern("HH:mm")
private fun timelineTime(at: Instant, day: TimelineDay) = at.atZone(day.zone).format(timelineClock)

private data class TimelineDrag(
    val task: Entity, val block: TimelineBlock?, val resize: Boolean,
    val originRoot: Offset, val pointerRoot: Offset, val initialScroll: Int,
    val start: Instant, val minutes: Int,
    val scheduleBaseline: TimelineScheduleBaseline?, val orderBaseline: TimelineOrderBaseline?,
    val originalOrder: List<String> = emptyList()
)

/** A real, proportional day axis. Gestures stage a preview; only release invokes a write callback. */
@Composable
fun TodayTimeline(
    state: SyncState, selectedDate: LocalDate, now: Instant,
    onSelectDate: (LocalDate) -> Unit,
    onOpenTask: (Entity) -> Unit,
    onComplete: (Entity) -> Unit,
    onCreateAt: (TimelineDay, Instant) -> Unit,
    onScheduleTask: (Entity, TimelineDay, Instant?, TimelineScheduleBaseline?) -> Unit,
    onEditSchedule: (TimelineBlock) -> Unit,
    onChangeSchedule: (TimelineBlock, Instant, Int, TimelineScheduleBaseline) -> Unit,
    onReorderUnscheduled: (TimelineDay, List<String>, TimelineOrderBaseline) -> Unit,
    revealUnscheduledKey: Int = 0,
    modifier: Modifier = Modifier,
    onMoveToDay: ((TimelineBlock, Instant, TimelineMoveBaseline) -> Unit)? = null,
    revealAt: Instant? = null
) {
    val day = remember(state, selectedDate) { TimelineDay.from(state, selectedDate) }
    val projection = remember(state, day) { TimelineProjection.project(state, day) }
    val dailyWinId = state.items["day_plan:${day.planId}"]?.takeUnless { it.deleted }?.content()?.timelineString("daily_win_task_id")
    val dailyWinTask = dailyWinId?.let { state.items["task:$it"] }?.takeIf(::taskCanBePlanned)
    val plannedLoad = remember(state, day.planId) { TodayLoad.calculate(state, day.start) }
    val latestState by rememberUpdatedState(state)
    val changeSchedule by rememberUpdatedState(onChangeSchedule)
    val moveToDay by rememberUpdatedState(onMoveToDay)
    val reorder by rememberUpdatedState(onReorderUnscheduled)
    val scheduleTask by rememberUpdatedState(onScheduleTask)
    val density = LocalDensity.current
    // Larger text receives more vertical space, with the minute scale remaining proportional.
    val hourHeight = (120f * max(1f, density.fontScale)).dp
    val gutterWidth = (56f * max(1f, density.fontScale)).dp
    val pxPerMinute = with(density) { hourHeight.toPx() } / 60f
    val minimumBlockHeight = 48.dp
    val minimumBlockMinutes = with(density) { minimumBlockHeight.toPx() } / pxPerMinute
    val scroll = rememberScrollState()
    var positionedPlan by rememberSaveable {mutableStateOf<String?>(null)}
    var positionedReveal by rememberSaveable {mutableStateOf<String?>(null)}
    val trayScroll = rememberScrollState()
    val horizontal = rememberScrollState()
    var expanded by rememberSaveable(selectedDate.toString()) { mutableStateOf(false) }
    var selectedBlockKey by rememberSaveable(selectedDate.toString()) { mutableStateOf<String?>(null) }
    var handledRevealKey by rememberSaveable { mutableStateOf(0) }
    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var trayBounds by remember { mutableStateOf(Rect.Zero) }
    var drag by remember { mutableStateOf<TimelineDrag?>(null) }
    var rowBounds by remember { mutableStateOf<Map<String, Rect>>(emptyMap()) }
    var rowOrigins by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }
    var previewOrder by remember { mutableStateOf<List<String>?>(null) }
    var dateDropBounds by remember {mutableStateOf<Map<Int,Rect>>(emptyMap())}
    var dateDropOffset by remember {mutableStateOf<Int?>(null)}
    var dateDropExpected by remember {mutableStateOf<TimelineMoveBaseline?>(null)}
    var dateDropStart by remember {mutableStateOf<Instant?>(null)}
    var dateDropError by remember {mutableStateOf<String?>(null)}
    val currentDrag = drag
    val selectedBlock = projection.blocks.firstOrNull { it.key == selectedBlockKey }
    val visual = remember(projection.blocks, minimumBlockMinutes) { timelineVisualLanes(projection.blocks, minimumBlockMinutes.toDouble()) }
    val laneCount = visual.values.maxOfOrNull { it.second } ?: 1

    fun updateDrag(pointer: Offset) {
        val old = drag ?: return
        val destination=if(old.block!=null && !old.resize && moveToDay!=null) dateDropBounds.entries.firstOrNull {it.value.contains(pointer)}?.key else null
        if(destination!=null) {
            if(destination!=dateDropOffset) {
                dateDropOffset=destination;dateDropExpected=null;dateDropStart=null;dateDropError=null
                try {
                    val target=TimelineDay(day.date.plusDays(destination.toLong()),day.zone)
                    dateDropStart=TimelineTime.onDate(old.block!!.start,target)
                    dateDropExpected=TimelineMoveBaseline.capture(latestState,requireNotNull(old.scheduleBaseline),target.planId)
                } catch(e:Exception) {dateDropError=e.message ?: "无法移到这个日期，请用调整时间查看"}
            }
            // The original card remains composed on this date; its pointer recognizer owns the whole gesture.
            drag=old.copy(pointerRoot=pointer,start=old.block!!.start,minutes=old.block.durationMinutes)
            return
        }
        dateDropOffset=null;dateDropExpected=null;dateDropStart=null;dateDropError=null
        if (old.block == null) {
            val minute = TimelineTime.snapMinute(((pointer.y - gridBounds.top) / pxPerMinute).toDouble())
                .coerceIn(0, (day.minutes - 15).coerceAtLeast(0))
            drag = old.copy(pointerRoot = pointer, start = TimelineTime.instantAt(day, minute))
            val target = rowBounds.entries.firstOrNull { it.value.contains(pointer) }?.key
            if (target != null && target != old.task.id) {
                val order = (previewOrder ?: old.originalOrder).toMutableList()
                val targetIndex = order.indexOf(target)
                if (targetIndex >= 0) { order.remove(old.task.id); order.add(targetIndex.coerceAtMost(order.size), old.task.id); previewOrder = order }
            }
        } else {
            val rawDelta = (pointer.y - old.originRoot.y + scroll.value - old.initialScroll) / pxPerMinute
            val delta = if (rawDelta < 0) -TimelineTime.snapMinute(-rawDelta.toDouble()) else TimelineTime.snapMinute(rawDelta.toDouble())
            if (old.resize) {
                val minutes = (old.block.durationMinutes + delta).coerceIn(1, 1440)
                drag = old.copy(pointerRoot = pointer, minutes = minutes)
            } else {
                val originalMinute = Duration.between(day.start, old.block.start).toMinutes().toInt()
                // A continuation can be resized here; moving it requires its source day's editor.
                val minute = if (delta == 0) originalMinute else TimelineTime.snapMinute((originalMinute + delta).toDouble()).coerceIn(0, (day.minutes - 15).coerceAtLeast(0))
                drag = old.copy(pointerRoot = pointer, start = TimelineTime.instantAt(day, minute))
            }
        }
    }
    fun finishDrag() {
        val finished = drag ?: return
        val crossDate=dateDropOffset
        val crossExpected=dateDropExpected
        val crossStart=dateDropStart
        drag = null
        dateDropOffset=null;dateDropExpected=null;dateDropStart=null
        val order = previewOrder
        previewOrder = null
        val block = finished.block
        if(block!=null && crossDate!=null) {
            if(crossExpected!=null && crossStart!=null) moveToDay?.invoke(block,crossStart,crossExpected)
            return
        }
        if (block != null) {
            val baseline = finished.scheduleBaseline ?: return
            if (finished.start != block.start || finished.minutes != block.durationMinutes)
                changeSchedule(block, finished.start, finished.minutes, baseline)
        } else if (viewport.contains(finished.pointerRoot) && finished.pointerRoot.x >= gridBounds.left + with(density) { gutterWidth.toPx() }) {
            scheduleTask(finished.task, day, finished.start, finished.scheduleBaseline)
        } else if (order != null && order != finished.originalOrder && finished.orderBaseline != null && rowBounds.values.any { it.contains(finished.pointerRoot) }) {
            reorder(day, order, finished.orderBaseline)
        }
    }
    fun cancelDrag() { drag = null; previewOrder = null; dateDropOffset=null;dateDropExpected=null;dateDropStart=null;dateDropError=null }

    // Keep dragging usable at either edge without committing intermediate positions.
    LaunchedEffect(currentDrag != null) {
        while (drag != null) {
            val active = drag ?: break
            val edge = with(density) { 48.dp.toPx() }
            val speed = when {
                dateDropOffset!=null -> 0f
                active.pointerRoot.y < viewport.top + edge && active.pointerRoot.y >= viewport.top -> -12f
                active.pointerRoot.y > viewport.bottom - edge && active.pointerRoot.y <= viewport.bottom -> 12f
                else -> 0f
            }
            if (speed != 0f && viewport.height > 0) {
                scroll.scrollTo((scroll.value + speed.roundToInt()).coerceIn(0, scroll.maxValue))
                updateDrag(active.pointerRoot)
            }
            if (active.block == null && trayBounds.contains(active.pointerRoot)) {
                val traySpeed = when {
                    active.pointerRoot.y < trayBounds.top + edge -> -10
                    active.pointerRoot.y > trayBounds.bottom - edge -> 10
                    else -> 0
                }
                if (traySpeed != 0) trayScroll.scrollTo((trayScroll.value + traySpeed).coerceIn(0, trayScroll.maxValue))
            }
            delay(32)
        }
    }
    LaunchedEffect(day.planId, revealAt) {
        cancelDrag()
        if(positionedPlan==day.planId && positionedReveal==revealAt?.toString())return@LaunchedEffect
        val initial = if(revealAt!=null && revealAt>=day.start && revealAt<day.end) {
            (Duration.between(day.start,revealAt).toMinutes()-30).coerceAtLeast(0)
        } else if (now >= day.start && now < day.end) {
            (Duration.between(day.start, now).toMinutes() - 90).coerceAtLeast(0)
        } else 8 * 60L
        scroll.scrollTo((initial * pxPerMinute).roundToInt())
        positionedPlan=day.planId;positionedReveal=revealAt?.toString()
    }
    LaunchedEffect(revealUnscheduledKey) {
        if (revealUnscheduledKey > 0 && revealUnscheduledKey != handledRevealKey) {
            handledRevealKey = revealUnscheduledKey
            expanded = true
            selectedBlockKey = null
            // Wait for the expanded tray's measurement before revealing an appended task.
            withFrameNanos { }
            withFrameNanos { }
            trayScroll.scrollTo(trayScroll.maxValue)
        }
    }

    Column(modifier.fillMaxSize().testTag("today_timeline")) {
        TodayDateHeader(day,now,onSelectDate)
        if (plannedLoad.personalMinutes != 0L || plannedLoad.checkMinutes != 0L || plannedLoad.unplannedCount != 0) {
            val summary=listOfNotNull(
                if(plannedLoad.personalMinutes>0) "自己做 ${plannedLoad.personalMinutes} 分钟" else null,
                if(plannedLoad.checkMinutes>0) "检查结果 ${plannedLoad.checkMinutes} 分钟" else null,
                if(plannedLoad.unplannedCount>0) "还有 ${plannedLoad.unplannedCount} 件没定做多久" else null).joinToString(" · ")
            Text(summary,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).testTag("timeline_planned_load")
                    .semantics { contentDescription = "计划投入：个人 ${plannedLoad.personalMinutes} 分钟，检查 ${plannedLoad.checkMinutes} 分钟，${plannedLoad.unplannedCount} 项未设投入" })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("timeline_unscheduled_toggle")) {
                Text("待安排 · ${projection.unscheduled.size}  ${if (expanded) "收起" else "展开"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (dailyWinTask != null) TextButton(onClick = { onOpenTask(dailyWinTask) }, contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("timeline_daily_win")
                    .semantics { contentDescription = "当天重点：${dailyWinTask.title()}，查看详情" }) {
                Text("★ ${dailyWinTask.title()}", color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (expanded) {
            val unscheduled = projection.unscheduled.associateBy { it.id }
            val originalIds = projection.unscheduled.map { it.id }
            val ids = previewOrder ?: originalIds
            Column(Modifier.fillMaxWidth().heightIn(max = 216.dp).onGloballyPositioned { trayBounds = it.boundsInRoot() }.verticalScroll(trayScroll).padding(horizontal = 12.dp).testTag("timeline_unscheduled_list")) {
                if (ids.isEmpty()) Text("这里没有待安排事项", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                ids.forEach { id ->
                    val task = unscheduled[id] ?: return@forEach
                    val focused = task.id == dailyWinTask?.id
                    key(id) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .onGloballyPositioned {
                                rowBounds = rowBounds + (id to it.boundsInRoot())
                                rowOrigins = rowOrigins + (id to it.positionInRoot())
                            }
                            .clip(MaterialTheme.shapes.small)
                            .background(when { drag?.task?.id == id -> MaterialTheme.colorScheme.primaryContainer; focused -> MaterialTheme.colorScheme.secondaryContainer; else -> MaterialTheme.colorScheme.surface })
                            .pointerInput(id, day.planId, originalIds) {
                                detectDragGesturesAfterLongPress(onDragStart = { offset ->
                                    val origin = (rowOrigins[id] ?: Offset.Zero) + offset
                                    drag = TimelineDrag(task, null, false, origin, origin, scroll.value, day.start, 30,
                                        TimelineScheduleBaseline.from(latestState, day.planId, task.id),
                                        TimelineOrderBaseline.from(latestState, day.planId), originalIds)
                                }, onDrag = { change, amount -> change.consume(); drag?.let { updateDrag(it.pointerRoot + amount) } },
                                    onDragEnd = ::finishDrag, onDragCancel = ::cancelDrag)
                            }.clickable { onOpenTask(task) }.padding(start = 12.dp).testTag("timeline_unscheduled_$id")
                            .semantics { contentDescription = "${if (focused) "当天重点，" else ""}${task.title()}，待安排。长按拖动排序或拖到时间轴。" },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (focused) "★ ${task.title()}" else task.title(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                                color = if (focused) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { onScheduleTask(task, day, null, null) }, modifier = Modifier.heightIn(min = 48.dp).testTag("timeline_schedule_$id")) { Text("定时") }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
        // This slot never changes height when selection or dragging begins, keeping the axis still.
        Box(Modifier.fillMaxWidth().height(if (density.fontScale > 1.3f) 64.dp else 48.dp).background(if (currentDrag != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .then(if(currentDrag?.block!=null && !currentDrag.resize && onMoveToDay!=null) Modifier.testTag("timeline_drag_preview") else Modifier), contentAlignment = Alignment.CenterStart) {
            if(currentDrag?.block!=null && !currentDrag.resize && onMoveToDay!=null) {
                Row(Modifier.fillMaxSize().testTag("timeline_date_drop_targets")) {
                    listOf(-1,1).forEach {offset->
                        val target=day.date.plusDays(offset.toLong())
                        Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp)
                            .onGloballyPositioned {dateDropBounds=dateDropBounds+(offset to it.boundsInRoot())}
                            .background(if(dateDropOffset==offset) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,MaterialTheme.shapes.small)
                            .testTag(if(offset<0) "timeline_drop_previous_day" else "timeline_drop_next_day")
                            .semantics {contentDescription="拖到${target}，保持${timelineTime(currentDrag.block.start,day)}和${currentDrag.block.durationMinutes}分钟"},contentAlignment=Alignment.Center) {
                            Text(if(dateDropOffset==offset && dateDropError!=null) dateDropError!! else "${if(offset<0) "前一天" else "后一天"} · ${target.format(DateTimeFormatter.ofPattern("M月d日"))}\n${timelineTime(currentDrag.block.start,day)} · ${currentDrag.block.durationMinutes} 分钟，松手移动",
                                style=MaterialTheme.typography.bodySmall,maxLines=2,color=if(dateDropError!=null && dateDropOffset==offset) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier=Modifier.padding(horizontal=4.dp))
                        }
                    }
                }
            } else if(currentDrag==null && dateDropError!=null) {
                Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically) {
                    Text(dateDropError!!,Modifier.weight(1f).padding(start=12.dp).testTag("timeline_move_date_error"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error,maxLines=2)
                    TextButton(onClick={dateDropError=null}) {Text("关闭")}
                }
            } else if (currentDrag == null && selectedBlock != null) {
                val selectedTask = selectedBlock.task
                val completed = selectedTask.completed()
                Row(Modifier.fillMaxSize().padding(horizontal = 4.dp).testTag("timeline_context_actions"), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onComplete(selectedTask) }, enabled = !completed,
                        contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.weight(1f).fillMaxHeight().testTag("timeline_complete_${selectedTask.id}")) {
                        Text(if (completed) "已完成" else "完成")
                    }
                    TextButton(onClick = { onEditSchedule(selectedBlock) }, enabled = !completed,
                        contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.weight(1.35f).fillMaxHeight().testTag("timeline_edit_${selectedTask.id}")) { Text("调整时间") }
                    TextButton(onClick = { onOpenTask(selectedTask) }, contentPadding = PaddingValues(horizontal = 6.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("timeline_detail_${selectedTask.id}")) { Text("详情") }
                    TextButton(onClick = { selectedBlockKey = null }, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.width(48.dp).fillMaxHeight().testTag("timeline_clear_selection").semantics { contentDescription = "关闭事项操作" }) { Text("×", style = MaterialTheme.typography.titleLarge) }
                }
            } else {
                Text(if (currentDrag != null) "${timelineTime(currentDrag.start, day)}–${timelineTime(currentDrag.start.plusSeconds(currentDrag.minutes * 60L), day)} · ${currentDrag.minutes} 分钟"
                    else if (laneCount >= 3) "左右滑动查看重叠事项；长按底边拖动调整时长"
                    else "点按空白添加；长按移动，长按底边拖动调整时长",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp).testTag(if (currentDrag != null) "timeline_drag_preview" else "timeline_gesture_hint")
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { viewport = it.boundsInRoot() }.testTag("timeline_frame")) {
            // Include the minimum touch height in lane allocation: neighboring short blocks never cover each other.
            val availableWidth = maxWidth
            val sharedWidth = (availableWidth - gutterWidth - 8.dp) / laneCount
            val laneWidth = if (laneCount <= 2) sharedWidth.coerceAtLeast(48.dp)
                else maxOf(sharedWidth, (152f * max(1f, density.fontScale * .85f)).dp)
            val gridWidth = maxOf(availableWidth, laneWidth * laneCount + gutterWidth + 8.dp)
            Box(Modifier.fillMaxSize().verticalScroll(scroll).horizontalScroll(horizontal).testTag("timeline_viewport")) {
                Box(Modifier.width(gridWidth).height(hourHeight * (day.minutes / 60f) + maxOf(minimumBlockHeight, 96.dp)).onGloballyPositioned {
                    // positionInRoot is deliberately unclipped: a scrolled grid still starts above the viewport.
                    val origin = it.positionInRoot()
                    gridBounds = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
                }) {
                    val hours = ceil(day.minutes / 60.0).toInt()
                    repeat(hours) { hourIndex ->
                        val instant = TimelineTime.instantAt(day, hourIndex * 60)
                        val clockHour = instant.atZone(day.zone).hour
                        Row(Modifier.offset(y = hourHeight * hourIndex).fillMaxWidth().height(hourHeight)) {
                            Text(timelineTime(instant, day), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, modifier = Modifier.width(gutterWidth).heightIn(min = 48.dp).padding(top = 4.dp, start = 4.dp).testTag("timeline_hour_$clockHour"))
                            Box(Modifier.weight(1f).fillMaxHeight().testTag("timeline_create_$clockHour")
                                .semantics { contentDescription = "在 ${timelineTime(instant, day)} 新建时间块"; onClick { onCreateAt(day, instant); true } }
                                .pointerInput(day.planId, hourIndex) { detectTapGestures { point ->
                                    val minute = TimelineTime.snapMinute((hourIndex * 60 + point.y / pxPerMinute).toDouble()).coerceIn(0, day.minutes - 15)
                                    onCreateAt(day, TimelineTime.instantAt(day, minute))
                                } }) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                HorizontalDivider(Modifier.padding(top = hourHeight / 2), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                            }
                        }
                    }
                    projection.blocks.forEach { block ->
                        key(block.key) {
                            val active = currentDrag?.takeIf { it.block?.key == block.key }
                            val start = active?.start ?: block.start
                            val duration = active?.minutes ?: block.durationMinutes
                            val startMinute = max(0.0, Duration.between(day.start, start).toMillis() / 60000.0)
                            val endMinute = minOf(day.minutes.toDouble(), Duration.between(day.start, start.plusSeconds(duration * 60L)).toMillis() / 60000.0)
                            val lane = visual[block.key]?.first ?: block.lane
                            val eventHeight = maxOf(minimumBlockHeight, hourHeight * ((endMinute - startMinute).toFloat() / 60f))
                            var cardBounds by remember { mutableStateOf(Rect.Zero) }
                            val eventBaseline = TimelineScheduleBaseline.from(state, block.sourcePlanId, block.task.id)
                            val completed = block.task.completed()
                            val focused = !completed && block.task.id == dailyWinTask?.id
                            val canMove = !completed && block.start >= day.start
                            val eventColor = when { completed -> MaterialTheme.colorScheme.onSurfaceVariant; focused || active != null -> MaterialTheme.colorScheme.onSecondaryContainer; else -> MaterialTheme.colorScheme.onPrimaryContainer }
                            fun begin(offset: Offset) {
                                val resize = offset.y > cardBounds.height - with(density) { 24.dp.toPx() }
                                if (!resize && !canMove) return
                                val origin = cardBounds.topLeft + offset
                                selectedBlockKey = block.key
                                dateDropError=null;dateDropOffset=null;dateDropExpected=null;dateDropStart=null
                                drag = TimelineDrag(block.task, block, resize, origin, origin, scroll.value, block.start, block.durationMinutes,
                                    eventBaseline, null)
                            }
                            Box(Modifier.offset { IntOffset(with(density) { (gutterWidth + laneWidth * lane).roundToPx() }, (startMinute * pxPerMinute).roundToInt()) }
                                .width(laneWidth - 6.dp).height(eventHeight).onGloballyPositioned {
                                    val origin = it.positionInRoot()
                                    cardBounds = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
                                }
                                .clip(RoundedCornerShape(8.dp))
                                .background(when { active != null || focused -> MaterialTheme.colorScheme.secondaryContainer; completed -> MaterialTheme.colorScheme.surfaceContainer; else -> MaterialTheme.colorScheme.primaryContainer })
                                .then(if (selectedBlockKey == block.key) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                                .testTag("timeline_move_${block.task.id}")
                                // One recognizer owns the whole card. The initial bottom 24dp chooses resize.
                                .pointerInput(block.key, eventBaseline, canMove, completed) {
                                    if (!completed) detectDragGesturesAfterLongPress(onDragStart = ::begin,
                                        onDrag = { change, amount -> if (drag != null) { change.consume(); drag?.let { updateDrag(it.pointerRoot + amount) } } },
                                        onDragEnd = ::finishDrag, onDragCancel = ::cancelDrag)
                                }
                                .clickable { selectedBlockKey = block.key }
                                .semantics {
                                    selected = selectedBlockKey == block.key
                                    contentDescription = "${if (focused) "当天重点，" else ""}${block.task.title()}，${timelineTime(start, day)}至${timelineTime(start.plusSeconds(duration * 60L), day)}，$duration 分钟。${when {completed -> "已完成；"; canMove -> "长按移动，长按底边拖动调整时长；"; else -> "从前一日继续；"}}点击显示事项操作。"
                                    customActions = buildList {
                                        add(CustomAccessibilityAction("查看详情") { onOpenTask(block.task); true })
                                        if (!completed) {
                                            add(CustomAccessibilityAction("完成") { onComplete(block.task); true })
                                            add(CustomAccessibilityAction("更改时间或时长") { onEditSchedule(block); true })
                                        }
                                    }
                                }) {
                                val title = when { completed -> "已完成 · ${block.task.title()}"; focused -> "★ ${block.task.title()}"; else -> block.task.title() }
                                if (eventHeight < (52f * density.fontScale).dp) {
                                    Row(Modifier.fillMaxSize().padding(horizontal = 5.dp).testTag("timeline_event_${block.key}"), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = eventColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${duration}分", style = MaterialTheme.typography.bodySmall, color = eventColor, maxLines = 1)
                                    }
                                } else {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp).testTag("timeline_event_${block.key}")) {
                                        Text(title, style = MaterialTheme.typography.bodyLarge, color = eventColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(timelineTime(start, day), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = eventColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${duration}分", style = MaterialTheme.typography.bodySmall, color = eventColor, maxLines = 1)
                                        }
                                    }
                                }
                                // The tagged handle has no pointer recognizer; its parent owns resize and cancel.
                                if (!completed) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp).testTag("timeline_resize_${block.task.id}")
                                    .semantics { contentDescription = "长按底部调整${block.task.title()}的时长"; onClick { onEditSchedule(block); true } }, contentAlignment = Alignment.BottomCenter) {
                                    Box(Modifier.padding(bottom = 2.dp).width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(eventColor.copy(alpha = .65f)))
                                }
                            }
                        }
                    }
                    if (now >= day.start && now < day.end) {
                        val minute = Duration.between(day.start, now).toMillis() / 60000f
                        val labelHeight = (20f * density.fontScale).dp
                        Row(Modifier.offset(y = hourHeight * (minute / 60f) - labelHeight / 2).height(labelHeight).fillMaxWidth().testTag("timeline_now"), verticalAlignment = Alignment.CenterVertically) {
                            Text(timelineTime(now, day), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                maxLines = 1, modifier = Modifier.width(gutterWidth).background(MaterialTheme.colorScheme.surface))
                            HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (currentDrag?.block == null && currentDrag != null && viewport.contains(currentDrag.pointerRoot)) {
                        val minute = Duration.between(day.start, currentDrag.start).toMinutes()
                        Box(Modifier.offset(x = gutterWidth, y = hourHeight * (minute / 60f)).width(gridWidth - gutterWidth - 8.dp).height(hourHeight / 2)
                            .clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer)) {
                            Text("${timelineTime(currentDrag.start, day)} · ${currentDrag.task.title()}", style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Assign lanes by rendered height, keeping short neighboring blocks accessible. */
private fun timelineVisualLanes(blocks: List<TimelineBlock>, minimumMinutes: Double): Map<String, Pair<Int, Int>> {
    val result = mutableMapOf<String, Pair<Int, Int>>()
    val ends = mutableListOf<Double>()
    val assignments = mutableMapOf<String, Int>()
    for (block in blocks.sortedWith(compareBy<TimelineBlock> { it.startMinute }.thenBy { it.key })) {
        val lane = ends.indexOfFirst { it <= block.startMinute }.let { if (it < 0) ends.size else it }
        val end = max(block.endMinute, block.startMinute + minimumMinutes)
        if (lane == ends.size) ends.add(end) else ends[lane] = end
        assignments[block.key] = lane
    }
    assignments.forEach { (key, lane) -> result[key] = lane to ends.size.coerceAtLeast(1) }
    return result
}
