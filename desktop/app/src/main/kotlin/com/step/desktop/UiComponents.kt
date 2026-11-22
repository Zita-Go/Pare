package com.step.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalClipboard
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
    fun complete(taskId:String,completed:Boolean)=run {runtime.setTaskCompleted(taskId,completed)}
    fun execute(change:(ExecutionController)->Unit) = run {runtime.execute(change)}
}
@Composable internal fun rememberWork(runtime:DesktopRuntime):UiWork {val scope=rememberCoroutineScope();return remember(runtime,scope) {UiWork(scope,runtime)}}
@Composable internal fun WorkFeedback(work:UiWork) {
    if(work.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("ui_saving"))
    work.message?.let { Text(it, style=MaterialTheme.typography.bodySmall, color=if(work.failed) MaterialTheme.colorScheme.error else StepGreen) }
}
@Composable internal fun Field(value:String,onChange:(String)->Unit,label:String,modifier:Modifier=Modifier,lines:Int=1,secret:Boolean=false,enabled:Boolean=true,inputModifier:Modifier=Modifier) {
    val source=remember {MutableInteractionSource()}
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text(label,style=MaterialTheme.typography.labelSmall,color=StepMuted,
            modifier=Modifier.padding(start=2.dp).clearAndSetSemantics {})
        OutlinedTextField(value,onChange,
            Modifier.fillMaxWidth().then(inputModifier).semantics {text=AnnotatedString(label)}
                .stepInputSurface(source,enabled),
            singleLine=lines==1,minLines=lines,maxLines=if(lines==1)1 else 8,
            textStyle=MaterialTheme.typography.bodyMedium,
            visualTransformation=if(secret)SecureVisualTransformation else VisualTransformation.None,
            enabled=enabled,shape=MaterialTheme.shapes.small,interactionSource=source,
            colors=OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor=Color.Transparent,focusedContainerColor=Color.Transparent,disabledContainerColor=Color.Transparent,
                unfocusedBorderColor=Color.Transparent,focusedBorderColor=Color.Transparent,disabledBorderColor=Color.Transparent,
                cursorColor=StepGold
            )
        )
    }
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
    // Read before entering Dialog. Native dialogs can exceed a small widget/capture owner;
    // constrain them to that monitor's work area. Headless scenes have no AWT window.
    val nativeArea=rememberNativeWindowWorkArea(LocalDesktopWindow.current)
    val ownerSize=LocalWindowInfo.current.containerSize
    val density=LocalDensity.current
    val availableWidth=nativeArea?.width?.dp?:with(density){ownerSize.width.toDp()}.coerceAtLeast(1.dp)
    val availableHeight=nativeArea?.height?.dp?:with(density){ownerSize.height.toDp()}.coerceAtLeast(1.dp)
    val modalWidth=minOf(width.dp,availableWidth)
    val modalHeight=minOf(680.dp,availableHeight)
    // Native Dialog scenes install their own platform locals. Re-provide the same protected
    // clipboard inside that boundary instead of reverting to the raw AWT implementation.
    val clipboard=LocalClipboard.current
    Dialog(onDismissRequest=requestClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        CompositionLocalProvider(LocalClipboard provides clipboard) {
        Surface(Modifier.width(modalWidth).heightIn(max=modalHeight).padding(16.dp).testTag("desktop_dialog").stepPanelSurface(radius=18.dp,elevation=8.dp),shape=MaterialTheme.shapes.large,color=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface) {
            Box {
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
                    Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
                    VerticalScrollbar(rememberScrollbarAdapter(scroll),Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                if(work!=null) WorkFeedback(work)
            }
            // A native modal covers its owner's feedback. Overlay it here without consuming
            // body height or changing the dialog's existing scrolling/measurement behavior.
            Box(Modifier.matchParentSize().padding(16.dp),contentAlignment=Alignment.BottomCenter) {
                DesktopClipboardFeedback()
            }
            }
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
    val source=remember {MutableInteractionSource()}
    androidx.compose.material3.Button(onClick,modifier.stepActionSurface(StepActionKind.PRIMARY,source,enabled),enabled,
        shape=MaterialTheme.shapes.small,interactionSource=source,
        colors=ButtonDefaults.buttonColors(containerColor=Color.Transparent,disabledContainerColor=Color.Transparent,contentColor=StepMaterials.primaryInk),
        elevation=ButtonDefaults.buttonElevation(defaultElevation=0.dp,pressedElevation=0.dp,focusedElevation=0.dp,hoveredElevation=0.dp,disabledElevation=0.dp),
        contentPadding=contentPadding,content=content)
}
@Composable internal fun StepOutlinedButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,contentPadding:PaddingValues=PaddingValues(horizontal=13.dp,vertical=9.dp),content:@Composable RowScope.()->Unit) {
    val source=remember {MutableInteractionSource()}
    androidx.compose.material3.OutlinedButton(onClick,modifier.stepActionSurface(StepActionKind.SECONDARY,source,enabled),enabled,
        shape=MaterialTheme.shapes.small,interactionSource=source,
        colors=ButtonDefaults.outlinedButtonColors(containerColor=Color.Transparent,disabledContainerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface),
        border=null,contentPadding=contentPadding,content=content)
}
@Composable internal fun StepTextButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,contentPadding:PaddingValues=PaddingValues(horizontal=9.dp,vertical=7.dp),content:@Composable RowScope.()->Unit) {
    val source=remember {MutableInteractionSource()}
    androidx.compose.material3.TextButton(onClick,modifier.stepActionSurface(StepActionKind.TERTIARY,source,enabled),enabled,
        shape=MaterialTheme.shapes.small,interactionSource=source,
        colors=ButtonDefaults.textButtonColors(containerColor=Color.Transparent,disabledContainerColor=Color.Transparent,contentColor=StepMuted),
        contentPadding=contentPadding,content=content)
}
