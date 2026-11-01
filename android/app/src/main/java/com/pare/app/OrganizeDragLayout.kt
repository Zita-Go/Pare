package com.pare.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal data class OrganizeDrop(val taskId:String,val quadrant:Int,val beforeId:String?,val baseline:OrganizeMoveBaseline)
private data class OrganizeDrag(val task:Entity,val baseline:OrganizeMoveBaseline,val point:Offset,val origin:Offset=point,val moved:Boolean=false)
private data class OrganizeTarget(val quadrant:Int,val beforeId:String?)

/** One gesture owns its immutable baseline; pointer movement never writes task data. */
@Stable internal class OrganizeDragState(
    val listState:LazyListState,
    private val state:State<SyncState>,
    private val enabled:State<Boolean>,
    private val submit:State<(OrganizeDrop)->Unit>
) {
    private var drag by mutableStateOf<OrganizeDrag?>(null)
    private val rows=mutableStateMapOf<String,Rect>()
    private val headers=mutableStateMapOf<Int,Rect>()
    private val ends=mutableStateMapOf<Int,Rect>()
    internal val targets=mutableStateMapOf<Int,Rect>()
    internal var viewport by mutableStateOf(Rect.Zero)
    internal var containerOrigin by mutableStateOf(Offset.Zero)
    internal var dragThreshold=8f
    internal val active get()=drag!=null
    internal val draggedTitle get()=drag?.task?.title().orEmpty()
    internal val pointer get()=drag?.point
    internal var error by mutableStateOf<String?>(null)
    private fun target():OrganizeTarget? {
        val current=drag ?: return null
        targets.entries.firstOrNull {it.value.contains(current.point)}?.let {return OrganizeTarget(it.key,null)}
        if(!viewport.contains(current.point))return null
        ends.entries.firstOrNull {it.value.contains(current.point)}?.let {return OrganizeTarget(it.key,null)}
        headers.entries.firstOrNull {it.value.contains(current.point)}?.let {
            return OrganizeTarget(it.key,OrganizeActions.tasks(state.value,it.key).firstOrNull {t->t.id!=current.task.id}?.id)
        }
        val row=rows.entries.firstOrNull {it.value.contains(current.point)} ?: return null
        val task=state.value.items["task:${row.key}"] ?: return null
        val q=ContentEditBaseline.from(task).quadrant
        if(task.id==current.task.id)return OrganizeTarget(q,task.id)
        val group=OrganizeActions.tasks(state.value,q).filterNot {it.id==current.task.id}
        val before=if(current.point.y<row.value.center.y) task.id else group.getOrNull(group.indexOfFirst {it.id==task.id}+1)?.id
        return OrganizeTarget(q,before)
    }
    internal fun highlighted(q:Int)=target()?.quadrant==q
    internal fun insertionBefore(id:String)=active && target()?.beforeId==id && drag?.task?.id!=id
    internal fun insertionAtEnd(q:Int)=active && target()==OrganizeTarget(q,null)
    internal fun cancel(){drag=null;targets.clear()}
    internal fun finish(){
        val current=drag;val destination=target();cancel()
        if(current!=null && current.moved && destination!=null && destination.beforeId!=current.task.id && enabled.value)
            submit.value(OrganizeDrop(current.task.id,destination.quadrant,destination.beforeId,current.baseline))
    }
    internal fun begin(point:Offset):Boolean {
        if(!enabled.value)return false
        val id=rows.entries.firstOrNull {it.value.contains(point)}?.key ?: return false
        val item=state.value.items["task:$id"] ?: return false
        return runCatching {OrganizeMoveBaseline.from(state.value,id)}.onSuccess {baseline->
            drag=OrganizeDrag(item,baseline,point);error=null
        }.onFailure {error=it.message}.isSuccess
    }
    internal fun canBegin(point:Offset)=enabled.value && viewport.contains(point) && rows.values.any {it.contains(point)}
    internal fun update(amount:Offset){drag=drag?.let {val next=it.point+amount;it.copy(point=next,moved=it.moved || (next-it.origin).getDistance()>dragThreshold)}}
    @Composable internal fun Task(item:Entity,content:@Composable ()->Unit) {
        DisposableEffect(item.id){onDispose {rows.remove(item.id)}}
        Box(Modifier.fillMaxWidth().onGloballyPositioned {rows[item.id]=it.boundsInRoot()}
            .testTag("organize_drag_${item.id}").alpha(if(drag?.task?.id==item.id)0.55f else 1f)
            .semantics {customActions=quadrantLabels.mapIndexed {q,label->CustomAccessibilityAction("移至$label") {
                if(enabled.value)runCatching {submit.value(OrganizeDrop(item.id,q,null,OrganizeMoveBaseline.from(state.value,item.id)))}
                    .onFailure {error=it.message}.isSuccess else false
            }}}
) {
            content()
            if(insertionBefore(item.id))HorizontalDivider(Modifier.align(Alignment.TopCenter).testTag("organize_insert_${item.id}"),thickness=3.dp,color=MaterialTheme.colorScheme.primary)
        }
    }
    @Composable internal fun Header(q:Int,count:Int) {
        DisposableEffect(q){onDispose {headers.remove(q)}}
        Box(Modifier.fillMaxWidth().onGloballyPositioned {headers[q]=it.boundsInRoot()}.testTag("organize_quadrant_$q")) {
            Row(Modifier.fillMaxWidth().heightIn(min=36.dp).padding(top=if(count>0)4.dp else 0.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PareIcon(PareIcons.Quadrants,null,Modifier.size(16.dp),tint=pareQuadrantColor(q))
                Text(quadrantLabels[q],Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,color=pareQuadrantColor(q))
                Text(if(count==0)"暂无任务" else "$count",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    @Composable internal fun End(q:Int,empty:Boolean) {
        DisposableEffect(q){onDispose {ends.remove(q)}}
        Box(Modifier.fillMaxWidth().height(if(empty)0.dp else 8.dp)
            .onGloballyPositioned {ends[q]=it.boundsInRoot()}.testTag("organize_end_$q"),contentAlignment=Alignment.CenterStart) {
            if(insertionAtEnd(q))HorizontalDivider(Modifier.align(Alignment.TopCenter),thickness=3.dp,color=MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable internal fun OrganizeDragLayout(state:SyncState,enabled:Boolean,modifier:Modifier=Modifier,onMove:(OrganizeDrop)->Unit,content:@Composable (OrganizeDragState)->Unit) {
    val latest=rememberUpdatedState(state);val allowed=rememberUpdatedState(enabled);val move=rememberUpdatedState(onMove)
    val listState=rememberLazyListState()
    val controller=remember {OrganizeDragState(listState,latest,allowed,move)}
    val edge=with(LocalDensity.current){48.dp.toPx()}
    controller.dragThreshold=with(LocalDensity.current){8.dp.toPx()}
    val haptic=LocalHapticFeedback.current
    LaunchedEffect(enabled){if(!enabled)controller.cancel()}
    LaunchedEffect(controller.active){
        while(controller.active){
            withFrameNanos { }
            val p=controller.pointer ?: break
            if(controller.targets.values.none {it.contains(p)}) {
                val rect=controller.viewport
                val speed=when {p.y<rect.top+edge -> -((rect.top+edge-p.y)/edge).coerceIn(0f,1f)*16f
                    p.y>rect.bottom-edge -> ((p.y-rect.bottom+edge)/edge).coerceIn(0f,1f)*16f;else->0f}
                if(speed!=0f)listState.scrollBy(speed)
            }
        }
    }
    BoxWithConstraints(modifier.onGloballyPositioned {controller.containerOrigin=it.boundsInRoot().topLeft}.then(if(!enabled)Modifier else Modifier.pointerInput(controller) {
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            if(!controller.canBegin(controller.containerOrigin+down.position))return@awaitEachGesture
            val held=awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if(!controller.begin(controller.containerOrigin+held.position))return@awaitEachGesture
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            try {
                while(true) {
                    // Claim the release before child click handlers: a stationary hold must
                    // neither save a move nor open/complete the task underneath it.
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    val change=event.changes.firstOrNull {it.id==held.id} ?: break
                    if(change.isConsumed || event.changes.count {it.pressed}>1)break
                    controller.update(change.positionChange())
                    change.consume()
                    if(!change.pressed){controller.finish();break}
                }
            } finally {controller.cancel()}
        }
    })) {
        Column(Modifier.fillMaxSize()) {
        if(controller.active)Surface(Modifier.fillMaxWidth().testTag("organize_drop_targets"),
            color=MaterialTheme.colorScheme.surfaceContainer,shape=MaterialTheme.shapes.medium,shadowElevation=6.dp) {
            Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("拖到象限，松手移动",style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(horizontal=8.dp))
                for(pair in listOf(0..1,2..3))Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    for(q in pair)Surface(Modifier.weight(1f).heightIn(min=48.dp)
                        .onGloballyPositioned {controller.targets[q]=it.boundsInRoot()}.testTag("organize_drop_$q")
                        .semantics {stateDescription=if(controller.highlighted(q))"松手放入此象限" else "可拖入"},
                        shape=MaterialTheme.shapes.small,border=BorderStroke(if(controller.highlighted(q))2.dp else 1.dp,if(controller.highlighted(q))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        color=if(controller.highlighted(q))MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                        Box(Modifier.padding(8.dp),contentAlignment=Alignment.Center) {Text(quadrantLabels[q],style=MaterialTheme.typography.labelMedium)}
                    }
                }
            }
        }
        Box(Modifier.weight(1f).onGloballyPositioned {controller.viewport=it.boundsInRoot()}) {content(controller)}
        }
        controller.pointer?.let {point->
            val width=(maxWidth-16.dp).coerceAtMost(260.dp)
            val density=LocalDensity.current
            val widthPx=with(density){width.toPx()};val heightPx=with(density){64.dp.toPx()}
            val maxX=with(density){maxWidth.toPx()}-widthPx
            val maxY=(with(density){maxHeight.toPx()}-heightPx).coerceAtLeast(0f)
            val local=point-controller.containerOrigin
            Surface(Modifier.offset {IntOffset((local.x-widthPx/2).coerceIn(0f,maxX.coerceAtLeast(0f)).roundToInt(),(local.y-heightPx-24).coerceIn(0f,maxY).roundToInt())}
                .width(width).testTag("organize_drag_preview"),shape=MaterialTheme.shapes.medium,
                color=MaterialTheme.colorScheme.primaryContainer,shadowElevation=8.dp,border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary)) {
                Row(Modifier.clearAndSetSemantics {}.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    PareIcon(PareIcons.Note,null,Modifier.size(20.dp))
                    Text(controller.draggedTitle,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
        }
        controller.error?.let {message->Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(),color=MaterialTheme.colorScheme.errorContainer) {
            Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(message,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={controller.error=null}) {Text("关闭")}
            }
        }}
    }
}
