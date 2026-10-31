package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate

/** One quiet toolbar replaces the separate mode and date rows. Hit regions stay 48dp. */
@Composable internal fun TodayViewControls(day:TimelineDay,now:Instant,timeline:Boolean,onTimeline:(Boolean)->Unit,onSelectDate:(LocalDate)->Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=8.dp)) {
        val hasTodayShortcut=day.date!=now.atZone(day.zone).toLocalDate()
        val requiredWidth=172.dp+96.dp+if(hasTodayShortcut)64.dp else 0.dp
        val stacked=maxWidth<requiredWidth || LocalDensity.current.fontScale>1.3f
        @Composable fun modes(modifier:Modifier) {
            Row(modifier.selectableGroup(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf(false to "清单",true to "时间轴").forEach {(value,label)->
                    Surface(selected=timeline==value,onClick={onTimeline(value)},shape=MaterialTheme.shapes.small,
                        color=if(timeline==value)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
                        contentColor=if(timeline==value)MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag(if(value)"today_mode_timeline" else "today_mode_list").semantics {role=Role.Tab}) {
                        Box(Modifier.padding(horizontal=8.dp,vertical=8.dp),contentAlignment=Alignment.Center) {Text(label,style=MaterialTheme.typography.labelLarge)}
                    }
                }
            }
        }
        @Composable fun dates() {
            if(day.date!=now.atZone(day.zone).toLocalDate())TextButton(onClick={onSelectDate(now.atZone(day.zone).toLocalDate())},modifier=Modifier.heightIn(min=48.dp).testTag("timeline_today"),contentPadding=PaddingValues(horizontal=4.dp)) {Text("今天")}
            TextButton(onClick={onSelectDate(day.date.minusDays(1))},modifier=Modifier.size(48.dp).testTag("timeline_previous_day").semantics {contentDescription="前一天"},contentPadding=PaddingValues(0.dp)) {Text("‹",style=MaterialTheme.typography.titleLarge)}
            TextButton(onClick={onSelectDate(day.date.plusDays(1))},modifier=Modifier.size(48.dp).testTag("timeline_next_day").semantics {contentDescription="后一天"},contentPadding=PaddingValues(0.dp)) {Text("›",style=MaterialTheme.typography.titleLarge)}
        }
        if(stacked)Column {
            modes(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {dates()}
        } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            modes(Modifier.width(172.dp));Spacer(Modifier.weight(1f));dates()
        }
    }
}
