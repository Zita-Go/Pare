package com.step.desktop

import com.pare.app.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.time.Instant

data class DesktopConfig(val syncUrl:String="",val syncPublicKey:String="")
data class DesktopAISettings(val config:MobileAIConfig=MobileAIConfig(),val hasKey:Boolean=false)

/** Owns one process's durable state and background services; networking never runs on the UI thread. */
class DesktopRuntime internal constructor(
    val home:Path,
    val config:DesktopConfig,
    val notify:(String,String)->Unit,
    protector:DesktopSecretProtector,
    remote:SyncRemote,
    private val clock:ExecutionClock,
    private val aiClient:MobileAIClient,
    executionPersistence:ExecutionPersistence=DesktopExecutionPersistence(home)
):AutoCloseable {
    constructor(home:Path,config:DesktopConfig,notify:(String,String)->Unit={_,_->}):this(
        home,config,notify,DesktopDPAPI(),HttpSyncRemote(SupabaseApi(config.syncUrl,config.syncPublicKey)),DesktopClock(),MobileAIClient())

    private val job=SupervisorJob()
    private val scope=CoroutineScope(job+Dispatchers.IO)
    private val store=DesktopStore(home)
    private val sessionVault=DesktopSessionVault(home,protector)
    private val aiVault=DesktopAIVault(home,protector)
    private val initialization=Mutex()
    private val aiLock=Mutex()
    private val localLifecycle=Any()
    private var aiReady=false
    @Volatile private var closed=false
    @Volatile private var scheduled=false
    private val schedule=object:SyncSchedule {
        override fun schedule(hasPending:Boolean) {
            val newlyEnabled=!scheduled
            scheduled=true
            if(newlyEnabled) scope.launch {requestAutomaticSync()}
        }
        override fun cancel() {scheduled=false}
    }
    val engine=SyncEngine(store,sessionVault,remote,schedule)
    val execution=ExecutionController(engine,executionPersistence,clock)
    private val ready=MutableStateFlow(false)
    val executionReady:StateFlow<Boolean> = ready
    val error=MutableStateFlow<String?>(null)
    private val settings=MutableStateFlow(DesktopAISettings())
    val aiSettings:StateFlow<DesktopAISettings> = settings
    private val history=MutableStateFlow<List<MobileAITrace>>(emptyList())
    val aiHistory:StateFlow<List<MobileAITrace>> = history
    private val requests=SyncRequestLoop(scope,SyncRetryPolicy {System.nanoTime()/1_000_000}) {manual->engine.sync(manual)}
    private val reminders=DesktopReminders(home) {title,body->if(!closed) notify(title,body)}

    suspend fun initialize():Unit=coroutineScope {
        // Recovery can perform blocking disk/DPAPI work. Own it before starting so close joins
        // every recovery write before releasing the single-instance lock, even if its caller exits.
        val startup=scope.async {initialization.withLock {
        if(closed || engine.initialized.value)return@withLock
        try {engine.initialize()}
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {error.value="本机资料或登录状态读取失败，原文件和备份已保留；请勿清空资料目录";return@withLock}
        currentCoroutineContext().ensureActive()
        try {execution.initialize();ready.value=true}
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {error.value="本机推进记录读取失败，原文件已保留；请勿清空资料目录"}
        currentCoroutineContext().ensureActive()
        try {publishAI(aiVault.load());aiReady=true}
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {error.value="本机 AI 设置读取失败，原文件已保留"}
        currentCoroutineContext().ensureActive()
        scope.launch {engine.state.drop(1).collect {
            if(ready.value) refreshLocal()
            requestAutomaticSync()
        }}
        scope.launch {
            while(isActive) {
                if(ready.value) refreshLocal()
                delay(5_000)
            }
        }
        scope.launch {
            while(isActive) {
                requestAutomaticSync()
                delay(30_000)
            }
        }
        }}
        try {startup.await();Unit} finally {startup.cancel()}
    }
    private fun refreshLocal()=synchronized(localLifecycle) {
        if(closed)return@synchronized
        try {
            execution.reconcile()
            reminders.refresh(engine.state.value,execution.state.value,clock.now())
        } catch(_:Exception) {error.value="本机推进状态或提醒未能更新，原记录已保留"}
    }
    suspend fun edit(change:(SyncState)->SyncState):Result<Unit> = localOperation {
        engine.edit(change)
        if(ready.value) refreshLocal()
        requestAutomaticSync()
    }
    suspend fun execute(command:(ExecutionController)->Unit):Result<Unit> = localOperation {
        check(ready.value) {"本机推进状态尚未准备好"}
        command(execution);refreshLocal();requestAutomaticSync()
    }
    /** Persist local effort before completion makes the task ineligible for execution reconciliation. */
    suspend fun setTaskCompleted(taskId:String,completed:Boolean):Result<Unit> = localOperation {
        check(ready.value) {"本机推进状态尚未准备好，暂不能更改任务完成状态"}
        val before=engine.state.value
        val timestamp=Instant.ofEpochMilli(clock.now().wallMillis)
        // Validate lifecycle/conflicts before touching the ledger, and make repeated desired values no-ops.
        if(CompletionActions.set(before,taskId,completed,timestamp)==before)return@localOperation
        if(completed && execution.state.value.runFor(taskId)!=null) execution.finish(taskId)
        // Read the latest content after finish has durably appended its history. A failed finish
        // exits before this write, retaining an incomplete task and its recoverable local run.
        engine.edit {CompletionActions.set(it,taskId,completed,Instant.ofEpochMilli(clock.now().wallMillis))}
        refreshLocal();requestAutomaticSync()
    }
    fun now():ExecutionMoment=clock.now()
    private suspend fun localOperation(action:()->Unit):Result<Unit> = withContext(Dispatchers.IO) {
        try {synchronized(localLifecycle) {check(!closed) {"应用已关闭"};action()};Result.success(Unit)}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {error.value=e.message ?: "操作未保存，请重试";Result.failure(e)}
    }
    private fun publishAI(data:DesktopAIData) {
        settings.value=DesktopAISettings(data.config,data.apiKey.isNotBlank());history.value=data.history
    }
    suspend fun saveAI(config:MobileAIConfig,key:String?):Result<Unit> = withContext(Dispatchers.IO) {aiLock.withLock {
        localOperation {
            check(aiReady) {"本机 AI 设置尚未准备好"}
            publishAI(aiVault.update(config,key))
        }
    }}
    suspend fun generateAI(input:MobileAIRequest):Result<MobileAIProposal> = generate(
        validate={MobileAIContext.requireFresh(engine.state.value,input)},
        call={data,record->aiClient.generate(data.config,data.apiKey,input,record)})
    suspend fun generatePlan(input:PlanningAIRequest):Result<PlanningAIProposal> = generate(
        validate={PlanningAIContext.requireFresh(engine.state.value,input)},
        call={data,record->aiClient.generate(data.config,data.apiKey,input,record)})

    /** No background AI, retries or automatic adoption. The input is the user's reviewed consent snapshot. */
    private suspend fun <T> generate(validate:()->Unit,call:(DesktopAIData,(MobileAITrace)->Unit)->T):Result<T> = coroutineScope {
        val request=scope.async {aiLock.withLock {
        var trace:MobileAITrace?=null
        try {
            check(!closed && aiReady) {"本机 AI 设置尚未准备好"}
            validate()
            val data=aiVault.load()
            check(data.apiKey.isNotBlank()) {"请先保存本机 AI 服务和密钥"}
            val proposal=runInterruptible {call(data) {trace=it.redacted(data.apiKey)}}
            Result.success(proposal)
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {error.value=e.message ?: "AI 请求未完成，请手动重试";Result.failure(e)}
        finally {trace?.let {record->
            withContext(NonCancellable+Dispatchers.IO) {
                try {publishAI(aiVault.append(record))}
                catch(_:Exception) {error.value="AI 请求已结束，但本机历史保存失败"}
            }
        }}
        }}
        try {request.await()} finally {request.cancel()}
    }
    suspend fun exportBackup(destination:Path):Result<Unit> = localOperation {
        check(engine.initialized.value) {"本机内容尚未读取完成"}
        val normalized=destination.toAbsolutePath().normalize()
        check(!normalized.startsWith(home.toAbsolutePath().normalize())) {"请将导出文件保存在 Step 资料目录之外"}
        val state=store.read()
        val content=buildJsonObject {
            put("format","step-content-backup");put("version",1)
            put("entities",JsonArray(state.items.values.map {entity->buildJsonObject {
                put("kind",entity.kind);put("id",entity.id);put("deleted",entity.deleted)
                put("payload",entity.payload ?: JsonNull)
                entity.conflict?.let {put("conflict",wire.encodeToJsonElement(it))}
            }}))
            put("localTrash",wire.encodeToJsonElement(state.localTrash))
        }
        DesktopAtomicFile(normalized).write(content.toString().toByteArray(Charsets.UTF_8))
    }
    private fun requestAutomaticSync() {if(!closed && scheduled && engine.initialized.value) requests.request()}
    fun requestSync() {if(!closed && engine.initialized.value) requests.request(manual=true)}
    override fun close() {
        if(closed)return
        closed=true
        engine.revoke()
        runBlocking {job.cancelAndJoin()}
        synchronized(localLifecycle) {
            try {
                if(ready.value) {
                    execution.state.value.focus?.let {execution.pause(it.taskId)}
                    execution.state.value.external.toList().forEach {execution.pause(it.taskId)}
                }
            }
            catch(_:Exception) {error.value="退出时推进状态未能保存；重启后将保守暂停，未确认时长不计入"}
            finally {sessionVault.close();store.close()}
        }
    }
}
