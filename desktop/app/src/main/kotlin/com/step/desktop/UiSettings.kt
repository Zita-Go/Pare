package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pare.app.*
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

@Composable internal fun SettingsUi(runtime:DesktopRuntime,onClose:()->Unit,onRequestClose:()->Unit) {
    val state by runtime.engine.state.collectAsState();val session by runtime.engine.session.collectAsState()
    val engineBusy by runtime.engine.busy.collectAsState();val previewReady by runtime.engine.previewReady.collectAsState();val status by runtime.engine.status.collectAsState()
    val aiSettings by runtime.aiSettings.collectAsState()
    val aiDraft=remember{UiAIServiceDraft(aiSettings.config)}
    val work=rememberWork(runtime);var tab by remember{mutableStateOf("sync")}
    var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var signup by remember{mutableStateOf(false)}
    var backup by remember{mutableStateOf("")}
    UiModal("设置",onClose,work,width=820,dirty=(session==null&&(email.isNotBlank()||password.isNotEmpty()))||aiDraft.dirty) {
        ChoiceRow(listOf("sync" to "账号与同步","ai" to "AI 服务","local" to "本机与备份"),tab,!work.busy){tab=it}
        when(tab) {
            "sync" -> {
                SectionLabel("同一账号，继续推进","本机内容先保存；首次登录拉取预览后，由你确认开启上传。")
                Text(status,color=StepGreen)
                if(runtime.config.syncUrl.isBlank() || runtime.config.syncPublicKey.isBlank())Text("此构建尚未配置同步服务。你仍可使用本机资料库。",color=MaterialTheme.colorScheme.error)
                if(session==null) {
                    Field(email,{email=it},"邮箱",enabled=!work.busy&&!engineBusy)
                    Field(password,{password=it},"密码",secret=true,enabled=!work.busy&&!engineBusy)
                    Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(signup,{signup=it},enabled=!work.busy&&!engineBusy);Text("注册新账号")}
                    Button({val e=email.trim();val p=password;val create=signup;work.run({if(runtime.engine.session.value!=null){password="";email=""}}) {runCatching {runtime.engine.authenticate(e,p,create)}}},enabled=email.isNotBlank()&&password.isNotBlank()&&!work.busy&&!engineBusy&&runtime.config.syncUrl.isNotBlank()&&runtime.config.syncPublicKey.isNotBlank()){Text(if(signup)"注册"else"登录并拉取预览")}
                } else {
                    if(!state.enabled) {
                        Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(if(previewReady)"合并预览"else"预览尚未就绪",style=MaterialTheme.typography.titleMedium)
                            Text("本机可见 ${state.items.values.count {!it.deleted}} 条内容 · ${state.items.values.count {it.dirty}} 条本机更改待上传 · ${state.items.values.count {it.conflict!=null}} 条需要你决定的冲突")
                            Text(if(previewReady)"云端内容已合并到本机预览。确认开启后，本机变更会上传到此账号；发生冲突的内容等待你选定版本。"else"当前显示本机已读取的内容；完整云端预览完成前不会启用上传。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
                        }}
                        if(!previewReady)Text("请等待拉取完成。若失败，可退出后重新登录获取预览。",color=StepMuted)
                        Button({work.run {runCatching {runtime.engine.enable()}}},enabled=previewReady&&!work.busy&&!engineBusy){Text("确认合并并开启同步")}
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(state.enabled) {OutlinedButton({runtime.requestSync()},enabled=!engineBusy&&!work.busy){Text("立即同步")};OutlinedButton({runtime.engine.revoke();work.run {runCatching {runtime.engine.pause()}}},enabled=!work.busy){Text("暂停同步")}}
                        TextButton({runtime.engine.revoke();work.run {runCatching {runtime.engine.signOut()}}},enabled=!work.busy){Text("退出账号")}
                    }
                    Text("退出后保留本机内容与未上传更改；资料库仍绑定原账号。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
                }
                val conflicts=state.items.values.filter {it.conflict!=null}
                if(conflicts.isNotEmpty()) {
                    HorizontalDivider();SectionLabel("需要你决定的版本","逐条查看。有关联的冲突会在全部选择完成后一起处理。")
                    conflicts.forEach {local->
                        val remote=local.conflict!!
                        val remoteContent=remote.payload?.get("content") as? JsonObject
                        Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(local.displayTitle(),style=MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text("本机版本"+(if(local.deleted)" · 已删除"else""),style=MaterialTheme.typography.titleMedium);SelectionContainer {Text(local.content()?.toString().orEmpty(),style=MaterialTheme.typography.bodySmall)};OutlinedButton({work.edit {current->require(current.items[local.key]?.conflict==remote && current.items[local.key]?.payload==local.payload){"冲突内容已更新，请重新查看"};StateMachine.resolve(current,local.key,true)}},enabled=!work.busy&&state.pending==null){Text(if(local.conflictChoice==true)"已选本机版本"else"保留本机版本")}}
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text("云端版本"+(if(remote.deletedAt!=null)" · 已删除"else""),style=MaterialTheme.typography.titleMedium);SelectionContainer {Text(remoteContent?.toString().orEmpty(),style=MaterialTheme.typography.bodySmall)};OutlinedButton({work.edit {current->require(current.items[local.key]?.conflict==remote && current.items[local.key]?.payload==local.payload){"冲突内容已更新，请重新查看"};StateMachine.resolve(current,local.key,false)}},enabled=!work.busy&&state.pending==null){Text(if(local.conflictChoice==false)"已选云端版本"else"保留云端版本")}}
                            }
                            if(state.pending!=null)Text("先同步待确认的请求，再选择版本。",color=StepMuted,style=MaterialTheme.typography.bodySmall)
                        }}
                    }
                }
            }
            "ai" -> AISettingsPanel(runtime,work,aiDraft)
            else -> {
                SectionLabel("内容备份","导出任务、想法、目标与日计划；不包含账号凭据、模型密钥和 AI 原始历史。")
                Field(backup,{backup=it},"备份文件完整路径（例如 D:\\Backups\\step-backup.json）",enabled=!work.busy)
                Button({val destination=backup;work.run {runtime.exportBackup(Path.of(destination))}},enabled=backup.isNotBlank()&&!work.busy){Text("导出内容备份")}
                HorizontalDivider();SectionLabel("窗口与提醒","关闭主窗口后的驻留行为由窗口设置控制；彻底退出后不会发送提醒。")
                TextButton(onRequestClose){Text("关闭主窗口")}
                Text("本机计时与等待记录属于此设备；跨端同步完成历史，不接管其他设备正在运行的专注。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
            }
        }
    }
}
@Composable internal fun AISettingsPanel(runtime:DesktopRuntime,work:UiWork,draft:UiAIServiceDraft) {
    val settings by runtime.aiSettings.collectAsState()
    SectionLabel("你选择的 AI 服务","默认不发送内容。每次生成前预览上下文，生成结果由你审阅采纳。")
    Field(draft.endpoint,{draft.endpoint=it},"完整 HTTPS Chat Completions 地址",enabled=!work.busy)
    Field(draft.model,{draft.model=it},"模型名称",enabled=!work.busy)
    Field(draft.key,{draft.key=it},if(settings.hasKey)"替换密钥（留空保留同一服务密钥）"else"API 密钥",secret=true,enabled=!work.busy)
    Text("密钥与 AI 原始历史仅保留本机。更换服务或模型时，请重新填写密钥。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button({val config=MobileAIConfig(draft.endpoint,draft.model);val secret=draft.key.takeIf {it.isNotEmpty()};work.run({draft.saved(config.validated())}){runtime.saveAI(config,secret)}},enabled=!work.busy&&draft.endpoint.isNotBlank()&&draft.model.isNotBlank()){Text("保存服务设置")}
        if(settings.hasKey)TextButton({work.run({draft.key=""}){runtime.saveAI(settings.config,"")}},enabled=!work.busy){Text("移除密钥并停用 AI")}
    }
}
