package com.pare.app

import org.junit.Assert.*
import org.junit.Test

class SyncRetryPolicyTest {
    @Test fun foregroundPollingAndQueuedEditsRespectTheDeadlineAfterFailure() {
        var now=0L
        val policy=SyncRetryPolicy {now}
        assertTrue(policy.tryStart())
        // The request itself took longer than the foreground polling interval.
        now=35_000;policy.completed(SyncOutcome.RETRY)
        assertFalse(policy.tryStart())
        now=50_000;assertFalse(policy.tryStart())
        now=64_999;assertFalse(policy.tryStart())
        now=65_000;assertTrue(policy.tryStart())
    }
    @Test fun repeatedFailuresBackOffUpToFiveMinutes() {
        var now=0L
        val policy=SyncRetryPolicy {now}
        for(delay in listOf(30_000L,60_000L,120_000L,240_000L,300_000L,300_000L)) {
            assertTrue(policy.tryStart())
            policy.completed(SyncOutcome.RETRY)
            now+=delay-1;assertFalse(policy.tryStart())
            now++;assertTrue(policy.tryStart())
        }
    }
    @Test fun manualOrRestoredNetworkCanExpediteOnceButFlappingCannotLoop() {
        var now=0L
        val policy=SyncRetryPolicy {now}
        assertTrue(policy.tryStart());policy.completed(SyncOutcome.RETRY)
        assertTrue(policy.tryStart(expedited=true));policy.completed(SyncOutcome.RETRY)
        repeat(20) {assertFalse(policy.tryStart(expedited=true))}
        now=60_000
        assertTrue(policy.tryStart());policy.completed(SyncOutcome.RETRY)
        assertTrue(policy.tryStart(expedited=true))
    }
    @Test fun successfulSyncResetsTheFailureBackoff() {
        var now=0L
        val policy=SyncRetryPolicy {now}
        assertTrue(policy.tryStart());policy.completed(SyncOutcome.RETRY)
        assertTrue(policy.tryStart(expedited=true));policy.completed(SyncOutcome.DONE)
        assertTrue(policy.tryStart());policy.completed(SyncOutcome.RETRY)
        now=30_000;assertTrue(policy.tryStart())
    }
}
