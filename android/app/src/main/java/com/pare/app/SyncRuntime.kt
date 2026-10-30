package com.pare.app

import android.content.Context
import android.os.SystemClock
import androidx.work.WorkManager
import kotlinx.coroutines.*

/** Application lifetime owner. Activity backgrounding never cancels persisted pending work. */
class SyncRuntime private constructor(context:Context) {
    companion object {
        @Volatile private var instance:SyncRuntime?=null
        fun get(context:Context):SyncRuntime=instance ?: synchronized(this) {
            instance ?: SyncRuntime(context.applicationContext).also {instance=it}
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val repo=PareRepository(context)
    val engine=SyncEngine(repo,SessionVault(context),HttpSyncRemote(),SyncScheduler(WorkManager.getInstance(context)))
    private val requests=SyncRequestLoop(scope,SyncRetryPolicy(SystemClock::elapsedRealtime)) {manual ->
        if(engine.initialized.value) engine.sync(manual) else SyncOutcome.SKIPPED
    }
    init {scope.launch {runCatching {engine.initialize()};if(engine.initialized.value) requestSync()}}
    fun persistControl(action:suspend ()->Unit) {
        scope.launch {
            try {action()}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {engine.status.value="操作未完成，本机变更已保留"}
        }
    }
    fun requestSync(manual:Boolean=false,networkReturned:Boolean=false)=requests.request(manual,networkReturned)

}

/** Blocking HTTPS has connect/read timeouts; cancellation is checked at every engine boundary. */
private class HttpSyncRemote:SyncRemote {
    private val api=SupabaseApi()
    private suspend fun <T> io(block:()->T):T=runInterruptible(Dispatchers.IO,block)
    override suspend fun login(email:String,password:String,signup:Boolean)=io {api.login(email,password,signup)}
    override suspend fun refresh(session:Session)=io {api.refresh(session)}
    override suspend fun pull(session:Session,cursor:Long)=io {api.pull(session,cursor)}
    override suspend fun push(session:Session,request:Push)=io {api.push(session,request)}
    override suspend fun logout(session:Session)=io {api.logout(session)}
}
