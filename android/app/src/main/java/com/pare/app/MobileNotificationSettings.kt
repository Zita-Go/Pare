package com.pare.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Put next to a real reminder/focus/check action; permission requests only follow a tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobileNotificationSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val runtime = remember(context) { MobileNotificationRuntime(context) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    fun reload() {
        refresh++
        scope.launch(Dispatchers.IO) { runCatching { runtime.refresh() } }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { reload() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reload() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val allowed = remember(refresh) { runtime.notificationsAllowed() }
    val exact = remember(refresh) { runtime.exactAllowed() }
    fun openSettings(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure { message = "无法打开系统设置，请在系统中找到 Pare 的通知与闹钟权限。" }
    }
    MobileNotificationControls(
        allowed = allowed, exact = exact, modifier = modifier,
        onEnable = {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        },
        onSystemSettings = { openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
        onExactSettings = if (allowed && !exact && Build.VERSION.SDK_INT >= 31) ({
            openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }) else null,
        error = message ?: runtime.lastError()
    )
}

/** Displaying permission status never requests a permission; each OS action follows its own tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MobileNotificationControls(
    allowed: Boolean,
    exact: Boolean,
    onEnable: () -> Unit,
    onSystemSettings: () -> Unit,
    onExactSettings: (() -> Unit)?,
    error: String? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(when {
            !allowed -> "手机通知未开启，安排仍会保留。"
            !exact -> "手机提醒已开启，按大约时间提醒。"
            else -> "手机提醒已开启，送达仍受系统限制。"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("notification_status"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!allowed) TextButton(onClick = onEnable, modifier = Modifier.heightIn(min = 48.dp).testTag("notification_enable"),
                contentPadding = PaddingValues(horizontal = 4.dp)) { Text("开启手机通知") }
            TextButton(onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp).testTag("notification_disclosure").semantics { stateDescription = if (expanded) "已展开" else "已收起" },
                contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("提醒设置")
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
            }
        }
        if (expanded) {
            Text("系统省电、强行停止应用或通知权限变化可能延迟或阻止提醒。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Keep system settings reachable after repeated permission denial or channel disable.
            TextButton(onClick = onSystemSettings,
                modifier = Modifier.heightIn(min = 48.dp).testTag("notification_system_settings"), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("系统通知设置") }
            if (onExactSettings != null) TextButton(onClick = onExactSettings,
                modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("允许更准时的提醒") }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
