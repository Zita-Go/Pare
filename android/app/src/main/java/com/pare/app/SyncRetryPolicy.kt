package com.pare.app

/** Monotonic foreground backoff; durable background retries remain owned by WorkManager. */
class SyncRetryPolicy(private val nowMillis:()->Long) {
    private var failures=0
    private var nextAttempt=0L
    private var expeditedUsed=false

    fun tryStart(expedited:Boolean=false):Boolean {
        if(nowMillis()>=nextAttempt) {
            expeditedUsed=false
            return true
        }
        if(expedited && !expeditedUsed) {
            expeditedUsed=true
            return true
        }
        return false
    }

    fun completed(outcome:SyncOutcome) {
        if(outcome==SyncOutcome.RETRY) {
            failures=minOf(failures+1,5)
            val delay=minOf(30_000L shl (failures-1),300_000L)
            // Count from completion: a slow request must still leave a quiet retry interval.
            nextAttempt=nowMillis()+delay
        } else {
            failures=0
            nextAttempt=0L
            expeditedUsed=false
        }
    }
}
