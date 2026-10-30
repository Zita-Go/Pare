package com.pare.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

private object ReminderReceiverWork {
    val executor = Executors.newSingleThreadExecutor()
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(MobileNotificationRuntime.ACTION_FIRE, MobileNotificationRuntime.ACTION_SNOOZE,
                MobileNotificationRuntime.ACTION_DISMISS, MobileNotificationRuntime.ACTION_REBUILD)) return
        val pending = goAsync()
        ReminderReceiverWork.executor.execute {
            try { MobileNotificationRuntime(context).handle(action, intent.getStringExtra(MobileNotificationRuntime.TOKEN_EXTRA)) }
            catch (_: Exception) { MobileNotificationRuntime(context).recordReceiverFailure() }
            finally { pending.finish() }
        }
    }
}

class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val pending = goAsync()
        ReminderReceiverWork.executor.execute {
            try { MobileNotificationRuntime(context).refresh(force = true) }
            catch (_: Exception) { MobileNotificationRuntime(context).recordReceiverFailure() }
            finally { pending.finish() }
        }
    }
}
