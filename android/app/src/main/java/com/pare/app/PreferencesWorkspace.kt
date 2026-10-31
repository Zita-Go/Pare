package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.*
import java.time.Instant

class PreferencesWorkspaceModel : ViewModel() {
    var profile: ProfileDraft? by mutableStateOf(null); private set
    var profileBaseline: ProfileBaseline? by mutableStateOf(null); private set
    var planningMinutes by mutableStateOf("30")
    var startingMinutes by mutableStateOf("5")
    var preference: ConfirmedPreferenceDraft? by mutableStateOf(null); private set
    var preferenceBaseline: PreferenceBaseline? by mutableStateOf(null); private set
    var source by mutableStateOf("用户填写"); private set
    var saving by mutableStateOf(false); private set
    var error: String? by mutableStateOf(null); private set
    var message: String? by mutableStateOf(null); private set
    var confirmDiscard by mutableStateOf(false)
    var forgetEntry: JsonObject? by mutableStateOf(null)
    var forgetBaseline: PreferenceBaseline? = null
    fun openProfile(entity: Entity?) {
        if(saving || profile!=null || preference!=null) return
        profile=ProfileDraft.from(entity); profileBaseline=ProfileBaseline.from(entity)
        planningMinutes=profile!!.planningMinutes.toString(); startingMinutes=profile!!.startingMinutes.toString(); error=null; message=null
    }
    fun openPreference(entity: Entity?,entry: JsonObject?) {
        if(saving || profile!=null || preference!=null) return
        preference=entry?.let(ConfirmedPreferenceDraft::from) ?: ConfirmedPreferenceDraft()
        preferenceBaseline=PreferenceBaseline.from(entity,preference!!.id); source=entry?.stringValue("source") ?: "用户填写"; error=null; message=null
    }
    fun changeProfile(value: ProfileDraft) { if(!saving) {profile=value;error=null} }
    fun changePreference(value: ConfirmedPreferenceDraft) { if(!saving) {preference=value;error=null} }
    fun profileValue() = profile?.copy(planningMinutes=planningMinutes.trim().toIntOrNull() ?: 0,startingMinutes=startingMinutes.trim().toIntOrNull() ?: 0)
    fun validationError(): String? = profileValue()?.validationError() ?: preference?.validationError()
    fun begin(forgetting: Boolean=false): Boolean {
        if(saving) return false
        if(!forgetting) validationError()?.let {error=it;return false}
        saving=true;error=null;return true
    }
    fun finish(result: Result<Unit>) {
        saving=false
        if(result.isSuccess) {clear();message="已保存"}
        else error=result.exceptionOrNull()?.message ?: "保存失败，草稿已保留，请重试。"
    }
    fun clear() {
        if(saving) return
        profile=null;profileBaseline=null;preference=null;preferenceBaseline=null;forgetEntry=null;forgetBaseline=null;confirmDiscard=false;error=null
    }
}

@Composable fun PreferencesWorkspace(state: SyncState,onEdit: ((SyncState)->SyncState,(Result<Unit>)->Unit)->Unit,onDismiss: ()->Unit) {
    val model: PreferencesWorkspaceModel=viewModel(key="preferences-workspace:${state.owner}")
    val current=state.items["profile:profile"]
    val editing=model.profile!=null || model.preference!=null
    val zone=PreferencesActions.planningZone(state)
    val close={ if(!model.saving) {if(editing) model.confirmDiscard=true else onDismiss()} }
    val title=when {model.profile!=null->"规划偏好";model.preference!=null->"确认一条偏好";else->"我的偏好"}
    GoalsPreferencesFrame(title,"preferences_workspace",model.saving,model.error,close,actions={
        TextButton(onClick=close,enabled=!model.saving,modifier=Modifier.heightIn(min=48.dp)) {Text(if(editing) "返回" else "关闭")}
        if(editing) Button(onClick={
            val profile=model.profileValue(); val preference=model.preference
            val profileExpected=model.profileBaseline; val preferenceExpected=model.preferenceBaseline
            if(model.begin()) {
                if(profile!=null && profileExpected!=null) onEdit({PreferencesActions.saveProfile(it,profile,profileExpected,zone)},model::finish)
                else if(preference!=null && preferenceExpected!=null) onEdit({PreferencesActions.saveConfirmed(it,preference,preferenceExpected,Instant.now(),zone)},model::finish)
            }
        },enabled=!model.saving && model.validationError()==null,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("preferences_save")) {
            Text(if(model.saving) "正在保存…" else "保存偏好")
        }
    }) {
        when {
            model.profile!=null -> {
                val draft=model.profile!!
                Text("用你明确填写的背景与节奏帮助规划。每次使用 AI 时仍由你选择是否提供这些信息。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                PareFormField(draft.background,{model.changeProfile(draft.copy(background=it))},"个人背景 · 可选",enabled=!model.saving,minLines=3,
                    modifier=Modifier.fillMaxWidth().testTag("preferences_background"))
                HorizontalDivider()
                Text("从可以开始的份量出发",style=MaterialTheme.typography.titleMedium)
                PareFormField(model.planningMinutes,{model.planningMinutes=it},"默认每次规划 · 5–480分钟",enabled=!model.saving,singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().testTag("preferences_planning_minutes"))
                PareFormField(model.startingMinutes,{model.startingMinutes=it},"启动小步骤 · 1–15分钟",enabled=!model.saving,singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().testTag("preferences_starting_minutes"))
                PareFormField(draft.notes,{model.changeProfile(draft.copy(notes=it))},"其他偏好 · 最多600字",enabled=!model.saving,minLines=2,
                    modifier=Modifier.fillMaxWidth().testTag("preferences_notes"))
                Text("当前规划时区：${zone.id}\n手机保留此设置。保存偏好不会重新安排已有日程。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                model.validationError()?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            }
            model.preference!=null -> {
                val draft=model.preference!!
                PareFormField(draft.text,{model.changePreference(draft.copy(text=it))},"希望如何安排工作？ · 最多500字",enabled=!model.saving,minLines=3,
                    modifier=Modifier.fillMaxWidth().testTag("preference_text"))
                Text("来源：${model.source}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                var scopesOpen by rememberSaveable(draft.id) {mutableStateOf(false)}
                var scopeQuery by rememberSaveable(draft.id) {mutableStateOf("")}
                Text("适用范围",style=MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick={scopesOpen=!scopesOpen},enabled=!model.saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("preference_scope")) {
                    Text(preferenceScopeLabel(draft.ideaId,state))
                }
                if(scopesOpen) {
                    TextButton(onClick={model.changePreference(draft.copy(ideaId=null));scopesOpen=false},enabled=!model.saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("preference_scope_global")) {Text("全局")}
                    PareFormField(scopeQuery,{scopeQuery=it},"搜索想法",enabled=!model.saving,singleLine=true,modifier=Modifier.fillMaxWidth().testTag("preference_scope_search"))
                    stableEntities(state.items.values.filter {it.kind=="idea" && !it.deleted && it.payload!=null && it.title().contains(scopeQuery,true)}).forEach { idea ->
                        TextButton(onClick={model.changePreference(draft.copy(ideaId=idea.id));scopesOpen=false},enabled=!model.saving && idea.conflict==null && idea.conflictGroup.isEmpty(),
                            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("preference_scope_${idea.id}")) {Text(idea.title(),modifier=Modifier.fillMaxWidth())}
                    }
                }
                GoalsPreferenceDateButton("有效期",draft.expiresAt,zone,true,!model.saving,"preference_expiry") {model.changePreference(draft.copy(expiresAt=it))}
                Text(if(draft.expiresAt==null) "未设置有效期时长期保留。" else "到期后保留记录，但不会用作 AI 建议依据。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("点击保存后才会记住；可随时改写或忘记。",style=MaterialTheme.typography.bodyMedium)
                model.validationError()?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            }
            else -> {
                Text("只记录你明确填写或确认的内容。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                val available=current==null || (!current.deleted && current.payload!=null && current.conflict==null && current.conflictGroup.isEmpty())
                if(!available) Text("偏好资料存在冲突或已删除，请先在同步页面查看。",color=MaterialTheme.colorScheme.error)
                val explicit=ProfileDraft.from(current)
                Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("我的规划节奏",style=MaterialTheme.typography.titleMedium)
                        Text("每次规划 ${explicit.planningMinutes} 分钟 · 从 ${explicit.startingMinutes} 分钟开始",style=MaterialTheme.typography.bodyMedium)
                        if(explicit.notes.isNotBlank()) Text(explicit.notes,style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={model.openProfile(current)},enabled=available && !model.saving,modifier=Modifier.heightIn(min=48.dp).testTag("preferences_edit_profile")) {Text("编辑背景与规划偏好")}
                    }
                }
                HorizontalDivider()
                Text("已确认的偏好",style=MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick={model.openPreference(current,null)},enabled=available && !model.saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("preference_add")) {Text("添加偏好")}
                model.message?.let {Text(it,color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("preferences_saved"))}
                val entries=PreferencesActions.entries(current).sortedByDescending {it.stringValue("updatedAt")}
                if(entries.isEmpty()) Text("可以写下你的工作节奏，或只适用于某个想法的限制。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                entries.forEach { entry ->
                    Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth().testTag("preference_${entry.stringValue("id")}")) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(entry.stringValue("text").orEmpty(),style=MaterialTheme.typography.bodyLarge)
                            Text("范围：${preferenceScopeLabel(entry.stringValue("ideaID"),state)}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                            Text("来源：${entry.stringValue("source").orEmpty()}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            val expiry=entry.stringValue("expiresAt")
                            val expired=expiry?.let {runCatching {Instant.parse(it)<=Instant.now()}.getOrDefault(false)} ?: false
                            Text(if(expiry==null) "有效期：长期" else "${if(expired) "已过期" else "有效至"}：${goalsDateText(expiry,zone,true)}",style=MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick={model.openPreference(current,entry)},enabled=available && !model.saving,modifier=Modifier.heightIn(min=48.dp).testTag("preference_edit_${entry.stringValue("id")}")) {Text("改写")}
                                TextButton(onClick={model.forgetEntry=entry;model.forgetBaseline=PreferenceBaseline.from(current,entry.stringValue("id")!!)},enabled=available && !model.saving,
                                    modifier=Modifier.heightIn(min=48.dp).testTag("preference_forget_${entry.stringValue("id")}")) {Text("忘记",color=MaterialTheme.colorScheme.error)}
                            }
                        }
                    }
                }
            }
        }
    }
    if(model.confirmDiscard) AlertDialog(onDismissRequest={model.confirmDiscard=false},title={Text("放弃这份草稿？")},text={Text("尚未保存的修改会被丢弃。")},
        confirmButton={TextButton(onClick=model::clear) {Text("放弃草稿")}},dismissButton={TextButton(onClick={model.confirmDiscard=false}) {Text("继续编辑")}})
    model.forgetEntry?.let { entry ->
        AlertDialog(onDismissRequest={if(!model.saving) model.forgetEntry=null},title={Text("忘记这条偏好？")},
            text={Text(entry.stringValue("text").orEmpty()+"\n\n保存后会从同步资料库移除。")},
            confirmButton={TextButton(onClick={
                val expected=model.forgetBaseline ?: return@TextButton
                if(model.begin(forgetting=true)) {model.forgetEntry=null;onEdit({PreferencesActions.forget(it,entry.stringValue("id")!!,expected)},model::finish)}
            },enabled=!model.saving,modifier=Modifier.testTag("preference_forget_confirm")) {Text("忘记")}},dismissButton={TextButton(onClick={model.forgetEntry=null},enabled=!model.saving) {Text("取消")}})
    }
}

private fun preferenceScopeLabel(id: String?,state: SyncState): String {
    if(id==null) return "全局"
    val idea=state.items["idea:$id"]?.takeUnless {it.deleted} ?: return "已删除的想法（不再使用）"
    return idea.title() + if(idea.content()?.stringValue("stage")=="archived") "（已归档）" else ""
}
