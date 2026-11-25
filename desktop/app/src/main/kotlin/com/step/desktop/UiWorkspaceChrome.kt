package com.step.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pare.app.*
import java.time.Instant
import java.time.LocalDate

/** Mac's four workspaces remain the primary map on both desktop platforms. */
@Composable internal fun WorkspaceHeader(
    page:String,onPage:(String)->Unit,planId:String,date:LocalDate,onDate:(LocalDate)->Unit,
    onCapture:()->Unit,onAI:()->Unit,onSettings:()->Unit,onCreate:()->Unit,onPick:()->Unit,
    onSearch:()->Unit,initialized:Boolean,onKeyboard:()->Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact=maxWidth<1000.dp
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("整理",style=MaterialTheme.typography.headlineLarge,modifier=Modifier.testTag("workspace_title"))
                if(page in setOf("四象限","今天"))Box(Modifier.weight(1f)) {PlanningDateHeader(planId,date,onDate)}
                else Spacer(Modifier.weight(1f))
                StepTextButton(onSearch,Modifier.testTag("workspace_search")){StepIcon(Icons.Outlined.Search,"搜索 · Ctrl+F")}
                StepOutlinedButton(onAI,enabled=initialized,contentPadding=PaddingValues(horizontal=10.dp,vertical=7.dp)){IconLabel(Icons.Outlined.AutoAwesome,"AI 助手")}
                StepTextButton({onPage("回顾")},Modifier.testTag("nav_回顾")){
                    StepIcon(Icons.Outlined.DateRange,"本周回顾");if(!compact){Spacer(Modifier.width(6.dp));Text("本周回顾")}
                }
                StepTextButton(onSettings,Modifier.testTag("workspace_settings")){
                    StepIcon(Icons.Outlined.Settings,"设置");if(!compact){Spacer(Modifier.width(6.dp));Text("设置")}
                }
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f,false).horizontalScroll(rememberScrollState()).stepPanelSurface(radius=10.dp).padding(3.dp)) {
                    listOf("四象限" to "四象限","今天" to "今日计划","资料库" to "搜索与记录","想法" to "想法与项目").forEach {(key,label)->
                        val selected=page==key||(key=="想法"&&page=="目标")
                        Surface(onClick={onPage(key)},modifier=Modifier.testTag("nav_$key").stepSelectionSurface(selected),shape=MaterialTheme.shapes.small,color=Color.Transparent) {
                            Text(label,style=MaterialTheme.typography.titleMedium,color=if(selected)StepGold else StepMuted,
                                modifier=Modifier.padding(horizontal=if(compact)14.dp else 20.dp,vertical=9.dp))
                        }
                    }
                }
                when(page) {
                    "今天" -> {
                        StepTextButton(onPick,enabled=initialized){Text("选择任务")}
                        StepOutlinedButton(onCreate,enabled=initialized,modifier=Modifier.testTag("create_day_task")){IconLabel(Icons.Outlined.Add,"添加${planningDayLabel(planId)}任务")}
                    }
                    "想法","目标" -> {
                        StepTextButton({onPage(if(page=="目标")"想法"else"目标")},Modifier.testTag(if(page=="目标")"back_to_ideas"else"nav_目标")){IconLabel(Icons.Outlined.Flag,if(page=="目标")"返回想法"else"长期目标")}
                        StepOutlinedButton(onCreate,enabled=initialized){IconLabel(Icons.Outlined.Add,if(page=="目标")"新建目标"else"新建想法")}
                    }
                    "四象限" -> {
                        StepTextButton(onKeyboard,Modifier.testTag("organize_keyboard"),enabled=initialized){StepIcon(Icons.Outlined.Keyboard,"键盘整理：上下选择，1–4归类，空格完成，Esc取消")}
                        StepTextButton(onCapture,enabled=initialized){IconLabel(Icons.Outlined.Add,"随手记")}
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun WorkspaceInbox(
    runtime:DesktopRuntime,state:SyncState,expanded:Boolean,onToggle:()->Unit,
    onCapture:()->Unit,onEdit:(String)->Unit,onAI:(String)->Unit,onSettings:()->Unit,selection:OrganizeSelection?=null
) {
    val inbox=state.items.values.filter {it.kind=="inbox"&&!it.deleted}.sortedByDescending {it.content().str("createdAt")}
    val work=rememberWork(runtime)
    val inboxScroll=rememberLazyListState()
    LaunchedEffect(selection,inbox.map {it.id},expanded) {
        if(expanded&&selection?.kind=="inbox")inbox.indexOfFirst {it.id==selection.id}.takeIf {it>=0}?.let {inboxScroll.animateScrollToItem(it)}
    }
    Column(Modifier.width(if(expanded)234.dp else 52.dp).fillMaxHeight().stepSidebarBackground().padding(horizontal=if(expanded)12.dp else 4.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            StepTextButton(onToggle,Modifier.testTag("inbox_toggle"),contentPadding=PaddingValues(8.dp)) {StepIcon(if(expanded)Icons.Outlined.ChevronLeft else Icons.Outlined.Inbox,if(expanded)"收起待整理"else"展开待整理")}
            if(expanded){Text("待整理",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));Text("${inbox.size}",color=StepGold)}
        }
        if(!expanded)Text("${inbox.size}",color=StepGold,modifier=Modifier.align(Alignment.CenterHorizontally))
        else {
            LazyColumn(Modifier.weight(1f),state=inboxScroll,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(inbox.isEmpty())item {Text("都整理完了",color=StepMuted,modifier=Modifier.padding(vertical=20.dp))}
                items(inbox,key={it.id}) {entry->
                    StepCard(Modifier.fillMaxWidth().then(if(selection==OrganizeSelection("inbox",entry.id))Modifier.border(1.dp,StepGold,MaterialTheme.shapes.medium)else Modifier)) {
                        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(entry.title(),maxLines=3,overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth().clickable {onEdit(entry.id)})
                            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                repeat(4){q->StepTextButton({val id=uid();work.edit {organizeInbox(it,entry,q,false,id,Instant.now())}},Modifier.weight(1f),enabled=!work.busy,contentPadding=PaddingValues(4.dp)) {Text("${q+1}",color=LocalStepPalette.current.quadrants[q])}}
                            }
                            Row {
                                StepTextButton({val id=uid();work.edit {IdeaManagementActions.keepInbox(it,entry.id,id,LibraryBaseline.from(entry),Instant.now())}},enabled=!work.busy){Text("留作想法")}
                                StepTextButton({onAI(entry.id)}){Text("AI 整理")}
                            }
                        }
                    }
                }
            }
            WorkFeedback(work)
            StepOutlinedButton(onCapture,Modifier.fillMaxWidth()){IconLabel(Icons.Outlined.Add,"随手记")}
            SyncStatusButton(runtime,onClick=onSettings)
        }
        if(!expanded){Spacer(Modifier.weight(1f));StepTextButton(onCapture,contentPadding=PaddingValues(8.dp)){StepIcon(Icons.Outlined.Add,"随手记")}}
    }
}
