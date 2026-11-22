package com.pare.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class SyncScheduler(private val manager:WorkManager):SyncSchedule {
    companion object {
        const val PERIODIC="pare.sync.periodic"
        const val PENDING="pare.sync.pending"
        private fun connected()=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun periodicRequest()=PeriodicWorkRequestBuilder<PareSyncWorker>(15,TimeUnit.MINUTES)
            .setConstraints(connected()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        fun pendingRequest()=OneTimeWorkRequestBuilder<PareSyncWorker>()
            .setConstraints(connected()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
    }
    override fun schedule(hasPending:Boolean) {
        manager.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,periodicRequest())
        if(hasPending) manager.enqueueUniqueWork(PENDING,ExistingWorkPolicy.KEEP,pendingRequest())
    }
    override fun cancel() {
        manager.cancelUniqueWork(PERIODIC)
        manager.cancelUniqueWork(PENDING)
    }
}

class PareSyncWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        return try {
            val engine=SyncRuntime.get(applicationContext).engine
            engine.initialize()
            when(engine.sync()) {
                SyncOutcome.RETRY -> if(runAttemptCount<5) Result.retry() else Result.failure()
                SyncOutcome.BLOCKED -> Result.failure()
                SyncOutcome.DONE,SyncOutcome.SKIPPED -> Result.success()
            }
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {Result.failure()}
    }
}
