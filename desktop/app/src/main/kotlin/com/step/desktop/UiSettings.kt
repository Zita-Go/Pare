package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pare.app.*
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

@Composable internal fun SettingsUi(runtime:DesktopRuntime,onClose:()->Unit,onRequestClose:()->Unit) {
    val state by runtime.engine.state.collectAsState();val session by runtime.engine.session.collectAsState()
    val engineBusy by runtime.engine.busy.collectAsState();val previewReady by runtime.engine.previewReady.collectAsState();val status by runtime.engine.status.collectAsState()
    val initialized by runtime.engine.initialized.collectAsState()
    val systemSettings by runtime.systemSettings.collectAsState()
    var systemDraft by remember {mutableStateOf(systemSettings)}
    var systemBaseline by remember {mutableStateOf(systemSettings)}
    LaunchedEffect(systemSettings) {if(systemDraft==systemBaseline)systemDraft=systemSettings;systemBaseline=systemSettings}
    val aiSettings by runtime.aiSettings.collectAsState()
    val aiDraft=remember{UiAIServiceDraft(aiSettings.config)}
    val work=rememberWork(runtime);var tab by remember{mutableStateOf("sync")}
    var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var signup by remember{mutableStateOf(false)}
    var backup by remember{mutableStateOf("")}
    var importPath by remember{mutableStateOf("")}
    var importPreview by remember{mutableStateOf<DesktopBackupPreview?>(null)}
    var confirmImport by remember{mutableStateOf(false)}
    var confirmRecovery by remember{mutableStateOf(false)}
    var preferences by remember{mutableStateOf(false)}
    var history by remember{mutableStateOf(false)}
    UiModal("设置",onClose,work,width=820,dirty=(session==null&&(email.isNotBlank()||password.isNotEmpty()))||aiDraft.dirty||systemDraft.copy(attentionSnoozedUntil=systemSettings.attentionSnoozedUntil)!=systemSettings) {
        ChoiceRow(listOf("sync" to "账号与同步","ai" to "AI 服务","preferences" to "背景与偏好","local" to "本机与备份","system" to "Windows"),tab,!work.busy){tab=it}
        when(tab) {
            "sync" -> {
                SectionLabel("同一账号，继续推进","本机内容先保存；首次登录拉取预览后，由你确认开启上传。")
                Text(status,color=StepGreen)
                if(runtime.config.syncUrl.isBlank() || runtime.config.syncPublicKey.isBlank())Text("此构建尚未配置同步服务。你仍可使用本机资料库。",color=MaterialTheme.colorScheme.error)
                if(session==null) {
                    Field(email,{email=it},"邮箱",enabled=!work.busy&&!engineBusy)
                    Field(password,{password=it},"密码",secret=true,enabled=!work.busy&&!engineBusy)
                    Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(signup,{signup=it},enabled=!work.busy&&!engineBusy);Text("注册新账号")}
                    StepButton({val e=email.trim();val p=password;val create=signup;work.run({if(runtime.engine.session.value!=null){password="";email=""}}) {runCatching {runtime.engine.authenticate(e,p,create)}}},enabled=email.isNotBlank()&&password.isNotBlank()&&!work.busy&&!engineBusy&&runtime.config.syncUrl.isNotBlank()&&runtime.config.syncPublicKey.isNotBlank()){Text(if(signup)"注册"else"登录并拉取预览")}
                } else {
                    if(!state.enabled) {
                        Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(if(previewReady)"合并预览"else"预览尚未就绪",style=MaterialTheme.typography.titleMedium)
                            Text("本机可见 ${state.items.values.count {!it.deleted}} 条内容 · ${state.items.values.count {it.dirty}} 条本机更改待上传 · ${state.items.values.count {it.conflict!=null}} 条需要你决定的冲突")
                            Text(if(previewReady)"云端内容已合并到本机预览。确认开启后，本机变更会上传到此账号；发生冲突的内容等待你选定版本。"else"当前显示本机已读取的内容；完整云端预览完成前不会启用上传。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
                        }}
                        if(!previewReady) {
                            Text("已读取的内容和拉取进度会保留。继续拉取完整云端内容后，再确认开启自动同步。",color=StepMuted)
                            StepOutlinedButton({work.run {runCatching {runtime.engine.sync(manual=true);Unit}}},enabled=!work.busy&&!engineBusy){
                                Text(if(engineBusy)"正在拉取云端内容…"else"继续拉取云端内容")
                            }
                        }
                        StepButton({work.run {runCatching {runtime.engine.enable()}}},enabled=previewReady&&!work.busy&&!engineBusy){Text("确认合并并开启同步")}
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(state.enabled) {StepOutlinedButton({runtime.requestSync()},enabled=!engineBusy&&!work.busy){Text("立即同步")};StepOutlinedButton({runtime.engine.revoke();work.run {runCatching {runtime.engine.pause()}}},enabled=!work.busy){Text("暂停同步")}}
                        StepTextButton({runtime.engine.revoke();work.run {runCatching {runtime.engine.signOut()}}},enabled=!work.busy){Text("退出账号")}
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
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text("本机版本"+(if(local.deleted)" · 已删除"else""),style=MaterialTheme.typography.titleMedium);SelectionContainer {Text(local.content()?.toString().orEmpty(),style=MaterialTheme.typography.bodySmall)};StepOutlinedButton({work.edit {current->require(current.items[local.key]?.conflict==remote && current.items[local.key]?.payload==local.payload){"冲突内容已更新，请重新查看"};StateMachine.resolve(current,local.key,true)}},enabled=!work.busy&&state.pending==null){Text(if(local.conflictChoice==true)"已选本机版本"else"保留本机版本")}}
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text("云端版本"+(if(remote.deletedAt!=null)" · 已删除"else""),style=MaterialTheme.typography.titleMedium);SelectionContainer {Text(remoteContent?.toString().orEmpty(),style=MaterialTheme.typography.bodySmall)};StepOutlinedButton({work.edit {current->require(current.items[local.key]?.conflict==remote && current.items[local.key]?.payload==local.payload){"冲突内容已更新，请重新查看"};StateMachine.resolve(current,local.key,false)}},enabled=!work.busy&&state.pending==null){Text(if(local.conflictChoice==false)"已选云端版本"else"保留云端版本")}}
                            }
                            if(state.pending!=null)Text("先同步待确认的请求，再选择版本。",color=StepMuted,style=MaterialTheme.typography.bodySmall)
                        }}
                    }
                }
            }
            "ai" -> {
                AISettingsPanel(runtime,work,aiDraft)
                HorizontalDivider(color=StepLine)
                StepOutlinedButton({history=true},enabled=!work.busy){Text("查看本机请求与反馈")}
            }
            "preferences" -> {
                SectionLabel("背景与偏好","保存明确填写的背景、规划节奏与确认的偏好。每次请求仍需单独授权参考。")
                StepButton({preferences=true},enabled=!work.busy){Text("管理我的背景与偏好")}
                HorizontalDivider(color=StepLine)
                IntelligencePreferencesPanel(runtime,work)
            }
            "system" -> WindowsIntegrationPanel(runtime,work,systemDraft){systemDraft=it}
            else -> {
                SectionLabel("内容备份","导出任务、想法、目标与日计划；不包含账号凭据、模型密钥和 AI 原始历史。")
                FilePathField(backup,{backup=it},"备份保存位置",save=true,enabled=!work.busy)
                StepButton({val destination=backup;work.run {runtime.exportBackup(Path.of(destination))}},enabled=backup.isNotBlank()&&!work.busy){Text("导出内容备份")}
                HorizontalDivider(color=StepLine);SectionLabel("导入内容备份","先校验并预览；确认前不修改资料。导入前保存恢复快照，不包含凭据、模型密钥或 AI 原始历史。")
                FilePathField(importPath,{importPath=it;importPreview=null},"待导入备份文件",enabled=!work.busy)
                StepOutlinedButton({val source=importPath;var preview:DesktopBackupPreview?=null;work.run({importPreview=preview}){runtime.previewBackup(Path.of(source)).map {preview=it;Unit}}},enabled=importPath.isNotBlank()&&!work.busy){Text("校验并预览备份")}
                runtime.lastImportBackup?.let {path->StepTextButton({var preview:DesktopBackupPreview?=null;work.run({importPath=path.toString();importPreview=preview}){runtime.previewBackup(path).map {preview=it;Unit}}},enabled=!work.busy){Text("预览恢复到上次导入前")}}
                importPreview?.let {preview->
                    StepCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("备份中 ${preview.entityCount} 条内容",style=MaterialTheme.typography.titleMedium)
                        Text("新增 ${preview.newCount} · 替换 ${preview.changedCount} · 删除标记 ${preview.deletedCount} · 回收站 ${preview.trashCount}")
                        if(preview.restoresPreviousImport)Text("这是本机上次导入前的恢复备份。会还原当时内容，移除那次导入新增的条目；补回旧回收记录并保留现有回收记录。请核对后确认。",color=StepGold,style=MaterialTheme.typography.bodySmall)
                        Text("只合并备份列出的记录；同一条内容会采用备份版本。预览后的新修改、同步冲突或正在推进的任务会阻止导入。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
                        StepButton({confirmImport=true},enabled=!work.busy){Text("确认导入此备份…")}
                    }}
                }
                HorizontalDivider(color=StepLine);SectionLabel("损坏资料恢复","读取本机自动备份，校验后恢复。原损坏文件保留；仅在当前资料读取失败时可用。")
                StepOutlinedButton({confirmRecovery=true},enabled=!work.busy&&!initialized){Text("从自动备份恢复…")}
                HorizontalDivider(color=StepLine);SectionLabel("其他设备的运行状态","只查看有时效的状态参考；暂停、结束和接管仍在运行设备操作。")
                PresenceSettingsPanel(runtime,work)
                StepTextButton(onRequestClose){Text("关闭主窗口")}
            }
        }
    }
    if(preferences)PreferencesUi(runtime){preferences=false}
    if(history)AIHistoryUi(runtime){history=false}
    if(confirmImport)AlertDialog(onDismissRequest={if(!work.busy)confirmImport=false},title={Text("导入已预览的备份？")},text={Column {Text("预览中的同一条记录将采用备份版本。导入前会保留恢复快照；若资料已经变化，请重新预览。");if(work.failed)WorkFeedback(work)}},confirmButton={StepButton({val preview=importPreview?:return@StepButton;work.run({confirmImport=false;importPreview=null;work.message="备份已导入"}){runtime.applyBackup(preview)}},enabled=!work.busy){Text("导入备份")}},dismissButton={StepTextButton({confirmImport=false},enabled=!work.busy){Text("取消")}})
    if(confirmRecovery)AlertDialog(onDismissRequest={if(!work.busy)confirmRecovery=false},title={Text("恢复本机自动备份？")},text={Column {Text("这会用校验通过的自动备份替换损坏的主资料。恢复后重新读取资料；现有文件会先保留。");if(work.failed)WorkFeedback(work)}},confirmButton={StepButton({work.run({confirmRecovery=false;work.message="自动备份已恢复并重新读取"}){runtime.recoverAutomaticBackup()}},enabled=!work.busy){Text("恢复并保留原文件")}},dismissButton={StepTextButton({confirmRecovery=false},enabled=!work.busy){Text("取消")}})
}
@Composable private fun PresenceSettingsPanel(runtime:DesktopRuntime,work:UiWork) {
    val enabled by runtime.presenceSharingEnabled.collectAsState()
    val presence by runtime.presence.state.collectAsState()
    val state by runtime.engine.state.collectAsState()
    Row(verticalAlignment=Alignment.CenterVertically) {
        Checkbox(enabled,{value->work.run {runtime.setPresenceSharing(value)}},enabled=!work.busy)
        Text("共享本机运行状态并查看其他设备",modifier=Modifier.weight(1f))
        StepTextButton(runtime::refreshPresence,enabled=enabled&&!presence.refreshing){Text("刷新")}
    }
    Text(presence.status,style=MaterialTheme.typography.bodySmall,color=StepMuted)
    presence.peers.forEach {peer->
        StepCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("${peer.label} · ${if(peer.fresh)"最近状态"else"已过期，仅供参考"}",style=MaterialTheme.typography.titleMedium)
            Text("最后报告：${peer.lastSeen}",style=MaterialTheme.typography.bodySmall,color=StepMuted)
            peer.runs.forEach {run->Text((state.items["task:${run.taskId}"]?.title()?:"任务已不可见")+" · "+(if(run.kind=="personal")"个人专注"else"外部等待")+" · "+(if(run.state=="running")"运行中"else"已暂停"),color=if(peer.fresh)MaterialTheme.colorScheme.onSurface else StepMuted)}
            if(peer.runs.isEmpty())Text("此设备没有报告正在推进的任务",color=StepMuted)
        }}
    }
}
@Composable private fun WindowsIntegrationPanel(runtime:DesktopRuntime,work:UiWork,draft:DesktopSystemSettings,onChange:(DesktopSystemSettings)->Unit) {
    val saved by runtime.systemSettings.collectAsState()
    val update by runtime.updateState.collectAsState()
    var confirmInstall by remember {mutableStateOf(false)}
    val supported=runtime.systemSupported
    SectionLabel("Windows 系统功能","按需启用；点击保存后才会更改当前用户的系统设置。")
    if(!supported)Text("系统功能需要 Windows 安装版；当前环境可查看设置，不能启用。",color=StepMuted)
    Row(verticalAlignment=Alignment.CenterVertically) {
        Checkbox(draft.startAtLogin,{onChange(draft.copy(startAtLogin=it))},enabled=!work.busy&&(supported||draft.startAtLogin))
        Text("登录 Windows 后启动 Step")
    }
    Row(verticalAlignment=Alignment.CenterVertically) {
        Checkbox(draft.systemNotifications,{onChange(draft.copy(systemNotifications=it,afterExitReminders=if(it)draft.afterExitReminders else false))},enabled=!work.busy&&(supported||draft.systemNotifications))
        Text("使用可操作的系统通知")
    }
    Text("在通知中完成或暂缓提醒；任务内容会显示在系统通知中。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
    Row(verticalAlignment=Alignment.CenterVertically) {
        Checkbox(draft.afterExitReminders,{onChange(draft.copy(afterExitReminders=it))},enabled=!work.busy&&draft.systemNotifications&&(supported||draft.afterExitReminders))
        Text("退出 Step 后仍接收已安排的提醒")
    }
    Text("需要先开启系统通知。使用当前用户的系统任务计划交付已安排的提醒。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
    HorizontalDivider(color=StepLine)
    Row(verticalAlignment=Alignment.CenterVertically) {
        Checkbox(draft.attentionEnabled,{onChange(draft.copy(attentionEnabled=it,attentionBrowserEnabled=it&&draft.attentionBrowserEnabled))},enabled=!work.busy&&(supported||draft.attentionEnabled))
        Text("本地注意力提醒",modifier=Modifier.weight(1f))
        StepTextButton({work.run({onChange(draft.copy(attentionSnoozedUntil=runtime.systemSettings.value.attentionSnoozedUntil));work.message="注意力提醒已暂缓1小时"}){runtime.snoozeAttention(60)}},enabled=!work.busy&&saved.attentionEnabled&&supported){Text("暂缓1小时")}
    }
    Text("仅在本机判断空闲与窗口切换频率；不采集截图，不上传应用或浏览历史。默认关闭。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
    Row(verticalAlignment=Alignment.CenterVertically) {
        Checkbox(draft.attentionBrowserEnabled,{onChange(draft.copy(attentionBrowserEnabled=it))},enabled=!work.busy&&supported&&draft.attentionEnabled)
        Text("浏览器增强：识别同一窗口内反复切换页面")
    }
    Text("前台浏览器标题仅在本机内存转换为临时摘要，不保存原文、网址或浏览历史，重启清空。它不理解工作内容；提示可以忽略或暂缓。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
    if(saved.attentionSnoozedUntil>System.currentTimeMillis())Text("已暂缓至 "+java.time.Instant.ofEpochMilli(saved.attentionSnoozedUntil).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm")),style=MaterialTheme.typography.bodySmall,color=StepMuted)
    HorizontalDivider(color=StepLine)
    Row(verticalAlignment=Alignment.CenterVertically) {
        Checkbox(draft.autoCheckUpdates,{onChange(draft.copy(autoCheckUpdates=it))},enabled=!work.busy&&(supported||draft.autoCheckUpdates))
        Text("自动检查更新（不自动下载或安装）")
    }
    Text("开启后，Step 运行或驻留托盘期间定期检查官方发布信息。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
    val dirty=draft.copy(attentionSnoozedUntil=saved.attentionSnoozedUntil)!=saved
    StepButton({val value=draft.copy(attentionSnoozedUntil=runtime.systemSettings.value.attentionSnoozedUntil);work.run({onChange(runtime.systemSettings.value);work.message="Windows 设置已保存"}){runtime.saveSystemSettings(value)}},enabled=!work.busy&&dirty){Text("保存 Windows 设置")}
    HorizontalDivider(color=StepLine);SectionLabel("应用更新","下载完成后校验安装文件；始终由你确认下载和安装。")
    Text(update.status,color=StepMuted)
    StepOutlinedButton({work.run {runtime.checkForUpdates()}},enabled=!work.busy&&!update.busy){Text("检查更新")}
    update.candidate?.let {candidate->
        Text("Step ${candidate.version}",style=MaterialTheme.typography.titleMedium)
        if(candidate.notes.isNotBlank())Text(candidate.notes,style=MaterialTheme.typography.bodySmall)
        Text("安装文件约 ${candidate.size/1024/1024} MB · 下载完成后校验 SHA-256",style=MaterialTheme.typography.bodySmall,color=StepMuted)
        if(!update.downloaded)StepButton({work.run {runtime.downloadUpdate()}},enabled=!work.busy&&!update.busy&&supported){Text("下载并校验更新")}
        else StepButton({confirmInstall=true},enabled=!work.busy&&!update.busy&&supported){Text("安装已校验的更新…")}
    }
    if(confirmInstall)AlertDialog(onDismissRequest={if(!work.busy)confirmInstall=false},title={Text("启动已校验的更新安装？")},text={Column {Text("Step 将先处理未保存的草稿，再退出并打开安装程序。现有资料保留。");if(work.failed)WorkFeedback(work)}},confirmButton={StepButton({work.run({confirmInstall=false}){runtime.requestUpdateInstall()}},enabled=!work.busy){Text("继续安装")}},dismissButton={StepTextButton({confirmInstall=false},enabled=!work.busy){Text("取消")}})
}
@Composable internal fun AISettingsPanel(runtime:DesktopRuntime,work:UiWork,draft:UiAIServiceDraft) {
    val settings by runtime.aiSettings.collectAsState()
    SectionLabel("你选择的 AI 服务","默认不发送内容。每次生成前预览上下文，生成结果由你审阅采纳。")
    Field(draft.endpoint,{draft.endpoint=it},"完整 HTTPS Chat Completions 地址",enabled=!work.busy)
    Field(draft.model,{draft.model=it},"模型名称",enabled=!work.busy)
    Field(draft.key,{draft.key=it},if(settings.hasKey)"替换密钥（留空保留同一服务密钥）"else"API 密钥",secret=true,enabled=!work.busy)
    Text("密钥与 AI 原始历史仅保留本机。更换服务或模型时，请重新填写密钥。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        StepButton({val config=MobileAIConfig(draft.endpoint,draft.model);val secret=draft.key.takeIf {it.isNotEmpty()};work.run({draft.saved(config.validated())}){runtime.saveAI(config,secret)}},enabled=!work.busy&&draft.endpoint.isNotBlank()&&draft.model.isNotBlank()){Text("保存服务设置")}
        if(settings.hasKey)StepTextButton({work.run({draft.key=""}){runtime.saveAI(settings.config,"")}},enabled=!work.busy){Text("移除密钥并停用 AI")}
    }
}
