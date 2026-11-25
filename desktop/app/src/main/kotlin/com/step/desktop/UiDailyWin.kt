package com.step.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pare.app.*
import java.time.Instant

/** Choosing a focus also includes the task in the selected day, in one local transaction. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun DailyWinButton(task:Entity,state:SyncState,planId:String,work:UiWork,modifier:Modifier=Modifier) {
    if(!taskCanBePlanned(task))return
    val win=planWin(state,planId,task.id)
    val included=DayTaskSelection.contains(state,task.id,planId)
    val day=planningDayLabel(planId)
    val hint=when {
        win->"取消${day}重点"
        included->"设为${day}重点"
        else->"加入${day}并设为重点"
    }
    TooltipArea(tooltip={
        Surface(shape=MaterialTheme.shapes.small,shadowElevation=4.dp,color=MaterialTheme.colorScheme.inverseSurface) {
            Text(hint,Modifier.padding(horizontal=10.dp,vertical=6.dp),color=MaterialTheme.colorScheme.inverseOnSurface,style=MaterialTheme.typography.bodySmall)
        }
    },delayMillis=450) {
        IconButton(
            onClick={work.edit {DayTaskActions.setWin(it,task.id,!win,planId,Instant.now())}},
            enabled=!work.busy&&task.conflict==null&&task.conflictGroup.isEmpty(),
            modifier=modifier.testTag("day_win_${task.id}").size(36.dp)
        ) {
            StepIcon(if(win)Icons.Outlined.Star else Icons.Outlined.StarBorder,hint,if(win)StepGold else StepMuted)
        }
    }
}
