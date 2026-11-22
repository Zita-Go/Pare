package com.pare.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseTaskForDayDialog(state: SyncState, selectedPlanId: String, onChoose: (Entity) -> Unit,
    saving: Boolean = false, error: String? = null, onDismiss: () -> Unit) {
    val tasks = remember(state, selectedPlanId) { DayTaskSelection.available(state, selectedPlanId) }
    val day = remember(selectedPlanId) { TimelineDay.fromPlanId(selectedPlanId) }
    val groups = tasks.groupBy { it.content()?.timelineInt("quadrant")?.takeIf { q -> q in 0..3 } ?: 1 }
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = !saving, containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("choose_task_for_day")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Text("从四象限选一件", style = MaterialTheme.typography.titleLarge)
            Text("选入 ${day.date.format(DateTimeFormatter.ofPattern("M月d日"))}，时间和投入可以稍后安排。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
            if (tasks.isEmpty()) {
                Text("没有可选任务。四象限中的未完成任务会出现在这里，已选入这一天的任务不会重复显示。",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 20.dp).testTag("choose_task_empty"))
            } else LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("choose_task_list")) {
                (0..3).forEach { quadrant ->
                    val entries = groups[quadrant].orEmpty()
                    if (entries.isNotEmpty()) {
                        item(key = "quadrant:$quadrant") {
                            Text(quadrantLabels[quadrant], style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                        items(entries, key = { it.id }) { task ->
                            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable(enabled = !saving, role = Role.Button, onClickLabel = "选入这一天") { onChoose(task) }
                                .testTag("choose_day_task_${task.id}").padding(vertical = 12.dp)) {
                                Text(task.title(), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                task.content()?.timelineString("nextAction")?.takeIf { it.isNotBlank() }?.let {
                                    Text("下一步：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp).testTag("choose_task_error")) }
            if (saving) Text("正在选入…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp).testTag("choose_task_saving"))
            TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 4.dp).testTag("choose_task_cancel")) { Text("取消") }
        }
    }
}
