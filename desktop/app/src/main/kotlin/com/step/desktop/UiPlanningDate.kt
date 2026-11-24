package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pare.app.TimelineDay
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun planningDayLabel(planId:String,now:Instant=Instant.now()):String {
    val day=TimelineDay.fromPlanId(planId);val today=now.atZone(day.zone).toLocalDate()
    return if(day.date==today)"今天" else day.date.format(DateTimeFormatter.ofPattern(if(day.date.year==today.year)"M月d日"else"yyyy年M月d日"))
}

@Composable internal fun PlanningDateHeader(planId:String,date:LocalDate,onDate:(LocalDate)->Unit,showContext:Boolean=false,trailing:@Composable RowScope.()->Unit={}) {
    val day=TimelineDay.fromPlanId(planId)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        if(showContext)Text("规划日期",style=MaterialTheme.typography.bodySmall,color=StepMuted)
        StepTextButton({onDate(date.minusDays(1))},modifier=Modifier.testTag("planning_previous_day"),contentPadding=PaddingValues(6.dp)){StepIcon(Icons.Outlined.ChevronLeft,"前一天")}
        Text(date.format(DateTimeFormatter.ofPattern(if(date.year==Instant.now().atZone(day.zone).year)"M月d日 · EEEE"else"yyyy年M月d日 · EEEE",Locale.CHINESE)),style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("planning_date"))
        StepTextButton({onDate(date.plusDays(1))},modifier=Modifier.testTag("planning_next_day"),contentPadding=PaddingValues(6.dp)){StepIcon(Icons.Outlined.ChevronRight,"后一天")}
        StepTextButton({onDate(Instant.now().atZone(day.zone).toLocalDate())},modifier=Modifier.testTag("planning_today")){Text("今天")}
        Spacer(Modifier.weight(1f));trailing()
    }
}
