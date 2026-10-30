package com.pare.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*

@Composable
internal fun PareHeader(tab: Int, status: String, busy: Boolean, signedIn: Boolean, onAccount: () -> Unit,
    onAI:(()->Unit)?=null,onReview:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp).testTag("page_topbar"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(listOf("今天", "整理", "想法")[tab], style = MaterialTheme.typography.headlineSmall,
            fontSize = 24.sp, lineHeight = 32.sp, modifier = Modifier.weight(1f))
        onAI?.let {TextButton(onClick=it,modifier=Modifier.heightIn(min=48.dp).testTag("ai_open")) {Text("AI 助手")}}
        onReview?.let {TextButton(onClick=it,modifier=Modifier.heightIn(min=48.dp).testTag("review_open")) {Text("回看")}}
        Box {
            IconButton(onClick = onAccount, modifier = Modifier.size(48.dp).testTag("account_open")) {
                Icon(Icons.Default.Person, if (signedIn) "账号与同步：$status" else "打开账号：$status", Modifier.size(22.dp))
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp), strokeWidth = 1.5.dp)
            else if (signedIn) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(5.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

@Composable
internal fun PareNavigation(tab: Int, onTab: (Int) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        listOf("今天", "整理", "想法").forEachIndexed { index, label ->
            NavigationBarItem(selected = tab == index, onClick = { onTab(index) },
                icon = { Icon(listOf(Icons.Default.DateRange, Icons.Default.List, Icons.Default.Create)[index], null, Modifier.size(22.dp)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant),
                label = { Text(label) }, modifier = Modifier.testTag(listOf("tab_today", "tab_organize", "tab_ideas")[index]))
        }
    }
}

@Composable
internal fun SectionLabel(title: String, count: Int, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
internal fun EmptyNote(title: String, detail: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

@Composable
internal fun InboxCard(item: Entity, onEdit: () -> Unit, onOrganize: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onEdit).testTag("inbox_${item.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(item.title(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)
            .clickable(onClick = onEdit).padding(vertical = 12.dp).testTag("edit_${item.id}"))
        TextButton(onClick = onOrganize, modifier = Modifier.heightIn(min = 48.dp).testTag("organize_${item.id}")) { Text("整理") }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun IdeaCard(item: Entity, onEdit: () -> Unit) {
    val content = item.content()
    val stage = ideaStageLabel(content?.get("stage")?.jsonPrimitive?.contentOrNull)
    val date = ideaDateLabel(content?.get("updatedAt")?.jsonPrimitive?.contentOrNull)
    val note = content?.get("notes")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val question = content?.get("nextQuestion")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val linkedCount = (content?.get("linkedTaskIDs") as? JsonArray)?.size ?: 0
    Column(Modifier.fillMaxWidth().heightIn(min = 80.dp).clickable(onClick = onEdit)
        .padding(vertical = 12.dp).testTag("idea_${item.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.title(), style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp, lineHeight = 24.sp)
        (question?.let { "正在探索：$it" } ?: note)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(listOfNotNull(stage, "$linkedCount 项关联行动", date?.let { "更新于 $it" }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, fontSize = 13.sp, lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun EntityCard(item: Entity, win: Boolean, onEdit: () -> Unit, onComplete: () -> Unit,
    onToday: () -> Unit, onWin: (() -> Unit)? = null, inToday: Boolean = true,
    planLabel: String? = null, onPlan: (() -> Unit)? = null, onSupport: (() -> Unit)? = null, onOrigin: (() -> Unit)? = null,
    onExecution:(()->Unit)?=null,onAI:(()->Unit)?=null,onReminder:(()->Unit)?=null,executionLabel:String?=null,
    executionPaused:Boolean=false,onExecutionToggle:(()->Unit)?=null) {
    val completed = item.completed()
    val content = item.content()
    var menu by remember { mutableStateOf(false) }
    val support = listOfNotNull(
        executionLabel,
        planLabel ?: content?.get("estimatedMinutes")?.jsonPrimitive?.intOrNull?.let { "总估时 $it 分钟" },
        content?.get("nextAction")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { "下一步：$it" }
            ?: content?.get("note")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
    val focused = win && !completed
    Column(Modifier.fillMaxWidth().testTag("task_${item.id}")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .background(if (focused) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).toggleable(value = completed, role = Role.Checkbox, onValueChange = { onComplete() })
                .testTag("complete_${item.id}").semantics { contentDescription = (if (completed) "标为未完成：" else "完成：") + item.title() },
                contentAlignment = Alignment.Center) {
                Checkbox(checked = completed, onCheckedChange = null)
            }
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = onEdit)
                .padding(vertical = 10.dp).testTag("edit_${item.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (focused) { Icon(Icons.Default.Star, "今日重点", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(4.dp)) }
                    Text(item.title(), style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp, lineHeight = 24.sp,
                        color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (completed) TextDecoration.LineThrough else null)
                }
                if (support.isNotBlank()) Text(support, style = MaterialTheme.typography.bodySmall, fontSize = 13.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (focused) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("more_${item.id}")) {
                    Icon(Icons.Default.MoreVert, "更多操作：${item.title()}", Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if(onExecution!=null && !completed) DropdownMenuItem(text={Text(if(executionLabel==null) "专注 / 外部推进" else "管理推进")},
                        onClick={menu=false;onExecution()},modifier=Modifier.heightIn(min=48.dp).testTag("run_${item.id}"))
                    if(onAI!=null && !completed) DropdownMenuItem(text={Text("AI 帮我找下一步")},
                        onClick={menu=false;onAI()},modifier=Modifier.heightIn(min=48.dp).testTag("ai_${item.id}"))
                    if (onPlan != null && !completed) DropdownMenuItem(text = { Text("今天怎么推进") },
                        onClick = { menu = false; onPlan() }, modifier = Modifier.heightIn(min = 48.dp).testTag("plan_${item.id}"))
                    if (onSupport != null && !completed) DropdownMenuItem(text = { Text("我卡住了") },
                        onClick = { menu = false; onSupport() }, modifier = Modifier.heightIn(min = 48.dp).testTag("support_${item.id}"))
                    DropdownMenuItem(text = { Text(if (inToday) "移出今天" else "加入今天") },
                        onClick = { menu = false; onToday() }, modifier = Modifier.heightIn(min = 48.dp).testTag("today_${item.id}"))
                    if (onWin != null) DropdownMenuItem(text = { Text(if (win) "取消重点" else "设为今日重点") },
                        onClick = { menu = false; onWin() }, modifier = Modifier.heightIn(min = 48.dp).testTag("win_${item.id}"))
                    if (onOrigin != null) DropdownMenuItem(text = { Text("查看关联想法") },
                        onClick = { menu = false; onOrigin() }, modifier = Modifier.heightIn(min = 48.dp).testTag("origin_${item.id}"))
                    if(onReminder!=null && !completed) DropdownMenuItem(text={Text("提醒我")},onClick={menu=false;onReminder()},
                        modifier=Modifier.heightIn(min=48.dp).testTag("remind_${item.id}"))
                }
            }
        }
        if(executionLabel!=null && !completed && onExecutionToggle!=null) Row(Modifier.fillMaxWidth().padding(start=48.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick=onExecutionToggle,modifier=Modifier.heightIn(min=48.dp).testTag("execution_toggle_${item.id}")) {Text(if(executionPaused) "继续 / 查看" else "暂停")}
            onExecution?.let {TextButton(onClick=it,modifier=Modifier.heightIn(min=48.dp)) {Text("结束或调整")}}
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
