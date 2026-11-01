package com.pare.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Wait for the OS alarm/receiver path; never invoke handle() or send the alarm PendingIntent. */
@SdkSuppress(minSdkVersion = 28)
class MobileAlarmDeliveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val reminders get() = MobileNotificationRuntime(context)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)
    private val power get() = context.getSystemService(PowerManager::class.java)
    private lateinit var runtime: SyncRuntime
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var launchIntent: Intent
    private var lockRequested = false
    private var lockObserved = false
    private val taskId = "99000000-0000-4000-8000-000000000001"
    private val title = "虚构：系统闹钟到点后检查笔记"

    @Before fun prepareOnlyIsolatedQa() {
        check(context.packageName == "com.pare.app.qa")
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            runCatching { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS) }
        }
        reminders.createChannel()
        requirePermission("QA notification permission or channel is blocked", reminders.notificationsAllowed())
        if (Build.VERSION.SDK_INT >= 31 && !reminders.exactAllowed()) {
            // Exact alarms are an app-op, not a runtime permission. This touches only the QA package.
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "appops set com.pare.app.qa SCHEDULE_EXACT_ALARM allow"
                )).bufferedReader().use { it.readText() }
            }
        }
        requirePermission("Exact alarms unavailable; a bounded exact-delivery check cannot run", reminders.exactAllowed())
        check(context.getSharedPreferences("session-vault", Context.MODE_PRIVATE).edit().clear().commit())
        val blank = PareRepository(context).use { it.update { SyncState() } }
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
        reminders.refresh(blank, force = true)
        runtime = SyncRuntime.get(context)
        runBlocking { runtime.engine.initialize(); runtime.engine.pause() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { launchIntent = Intent(it.intent) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tab_today").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun realAlarmPostsPrivateNotificationAndOpensItsTask() {
        val at = Instant.now().plusSeconds(5)
        val payload = ContentActions.save(null, "task", title, "虚构测试内容", 1, taskId, Instant.now())
        val withReminder = JsonObject(payload + ("content" to JsonObject(payload.getValue("content").jsonObject + ("remindAt" to JsonPrimitive(at.toString())))))
        val state = runtime.engine.edit { StateMachine.edit(it, "task", taskId, withReminder) }
        reminders.refresh(state, force = true)
        val event = reminders.readLedger().scheduled.values.single { it.taskId == taskId && it.kind == "task" }
        assertTrue("This bounded test requires the real exact-alarm path", reminders.readLedger().exact)
        assertNull("ALARM_REGISTRATION_FAILED", reminders.lastError())

        // A secure test device must never be unlocked or have its credentials changed by this test.
        if (!keyguard.isDeviceSecure && !keyguard.isKeyguardSecure) {
            lockRequested = instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            if (lockRequested) lockObserved = waitForCondition(2_000) { keyguard.isKeyguardLocked && !power.isInteractive }
        }
        val delivered = waitForCondition(25_000) { notifications.activeNotifications.any { it.tag == event.token } }
        requirePermission("Notification access was revoked while waiting for the alarm", reminders.notificationsAllowed())
        requirePermission("Exact-alarm access was revoked while waiting for the alarm", reminders.exactAllowed())
        assertTrue("ALARM_DELIVERY_FAILED: no real notification within 25s; receiverError=${reminders.lastError()}; handled=${event.token in reminders.readLedger().handled}", delivered)

        val lockedAtDelivery = keyguard.isKeyguardLocked
        if (lockObserved) assertTrue("The observed keyguard must still be locked at delivery", lockedAtDelivery)
        val posted = notifications.activeNotifications.single { it.tag == event.token }
        assertTrue("Delivery must occur at the scheduled time, not at registration", posted.postTime >= event.atMillis - 1_000)
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.notification.visibility)
        assertEquals(title, posted.notification.extras.getString(Notification.EXTRA_TEXT))
        assertNotNull(posted.notification.publicVersion)
        assertFalse(posted.notification.publicVersion.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains(title))
        assertEquals(context.packageName, posted.notification.contentIntent.creatorPackage)
        val expectedTarget = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setData(Uri.parse("pare-task://${context.packageName}/${Uri.encode(taskId)}")),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull("The target must be this QA Activity and task URI", expectedTarget)
        assertEquals(expectedTarget, posted.notification.contentIntent)
        assertTrue(event.token in reminders.readLedger().handled)
        assertFalse(reminders.readLedger().scheduled.values.any { it.token == event.token })
        assertEquals("Delivery cannot change shared task content", state.items.getValue("task:$taskId"),
            PareRepository(context).use { it.read().items.getValue("task:$taskId") })

        wakeQaActivity()
        posted.notification.contentIntent.send()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor_text").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor_text").assertTextContains(title)
        val evidence = buildJsonObject {
            put("scheduled_at_millis", event.atMillis)
            put("notification_posted_at_millis", posted.postTime)
            put("exact_alarm", true)
            put("lock_requested", lockRequested)
            put("screen_off_and_keyguard_observed_before_delivery", lockObserved)
            put("keyguard_locked_at_delivery", lockedAtDelivery)
            put("private_notification_metadata", true)
            put("target_task_opened", true)
            put("visual_lockscreen_redaction_checked", false)
        }
        Log.i("PareRealAlarm", evidence.toString())
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { path ->
            File(path).apply { mkdirs() }.resolve("real-alarm-delivery.json").writeText(evidence.toString())
        }
    }

    @After fun cleanOnlyQaAndRestoreInteractiveScreen() {
        check(context.packageName == "com.pare.app.qa")
        try {
            if (::scenario.isInitialized) {
                try { wakeQaActivity() } finally {
                    scenario.onActivity {
                        it.setTurnScreenOn(false)
                        it.setShowWhenLocked(false)
                        if (::launchIntent.isInitialized) it.intent = Intent(launchIntent)
                    }
                    scenario.close()
                }
            }
        } finally {
            if (::runtime.isInitialized) {
                val blank = runtime.engine.edit { SyncState(device = it.device) }
                if (runtime.executionReady.value) runtime.execution.reconcile()
                ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
                reminders.refresh(blank, force = true)
                // The runtime also owns a next-day rebuild alarm, which must not escape this test.
                val rebuild = PendingIntent.getBroadcast(context, 0,
                    Intent(context, ReminderAlarmReceiver::class.java).setAction(MobileNotificationRuntime.ACTION_REBUILD),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                rebuild?.let { context.getSystemService(android.app.AlarmManager::class.java).cancel(it); it.cancel() }
                notifications.cancelAll()
                assertTrue(reminders.readLedger().scheduled.isEmpty())
                assertTrue(reminders.readLedger().visible.isEmpty())
            }
        }
    }

    private fun wakeQaActivity() {
        if (!lockRequested) return
        scenario.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        assertTrue("QA_SCREEN_WAKE_FAILED", waitForCondition(5_000) { power.isInteractive })
        scenario.onActivity { keyguard.requestDismissKeyguard(it, null) }
        assertTrue("QA_SCREEN_RESTORE_FAILED", waitForCondition(5_000) { power.isInteractive && !keyguard.isKeyguardLocked })
    }

    private fun requirePermission(reason: String, granted: Boolean) {
        if (!granted) Log.w("PareRealAlarm", "PERMISSION_BLOCKED: $reason")
        assumeTrue("PERMISSION_BLOCKED: $reason", granted)
    }

    private fun waitForCondition(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            if (condition()) return true
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        return condition()
    }
}
