package com.step.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pare.app.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.awt.Cursor
import java.time.Instant

/** Drag previews are local; the immutable shown version is checked only when the pointer is released. */
internal class UiTaskDrag(private val work:UiWork,val planId:String?=null) {
    data class Drag(val id:String,val shown:SyncState,val pointer:Offset,val organize:OrganizeMoveBaseline?,val order:TimelineOrderBaseline?)
    var active by mutableStateOf<Drag?>(null);private set
    var message by mutableStateOf<String?>(null)
    val rows=mutableStateMapOf<String,Rect>()
    val groups=mutableStateMapOf<Int,Rect>()
    val scrollAreas=mutableMapOf<Int,Pair<Rect,LazyListState>>()
    private var undo by mutableStateOf<((SyncState)->SyncState)?>(null)
    val canUndo get()=undo!=null
    fun begin(shown:SyncState,id:String,pointer:Offset):Boolean {
        if(work.busy)return false
        return runCatching {
            val order=planId?.let {TimelineOrderBaseline.from(shown,it)}
            require(order==null||id in order.orderedTaskIds){"这项安排来自其他日期，请先在时间轴处理"}
            active=Drag(id,shown,pointer,if(planId==null)OrganizeMoveBaseline.from(shown,id)else null,order);message=null
        }.onFailure {message=it.message}.isSuccess
    }
    fun update(pointer:Offset){active=active?.copy(pointer=pointer)}
    fun cancel(){active=null}
    fun target():Pair<Int,String?>? {
        val drag=active?:return null
        val group=groups.entries.firstOrNull {it.value.contains(drag.pointer)}?.key?:return null
        // Visible completed rows are feedback only; ordering anchors must stay in the active domain.
        val ids=if(planId==null)OrganizeActions.tasks(drag.shown,group).map {it.id}else drag.order!!.orderedTaskIds
        val hit=ids.firstOrNull {rows[it]?.contains(drag.pointer)==true}
        if(hit==drag.id)return group to drag.id
        val remaining=ids.filterNot {it==drag.id}
        val before=if(hit!=null) {
            if(drag.pointer.y<rows.getValue(hit).center.y)hit else remaining.getOrNull(remaining.indexOf(hit)+1)
        } else remaining.firstOrNull {rows[it]?.top?.let {top->top>drag.pointer.y}==true}
        return group to before
    }
    fun finish() {
        val drag=active?:return;val target=target();active=null
        if(target==null||target.second==drag.id)return
        var receipt:((SyncState)->SyncState)?=null
        work.edit({undo=receipt;message=if(planId==null)"位置已保存"else"今日顺序已保存"}) {current->
            val next=if(planId==null)OrganizeActions.move(current,drag.id,target.first,target.second,drag.organize!!,Instant.now())
            else TimelineActions.reorder(current,planId,insertBefore(drag.order!!.orderedTaskIds,drag.id,target.second),drag.order)
            receipt=if(planId==null)OrganizeUndo.capture(current,next)?.let {u->{s:SyncState->u.apply(s)}}
                else TimelineUndo.capture(current,next)?.let {u->{s:SyncState->u.apply(s)}}
            next
        }
    }
    fun undo(){val receipt=undo?:return;work.edit({undo=null;message="已撤销"},receipt)}
    companion object {
        fun insertBefore(ids:List<String>,id:String,before:String?):List<String> {
            val result=ids.filterNot {it==id}.toMutableList()
            val index=before?.let {result.indexOf(it).takeIf {i->i>=0}}?:result.size
            result.add(index,id);return result
        }
    }
}

/** Shared mouse/touch gesture: preserve the original grab point and consume only an actual drag. */
@Composable internal fun Modifier.stepDragHandle(
    tag:String,enabled:Boolean=true,onBegin:(Offset)->Boolean,onMove:(Offset)->Unit,onFinish:()->Unit,onCancel:()->Unit
):Modifier {
    var coordinates by remember {mutableStateOf<LayoutCoordinates?>(null)}
    val begin by rememberUpdatedState(onBegin);val move by rememberUpdatedState(onMove)
    val finish by rememberUpdatedState(onFinish);val cancel by rememberUpdatedState(onCancel)
    val focus=remember {FocusRequester()}
    return testTag(tag).onGloballyPositioned {coordinates=it}.focusRequester(focus)
        .onPreviewKeyEvent {if(it.key==Key.Escape&&it.type==KeyEventType.KeyDown){cancel();true}else false}
        .focusable(enabled)
        .pointerInput(enabled) {
            if(!enabled)return@pointerInput
            awaitEachGesture {
                var started=false
                try {
                    val down=awaitFirstDown(requireUnconsumed=false)
                    val trigger=awaitTouchSlopOrCancellation(down.id) {change,_->
                        val c=coordinates
                        if(c!=null&&c.isAttached&&begin(c.localToRoot(down.position))) {
                            started=true;focus.requestFocus();change.consume();move(c.localToRoot(change.position))
                        }
                    }
                    if(trigger!=null&&started) {
                        val ended=drag(trigger.id) {change->
                            coordinates?.takeIf {it.isAttached}?.let {move(it.localToRoot(change.position))};change.consume()
                        }
                        if(ended)finish()else cancel()
                        started=false
                    }
                } catch(e:CancellationException) {if(started)cancel();throw e}
            }
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TaskDragHandle(controller:UiTaskDrag,state:SyncState,task:Entity) {
    val interaction=remember {MutableInteractionSource()}
    val hovered by interaction.collectIsHoveredAsState()
    val active=controller.active?.id==task.id
    val enabled=!task.completed()
    val hint=if(controller.planId==null)"拖动到其他象限或调整顺序"else"拖动调整今日顺序"
    val cursor=remember {PointerIcon(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR))}
    TooltipArea(tooltip={
        if(controller.active==null)Surface(shape=MaterialTheme.shapes.small,shadowElevation=4.dp,color=MaterialTheme.colorScheme.inverseSurface) {
            Text(hint,Modifier.padding(horizontal=10.dp,vertical=6.dp),color=MaterialTheme.colorScheme.inverseOnSurface,style=MaterialTheme.typography.bodySmall)
        }
    },delayMillis=450) {
        Box(Modifier.width(36.dp).height(44.dp).semantics {contentDescription="$hint：${task.title()}"}
            .pointerHoverIcon(if(enabled)cursor else PointerIcon.Default).hoverable(interaction,enabled)
            .stepDragHandle("${if(controller.planId==null)"quadrant"else"today"}_drag_${task.id}",enabled=enabled,
                onBegin={controller.begin(state,task.id,it)},onMove=controller::update,onFinish=controller::finish,onCancel=controller::cancel)
            .padding(horizontal=3.dp,vertical=4.dp)
            .background(if(active||hovered)LocalStepPalette.current.selection else LocalStepPalette.current.raised.copy(alpha=.55f),MaterialTheme.shapes.small)
            .border(1.dp,if(active||hovered)StepGold.copy(alpha=.65f)else StepLine,MaterialTheme.shapes.small),contentAlignment=Alignment.Center) {
            Icon(Icons.Outlined.DragIndicator,null,tint=if(active||hovered)StepGold else StepMuted,modifier=Modifier.size(22.dp))
        }
    }
}
@Composable internal fun DragFeedback(controller:UiTaskDrag) {
    val active=controller.active;val target=controller.target()
    if(controller.planId!=null) {
        // Today places feedback beside its view controls; leave space for the overview action.
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            if(active!=null)Text(if(target==null)"移到目标位置 · Esc 取消"else"松开调整顺序 · Esc 取消",
                maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=StepGold)
            else controller.message?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=StepMuted)}
            if(controller.canUndo)StepTextButton(controller::undo,enabled=active==null,modifier=Modifier.testTag("today_order_undo")){Text("撤销排序")}
        }
    } else {
    Row(Modifier.fillMaxWidth().heightIn(min=32.dp)
        .then(if(active!=null)Modifier.background(LocalStepPalette.current.selection,MaterialTheme.shapes.small)else Modifier)
        .padding(horizontal=8.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
        if(active!=null) {
            val title=active.shown.items["task:${active.id}"]?.title().orEmpty()
            Text("正在移动 · $title",modifier=Modifier.weight(1f).testTag("task_drag_feedback"),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=StepGold)
            Text(when {
                target==null->"移到目标位置"
                target.second==active.id->"拖到新位置后松开"
                target.first==active.organize?.sourceQuadrant->"松开调整顺序"
                else->"松开移入 · ${quadrantNames[target.first]}"
            },style=MaterialTheme.typography.bodySmall,color=StepGold)
            Text("Esc 取消",style=MaterialTheme.typography.bodySmall,color=StepMuted)
        } else {
            val hint=controller.message?:"拖动左侧把手，可调整顺序或移到其他象限"
            Text(hint,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=StepMuted)
        }
        // Keep the existing receipt in layout while dragging so the board does not jump on a second move.
        if(controller.canUndo)StepTextButton(controller::undo,enabled=active==null,modifier=Modifier.testTag("organize_undo")){Text("撤销移动")}
    }
    }
    LaunchedEffect(active!=null) {
        while(controller.active!=null) {
            val point=controller.active!!.pointer
            controller.scrollAreas.values.firstOrNull {it.first.contains(point)}?.let {(rect,list)->
                val delta=when {point.y<rect.top+36f->-10f;point.y>rect.bottom-36f->10f;else->0f}
                if(delta!=0f)list.scrollBy(delta)
            }
            delay(30)
        }
    }
}
