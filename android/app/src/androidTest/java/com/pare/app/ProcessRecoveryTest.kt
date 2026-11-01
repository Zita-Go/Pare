package com.pare.app

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit, two-process QA acceptance for force-stop followed by reopening, not OS reclaim or reboot.
 * Both methods skip in the normal suite before touching storage. Run the seed method with
 * `-e pareRecoveryPhase seed`, force-stop com.pare.app.qa, then run the verify method with
 * `-e pareRecoveryPhase verify`. Never run the whole class with a shared phase argument.
 * Seed deliberately leaves its fictional fixture and running focus for the separate process.
 */
@RunWith(AndroidJUnit4::class)
class ProcessRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("pare-process-recovery-qa", Context.MODE_PRIVATE)
    private val taskId = "98200000-0000-4000-8000-000000000001"
    private val allocationId = "98200000-0000-4000-8000-000000000002"
    private val title = "虚构：进程恢复观察记录"

    private fun requirePhase(phase: String) {
        assumeTrue("Manual force-stop/reopen acceptance requires pareRecoveryPhase=$phase",
            InstrumentationRegistry.getArguments().getString("pareRecoveryPhase") == phase)
        check(context.packageName == "com.pare.app.qa") { "Process recovery acceptance must never access production data" }
    }

    private fun read() = PareRepository(context).use { it.read() }
    private fun visible(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun seedOfflineFocus() {
        requirePhase("seed")
        check(context.getSharedPreferences("session-vault", Context.MODE_PRIVATE).edit().clear().commit())
        check(preferences.edit().clear().commit())
        val blank = PareRepository(context).use { it.update { SyncState() } }
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
        val runtime = SyncRuntime.get(context)
        runBlocking { runtime.engine.initialize(); runtime.engine.pause() }
        runtime.presence.clear()
        val now = Instant.now()
        val seeded = runtime.engine.edit { state ->
            val profile = Entity("profile", "profile", buildJsonObject { put("content", buildJsonObject {
                put("background", "虚构的离线验收资料"); put("planningTimeZone", "UTC")
                put("preferences", buildJsonObject { put("planningMinutes", 30); put("startingMinutes", 5); put("notes", "") })
                put("confirmedPreferences", JsonArray(emptyList()))
            }) })
            var next = SyncState(device = state.device, items = mapOf(profile.key to profile))
            next = StateMachine.edit(next, "task", taskId,
                ContentActions.save(null, "task", title, "隔离 QA 进程恢复验收", 1, taskId, now, nextAction = "记录一条虚构观察"))
            val planId = ContentActions.planId(next, now)
            next = DayPlanActions.allocate(next, taskId,
                TodayAllocationDraft(planId, "personal", 25, allocationId, DayAllocationBaseline.from(next, taskId, now)), now)
            next = TaskReminderActions.save(next, taskId, TaskReminderBaseline.from(next.items.getValue("task:$taskId")),
                now.plusSeconds(3_600), null, now)
            // Persist the real offline outbox, including its stable operation ID, before restarting.
            StateMachine.prepare(StateMachine.group(next, setOf("task:$taskId", "day_plan:$planId")))
        }
        val planId = ContentActions.planId(seeded, now)
        ActivityScenario.launch(MainActivity::class.java).use {
            visible("today_overview")
            compose.waitUntil(10_000) { runtime.executionReady.value }
            runtime.execution.reconcile()
            runtime.execute(ExecutionCommand(taskId, ExecutionAction.START_FOCUS, 5))
            val focus = requireNotNull(runtime.execution.state.value.focus)
            assertEquals(taskId, focus.taskId)
            assertEquals(5, focus.targetMinutes)
            assertFalse(focus.paused)
            assertNull(focus.pendingFinish)
            assertEquals(focus.id, ExecutionFilePersistence(context).load()?.focus?.id)
            val persisted = read()
            assertEquals(seeded, persisted)
            assertNotNull(persisted.pending)
            assertEquals(setOf("task:$taskId", "day_plan:$planId"), persisted.items.values.filter { entity -> entity.dirty }.map { entity -> entity.key }.toSet())
            assertNull(runtime.engine.session.value)
            assertNull(persisted.owner)
            assertFalse(persisted.enabled)
            assertEquals(1, ReminderPlanner.sources(persisted, emptyList(), now).count { source -> source.kind == "task" && source.taskId == taskId })
            // These preferences belong to the QA app and contain only this fictional fixture.
            check(preferences.edit()
                .putString("expected_state", wire.encodeToString(persisted))
                .putString("expected_run_id", focus.id)
                .putString("expected_plan_id", planId)
                .putString("seed_boot_id", requireNotNull(focus.anchor).bootId)
                .putLong("seed_started_at", focus.startedAtMillis)
                .putInt("seed_pid", Process.myPid())
                .putBoolean("seed_complete", true).commit())
            visible("execution_running")
            Log.i("PareRecoveryQA", "force-stop/reopen seed persisted; pid=${Process.myPid()}, run=${focus.id}")
        }
        // No teardown: the next independently invoked test must read this exact saved run.
    }

    @Test fun verifyAfterProcessRestart() {
        requirePhase("verify")
        var runtime: SyncRuntime? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            check(preferences.getBoolean("seed_complete", false)) { "Run seedOfflineFocus with the seed phase first" }
            val seeded = wire.decodeFromString<SyncState>(requireNotNull(preferences.getString("expected_state", null)))
            val runId = requireNotNull(preferences.getString("expected_run_id", null))
            val planId = requireNotNull(preferences.getString("expected_plan_id", null))
            val seedPid = preferences.getInt("seed_pid", -1)
            assertTrue(seedPid > 0)
            assertNotEquals("This acceptance must run in a different real process", seedPid, Process.myPid())
            // Read actual SQLite and atomic execution storage before any runtime initialization.
            assertEquals(seeded, read())
            val diskFocus = requireNotNull(ExecutionFilePersistence(context).load()?.focus)
            assertEquals(runId, diskFocus.id)
            assertEquals(taskId, diskFocus.taskId)
            assertEquals(preferences.getLong("seed_started_at", -1), diskFocus.startedAtMillis)
            assertEquals(5, diskFocus.targetMinutes)
            assertNull(diskFocus.pendingFinish)

            val activeRuntime = SyncRuntime.get(context)
            runtime = activeRuntime
            runBlocking { activeRuntime.engine.initialize() }
            assertNull(activeRuntime.engine.session.value)
            assertNull(activeRuntime.engine.state.value.owner)
            assertFalse(activeRuntime.engine.state.value.enabled)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            visible("today_overview")
            compose.waitUntil(10_000) { activeRuntime.executionReady.value }
            activeRuntime.execution.reconcile()
            val recovered = requireNotNull(activeRuntime.execution.state.value.focus)
            assertEquals(runId, recovered.id)
            assertEquals(taskId, recovered.taskId)
            assertEquals(5, recovered.targetMinutes)
            assertEquals(diskFocus.startedAtMillis, recovered.startedAtMillis)
            assertNull(recovered.pendingFinish)
            val bootId = requireNotNull(preferences.getString("seed_boot_id", null))
            if (bootId.startsWith("process-")) {
                // The production clock conservatively pauses when Android cannot provide BOOT_COUNT.
                assertTrue(recovered.paused)
                assertNotNull(recovered.recoveryNotice)
            } else {
                assertEquals("This is a force-stop/reopen check on the same boot", bootId, AndroidExecutionClock(context).now().bootId)
                if (!recovered.due) assertFalse(recovered.paused)
            }
            val restored = read()
            assertEquals(seeded.items.getValue("task:$taskId"), restored.items.getValue("task:$taskId"))
            assertEquals(seeded.items.getValue("day_plan:$planId"), restored.items.getValue("day_plan:$planId"))
            assertEquals(seeded.pending, restored.pending)
            assertEquals(setOf("task:$taskId", "day_plan:$planId"), restored.items.values.filter { it.dirty }.map { it.key }.toSet())
            assertEquals("个人投入 25 分钟", TodayEffort.forTask(restored, TimelineDay.fromPlanId(planId), taskId).label)
            assertEquals(seeded.items.getValue("task:$taskId").content()!!["remindAt"], restored.items.getValue("task:$taskId").content()!!["remindAt"])
            visible("execution_running")
            compose.onNodeWithTag("execution_running").assertTextContains(title, substring = true)

            activeRuntime.execute(ExecutionCommand(taskId, ExecutionAction.FINISH))
            assertNull(activeRuntime.execution.state.value.focus)
            assertNull(ExecutionFilePersistence(context).load()?.focus)
            val finished = read()
            val history = finished.items.getValue("task:$taskId").content()!!.getValue("progressSessions").jsonArray
            assertEquals(1, history.size)
            val entry = history.single().jsonObject
            assertEquals(runId, entry.timelineString("id"))
            assertEquals(5, entry.timelineInt("targetMinutes"))
            assertTrue(requireNotNull(entry.timelineInt("minutes")) in 1..5)
            assertEquals(entry["minutes"], finished.items.getValue("task:$taskId").content()!!["actualMinutes"])
            assertFalse(finished.items.getValue("task:$taskId").completed())
            repeat(3) {
                activeRuntime.execution.reconcile()
                activeRuntime.execute(ExecutionCommand(taskId, ExecutionAction.FINISH))
            }
            val reopened = read()
            assertEquals(history, reopened.items.getValue("task:$taskId").content()!!["progressSessions"])
            assertEquals(entry["minutes"], reopened.items.getValue("task:$taskId").content()!!["actualMinutes"])
            assertEquals(seeded.items.getValue("day_plan:$planId"), reopened.items.getValue("day_plan:$planId"))
            assertTrue(reopened.items.getValue("task:$taskId").dirty)
            Log.i("PareRecoveryQA", "force-stop/reopen verified; seedPid=$seedPid, verifyPid=${Process.myPid()}, one history entry for run=$runId")
        } finally {
            try { scenario?.close() }
            finally {
                runtime?.presence?.clear()
                val blank = runtime?.engine?.edit { SyncState(device = it.device) }
                    ?: PareRepository(context).use { it.update { previous -> SyncState(device = previous.device) } }
                if (runtime?.executionReady?.value == true) runtime?.execution?.reconcile()
                ExecutionFilePersistence(context).save(ExecutionSnapshot(device = blank.device))
                MobileNotificationRuntime(context).refresh(blank, force = true)
                check(context.getSharedPreferences("session-vault", Context.MODE_PRIVATE).edit().clear().commit())
                check(preferences.edit().clear().commit())
            }
        }
    }
}
