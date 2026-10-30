package com.pare.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncRequestLoopTest {
    @Test fun slowFailureDoesNotDrainQueuedPollsIntoAnImmediateRetry()=runTest {
        var attempts=0
        val requests=SyncRequestLoop(backgroundScope,SyncRetryPolicy {testScheduler.currentTime}) {
            attempts++;delay(35_000);SyncOutcome.RETRY
        }
        requests.request();runCurrent();assertEquals(1,attempts)
        advanceTimeBy(15_000);requests.request();runCurrent()
        advanceTimeBy(15_000);requests.request();runCurrent()
        advanceTimeBy(5_000);runCurrent()
        assertEquals(1,attempts)
        // No active screen means no more requests. Durable WorkManager owns background retries.
        advanceTimeBy(120_000);runCurrent();assertEquals(1,attempts)
        requests.request();runCurrent();assertEquals(2,attempts)
    }
    @Test fun manualAndConnectivitySignalsCannotCreateAnUnboundedFailureLoop()=runTest {
        val manualFlags=mutableListOf<Boolean>()
        val requests=SyncRequestLoop(backgroundScope,SyncRetryPolicy {testScheduler.currentTime}) {manual ->
            manualFlags.add(manual);delay(1);SyncOutcome.RETRY
        }
        requests.request();runCurrent();advanceTimeBy(1);runCurrent()
        requests.request(networkReturned=true);runCurrent()
        repeat(20) {requests.request(manual=true);requests.request(networkReturned=true)}
        advanceTimeBy(1);runCurrent()
        assertEquals(listOf(false,false),manualFlags)
        repeat(20) {requests.request(manual=true);runCurrent()}
        assertEquals(2,manualFlags.size)
        advanceTimeBy(60_000);requests.request(manual=true);runCurrent()
        assertEquals(listOf(false,false,true),manualFlags)
    }
    @Test fun successfulRunCoalescesEditsAndPreservesExplicitPreviewRequest()=runTest {
        val manualFlags=mutableListOf<Boolean>()
        val requests=SyncRequestLoop(backgroundScope,SyncRetryPolicy {testScheduler.currentTime}) {manual ->
            manualFlags.add(manual);delay(100);SyncOutcome.DONE
        }
        requests.request();runCurrent()
        repeat(20) {requests.request()}
        requests.request(manual=true)
        advanceTimeBy(100);runCurrent()
        assertEquals(listOf(false,true),manualFlags)
        advanceTimeBy(100);runCurrent()
        assertEquals(2,manualFlags.size)
    }
}
