package com.pare.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun PareHeader(tab: Int, status: String, busy: Boolean, signedIn: Boolean, onAccount: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("PARE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(18.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary)
                    else Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                }
                Spacer(Modifier.width(5.dp))
                Text(status, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            IconButton(onClick = onAccount, modifier = Modifier.size(48.dp).testTag("account_open")
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)) {
                Icon(Icons.Default.Person, if (signedIn) "账号与同步" else "打开账号", Modifier.size(21.dp),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(26.dp))
        Text(listOf("今天", "整理", "想法")[tab], style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(listOf("把精力留给重要的事", "理清轻重，再从容出发", "给每一个念头，生长的空间")[tab],
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PareDock(tab: Int, onTab: (Int) -> Unit, onAdd: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()
        .padding(horizontal = 18.dp, vertical = 12.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth().padding(7.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                listOf("今天", "整理", "想法").forEachIndexed { index, label ->
                    val selected = tab == index
                    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                        .selectable(selected = selected, onClick = { onTab(index) }, role = Role.Tab)
                        .heightIn(min = 62.dp).padding(horizontal = 4.dp, vertical = 7.dp)
                        .testTag(listOf("tab_today", "tab_organize", "tab_ideas")[index]),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        PareGlyph(index, Modifier.size(22.dp), color)
                        Text(label, color = color, style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                Spacer(Modifier.width(9.dp))
                FilledIconButton(onClick = onAdd, shape = RoundedCornerShape(20.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary),
                    modifier = Modifier.size(56.dp).testTag("add_item")) {
                    Icon(Icons.Default.Add, listOf("添加任务", "随手记", "记个想法")[tab], Modifier.size(27.dp))
                }
                Spacer(Modifier.width(3.dp))
            }
        }
    }
}

/** Small purpose-drawn line marks keep the navigation and empty states in one visual family. */
@Composable
internal fun PareGlyph(kind: Int, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val line = 1.6f * unit
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        when (kind) {
            0 -> {
                drawCircle(color, 4.2f * unit, point(12f, 12f), style = Stroke(line))
                repeat(8) { ray ->
                    val angle = ray * Math.PI / 4
                    val start = point(12f + cos(angle).toFloat() * 7.6f, 12f + sin(angle).toFloat() * 7.6f)
                    val end = point(12f + cos(angle).toFloat() * 10f, 12f + sin(angle).toFloat() * 10f)
                    drawLine(color, start, end, line, StrokeCap.Round)
                }
            }
            1 -> {
                listOf(5f, 12f, 19f).forEachIndexed { index, y ->
                    drawCircle(color, 1.5f * unit, point(4f, y))
                    drawLine(color, point(9f, y), point(if (index == 1) 18f else 21f, y), line, StrokeCap.Round)
                }
            }
            else -> {
                val leaves = Path().apply {
                    moveTo(12f * unit, 20f * unit)
                    cubicTo(12f * unit, 13f * unit, 13f * unit, 8f * unit, 20f * unit, 5f * unit)
                    cubicTo(21f * unit, 11f * unit, 17f * unit, 14f * unit, 12f * unit, 14f * unit)
                    moveTo(12f * unit, 17f * unit)
                    cubicTo(5f * unit, 17f * unit, 3f * unit, 13f * unit, 4f * unit, 8f * unit)
                    cubicTo(9f * unit, 8f * unit, 12f * unit, 11f * unit, 12f * unit, 17f * unit)
                }
                drawPath(leaves, color, style = Stroke(line, cap = StrokeCap.Round))
                drawLine(color, point(8f, 22f), point(16f, 22f), line, StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun TodayOverview(tasks: List<Entity>) {
    val done = tasks.count { it.completed() }
    val active = tasks.size - done
    val estimated = tasks.filterNot { it.completed() }.mapNotNull { it.content()?.get("estimatedMinutes")?.jsonPrimitive?.intOrNull }.sum()
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PareGlyph(0, Modifier.size(27.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (active > 0) "$active 件待办" else if (done > 0) "今天的安排已完成" else "今天，由你安排",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(if (estimated > 0) "已估时任务 · 共 $estimated 分钟" else if (tasks.isEmpty()) "从一件小事开始就好" else "按自己的节奏，一件件来",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tasks.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Text("$done / ${tasks.size}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { contentDescription = "已完成 $done 项，共 ${tasks.size} 项" })
                }
            }
            if (tasks.isNotEmpty()) {
                val progress = done.toFloat() / tasks.size
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(MaterialTheme.colorScheme.primary, CircleShape))
                }
            }
        }
    }
}

@Composable
internal fun SectionLabel(title: String, count: Int, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Spacer(Modifier.width(9.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.weight(1f))
        Text(count.toString().padStart(2, '0'), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
internal fun EmptyNote(title: String, detail: String, idea: Boolean = false, action: (@Composable () -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                PareGlyph(if (idea) 2 else 0, Modifier.size(36.dp), MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(2.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            action?.invoke()
        }
    }
}

@Composable
internal fun InboxCard(item: Entity, onEdit: () -> Unit, onOrganize: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(item.title(), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onEdit).padding(vertical = 12.dp).testTag("edit_${item.id}"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOrganize, modifier = Modifier.heightIn(min = 48.dp).testTag("organize_${item.id}")) {
                    Text("整理为任务", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IdeaCard(item: Entity, onEdit: () -> Unit) {
    val content = item.content()
    val entries = (content?.get("entries") as? JsonArray)?.size ?: 0
    val date = ideaDateLabel(content?.get("updatedAt")?.jsonPrimitive?.contentOrNull)
    Surface(onClick = onEdit, modifier = Modifier.fillMaxWidth().testTag("idea_${item.id}"),
        shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PareGlyph(2, Modifier.size(21.dp), MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
                    Text(ideaStageLabel(content?.get("stage")?.jsonPrimitive?.contentOrNull),
                        Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
            Text(item.title(), style = MaterialTheme.typography.titleMedium)
            content?.get("notes")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$entries 条记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                date?.let { Text("更新于 $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EntityCard(item: Entity, win: Boolean, onEdit: () -> Unit, onComplete: () -> Unit,
    onToday: () -> Unit, onWin: (() -> Unit)? = null, inToday: Boolean = true) {
    val completed = item.completed()
    val content = item.content()
    val quadrant = content?.get("quadrant")?.jsonPrimitive?.intOrNull ?: 1
    val accent = if (win) MaterialTheme.colorScheme.primary else pareQuadrantColor(quadrant)
    val border = if (win && !completed) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
    Surface(modifier = Modifier.fillMaxWidth().testTag("task_${item.id}"), shape = RoundedCornerShape(22.dp),
        color = if (win && !completed) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, border)) {
        Column(Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(48.dp).clip(CircleShape).toggleable(value = completed, role = Role.Checkbox,
                    onValueChange = { onComplete() }).testTag("complete_${item.id}")
                    .semantics { contentDescription = (if (completed) "标为未完成：" else "完成：") + item.title() },
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(22.dp).background(if (completed) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                        .border(1.5.dp, if (completed) MaterialTheme.colorScheme.primary else accent.copy(alpha = 0.8f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (completed) Icon(Icons.Default.Check, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).heightIn(min = 48.dp)
                    .clickable(onClick = onEdit).padding(top = 12.dp, bottom = 8.dp).testTag("edit_${item.id}"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (win && !completed) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, null, Modifier.size(12.dp), tint = accent)
                        Spacer(Modifier.width(5.dp))
                        Text("今日重点", style = MaterialTheme.typography.labelSmall, color = accent)
                    }
                    Text(item.title(), style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, lineHeight = 24.sp),
                        color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (completed) TextDecoration.LineThrough else null)
                    content?.get("nextAction")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        Text("下一步 · $it", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    content?.get("note")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(quadrantLabels.getOrElse(quadrant) { "未分类" }, style = MaterialTheme.typography.labelSmall,
                            color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else accent,
                            modifier = Modifier.padding(vertical = 4.dp))
                        content?.get("estimatedMinutes")?.jsonPrimitive?.intOrNull?.let { minutes ->
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Text("$minutes 分钟", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onToday, modifier = Modifier.heightIn(min = 48.dp).testTag("today_${item.id}")
                    .semantics { contentDescription = (if (inToday) "移出今天：" else "加入今天：") + item.title() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    PareGlyph(0, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(5.dp))
                    Text(if (inToday) "移出今天" else "加入今天", style = MaterialTheme.typography.labelSmall)
                }
                if (onWin != null) TextButton(onClick = onWin, modifier = Modifier.heightIn(min = 48.dp).testTag("win_${item.id}")
                    .semantics { contentDescription = (if (win) "取消重点：" else "设为重点：") + item.title() },
                    colors = ButtonDefaults.textButtonColors(contentColor = if (win) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.Star, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (win) "取消重点" else "设为重点", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
