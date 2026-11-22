package com.step.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pare.app.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Instant

internal fun JsonObject?.str(key:String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject?.number(key:String) = (this?.get(key) as? JsonPrimitive)?.intOrNull
internal fun JsonObject?.flag(key:String) = (this?.get(key) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject?.array(key:String) = (this?.get(key) as? JsonArray).orEmpty()
internal fun Entity.displayTitle() = if(kind=="goal") content().str("title") else title()
internal val quadrantNames = listOf("重要且紧急", "重要不紧急", "紧急不重要", "不紧急不重要")
internal val stageNames = linkedMapOf("seed" to "种子", "exploring" to "探索中", "building" to "推进中", "parked" to "暂放", "archived" to "归档")
internal fun activeTasks(state:SyncState) = state.items.values.filter {it.kind=="task" && !it.deleted && !it.completed() && it.payload.str("lifecycle")!="archived"}.sortedBy {it.title()}
internal fun planEntry(state:SyncState,planId:String,taskId:String) = state.items["day_plan:$planId"]?.content().array("task_plans").filterIsInstance<JsonObject>().firstOrNull {it.str("task_id")==taskId}
internal fun planWin(state:SyncState,planId:String,taskId:String) = state.items["day_plan:$planId"]?.content().str("daily_win_task_id")==taskId
// Password masking is always enabled for secrets; no reveal or logging path.
internal val SecureVisualTransformation:VisualTransformation = PasswordVisualTransformation()

internal class UiWork(private val scope:CoroutineScope, private val runtime:DesktopRuntime) {
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false); private set
    fun run(success:()->Unit={}, block:suspend ()->Result<Unit>) {
        if(busy)return
        busy=true; message=null; failed=false
        scope.launch {
            val result=try { withContext(Dispatchers.IO) { block() } } catch(e:CancellationException) {throw e} catch(e:Exception) {Result.failure(e)}
            busy=false;failed=result.isFailure
            if(result.isSuccess) {message=null;success()} else message=result.exceptionOrNull()?.message ?: "保存失败，草稿已保留。"
        }
    }
    fun edit(success:()->Unit={}, change:(SyncState)->SyncState) = run({message="已保存到本机";success()}) {runtime.edit(change)}
    fun editSnapshot(success:(SyncState)->Unit,change:(SyncState)->SyncState) {
        var saved:SyncState?=null
        run({message="已保存到本机";success(requireNotNull(saved))}) {
            runtime.edit {change(it).also {snapshot->saved=snapshot}}
        }
    }
    fun execute(change:(ExecutionController)->Unit) = run {runtime.execute(change)}
}
@Composable internal fun rememberWork(runtime:DesktopRuntime):UiWork {val scope=rememberCoroutineScope();return remember(runtime,scope) {UiWork(scope,runtime)}}
@Composable internal fun WorkFeedback(work:UiWork) {
    if(work.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("ui_saving"))
    work.message?.let { Text(it, style=MaterialTheme.typography.bodySmall, color=if(work.failed) MaterialTheme.colorScheme.error else StepGreen) }
}
@Composable internal fun Field(value:String,onChange:(String)->Unit,label:String,modifier:Modifier=Modifier,lines:Int=1,secret:Boolean=false,enabled:Boolean=true) {
    OutlinedTextField(value,onChange,modifier.fillMaxWidth(),label={Text(label)},singleLine=lines==1,minLines=lines,maxLines=if(lines==1)1 else 8,
        visualTransformation=if(secret) SecureVisualTransformation else VisualTransformation.None,enabled=enabled,shape=MaterialTheme.shapes.small,
        colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=LocalStepPalette.current.field,focusedContainerColor=LocalStepPalette.current.field,
            unfocusedBorderColor=MaterialTheme.colorScheme.outline.copy(alpha=.55f),focusedBorderColor=StepGold))
}
@Composable internal fun SectionLabel(title:String,caption:String?=null) {
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {Text(title,style=MaterialTheme.typography.titleMedium);caption?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=StepMuted)}}
}
@Composable internal fun EmptyState(title:String,detail:String) {
    Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium);Text(detail,style=MaterialTheme.typography.bodyMedium,color=StepMuted)
    }
}
@Composable internal fun ChoiceRow(options:List<Pair<String,String>>, selected:String, enabled:Boolean=true, onChange:(String)->Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        options.forEach {(key,label)->FilterChip(selected==key,{onChange(key)},label={Text(label)},enabled=enabled,shape=MaterialTheme.shapes.small,
            colors=FilterChipDefaults.filterChipColors(selectedContainerColor=LocalStepPalette.current.selection,selectedLabelColor=StepGold),
            border=BorderStroke(1.dp,if(selected==key)StepGold.copy(alpha=.22f)else StepLine))}
    }
}
@Composable internal fun UiModal(title:String,onClose:()->Unit,work:UiWork?=null,width:Int=760,dirty:Boolean=false,subtitle:String?=null,content:@Composable ColumnScope.()->Unit) {
    ReportUnsavedEdits(dirty)
    var confirmDiscard by remember {mutableStateOf(false)}
    val requestClose:()->Unit={if(work?.busy!=true){if(dirty)confirmDiscard=true else onClose()}}
    Dialog(onDismissRequest=requestClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.width(width.dp).heightIn(max=680.dp).padding(16.dp).testTag("desktop_dialog"),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,shadowElevation=12.dp) {
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(subtitle!=null)Text(title,style=MaterialTheme.typography.labelLarge,color=StepGold)
                        Text(subtitle?:title,style=MaterialTheme.typography.headlineMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                    StepTextButton(requestClose,enabled=work?.busy!=true){StepIcon(Icons.Outlined.Close);Spacer(Modifier.width(5.dp));Text("关闭")}
                }
                HorizontalDivider(color=StepLine)
                Box(Modifier.weight(1f,false)) {
                    val scroll=rememberScrollState()
                    Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end=12.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
                    VerticalScrollbar(rememberScrollbarAdapter(scroll),Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                if(work!=null) WorkFeedback(work)
            }
        }
    }
    if(confirmDiscard) AlertDialog(
        onDismissRequest={confirmDiscard=false},
        title={Text("放弃未保存的修改？")},
        text={Text("这份草稿还没有保存。继续编辑会保留当前输入，放弃后将关闭编辑器。")},
        confirmButton={StepTextButton({confirmDiscard=false;onClose()}){Text("放弃修改")}},
        dismissButton={StepTextButton({confirmDiscard=false}){Text("继续编辑")}}
    )
}
internal fun checkedContentSave(state:SyncState,entity:Entity?,kind:String,id:String,title:String,note:String,quadrant:Int,expected:ContentEditBaseline):SyncState {
    val current=state.items["$kind:$id"]
    require(current?.conflict==null && current?.conflictGroup.orEmpty().isEmpty()) {"请先处理同步冲突，草稿已保留"}
    val payload=ContentActions.save(current,kind,title,note,quadrant,id,Instant.now(),expected=expected)
    return StateMachine.edit(state,kind,id,payload)
}

@Composable internal fun StepIcon(icon:ImageVector,description:String?=null,color:Color=LocalContentColor.current) {
    Icon(icon,description,Modifier.size(18.dp),tint=color)
}
@Composable internal fun IconLabel(icon:ImageVector,text:String) {
    StepIcon(icon);Spacer(Modifier.width(7.dp));Text(text,maxLines=1)
}
@Composable internal fun StepButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,contentPadding:PaddingValues=PaddingValues(horizontal=14.dp,vertical=9.dp),content:@Composable RowScope.()->Unit) {
    androidx.compose.material3.Button(onClick,modifier,enabled,shape=MaterialTheme.shapes.small,contentPadding=contentPadding,content=content)
}
@Composable internal fun StepOutlinedButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,contentPadding:PaddingValues=PaddingValues(horizontal=13.dp,vertical=9.dp),content:@Composable RowScope.()->Unit) {
    androidx.compose.material3.OutlinedButton(onClick,modifier,enabled,shape=MaterialTheme.shapes.small,
        colors=ButtonDefaults.outlinedButtonColors(containerColor=LocalStepPalette.current.raised,contentColor=MaterialTheme.colorScheme.onSurface),
        border=BorderStroke(1.dp,StepLine),contentPadding=contentPadding,content=content)
}
@Composable internal fun StepTextButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,contentPadding:PaddingValues=PaddingValues(horizontal=9.dp,vertical=7.dp),content:@Composable RowScope.()->Unit) {
    androidx.compose.material3.TextButton(onClick,modifier,enabled,shape=MaterialTheme.shapes.small,
        colors=ButtonDefaults.textButtonColors(contentColor=StepMuted),contentPadding=contentPadding,content=content)
}
