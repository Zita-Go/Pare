package com.pare.app

import kotlinx.coroutines.*

/** Coalesces UI signals without allowing polls or edits queued during a failure to spin. */
class SyncRequestLoop(
    private val scope:CoroutineScope,
    private val retry:SyncRetryPolicy,
    private val synchronize:suspend (manual:Boolean)->SyncOutcome
) {
    private val lock=Any()
    private var running:Job?=null
    private var pending=false
    private var manualPending=false
    private var networkPending=false

    fun request(manual:Boolean=false,networkReturned:Boolean=false)=synchronized(lock) {
        pending=true
        manualPending=manualPending || manual
        networkPending=networkPending || networkReturned
        if(running!=null) return@synchronized
        // Assign before starting so completion on an immediate dispatcher cannot leave a stale job.
        running=scope.launch(start=CoroutineStart.LAZY) {drain()}
        running!!.start()
    }

    private suspend fun drain() {
        val ownJob=currentCoroutineContext()[Job]
        try {
            while(true) {
                val manual=synchronized(lock) {
                    val nextManual=manualPending
                    val allowed=pending && retry.tryStart(expedited=manualPending || networkPending)
                    pending=false;manualPending=false;networkPending=false
                    if(allowed) nextManual else {running=null;null}
                } ?: return
                val outcome=try {synchronize(manual)}
                catch(e:CancellationException) {throw e}
                catch(_:Exception) {SyncOutcome.RETRY}
                synchronized(lock) {retry.completed(outcome)}
            }
        } finally {
            synchronized(lock) {
                // A new request may have started after the previous loop released its slot.
                if(running===ownJob) {running=null;pending=false;manualPending=false;networkPending=false}
            }
        }
    }
}
