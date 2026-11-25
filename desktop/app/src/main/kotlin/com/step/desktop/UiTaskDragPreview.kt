package com.step.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pare.app.*
import kotlin.math.roundToInt

/** Paint-only snapshot outside scrolling cards; no actions, pointer handlers or row registration. */
@Composable internal fun BoxScope.TaskDragPreview(controller:UiTaskDrag,origin:Offset,planId:String) {
    val drag=controller.active?:return
    val task=drag.shown.items["task:${drag.id}"]?:return
    val density=LocalDensity.current
    val width=with(density){drag.previewBounds.width.toDp()}
    val height=with(density){drag.previewBounds.height.toDp()}
    val at=drag.pointer-drag.grabOffset-origin
    Box(Modifier.offset {IntOffset(at.x.roundToInt(),at.y.roundToInt())}
        .requiredWidth(width).heightIn(min=height).alpha(.82f)
        .shadow(7.dp,MaterialTheme.shapes.small,clip=false)
        .background(LocalStepPalette.current.raised,MaterialTheme.shapes.small)
        .border(1.dp,StepLine,MaterialTheme.shapes.small)
        .testTag("task_drag_preview").semantics(mergeDescendants=true) {}) {
        Column(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                StepIcon(Icons.Outlined.RadioButtonUnchecked,color=StepMuted)
                Text(task.title(),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.weight(1f).testTag("task_drag_feedback"))
                task.content().number("estimatedMinutes")?.let {Text("${it}m",style=MaterialTheme.typography.bodySmall,color=StepMuted)}
                StepIcon(Icons.Outlined.LightMode,color=if(DayTaskSelection.contains(drag.shown,task.id,planId))StepGold.copy(alpha=.65f)else StepMuted)
                StepIcon(if(planWin(drag.shown,planId,task.id))Icons.Outlined.Star else Icons.Outlined.StarBorder,color=StepMuted)
                StepIcon(Icons.Outlined.MoreHoriz,color=StepMuted)
            }
            val next=task.content().str("nextAction")
            if(next.isNotBlank())Text("下一步 · $next",style=MaterialTheme.typography.bodySmall,color=StepMuted,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(start=28.dp))
        }
    }
}
