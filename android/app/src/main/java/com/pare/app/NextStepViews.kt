package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

/** Retained drafts never reload over user input or a save that is still in flight. */
class NextStepDraftModel:ViewModel() {
    var expected:SmallStepBaseline?=null;private set
    var nextAction by mutableStateOf("")
    var definitionOfDone by mutableStateOf("")
    var saving by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null);private set
    var completionId=uid();private set
    private var submitted:Pair<String,String>?=null
    val changed get()=expected?.let {nextAction!=it.nextAction || definitionOfDone!=it.definitionOfDone}==true
    fun sync(task:Entity) {
        if(!saving && !changed)read(task)
    }
    private fun read(task:Entity) {
        expected=SmallStepBaseline.from(task)
        nextAction=expected!!.nextAction;definitionOfDone=expected!!.definitionOfDone
    }
    fun begin():Boolean {
        if(saving)return false
        submitted=nextAction to definitionOfDone;saving=true;error=null;return true
    }
    fun finish(result:Result<Unit>,task:Entity?) {
        if(result.isSuccess && task!=null) {
            if(submitted==nextAction to definitionOfDone)read(task) else expected=SmallStepBaseline.from(task)
            completionId=uid()
        }
        error=result.exceptionOrNull()?.message;saving=false;submitted=null
    }
}

@Composable internal fun NextStepControls(task:Entity,enabled:Boolean,hasUnsavedDraft:Boolean,onComplete:()->Unit,onUndo:(String)->Unit) {
    val current=SmallStepBaseline.from(task)
    val history=(task.content()?.get("completedSteps") as? JsonArray).orEmpty()
    if(current.nextAction.isNotBlank()) {
        TextButton(onClick=onComplete,enabled=enabled && !hasUnsavedDraft,modifier=Modifier.heightIn(min=48.dp).testTag("next_step_complete")) {Text("完成这一步")}
        if(hasUnsavedDraft)Text("先保存下一步的修改，再标记这一步完成。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    } else if(history.isNotEmpty()) {
        Text("这一步已完成，主任务继续保留。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(current.definitionOfDone.isBlank())TextButton(onClick={onUndo(history.last().jsonObject.getValue("id").jsonPrimitive.content)},enabled=enabled && !hasUnsavedDraft,
            modifier=Modifier.heightIn(min=48.dp).testTag("next_step_undo")) {Text("撤销完成这一步")}
    }
}

@Composable internal fun NextStepHistory(task:Entity) {
    val history=(task.content()?.get("completedSteps") as? JsonArray).orEmpty()
    var expanded by rememberSaveable(task.id) {mutableStateOf(false)}
    if(history.isNotEmpty()) {
        TextButton(onClick={expanded=!expanded},modifier=Modifier.heightIn(min=48.dp).testTag("next_step_history")) {Text("${if(expanded) "收起" else "查看"}已完成的步骤（${history.size}）")}
        if(expanded)Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            history.asReversed().sortedByDescending {Instant.parse(it.jsonObject.getValue("completedAt").jsonPrimitive.content)}.forEach {value ->
                val entry=value.jsonObject
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(entry.getValue("text").jsonPrimitive.content,style=MaterialTheme.typography.bodyMedium)
                    entry["definitionOfDone"]?.jsonPrimitive?.contentOrNull?.takeIf {it.isNotBlank()}?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Text(DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(entry.getValue("completedAt").jsonPrimitive.content)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable internal fun NextStepCard(state:SyncState,task:Entity,onEdit:((SyncState)->SyncState,(Result<Unit>)->Unit)->Unit) {
    val model:NextStepDraftModel=viewModel(key="next-step:${state.owner}:${state.device}:${task.id}")
    model.sync(task)
    val active=taskCanBePlanned(task) && task.conflict==null && task.conflictGroup.isEmpty()
    fun edit(change:(SyncState)->SyncState) {
        if(!model.begin())return
        var updated:Entity?=null
        onEdit({current->
            require(current.owner==state.owner && current.device==state.device) {"资料库已改变，请重新打开任务"}
            change(current).also {updated=it.items[task.key]}
        }) {model.finish(it,updated)}
    }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("最小下一步",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
        if(SmallStepBaseline.from(task).nextAction.isNotBlank() && !model.changed) {
            Text(SmallStepBaseline.from(task).nextAction,style=MaterialTheme.typography.bodyLarge)
        } else if(active) {
            PareFormField(model.nextAction,{model.nextAction=it},"接下来能做的一件小事",enabled=!model.saving,minLines=2,
                modifier=Modifier.fillMaxWidth().testTag("next_step_new_action"))
            PareFormField(model.definitionOfDone,{model.definitionOfDone=it},"做到哪里就停（可选）",enabled=!model.saving,
                modifier=Modifier.fillMaxWidth().testTag("next_step_new_done"))
            TextButton(onClick={val expected=model.expected ?: return@TextButton;val next=model.nextAction;val done=model.definitionOfDone
                edit {NextStepActions.save(it,task.id,next,done,expected,Instant.now())}},enabled=!model.saving && model.nextAction.isNotBlank(),
                modifier=Modifier.heightIn(min=48.dp).testTag("next_step_save")) {Text(if(model.saving) "正在保存…" else "保存下一步")}
        }
        NextStepControls(task,active && !model.saving,model.changed,onComplete={
            val expected=model.expected ?: return@NextStepControls;val id=model.completionId
            edit {NextStepActions.complete(it,task.id,id,expected,Instant.now())}
        },onUndo={id->edit {NextStepActions.undo(it,task.id,id,Instant.now())}})
        model.error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        NextStepHistory(task)
    }
}
