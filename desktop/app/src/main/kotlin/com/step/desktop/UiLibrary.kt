package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pare.app.*
import kotlinx.serialization.json.JsonObject
import java.time.Instant

internal object DesktopLibraryQuery {
    fun find(state:SyncState,query:String,scope:LibraryScope,stage:String?):List<Entity> {
        val words=query.trim().split(Regex("\\s+")).filter {it.isNotEmpty()}
        return LibrarySearch.find(state,"",scope,stage).filter {item->
            val c=item.content()
            val text=(listOf(item.displayTitle())+listOf("note","notes","nextAction","nextQuestion","motivation").map {c.str(it)}+
                c.array("entries").filterIsInstance<JsonObject>().map {it.str("text")}+
                c.array("completedSteps").filterIsInstance<JsonObject>().flatMap {listOf(it.str("text"),it.str("definitionOfDone"))}).joinToString(" ")
            words.all {text.contains(it,ignoreCase=true)}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ColumnScope.LibraryPane(runtime:DesktopRuntime,onOpen:(String,String)->Unit) {
    val state by runtime.engine.state.collectAsState()
    val work=rememberWork(runtime)
    var query by remember {mutableStateOf("")}
    var scope by remember {mutableStateOf(LibraryScope.ALL)}
    var stage by remember {mutableStateOf<String?>(null)}
    var pending by remember {mutableStateOf<Pair<String,Entity>?>(null)}
    var erase by remember {mutableStateOf<LocalTrashEntry?>(null)}
    Field(query,{query=it},"搜索任务、想法、下一步或背景",Modifier.testTag("library_search"))
    FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        LibraryScope.entries.forEach {s->FilterChip(scope==s,{scope=s},label={Text(s.label)},modifier=Modifier.testTag("library_scope_${s.name}"))}
    }
    if(scope==LibraryScope.IDEAS)FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        FilterChip(stage==null,{stage=null},label={Text("所有阶段")})
        stageNames.forEach {(key,title)->FilterChip(stage==key,{stage=key},label={Text(title)})}
    }
    WorkFeedback(work)
    LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=16.dp)) {
        if(scope==LibraryScope.TRASH) {
            val entries=LocalTrashActions.visible(state).filter {query.isBlank()||LibrarySearch.title(it.before).contains(query,true)}
            item {Text("回收记录只保留在删除操作所在的设备；同步后仍可恢复为副本。清理回收记录不会再次删除云端任务。",style=MaterialTheme.typography.bodySmall,color=StepMuted)}
            if(entries.isEmpty())item {EmptyState("回收站是空的","从这里可以恢复本机删除的内容。")}
            items(entries,key={it.id}) {entry->
                StepCard(Modifier.fillMaxWidth().testTag("trash_${entry.before.id}")) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(LibrarySearch.title(entry.before),style=MaterialTheme.typography.titleMedium)
                        Text("删除于 ${entry.deletedAt.take(16).replace('T',' ')}",style=MaterialTheme.typography.bodySmall,color=StepMuted)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            val restored=entry.restoredKey?.let {state.items[it]}?.takeUnless {it.deleted}
                            if(restored!=null)StepOutlinedButton({onOpen(restored.kind,restored.id)}) {Text("打开已恢复内容")}
                            else if(entry.restoredKey==null) {
                                StepButton({work.edit {if(entry.canRestoreOriginal(it))LocalTrashActions.restoreOriginal(it,entry.id)else LocalTrashActions.restoreCopy(it,entry.id)}} ,enabled=!work.busy,modifier=Modifier.testTag("trash_restore_${entry.before.id}")) {Text(if(entry.canRestoreOriginal(state))"恢复原内容"else"恢复为副本")}
                            } else Text("恢复后的内容又发生了变化，不会重复创建。",color=StepMuted)
                            StepTextButton({erase=entry},enabled=!work.busy){Text("清理这条回收记录")}
                        }
                    }
                }
            }
        } else {
            val entities=DesktopLibraryQuery.find(state,query,scope,stage)
            if(entities.isEmpty())item {EmptyState("这里还没有匹配内容","可以调整筛选或搜索词。")}
            items(entities,key={it.key}) {item->
                StepCard(Modifier.fillMaxWidth().testTag("library_${item.id}")) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(item.displayTitle(),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(listOfNotNull(mapOf("task" to "任务","idea" to "想法","goal" to "目标","inbox" to "随手记")[item.kind],if(item.completed())"已完成"else null,if(LibrarySearch.archived(item))"已归档"else null,LibrarySearch.deadline(item)?.let {"截止 $it"}).joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=StepMuted)
                            }
                            StepOutlinedButton({onOpen(item.kind,item.id)},modifier=Modifier.testTag("library_open_${item.id}")){Text("打开")}
                        }
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(item.kind=="task") {
                                if(LibrarySearch.archived(item))StepTextButton({work.edit {LibraryActions.restoreArchive(it,item.id,LibraryBaseline.from(item),Instant.now())}},enabled=!work.busy,modifier=Modifier.testTag("library_unarchive_${item.id}")){Text("恢复到四象限")}
                                else {
                                    if(item.completed())StepTextButton({work.complete(item.id,false)},enabled=!work.busy,modifier=Modifier.testTag("library_reopen_${item.id}")){Text("恢复为未完成")}
                                    StepTextButton({pending="archive" to item},enabled=!work.busy,modifier=Modifier.testTag("library_archive_${item.id}")){Text("归档")}
                                }
                            }
                            StepTextButton({pending="delete" to item},enabled=!work.busy,modifier=Modifier.testTag("library_delete_${item.id}")){Text("移入回收站")}
                        }
                    }
                }
            }
        }
    }
    pending?.let {(action,item)->AlertDialog(onDismissRequest={if(!work.busy)pending=null},title={Text(if(action=="delete")"移入回收站？"else"归档任务？")},text={Column {Text("${item.displayTitle()}\n"+if(action=="delete")"这项内容将从活动列表移除；可以在本机回收站恢复。"else"保留步骤和投入记录，之后可从资料库恢复。");if(work.failed)WorkFeedback(work)}},confirmButton={StepButton({work.run({pending=null}){if(action=="delete")runtime.deleteContent(item.kind,item.id,LibraryBaseline.from(item))else runtime.archiveTask(item.id,LibraryBaseline.from(item))}},enabled=!work.busy,modifier=Modifier.testTag("library_confirm")){Text("确认")}},dismissButton={StepTextButton({pending=null},enabled=!work.busy){Text("取消")}})}
    erase?.let {entry->AlertDialog(onDismissRequest={if(!work.busy)erase=null},title={Text("清理回收记录？")},text={Text("将无法再从本机回收站找回「${LibrarySearch.title(entry.before)}」。已恢复的内容不受影响。")},confirmButton={StepButton({work.edit({erase=null}){LocalTrashActions.clear(it,listOf(entry))}},enabled=!work.busy){Text("清理")}},dismissButton={StepTextButton({erase=null},enabled=!work.busy){Text("取消")}})}
}
