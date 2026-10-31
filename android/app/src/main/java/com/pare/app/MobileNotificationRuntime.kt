package com.pare.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import java.time.Instant
import java.time.ZoneId

/** Local OS effects only. Receivers never modify shared tasks or contact the sync server. */
class MobileNotificationRuntime(
    context: Context,
    private val loadState: () -> SyncState = { val repo = PareRepository(context.applicationContext); try { repo.read() } finally { repo.close() } },
    private val loadDeadlines: (SyncState) -> List<ReminderDeadline> = { executionDeadlines(context.applicationContext, it) }
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("pare-reminders", Context.MODE_PRIVATE)
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)

    companion object {
        const val TASK_ID_EXTRA = "com.pare.app.reminder.taskId"
        const val TOKEN_EXTRA = "reminderToken"
        const val ACTION_FIRE = "com.pare.app.reminder.FIRE"
        const val ACTION_SNOOZE = "com.pare.app.reminder.SNOOZE"
        const val ACTION_DISMISS = "com.pare.app.reminder.DISMISS"
        const val ACTION_REBUILD = "com.pare.app.reminder.REBUILD"
        const val CHANNEL = "pare-task-reminders"
        private val lock = Any()

        private fun executionDeadlines(context: Context, state: SyncState): List<ReminderDeadline> {
            val snapshot = ExecutionFilePersistence(context).load() ?: return emptyList()
            if (snapshot.owner != state.owner || snapshot.device != state.device) return emptyList()
            val now = AndroidExecutionClock(context).now()
            fun valid(run: ExecutionRun) = run.pendingFinish == null && run.anchor?.let { it.bootId == now.bootId && now.elapsedMillis >= it.elapsedMillis } != false
            return buildList {
                snapshot.focus?.takeIf { valid(it) }?.let { run ->
                    run.currentFocusDeadlineMillis(now)?.let { liveDeadline ->
                        // One focus target per run; automatic settling and clock shifts cannot rearm a delivered target.
                        add(ReminderDeadline(run.taskId, "focus", run.id, liveDeadline, "${run.startedAtMillis}:${run.targetMinutes}:${run.deadlineGeneration}"))
                    }
                }
                snapshot.external.filter { valid(it) && !it.paused }.forEach { run ->
                    run.nextCheckAtMillis?.let { add(ReminderDeadline(run.taskId, "check", run.id, it)) }
                }
            }
        }
    }

    fun createChannel() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "任务与推进提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "任务提醒、今日时间安排、专注目标与外部结果检查"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    fun notificationsAllowed(): Boolean = (Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    fun lastError(): String? = prefs.getString("error", null)
    internal fun recordReceiverFailure() = synchronized(lock) {
        prefs.edit().putBoolean("needsRestore", true).putString("error", "手机提醒暂未送达，请重新打开 Pare 重试。").commit()
    }
    fun readLedger(): ReminderLedger = synchronized(lock) {
        prefs.getString("ledger", null)?.let { wire.decodeFromString<ReminderLedger>(it) } ?: ReminderLedger()
    }
    private fun save(ledger: ReminderLedger) { check(prefs.edit().putString("ledger", wire.encodeToString(ledger)).commit()) { "提醒状态未能保存" } }

    /** IO caller. Equal sync snapshots do not repost notifications or replace pending alarms. */
    fun refresh(state: SyncState = loadState(), force: Boolean = false, now: Instant = Instant.now()) = synchronized(lock) {
        createChannel()
        val ledger = readLedger()
        val deadlines = runCatching { loadDeadlines(state) }
        val sources = ReminderPlanner.sources(state, deadlines.getOrDefault(emptyList()), now)
        val rebuild = force || prefs.getBoolean("needsRestore", false)
        val exact = exactAllowed()
        val desired = if (notificationsAllowed()) ReminderPlanner.reconcile(sources, ledger, now).associateBy { it.sourceKey } else emptyMap()
        for ((key, event) in ledger.scheduled) if (desired[key] != event || rebuild || ledger.exact != exact) cancelAlarm(event)
        val visible = ledger.visible.filterValues { event -> sources.any { it.accepts(event) } && notificationsAllowed() }
        for ((token, _) in ledger.visible) if (token !in visible) notifications.cancel(token, 0)
        val installed = mutableMapOf<String, ReminderOccurrence>()
        var failed = false
        for ((key, event) in desired) {
            try {
                if (ledger.scheduled[key] != event || rebuild || ledger.exact != exact) schedule(event, exact, now)
            } catch (_: RuntimeException) { failed = true }
            // Keep the intended occurrence across a transient scheduling error for the next retry.
            installed[key] = event
        }
        val zone = ZoneId.of(ContentActions.planId(state, now).substringAfter('@'))
        val nextDay = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (notificationsAllowed() && (rebuild || ledger.nextRebuildAt != nextDay)) {
            try { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextDay, rebuildIntent()) }
            catch (_: RuntimeException) { failed = true }
        } else if (!notificationsAllowed()) alarms.cancel(rebuildIntent())
        save(ledger.copy(scheduled = installed, visible = visible, exact = exact, nextRebuildAt = if (notificationsAllowed()) nextDay else 0))
        prefs.edit().putBoolean("needsRestore", failed).putString("error", when {
            failed -> "部分手机提醒未能安排，请重新打开 Pare 后重试。"
            deadlines.isFailure -> "本机推进记录暂时无法读取；普通任务提醒仍可使用，专注与检查提醒需稍后重试。"
            else -> null
        }).commit()
    }

    /** A delivery/action is valid only for the still-current source and durable event token. */
    fun handle(action: String, token: String?, now: Instant = Instant.now()) = synchronized(lock) {
        if (action == ACTION_REBUILD) { refresh(force = true, now = now); return@synchronized }
        if (token == null) return@synchronized
        val state = loadState()
        var ledger = readLedger()
        val event = if (action == ACTION_FIRE) ledger.scheduled.values.firstOrNull { it.token == token } else ledger.visible[token]
        if (event == null) return@synchronized
        val source = ReminderPlanner.sources(state, runCatching { loadDeadlines(state) }.getOrDefault(emptyList()), now).firstOrNull { it.accepts(event) }
        if (source == null || (action == ACTION_FIRE && source.kind == "focus" && event.suffix.isEmpty() && source.anchorMillis > now.toEpochMilli() + 1_000)) {
            refresh(state, now = now); return@synchronized
        }
        when (action) {
            ACTION_FIRE -> {
                if (token in ledger.handled || event.atMillis > now.toEpochMilli() + 1_000) return@synchronized
                cancelAlarm(event)
                ledger = ledger.copy(scheduled = ledger.scheduled - event.sourceKey, handled = ledger.handled + token,
                    visible = if (notificationsAllowed()) ledger.visible + (token to event) else ledger.visible)
                // Commit before posting: process death cannot replay an already handled occurrence.
                save(ledger)
                if (notificationsAllowed()) {
                    try {
                        val task=state.items.getValue("task:${event.taskId}")
                        val actionText=(task.content()?.get("implementationPlan") as? kotlinx.serialization.json.JsonObject)?.get("action") as? kotlinx.serialization.json.JsonPrimitive
                        val text=if(event.kind=="implementation") "${task.title()} · ${actionText?.content.orEmpty()}" else task.title()
                        notifications.notify(token, 0, notification(event, text))
                    }
                    catch (_: SecurityException) { /* Permission can be revoked between check and post. */ }
                }
            }
            ACTION_SNOOZE -> {
                val snoozed = event.copy(atMillis = now.plusSeconds(600).toEpochMilli(), suffix = "snooze:${now.toEpochMilli()}")
                ledger.scheduled[event.sourceKey]?.let { cancelAlarm(it) }
                save(ledger.copy(scheduled = ledger.scheduled + (event.sourceKey to snoozed), visible = ledger.visible - token))
                notifications.cancel(token, 0)
                // Force installs the persisted snooze after replacing any next recurring occurrence.
                refresh(state, force = true, now = now)
                return@synchronized
            }
            ACTION_DISMISS -> { save(ledger.copy(visible = ledger.visible - token)); notifications.cancel(token, 0) }
            else -> return@synchronized
        }
        refresh(state, now = now)
    }

    private fun schedule(event: ReminderOccurrence, exact: Boolean, now: Instant) {
        val trigger = maxOf(event.atMillis, now.toEpochMilli() + 1_000)
        val operation = eventIntent(event, ACTION_FIRE)
        if (exact) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation); return }
            catch (_: SecurityException) { /* Recheck next refresh; retain an honest inexact fallback now. */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
    }
    private fun cancelAlarm(event: ReminderOccurrence) { val pending = eventIntent(event, ACTION_FIRE); alarms.cancel(pending); pending.cancel() }
    private fun eventIntent(event: ReminderOccurrence, action: String): PendingIntent = PendingIntent.getBroadcast(app, 0,
        Intent(app, ReminderAlarmReceiver::class.java).setAction(action).setData(Uri.parse("pare-reminder://${app.packageName}/$action/${Uri.encode(event.token)}"))
            .putExtra(TOKEN_EXTRA, event.token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun rebuildIntent(): PendingIntent = PendingIntent.getBroadcast(app, 0,
        Intent(app, ReminderAlarmReceiver::class.java).setAction(ACTION_REBUILD), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(event: ReminderOccurrence, text: String): Notification {
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java)
            .setData(Uri.parse("pare-task://${app.packageName}/${Uri.encode(event.taskId)}"))
            .putExtra(TASK_ID_EXTRA, event.taskId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when (event.kind) { "focus" -> "这段专注到目标时间了"; "check" -> "回来看看等待的结果"; "schedule" -> "安排的行动快开始了"; "implementation" -> "如果到这个时刻，那么先行动"; else -> "该做这件事了" }
        val publicVersion = Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.pare_notification).setContentTitle("Pare 提醒")
            .setContentText("打开 Pare 查看安排").setVisibility(Notification.VISIBILITY_PUBLIC).build()
        return Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.pare_notification).setContentTitle(title).setContentText(text.take(240))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).setContentIntent(open).setAutoCancel(true)
            .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_REMINDER)
            .setDeleteIntent(eventIntent(event, ACTION_DISMISS))
            .addAction(Notification.Action.Builder(null as Icon?, "10分钟后", eventIntent(event, ACTION_SNOOZE)).build())
            .addAction(Notification.Action.Builder(null as Icon?, "忽略", eventIntent(event, ACTION_DISMISS)).build()).build()
    }
}
