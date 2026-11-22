package com.pare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class SyncSchedulerTest {
    @Test fun jobsUseConnectedNetworkMinimumPeriodicIntervalAndExponentialBackoff() {
        val periodic=SyncScheduler.periodicRequest().workSpec
        val pending=SyncScheduler.pendingRequest().workSpec
        assertEquals(NetworkType.CONNECTED,periodic.constraints.requiredNetworkType)
        assertEquals(NetworkType.CONNECTED,pending.constraints.requiredNetworkType)
        assertEquals(15*60*1000L,periodic.intervalDuration)
        assertEquals(BackoffPolicy.EXPONENTIAL,pending.backoffPolicy)
        assertEquals(30_000L,pending.backoffDelayDuration)
    }
    @Test fun repeatedSchedulingKeepsOneOfEachJobAndPauseCancelsBoth() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(java.util.concurrent.Executor {it.run()}).build())
        val manager=WorkManager.getInstance(context)
        val scheduler=SyncScheduler(manager)
        scheduler.schedule(true);scheduler.schedule(true)
        assertEquals(1,manager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC).get().count {!it.state.isFinished})
        assertEquals(1,manager.getWorkInfosForUniqueWork(SyncScheduler.PENDING).get().count {!it.state.isFinished})
        scheduler.cancel()
        assertTrue(manager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC).get().all {it.state==WorkInfo.State.CANCELLED})
        assertTrue(manager.getWorkInfosForUniqueWork(SyncScheduler.PENDING).get().all {it.state==WorkInfo.State.CANCELLED})
    }
}
