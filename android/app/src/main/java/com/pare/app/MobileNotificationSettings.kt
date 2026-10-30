package com.pare.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Put next to a real reminder/focus/check action; permission requests only follow a tap. */
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
    Column(modifier) {
        Text(when {
            !allowed -> "手机通知尚未开启；时间安排仍会保留。"
            !exact -> "手机提醒使用大约时间，系统省电可能进一步延迟。"
            else -> "已允许按时间提醒；强行停止应用或系统限制仍可能影响送达。"
        }, style = MaterialTheme.typography.bodySmall)
        if (!allowed) TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text("开启手机通知") }
        // Users can always reach settings after a repeated permission denial or channel disable.
        TextButton(onClick = { openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("系统通知设置") }
        if (allowed && !exact && Build.VERSION.SDK_INT >= 31) TextButton(onClick = {
            openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }) { Text("允许更准时的提醒") }
        (message ?: runtime.lastError())?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
