package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pare.app.Session
import com.pare.app.SyncState

internal enum class DesktopSyncPhase { READING, LOCAL, AUTHENTICATING, PULLING, SYNCING, PREVIEW_PENDING, AWAITING_CONSENT, PAUSED, WAITING, ERROR, CONFLICT, SYNCED }
internal data class DesktopSyncPresentation(val phase:DesktopSyncPhase,val label:String,val detail:String)

/** Enabled means permission to exchange changes, never evidence that a sync completed. */
internal fun desktopSyncPresentation(state:SyncState,session:Session?,initialized:Boolean,busy:Boolean,previewReady:Boolean,status:String):DesktopSyncPresentation {
    val conflicts=state.items.values.count {it.conflict!=null || it.conflictGroup.isNotEmpty()}
    val dirty=state.items.values.count {it.dirty}
    val failed=listOf("失败","重试","超时","失效","拒绝","已停止").any {it in status}
    return when {
        failed -> DesktopSyncPresentation(DesktopSyncPhase.ERROR,"同步未完成",status)
        !initialized -> DesktopSyncPresentation(DesktopSyncPhase.READING,"正在读取本机内容",status)
        busy && session==null -> DesktopSyncPresentation(DesktopSyncPhase.AUTHENTICATING,"正在连接账号",status)
        session==null -> DesktopSyncPresentation(DesktopSyncPhase.LOCAL,"本机保存","未登录账号；内容保存在本机。登录后先拉取预览，由你确认开启上传。"+(if(status.startsWith("请到邮箱"))"\n$status"else""))
        session.owner!=state.owner -> DesktopSyncPresentation(DesktopSyncPhase.ERROR,"请检查同步账号","登录账号与本机资料库不一致，请在设置中检查账号；本机内容保留。")
        conflicts>0 -> DesktopSyncPresentation(DesktopSyncPhase.CONFLICT,"需要处理冲突","有 $conflicts 条内容需要选择版本；两个版本都已保留。")
        busy -> if(state.enabled)DesktopSyncPresentation(DesktopSyncPhase.SYNCING,"正在同步","正在交换已保存的内容；完成前不会显示为已同步。")
            else DesktopSyncPresentation(DesktopSyncPhase.PULLING,"正在拉取预览","正在读取云端内容；尚未开启上传。")
        !state.enabled && status.startsWith("同步已暂停") -> DesktopSyncPresentation(DesktopSyncPhase.PAUSED,"同步已暂停","变更保留在本机。重新拉取完整预览后，由你确认恢复同步。")
        !state.enabled && !previewReady -> DesktopSyncPresentation(DesktopSyncPhase.PREVIEW_PENDING,"预览未就绪","同步未开启。继续拉取完整云端内容后，再确认合并与上传。")
        !state.enabled -> DesktopSyncPresentation(DesktopSyncPhase.AWAITING_CONSENT,"待确认开启同步","云端预览已就绪。确认合并后才会上传本机更改。")
        dirty>0 || state.pending!=null -> DesktopSyncPresentation(DesktopSyncPhase.WAITING,"等待同步",if(dirty>0)"$dirty 条本机更改等待同步；内容已保存在本机。"else"上次请求等待云端确认；内容已保存在本机。")
        status=="已同步" && previewReady -> DesktopSyncPresentation(DesktopSyncPhase.SYNCED,"已同步","最近一次同步已完成，没有待上传的本机更改或未处理冲突。")
        else -> DesktopSyncPresentation(DesktopSyncPhase.WAITING,"等待同步","尚未完成本次同步确认。"+(if(status.isNotBlank())"\n$status"else""))
    }
}

@Composable internal fun rememberDesktopSyncPresentation(runtime:DesktopRuntime):DesktopSyncPresentation {
    val state by runtime.engine.state.collectAsState()
    val session by runtime.engine.session.collectAsState()
    val initialized by runtime.engine.initialized.collectAsState()
    val busy by runtime.engine.busy.collectAsState()
    val previewReady by runtime.engine.previewReady.collectAsState()
    val status by runtime.engine.status.collectAsState()
    return desktopSyncPresentation(state,session,initialized,busy,previewReady,status)
}

@Composable internal fun DesktopSyncPresentation.color():Color=when(phase) {
    DesktopSyncPhase.ERROR,DesktopSyncPhase.CONFLICT -> MaterialTheme.colorScheme.error
    DesktopSyncPhase.SYNCED -> StepGreen
    DesktopSyncPhase.PULLING,DesktopSyncPhase.SYNCING,DesktopSyncPhase.AWAITING_CONSENT -> StepGold
    else -> StepMuted
}

@Composable internal fun SyncStatusButton(runtime:DesktopRuntime,onClick:()->Unit) {
    val presentation=rememberDesktopSyncPresentation(runtime)
    val icon=when(presentation.phase) {
        DesktopSyncPhase.SYNCED -> Icons.Outlined.CloudDone
        DesktopSyncPhase.ERROR,DesktopSyncPhase.CONFLICT -> Icons.Outlined.ErrorOutline
        DesktopSyncPhase.PULLING,DesktopSyncPhase.SYNCING,DesktopSyncPhase.AUTHENTICATING -> Icons.Outlined.Sync
        DesktopSyncPhase.PAUSED -> Icons.Outlined.Pause
        else -> Icons.Outlined.CloudOff
    }
    StepTextButton(onClick,modifier=Modifier.testTag("sync_status")) {
        StepIcon(icon,color=presentation.color());Spacer(Modifier.width(8.dp))
        Text(presentation.label,color=presentation.color(),maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
