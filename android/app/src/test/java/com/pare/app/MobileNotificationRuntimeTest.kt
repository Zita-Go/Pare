package com.pare.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant

/** Robolectric only: no device service or personal repository receives notifications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MobileNotificationRuntimeTest {
    private lateinit var context: Context
    private lateinit var state: SyncState
    private lateinit var runtime: MobileNotificationRuntime
    private val now = Instant.parse("2026-10-30T08:00:00Z")
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pare-reminders", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        state = SyncState(items = mapOf("task:fictional" to Entity("task", "fictional", buildJsonObject {
            put("lifecycle", "active"); put("content", buildJsonObject { put("text", "虚构私密任务"); put("remindAt", "2026-10-30T09:00:00Z") })
        })))
        runtime = MobileNotificationRuntime(context, { state }, { emptyList() })
    }
    private fun event() = runtime.readLedger().scheduled.values.single()

    @Test fun unchangedRefreshDoesNotDuplicateAndDeletedTaskCancelsAlarm() {
        runtime.refresh(now = now)
        val first = event()
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        val count = alarms.scheduledAlarms.size
        runtime.refresh(now = now.plusSeconds(10))
        assertEquals(first, event())
        assertEquals(count, alarms.scheduledAlarms.size)
        state = state.copy(items = emptyMap())
        runtime.refresh(now = now.plusSeconds(20))
        assertTrue(runtime.readLedger().scheduled.isEmpty())
        assertEquals(count - 1, alarms.scheduledAlarms.size)
    }

    @Test fun fireIsDurablePrivateDeepLinkedAndDoesNotMutateTask() {
        runtime.refresh(now = now)
        val event = event()
        runtime.handle(MobileNotificationRuntime.ACTION_FIRE, event.token, Instant.ofEpochMilli(event.atMillis))
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals(1, notifications.size)
        val notification = notifications.single()
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("虚构私密任务"))
        assertEquals("fictional", shadowOf(notification.contentIntent).savedIntent.getStringExtra(MobileNotificationRuntime.TASK_ID_EXTRA))
        assertEquals(2, notification.actions.size)
        val restarted = MobileNotificationRuntime(context, { state }, { emptyList() })
        restarted.handle(MobileNotificationRuntime.ACTION_FIRE, event.token, Instant.ofEpochMilli(event.atMillis).plusSeconds(1))
        restarted.refresh(now = now.plusSeconds(1))
        assertTrue(restarted.readLedger().scheduled.isEmpty())
        assertEquals(1, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
        assertFalse(state.items.getValue("task:fictional").completed())
    }

    @Test fun snoozeSurvivesRestartThenStopsWhenTaskIsCompleted() {
        runtime.refresh(now = now)
        val event = event()
        val firedAt = Instant.ofEpochMilli(event.atMillis)
        runtime.handle(MobileNotificationRuntime.ACTION_FIRE, event.token, firedAt)
        runtime.handle(MobileNotificationRuntime.ACTION_SNOOZE, event.token, firedAt.plusSeconds(3))
        val snooze = event()
        assertEquals(firedAt.plusSeconds(603).toEpochMilli(), snooze.atMillis)
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        val restarted = MobileNotificationRuntime(context, { state }, { emptyList() })
        restarted.refresh(now = firedAt.plusSeconds(4), force = true)
        assertEquals(snooze, restarted.readLedger().scheduled.values.single())
        val task = state.items.getValue("task:fictional")
        state = state.copy(items = mapOf(task.key to task.copy(payload = JsonObject(task.payload!! + ("content" to JsonObject(task.content()!! + ("completed" to JsonPrimitive(true))))))))
        restarted.handle(MobileNotificationRuntime.ACTION_FIRE, snooze.token, Instant.ofEpochMilli(snooze.atMillis))
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        assertTrue(restarted.readLedger().scheduled.isEmpty())
    }

    @Test fun deniedNotificationsDoNotArmAndDismissCannotCreateAnotherDelivery() {
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runtime.refresh(now = now)
        assertTrue(runtime.readLedger().scheduled.isEmpty())
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runtime.refresh(now = now)
        val event = event()
        runtime.handle(MobileNotificationRuntime.ACTION_FIRE, event.token, Instant.ofEpochMilli(event.atMillis))
        runtime.handle(MobileNotificationRuntime.ACTION_DISMISS, event.token, Instant.ofEpochMilli(event.atMillis).plusSeconds(1))
        runtime.refresh(now = now)
        assertTrue(runtime.readLedger().scheduled.isEmpty())
        assertTrue(runtime.readLedger().visible.isEmpty())
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test fun exactPermissionFallsBackAndForcedRestoreRebuildsOnlyCurrentSchedules() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        runtime.refresh(now = now)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        fun reminderAlarm() = alarms.scheduledAlarms.single { shadowOf(it.operation).savedIntent.action == MobileNotificationRuntime.ACTION_FIRE }
        assertNotEquals(0L, reminderAlarm().windowLengthMs)
        assertTrue(reminderAlarm().isAllowWhileIdle)
        assertFalse(runtime.exactAllowed())
        val event = event()
        // Reboot/package replacement loses OS registrations, while our durable intent remains.
        context.getSystemService(AlarmManager::class.java).cancelAll()
        runtime.refresh(now = now.plusSeconds(30), force = true)
        assertEquals(event.atMillis, reminderAlarm().triggerAtMs)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        runtime.refresh(now = now.plusSeconds(40))
        assertEquals(0L, reminderAlarm().windowLengthMs)
        assertEquals(event.token, event().token)
    }

    @Test fun removedTaskAlsoClearsAlreadyVisibleNotification() {
        runtime.refresh(now = now)
        val event = event()
        runtime.handle(MobileNotificationRuntime.ACTION_FIRE, event.token, Instant.ofEpochMilli(event.atMillis))
        assertEquals(1, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
        state = state.copy(items = emptyMap())
        runtime.refresh(now = now.plusSeconds(4000))
        assertTrue(runtime.readLedger().visible.isEmpty())
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test fun failedReceiverReadMakesNextRefreshReinstallConsumedAlarm() {
        runtime.refresh(now = now)
        val scheduled = event()
        context.getSystemService(AlarmManager::class.java).cancelAll()
        runtime.recordReceiverFailure()
        assertNotNull(runtime.lastError())
        runtime.refresh(now = now.plusSeconds(10))
        val restored = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.filter {
            shadowOf(it.operation).savedIntent.action == MobileNotificationRuntime.ACTION_FIRE
        }
        assertEquals(1, restored.size)
        assertEquals(scheduled.atMillis, restored.single().triggerAtMs)
        assertNull(runtime.lastError())
    }

    @Test fun damagedExecutionLedgerDoesNotDisableUnrelatedTaskReminders() {
        val runtime = MobileNotificationRuntime(context, { state }, { throw IllegalArgumentException("fictional corrupt execution") })
        runtime.refresh(now = now)
        assertEquals(1, runtime.readLedger().scheduled.size)
        assertNotNull(runtime.lastError())
    }
}
