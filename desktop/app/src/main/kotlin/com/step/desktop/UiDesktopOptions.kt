package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Owned by the settings dialog, so switching tabs cannot discard shortcut input. */
internal class DesktopOptionsDraft {
    private var baseline by mutableStateOf<String?>(null)
    var shortcut by mutableStateOf("")
    val dirty:Boolean get()=baseline?.let {shortcut!=it}?:false
    fun observeSavedShortcut(saved:String) {
        val wasDirty=dirty
        if(!wasDirty || saved==shortcut.trim())shortcut=saved
        baseline=saved
    }
}

internal val LocalDesktopOptionsContent=staticCompositionLocalOf<(@Composable (DesktopOptionsDraft)->Unit)?> {null}

@Composable internal fun DesktopOptionsContent(platform:DesktopPlatform,draft:DesktopOptionsDraft=remember {DesktopOptionsDraft()}) {
    val current by platform.shortcut.collectAsState()
    val tray by platform.keepInTray.collectAsState()
    val darkAppearance by platform.darkAppearance.collectAsState()
    val widgetVisible by platform.widgetVisible.collectAsState()
    val widgetPinned by platform.widgetPinned.collectAsState()
    val status by platform.status.collectAsState()
    LaunchedEffect(current) {draft.observeSavedShortcut(current)}
    ReportUnsavedEdits(draft.dirty)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("外观与桌面",style=MaterialTheme.typography.titleLarge)
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("外观",style=MaterialTheme.typography.bodyMedium)
            FilterChip(darkAppearance,{platform.changeAppearance(true)},label={Text("深色")},shape=MaterialTheme.shapes.small)
            FilterChip(!darkAppearance,{platform.changeAppearance(false)},label={Text("浅色")},shape=MaterialTheme.shapes.small)
        }
        Text("随手记快捷键",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(draft.shortcut,{draft.shortcut=it},label={Text("例如 Ctrl+Shift+Space")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("desktop_shortcut"))
        Button(onClick={platform.changeShortcut(draft.shortcut)},shape=MaterialTheme.shapes.small) {Text("保存快捷键")}
        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(tray,{platform.changeTray(it)});Text("关闭主窗口后留在系统托盘",modifier=Modifier.weight(1f))}
        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(widgetVisible,{platform.changeWidget(it)},modifier=Modifier.testTag("desktop_widget_visible"));Text("显示 Step 桌面小窗",modifier=Modifier.weight(1f))}
        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(widgetPinned,{platform.pinWidget(it)});Text("桌面小窗保持置顶",modifier=Modifier.weight(1f))}
        Text("运行或托盘驻留时自动同步。退出后的系统提醒可在设置中单独开启；不接管其他设备计时。",style=MaterialTheme.typography.bodySmall)
        status?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
}
