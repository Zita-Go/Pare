package com.pare.app

import android.content.Context
import android.os.SystemClock
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

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
    private val presencePreferences=DevicePresenceSharingStore(context)
    val presenceSharingEnabled=MutableStateFlow(false)
    val presenceSharingError=MutableStateFlow<String?>(null)
    private val sharingWrites=Mutex()
    private val sharingTicket=AtomicLong(0)
    @Volatile private var pendingPresenceChoice:Triple<String?,String,Boolean>?=null
    private fun sharingAllowed():Boolean {
        val current=engine.state.value
        val pending=pendingPresenceChoice
        if(pending?.first==current.owner && pending?.second==current.device && pending?.third==false)return false
        return runCatching {presencePreferences.enabled(current.owner,current.device)}.getOrDefault(false)
    }
    val presence=DevicePresenceController(engine,DevicePresenceFileSequenceStore(context),AndroidExecutionClock(context),scope,::sharingAllowed)
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
            launch {engine.state.collect {refreshLocal();presenceSharingEnabled.value=sharingAllowed();presence.refreshFreshness()}}
            launch {engine.session.collect {presence.refreshFreshness()}}
            launch {execution.state.drop(1).collect {refreshReminders();refreshPresence()}}
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
    fun refreshPresence() {scope.launch {if(executionReady.value)presence.poll(execution.state.value)}}
    fun refreshPresenceManually() {scope.launch {
        if(sharingAllowed()) {if(executionReady.value)presence.poll(execution.state.value)}
        else if(presence.state.value.status.contains("暂未确认清空"))presence.withdraw()
    }}
    fun setPresenceSharing(enabled:Boolean) {
        val current=engine.state.value
        val ticket=sharingTicket.incrementAndGet()
        pendingPresenceChoice=Triple(current.owner,current.device,enabled)
        // Revocation takes effect before a background disk write or queued HTTP request.
        if(!enabled) {presenceSharingEnabled.value=false;presence.clear()}
        scope.launch {sharingWrites.withLock {
            if(ticket!=sharingTicket.get())return@withLock
            try {
                val owner=requireNotNull(current.owner) {"请先登录账号，再选择共享运行状态"}
                check(engine.state.value.owner==owner && engine.state.value.device==current.device) {"账号已改变，请重新选择"}
                presencePreferences.set(owner,current.device,enabled)
                if(ticket==sharingTicket.get()) {
                    pendingPresenceChoice=null
                    presenceSharingEnabled.value=sharingAllowed();presenceSharingError.value=null
                    presence.onSharingChanged()
                    if(enabled)refreshPresence()
                }
            } catch(e:Exception) {if(ticket==sharingTicket.get()) {
                pendingPresenceChoice=null
                presenceSharingEnabled.value=sharingAllowed();presence.clear()
                presenceSharingError.value=e.message ?: "共享选择未保存，请重试"
            }}
        }}
    }
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
    override suspend fun presence(session:Session,request:DevicePresenceRequest)=io {api.presence(session,request)}
}
