package com.pare.app

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.*

@Composable
internal fun PareHeader(tab: Int, status: String, busy: Boolean, signedIn: Boolean, onAccount: () -> Unit,
    onAI:(()->Unit)?=null,onReview:(()->Unit)?=null,day:TimelineDay?=null,onChooseDate:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp).testTag("page_topbar"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).heightIn(min=48.dp).then(if(day!=null && onChooseDate!=null)Modifier.clickable(onClick=onChooseDate).testTag("today_choose_date") else Modifier),verticalArrangement=Arrangement.Center) {
            Text(listOf("今天", "整理", "想法")[tab],style=MaterialTheme.typography.headlineSmall)
            if(tab==1)Text("长按任务调整位置",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(day!=null) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(day.date.format(DateTimeFormatter.ofPattern("M月d日 EEEE",Locale.SIMPLIFIED_CHINESE)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f,fill=false))
                    PareIcon(PareIcons.Down,null,Modifier.size(14.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(day.zone.rules!=ZoneId.systemDefault().rules)Text(day.zone.id,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        onAI?.let {TextButton(onClick=it,shape=pareActionShape(),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),
            colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.primary),
            modifier=Modifier.heightIn(min=48.dp).testTag("ai_open")) {Text("AI 助手")}}
        onReview?.let {TextButton(onClick=it,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSurfaceVariant),modifier=Modifier.heightIn(min=48.dp).testTag("review_open")) {Text("回看")}}
        Box {
            IconButton(onClick = onAccount, modifier = Modifier.size(48.dp).testTag("more_workspace")) {
                PareIcon(PareIcons.More, "更多功能与设置：$status", Modifier.size(22.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp), strokeWidth = 1.5.dp)
            else if (signedIn) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(5.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

@Composable
internal fun PareNavigation(tab: Int, onTab: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                    .height(IntrinsicSize.Min)
                    .selectableGroup()
            ) {
                listOf("今天", "整理", "想法").forEachIndexed { index, label ->
                    val selected = tab == index
                    val tag = listOf("tab_today", "tab_organize", "tab_ideas")[index]
                    val color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    val icon = (if (selected)
                        listOf(PareIcons.TodaySelected, PareIcons.QuadrantsSelected, PareIcons.IdeaSelected)
                    else listOf(PareIcons.Today, PareIcons.Quadrants, PareIcons.Idea))[index]
                    // The content sets the height at large font scales; 64dp is only a minimum.
                    Column(
                        Modifier.weight(1f).fillMaxHeight().heightIn(min = 64.dp)
                            .selectable(selected = selected, role = Role.Tab, onClick = { onTab(index) })
                            .testTag(tag).padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                    ) {
                        Box(
                            Modifier.size(width = 64.dp, height = 32.dp)
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            PareIcon(icon, null, Modifier.size(24.dp).testTag("${tag}_icon"), tint = color)
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionLabel(title: String, count: Int, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(16.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.weight(1f))
        Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=8.dp,vertical=3.dp))
        }
    }
}

@Composable
internal fun EmptyNote(title: String, detail: String, icon: Int = PareIcons.Note, action: (@Composable () -> Unit)? = null) {
    Surface(Modifier.fillMaxWidth().padding(vertical=12.dp),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(40.dp),contentAlignment=Alignment.Center) {PareIcon(icon,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)}
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
    }
}

@Composable
internal fun InboxCard(item: Entity, onEdit: () -> Unit, onOrganize: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).background(MaterialTheme.colorScheme.surface).clickable(onClick = onEdit).testTag("inbox_${item.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) {
            PareIcon(PareIcons.Note,null,Modifier.size(19.dp),tint=MaterialTheme.colorScheme.primary)
        }
        Text(item.title(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)
            .clickable(onClick = onEdit).padding(vertical = 12.dp).testTag("edit_${item.id}"))
        TextButton(onClick = onOrganize, modifier = Modifier.heightIn(min = 48.dp).testTag("organize_${item.id}")) { Text("整理") }
    }
    HorizontalDivider(Modifier.padding(start=48.dp),color = MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.65f))
}

@Composable
internal fun IdeaCard(item: Entity, onEdit: () -> Unit) {
    val content = item.content()
    val stage = ideaStageLabel(content?.get("stage")?.jsonPrimitive?.contentOrNull)
    val date = ideaDateLabel(content?.get("updatedAt")?.jsonPrimitive?.contentOrNull)
    val note = content?.get("notes")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val question = content?.get("nextQuestion")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val linkedCount = (content?.get("linkedTaskIDs") as? JsonArray)?.size ?: 0
    Surface(Modifier.fillMaxWidth().padding(bottom=10.dp),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxWidth().heightIn(min = 80.dp).clickable(onClick = onEdit)
        .padding(16.dp).testTag("idea_${item.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            stage?.let {Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.primaryContainer) {
                Text(it,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onPrimaryContainer,modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp))
            }}
            Spacer(Modifier.weight(1f))
            date?.let {Text(it,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        Text(item.title(), style = MaterialTheme.typography.titleMedium)
        (question?.let { "正在探索：$it" } ?: note)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("$linkedCount 项关联行动",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    }
}

@Composable
internal fun EntityCard(item: Entity, win: Boolean, onEdit: () -> Unit, onComplete: () -> Unit,
    onToday: () -> Unit, onWin: (() -> Unit)? = null, inToday: Boolean = true,
    planLabel: String? = null, onPlan: (() -> Unit)? = null, onSupport: (() -> Unit)? = null, onOrigin: (() -> Unit)? = null,
    onExecution:(()->Unit)?=null,onAI:(()->Unit)?=null,onReminder:(()->Unit)?=null,executionLabel:String?=null,
    executionPaused:Boolean=false,onExecutionToggle:(()->Unit)?=null,firstInGroup:Boolean=false,lastInGroup:Boolean=false,onSchedule:(()->Unit)?=null,showTodayAction:Boolean=false) {
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
    val rowShape=RoundedCornerShape(topStart=if(firstInGroup)16.dp else 0.dp,topEnd=if(firstInGroup)16.dp else 0.dp,
        bottomStart=if(lastInGroup)16.dp else 0.dp,bottomEnd=if(lastInGroup)16.dp else 0.dp)
    Column(Modifier.fillMaxWidth().clip(rowShape).background(MaterialTheme.colorScheme.surface).testTag("task_${item.id}")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .background(if (focused) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).toggleable(value = completed, role = Role.Checkbox, onValueChange = { onComplete() })
                .testTag("complete_${item.id}").semantics { contentDescription = (if (completed) "标为未完成：" else "完成：") + item.title() },
                contentAlignment = Alignment.Center) {
                val completionColor=if(focused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                Box(Modifier.size(23.dp).then(if(completed)Modifier.background(completionColor,CircleShape)
                    else Modifier.border(1.5.dp,if(focused)completionColor else MaterialTheme.colorScheme.outline,CircleShape)),contentAlignment=Alignment.Center) {
                    if(completed)PareIcon(PareIcons.Check,null,Modifier.size(16.dp),tint=if(focused)MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary)
                }
            }
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = onEdit)
                .padding(vertical = 10.dp).testTag("edit_${item.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (focused) { PareIcon(PareIcons.Star, "今日重点", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(4.dp)) }
                    Text(item.title(), style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp, lineHeight = 24.sp,
                        color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (completed) TextDecoration.LineThrough else null)
                }
                if (support.isNotBlank()) Text(support, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (focused) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
            if(showTodayAction && !completed) TextButton(onClick=onToday,modifier=Modifier.width(48.dp).heightIn(min=48.dp).testTag("quick_today_${item.id}")
                .semantics {contentDescription=if(inToday)"已加入今天，查看今天" else "加入今天"},contentPadding=PaddingValues(horizontal=2.dp)) {
                PareIcon(if(inToday)PareIcons.Check else PareIcons.Add,null,Modifier.size(12.dp))
                Spacer(Modifier.width(2.dp))
                Text("今天",style=MaterialTheme.typography.labelMedium)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("more_${item.id}")) {
                    PareIcon(PareIcons.More, "更多操作：${item.title()}", Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if(onSchedule!=null && !completed) DropdownMenuItem(text={Text("安排时间")},onClick={menu=false;onSchedule()},
                        modifier=Modifier.heightIn(min=48.dp).testTag("schedule_${item.id}"))
                    if(onExecution!=null && !completed) DropdownMenuItem(text={Text(if(executionLabel==null) "开始专注" else "查看进度")},
                        onClick={menu=false;onExecution()},modifier=Modifier.heightIn(min=48.dp).testTag("run_${item.id}"))
                    DropdownMenuItem(text={Text("任务详情")},onClick={menu=false;onEdit()},modifier=Modifier.heightIn(min=48.dp).testTag("details_${item.id}"))
                }
            }
        }
        if(executionLabel!=null && !completed && onExecutionToggle!=null) Row(Modifier.fillMaxWidth().padding(start=48.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick=onExecutionToggle,modifier=Modifier.heightIn(min=48.dp).testTag("execution_toggle_${item.id}")) {Text(if(executionPaused) "继续 / 查看" else "暂停")}
            onExecution?.let {TextButton(onClick=it,modifier=Modifier.heightIn(min=48.dp)) {Text("结束或调整")}}
        }
        if(!lastInGroup)HorizontalDivider(Modifier.padding(start=48.dp,end=12.dp),color = MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.65f))
    }
}
