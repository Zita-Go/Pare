package com.pare.app

import android.content.Context
import android.os.SystemClock
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect

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
    val execution=ExecutionController(engine,ExecutionFilePersistence(context),AndroidExecutionClock(context))
    val executionReady=MutableStateFlow(false)
    val localError=MutableStateFlow<String?>(null)
    val reminderError=MutableStateFlow<String?>(null)
    private val reminders=MobileNotificationRuntime(context)
    private val requests=SyncRequestLoop(scope,SyncRetryPolicy(SystemClock::elapsedRealtime)) {manual ->
        if(engine.initialized.value) engine.sync(manual) else SyncOutcome.SKIPPED
    }
    init {scope.launch {
        runCatching {engine.initialize()}
        if(engine.initialized.value) {
            try {execution.initialize();executionReady.value=true}
            catch(_:Exception) {localError.value="本机推进状态未能读取，原记录已保留；请勿清除应用数据。"}
            launch {engine.state.collect {refreshLocal()}}
            launch {execution.state.drop(1).collect {refreshReminders()}}
            requestSync()
        }
    }}
    private fun refreshReminders() {
        try {reminders.refresh(engine.state.value);reminderError.value=reminders.lastError()}
        catch(_:Exception) {reminderError.value="手机提醒未能安排，请重新打开 Pare 后重试；任务仍已保存。"}
    }
    private fun refreshLocal() {
        if(!executionReady.value)try {execution.initialize();executionReady.value=true;localError.value=null}
        catch(_:Exception) {localError.value="本机推进状态未能读取，原记录已保留；请勿清除应用数据。"}
        if(executionReady.value) try {execution.reconcile()} catch(_:Exception) {localError.value="推进记录未能更新，原记录已保留，请稍后重试。"}
        refreshReminders()
    }
    fun refreshDeviceState() {scope.launch {if(engine.initialized.value)refreshLocal()}}
    fun execute(command:ExecutionCommand) {
        check(executionReady.value) {"本机推进状态尚未准备好，请稍后再试"}
        execution.execute(command);localError.value=null;refreshReminders();requestSync()
    }
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
