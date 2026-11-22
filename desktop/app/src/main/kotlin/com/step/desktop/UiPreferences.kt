package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pare.app.*
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate

@Composable internal fun PreferencesUi(runtime:DesktopRuntime,onClose:()->Unit) {
    val state by runtime.engine.state.collectAsState()
    val current=state.items["profile:profile"]
    val work=rememberWork(runtime)
    var editingProfile by remember {mutableStateOf(false)}
    var profile by remember {mutableStateOf(ProfileDraft.from(current))}
    var profileBaseline by remember {mutableStateOf(ProfileBaseline.from(current))}
    var planning by remember {mutableStateOf(profile.planningMinutes.toString())}
    var starting by remember {mutableStateOf(profile.startingMinutes.toString())}
    var preference by remember {mutableStateOf<ConfirmedPreferenceDraft?>(null)}
    var preferenceBaseline by remember {mutableStateOf<PreferenceBaseline?>(null)}
    var initialPreference by remember {mutableStateOf<ConfirmedPreferenceDraft?>(null)}
    var expiry by remember {mutableStateOf("")}
    var initialExpiry by remember {mutableStateOf("")}
    var discardEditor by remember {mutableStateOf(false)}
    var forget by remember {mutableStateOf<Pair<String,PreferenceBaseline>?>(null)}
    val zone=PreferencesActions.planningZone(state)
    val available=current==null || (!current.deleted&&current.conflict==null&&current.conflictGroup.isEmpty()&&current.payload!=null)
    val dirty=if(editingProfile)profile!=profileBaseline.draft||planning!=profileBaseline.draft.planningMinutes.toString()||starting!=profileBaseline.draft.startingMinutes.toString()
        else preference!=initialPreference||expiry!=initialExpiry
    fun editPreference(entry:JsonObject?) {
        preference=entry?.let(ConfirmedPreferenceDraft::from)?:ConfirmedPreferenceDraft()
        initialPreference=preference
        preferenceBaseline=PreferenceBaseline.from(current,preference!!.id)
        expiry=preference!!.expiresAt?.let {Instant.parse(it).atZone(zone).minusNanos(1).toLocalDate().toString()}.orEmpty()
        initialExpiry=expiry
    }
    fun returnToList() {editingProfile=false;preference=null;initialPreference=null;expiry="";initialExpiry="";discardEditor=false}
    UiModal("我的背景与偏好",onClose,work,width=780,dirty=dirty) {
        Text("只记住你填写或确认的内容。每次使用 AI 时，仍由你选择是否提供。",color=StepMuted)
        if(editingProfile||preference!=null)StepTextButton({if(dirty)discardEditor=true else returnToList()},enabled=!work.busy){Text("返回偏好列表")}
        if(editingProfile) {
            SectionLabel("我的规划节奏","保存不会重新安排已有日程；背景和偏好属于可同步的个人资料。")
            Field(profile.background,{profile=profile.copy(background=it)},"个人背景 · 可选",lines=4,enabled=!work.busy,modifier=Modifier.testTag("preferences_background"))
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Field(planning,{planning=it},"默认每次规划 · 5–480分钟",Modifier.weight(1f),enabled=!work.busy)
                Field(starting,{starting=it},"启动小步骤 · 1–15分钟",Modifier.weight(1f),enabled=!work.busy)
            }
            Field(profile.notes,{profile=profile.copy(notes=it)},"其他偏好 · 最多600字",lines=3,enabled=!work.busy)
            Text("规划时区：${zone.id}",style=MaterialTheme.typography.bodySmall,color=StepMuted)
            val value=profile.copy(planningMinutes=planning.toIntOrNull()?:0,startingMinutes=starting.toIntOrNull()?:0)
            value.validationError()?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            StepButton({val expected=profileBaseline;work.edit({editingProfile=false;profileBaseline=ProfileBaseline.from(runtime.engine.state.value.items["profile:profile"]);profile=profileBaseline.draft}) {PreferencesActions.saveProfile(it,value,expected,zone)}},enabled=!work.busy&&dirty&&value.validationError()==null,modifier=Modifier.testTag("preferences_save")){Text("保存背景与节奏")}
        } else if(preference!=null) {
            val draft=preference!!
            SectionLabel("确认一条偏好","保存后才会记住；到期后保留记录，但不作为 AI 建议依据。")
            Field(draft.text,{preference=draft.copy(text=it)},"希望如何安排工作？ · 最多500字",lines=3,enabled=!work.busy)
            Text("适用范围",style=MaterialTheme.typography.titleMedium)
            ChoiceRow(listOf("" to "全局")+state.items.values.filter {it.kind=="idea"&&!it.deleted&&it.conflict==null&&it.conflictGroup.isEmpty()}.map {it.id to it.title()},draft.ideaId.orEmpty(),!work.busy){preference=draft.copy(ideaId=it.ifEmpty {null})}
            Field(expiry,{expiry=it},"有效期 · YYYY-MM-DD，留空长期保留",enabled=!work.busy)
            val expiryValid=expiry.isBlank()||runCatching {LocalDate.parse(expiry)}.isSuccess
            if(!expiryValid)Text("请输入有效日期，例如 2026-12-31",color=MaterialTheme.colorScheme.error)
            draft.validationError()?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            StepButton({val value=draft.copy(expiresAt=if(expiry==initialExpiry)draft.expiresAt else expiry.takeIf {it.isNotBlank()}?.let {LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant().toString()});val expected=preferenceBaseline!!;work.edit({preference=null;initialPreference=null;expiry="";initialExpiry=""}) {PreferencesActions.saveConfirmed(it,value,expected,Instant.now(),zone)}},enabled=!work.busy&&dirty&&expiryValid&&draft.validationError()==null,modifier=Modifier.testTag("preference_save")){Text("保存这条偏好")}
        } else {
            if(!available)Text("偏好资料存在冲突或已删除，请先在账号与同步中处理。",color=MaterialTheme.colorScheme.error)
            val saved=ProfileDraft.from(current)
            StepCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("每次规划 ${saved.planningMinutes} 分钟 · 从 ${saved.startingMinutes} 分钟开始",style=MaterialTheme.typography.titleMedium)
                Text(saved.background.ifBlank {"还没有填写个人背景"},color=StepMuted)
                if(saved.notes.isNotBlank())Text(saved.notes,color=StepMuted)
                StepTextButton({profile=ProfileDraft.from(current);profileBaseline=ProfileBaseline.from(current);planning=profile.planningMinutes.toString();starting=profile.startingMinutes.toString();editingProfile=true},enabled=available&&!work.busy){Text("编辑背景与规划节奏")}
            }}
            Row(verticalAlignment=Alignment.CenterVertically) {Text("已确认偏好",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));StepOutlinedButton({editPreference(null)},enabled=available&&!work.busy){Text("添加偏好")}}
            PreferencesActions.entries(current).forEach {entry->
                StepCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(entry.str("text"))
                    Text((entry.str("ideaID").ifBlank {null}?.let {state.items["idea:$it"]?.title()?:"关联想法不可用"}?:"全局")+" · "+(entry.str("expiresAt").ifBlank {"长期保留"}),style=MaterialTheme.typography.bodySmall,color=StepMuted)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        StepTextButton({editPreference(entry)},enabled=available&&!work.busy){Text("修改")}
                        StepTextButton({forget=entry.str("id") to PreferenceBaseline.from(current,entry.str("id"))},enabled=available&&!work.busy){Text("忘记")}
                    }
                }}
            }
            if(PreferencesActions.entries(current).isEmpty())Text("还没有确认偏好。AI 不会根据聊天自动记住。",color=StepMuted)
        }
    }
    if(discardEditor)AlertDialog(onDismissRequest={discardEditor=false},title={Text("放弃未保存的偏好修改？")},text={Text("返回列表后，这次草稿不会保存。")},confirmButton={StepButton({returnToList()}){Text("放弃修改")}},dismissButton={StepTextButton({discardEditor=false}){Text("继续编辑")}})
    forget?.let {(id,baseline)->AlertDialog(onDismissRequest={if(!work.busy)forget=null},title={Text("忘记这条偏好？")},text={Column {Text("之后的建议不再引用它。已生成的本机请求历史需要另行删除。");if(work.failed)WorkFeedback(work)}},confirmButton={StepButton({work.edit({forget=null}) {PreferencesActions.forget(it,id,baseline)}},enabled=!work.busy){Text("忘记偏好")}},dismissButton={StepTextButton({forget=null},enabled=!work.busy){Text("取消")}})}
}
