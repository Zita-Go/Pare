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
    private val presenceStore=DesktopPresenceStore(home)
    private val sharing=MutableStateFlow(false)
    val presenceSharingEnabled:StateFlow<Boolean> = sharing
    private var sharingIdentity:Pair<String?,String>?=null
    private fun sharingAllowed():Boolean = !closed && sharing.value && sharingIdentity==Pair(engine.state.value.owner,engine.state.value.device)
    val presence=DevicePresenceController(engine,presenceStore,clock,scope,::sharingAllowed,"Windows")
    private val ready=MutableStateFlow(false)
    val executionReady:StateFlow<Boolean> = ready
    val error=MutableStateFlow<String?>(null)
    private val settings=MutableStateFlow(DesktopAISettings())
    val aiSettings:StateFlow<DesktopAISettings> = settings
    private val history=MutableStateFlow<List<MobileAITrace>>(emptyList())
    val aiHistory:StateFlow<List<MobileAITrace>> = history
    private val context=MutableStateFlow(DesktopAIContext())
    val aiContext:StateFlow<DesktopAIContext> = context
    private val requests=SyncRequestLoop(scope,SyncRetryPolicy {System.nanoTime()/1_000_000}) {manual->engine.sync(manual)}
    private val native=DesktopWindows(home)
    val systemSupported:Boolean get()=native.supported
    internal val notificationScheme:String get()=native.scheme
    private val systemFile=DesktopAtomicFile(home.resolve("system-settings.json"),32*1024)
    private val systemMutable=MutableStateFlow(DesktopSystemSettings())
    val systemSettings:StateFlow<DesktopSystemSettings> = systemMutable
    private val systemLock=Mutex()
    private var scheduledSignature:String?=null
    private val attention=DesktopAttentionPolicy()
    @Volatile private var attentionPermitted=false
    @Volatile private var browserAttentionPermitted=false
    private val lastMeaningfulAction=java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
    private fun recordMeaningfulAction() {lastMeaningfulAction.set(System.currentTimeMillis());attention.clearSignals()}
    private val updates=DesktopUpdates(home)
    val updateState:StateFlow<DesktopUpdateState> = updates.state
    val requestedInstaller=MutableStateFlow<Path?>(null)
    val requestedTask=MutableStateFlow<String?>(null)
    private val nativeDeliveries=java.util.concurrent.ConcurrentLinkedQueue<Job>()
    private val signalLock=Any()
    private val reminders=DesktopReminders(home,actionable={notice->
        if(!closed) {
        val delivery=scope.launch(start=CoroutineStart.LAZY) {
            if(systemMutable.value.systemNotifications && systemSupported) {
                try {runInterruptible {native.show(notice)}}
                catch(e:CancellationException) {throw e}
                catch(_:Exception) {error.value="系统通知未发送，已保留应用内提醒";notify(notice.title,notice.body)}
            } else notify(notice.title,notice.body)
        }
        nativeDeliveries.add(delivery);delivery.invokeOnCompletion {nativeDeliveries.remove(delivery)};delivery.start()
        }
    }) {title,body->if(!closed)notify(title,body)}

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
        try {
            val settings=systemFile.read()?.let {wire.decodeFromString<DesktopSystemSettings>(it.toString(Charsets.UTF_8))} ?: DesktopSystemSettings()
            check(!settings.afterExitReminders || settings.systemNotifications)
            check(!settings.attentionBrowserEnabled || settings.attentionEnabled)
            systemMutable.value=settings
            attentionPermitted=settings.attentionEnabled;browserAttentionPermitted=settings.attentionBrowserEnabled
            if(systemSupported && (settings.startAtLogin || settings.systemNotifications || settings.afterExitReminders))runInterruptible {native.configure(settings)}
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {error.value="Windows 系统设置未能恢复，请检查设置后重新保存"}
        refreshPresenceChoice()
        scope.launch {engine.state.drop(1).collect {
            refreshPresenceChoice();presence.refreshFreshness()
            if(ready.value) refreshLocal()
            requestAutomaticSync()
        }}
        scope.launch {
            while(isActive) {
                if(ready.value) refreshLocal()
                checkAttention()
                delay(5_000)
            }
        }
        scope.launch {engine.session.collect {presence.refreshFreshness()}}
        scope.launch {while(isActive) {
            processDesktopSignals()
            delay(1_000)
        }}
        scope.launch {while(isActive) {
            if(ready.value)presence.poll(execution.state.value)
            delay(15_000)
        }}
        scope.launch {
            while(isActive) {
                requestAutomaticSync()
                if(systemMutable.value.autoCheckUpdates) {
                    try {systemLock.withLock {runInterruptible {updates.checkIfDue()}}}
                    catch(e:CancellationException) {throw e}
                    catch(_:Exception) { /* Update state reports failures; persisted backoff bounds checks. */ }
                }
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
            scope.launch {syncSystemSchedules()}
        } catch(_:Exception) {error.value="本机推进状态或提醒未能更新，原记录已保留"}
    }
    private suspend fun ownedResult(action:suspend ()->Unit):Result<Unit> = coroutineScope {
        val request=scope.async {
            try {check(!closed);action();Result.success(Unit)}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {error.value=e.message ?: "操作未完成";Result.failure(e)}
        }
        try {request.await()} finally {request.cancel()}
    }
    suspend fun saveSystemSettings(settings:DesktopSystemSettings):Result<Unit> {
        // A revocation stops local sampling before waiting on the native-settings mutex or disk.
        if(!settings.attentionEnabled)attentionPermitted=false
        if(!settings.attentionEnabled || !settings.attentionBrowserEnabled) {browserAttentionPermitted=false;native.clearBrowserIdentity();attention.clearSignals()}
        return ownedResult {systemLock.withLock {
        require(!settings.afterExitReminders || settings.systemNotifications) {"退出后提醒需要同时开启系统通知"}
        require(!settings.attentionBrowserEnabled || settings.attentionEnabled) {"浏览器增强需要先开启本机注意力提示"}
        check(systemSupported || !(settings.startAtLogin || settings.systemNotifications || settings.afterExitReminders || settings.attentionEnabled)) {"这些系统功能需要 Windows 安装包"}
        val old=systemMutable.value
        try {
            if(systemSupported)runInterruptible {native.configure(settings)}
            systemFile.write(wire.encodeToString(settings).toByteArray())
            systemMutable.value=settings;scheduledSignature=null
            attentionPermitted=settings.attentionEnabled;browserAttentionPermitted=settings.attentionBrowserEnabled
            if(old.attentionEnabled!=settings.attentionEnabled || old.attentionBrowserEnabled!=settings.attentionBrowserEnabled)recordMeaningfulAction()
        } catch(e:Exception) {
            if(systemSupported)withContext(NonCancellable) {runCatching {native.configure(old)}}
            throw e
        }
    };syncSystemSchedules()}
    }
    suspend fun snoozeAttention(minutes:Int=60):Result<Unit> {
        require(minutes in 1..10080)
        val settings=systemMutable.value.copy(attentionSnoozedUntil=System.currentTimeMillis()+minutes*60_000L)
        return saveSystemSettings(settings)
    }
    private fun checkAttention() {
        val settings=systemMutable.value
        if(closed || !attentionPermitted || !settings.attentionEnabled || System.currentTimeMillis()<settings.attentionSnoozedUntil)return
        try {
            native.sample(browserAttentionPermitted)?.let {sample->
                if(!attentionPermitted)return
                val actionable=engine.state.value.items.values.any {it.kind=="task" && !it.deleted && !it.completed() && it.conflict==null && it.payload?.get("lifecycle")!=JsonPrimitive("archived")}
                attention.observe(System.currentTimeMillis(),sample.idleMillis,sample.foreground,execution.state.value.focus?.paused==false,
                    sample.browserToken.takeIf {browserAttentionPermitted},actionable,lastMeaningfulAction.get())?.let {notify("温和提醒",it)}
            }
        } catch(_:Exception) {error.value="本机注意力信号暂不可用，未记录应用或浏览内容"}
    }
    suspend fun checkForUpdates():Result<Unit> = ownedResult {systemLock.withLock {runInterruptible {updates.check()}}}
    suspend fun downloadUpdate():Result<Unit> = ownedResult {systemLock.withLock {runInterruptible {updates.download()}}}
    suspend fun requestUpdateInstall():Result<Unit> = ownedResult {systemLock.withLock {
        check(systemSupported) {"请在 Windows 安装包内启动更新"}
        requestedInstaller.value=updates.installer()
    }}
    internal fun verifiedInstaller():Path=updates.installer()
    private suspend fun syncSystemSchedules() {
        if(closed || !systemMutable.value.afterExitReminders || !systemSupported)return
        systemLock.withLock {
            if(closed || !systemMutable.value.afterExitReminders)return@withLock
            // One next wake-up drains every due event and arms its successor, including recurring
            // reminders. This avoids leaving hundreds of persistent Windows tasks behind.
            val list=reminders.scheduled().filter {it.atMillis>System.currentTimeMillis()}.sortedBy {it.atMillis}.take(1)
            val signature=list.toString()
            if(scheduledSignature==signature)return@withLock
            try {runInterruptible {native.schedule(list)};scheduledSignature=signature}
            catch(e:CancellationException) {throw e}
            catch(_:Exception) {error.value="退出后系统提醒安排失败；请保持 Step 运行并重新保存通知设置"}
        }
    }
    internal suspend fun processDesktopSignals() {
        if(!engine.initialized.value || !ready.value || closed)return
        withContext(Dispatchers.IO) {
            try {synchronized(signalLock) {DesktopSignals.drain(home) {signal->
                if(signal.action=="deliver") {refreshLocal();refreshLocal()}
                else synchronized(localLifecycle) {
                    refreshLocal()
                    val event=reminders.action(signal.token,engine.state.value,clock.now())
                    when(signal.action) {
                        "open"->requestedTask.value=event.taskId
                        "snooze"->reminders.consume(event,clock.now().wallMillis+15*60_000)
                        "complete"->{
                            if(event.kind in setOf("focus","check"))execution.finish(event.taskId)
                            else {
                                if(execution.state.value.runFor(event.taskId)!=null)execution.finish(event.taskId)
                                engine.edit {CompletionActions.set(it,event.taskId,true,Instant.ofEpochMilli(clock.now().wallMillis))}
                            }
                            reminders.consume(event);requestAutomaticSync()
                        }
                    }
                    refreshLocal()
                }
            }}} catch(_:Exception) {error.value="这条提醒已更新或操作未保存，请打开任务查看最新内容"}
        }
        syncSystemSchedules()
    }
    internal suspend fun finishSystemDelivery() {
        processDesktopSignals();refreshLocal();refreshLocal();syncSystemSchedules()
        // Await short native delivery calls before the headless invocation closes its runtime.
        withTimeoutOrNull(35_000) {nativeDeliveries.toList().forEach {it.join()}}
    }
    private fun refreshPresenceChoice() {
        val current=engine.state.value
        val identity=Pair(current.owner,current.device)
        if(sharingIdentity==identity)return
        sharing.value=false;sharingIdentity=identity;presence.clear()
        try {sharing.value=presenceStore.enabled(current.owner,current.device)}
        catch(_:Exception) {error.value="共享设置无法读取，本机已停止共享"}
    }
    suspend fun setPresenceSharing(enabled:Boolean):Result<Unit> {
        if(!enabled) {sharing.value=false;presence.clear()}
        val result=localOperation {
            val current=engine.state.value
            val owner=requireNotNull(current.owner) {"请先登录并确认账号同步"}
            try {
                presenceStore.set(owner,current.device,enabled)
                sharingIdentity=Pair(owner,current.device);sharing.value=enabled
                presence.onSharingChanged()
            } catch(e:Exception) {sharing.value=false;presence.clear();throw e}
        }
        if(result.isSuccess && enabled)refreshPresence()
        return result
    }
    fun refreshPresence() {if(!closed)scope.launch {
        if(sharingAllowed() && ready.value)presence.poll(execution.state.value)
        else presence.withdraw()
    }}
    suspend fun edit(change:(SyncState)->SyncState):Result<Unit> = localOperation {
        engine.edit(change)
        recordMeaningfulAction()
        if(ready.value) refreshLocal()
        requestAutomaticSync()
    }
    suspend fun execute(command:(ExecutionController)->Unit):Result<Unit> = localOperation {
        check(ready.value) {"本机推进状态尚未准备好"}
        command(execution);recordMeaningfulAction();refreshLocal();requestAutomaticSync()
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
        recordMeaningfulAction()
        refreshLocal();requestAutomaticSync()
    }
    suspend fun archiveTask(id:String,expected:LibraryBaseline):Result<Unit> = lifecycle("task",id,expected) {s,baseline->LibraryActions.archive(s,id,baseline)}
    suspend fun deleteContent(kind:String,id:String,expected:LibraryBaseline):Result<Unit> = lifecycle(kind,id,expected) {s,baseline->LibraryActions.delete(s,kind,id,baseline,Instant.ofEpochMilli(clock.now().wallMillis))}
    private suspend fun lifecycle(kind:String,id:String,expected:LibraryBaseline,change:(SyncState,LibraryBaseline)->SyncState):Result<Unit> = localOperation {
        check(ready.value) {"本机推进记录尚未准备好，不能归档或删除"}
        change(engine.state.value,expected) // Validate the user's confirmed baseline before ending any run.
        val run=if(kind=="task")execution.state.value.runFor(id) else null
        val focus=run!=null && execution.state.value.focus?.id==run.id
        if(run!=null)execution.finish(id)
        engine.edit {current->
            val item=requireNotNull(current.items["$kind:$id"])
            var permitted=expected.payload
            if(run!=null) {
                val field=if(focus)"progressSessions"else"parallelRunSessions"
                val oldContent=requireNotNull(expected.payload?.get("content") as? JsonObject)
                val oldHistory=(oldContent[field] as? JsonArray).orEmpty()
                if(oldHistory.none {(it as? JsonObject)?.get("id")==JsonPrimitive(run.id)}) {
                    val entry=(item.content()?.get(field) as? JsonArray)?.firstOrNull {(it as? JsonObject)?.get("id")==JsonPrimitive(run.id)}?.jsonObject
                        ?: error("推进历史尚未保存，未删除或归档")
                    val content=oldContent.toMutableMap()
                    content[field]=JsonArray(oldHistory+entry)
                    if(focus)content["actualMinutes"]=JsonPrimitive(((oldContent["actualMinutes"]?.jsonPrimitive?.intOrNull ?: 0).toLong()+entry.getValue("minutes").jsonPrimitive.int).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    val ended=Instant.parse(entry.getValue("endedAt").jsonPrimitive.content)
                    val oldProgress=oldContent["lastProgressAt"]?.jsonPrimitive?.contentOrNull?.let {runCatching {Instant.parse(it)}.getOrNull()}
                    if(oldProgress==null || !oldProgress.isAfter(ended))content["lastProgressAt"]=JsonPrimitive(ended.toString())
                    permitted=JsonObject(expected.payload!!.toMutableMap().apply {put("content",JsonObject(content))})
                }
            }
            check(item.payload==permitted) {"确认后内容已改变，请重新查看；推进历史已保留"}
            change(current,LibraryBaseline.from(item))
        }
        refreshLocal();requestAutomaticSync()
    }
    private val backupPreviews=mutableMapOf<String,DesktopBackupPreview>()
    @Volatile var lastImportBackup:Path?=runCatching {DesktopBackup.latestBeforeImport(home)}.getOrNull()
        private set
    suspend fun previewBackup(path:Path):Result<DesktopBackupPreview> = withContext(Dispatchers.IO) {
        try {synchronized(localLifecycle) {
            check(!closed && engine.initialized.value) {"资料库尚未准备好"}
            Result.success(DesktopBackup.preview(path,store.read()).copy(restoresPreviousImport=DesktopBackup.isBeforeImportFile(path,home)).also {backupPreviews.clear();backupPreviews[it.id]=it})
        }} catch(e:Exception) {if(e is CancellationException)throw e;Result.failure(e)}
    }
    suspend fun applyBackup(preview:DesktopBackupPreview):Result<Unit> = localOperation {
        check(backupPreviews[preview.id]===preview) {"请先在这台设备预览备份"}
        check(ready.value && execution.state.value.focus==null && execution.state.value.external.isEmpty()) {"请先结束本机专注和等待记录，再导入备份"}
        var prepared:Path?=null
        var committed=false
        try {
            engine.edit {current->
                val next=DesktopBackup.apply(preview,current)
                store.saveRollback(current)
                prepared=DesktopBackup.prepareBeforeImport(home,current,next)
                next
            }
            committed=true
            try {lastImportBackup=DesktopBackup.publishBeforeImport(requireNotNull(prepared))}
            catch(_:Exception) {
                // The content transaction already committed. Report that outcome accurately and
                // retain the prepared content/raw rollback instead of pretending it did not save.
                error.value="内容已导入，但恢复入口未能更新；原恢复点仍可用，新的导入前内容已保留在 $prepared"
            }
        } finally {
            // A failed state commit must not supersede a successful point, even after restart.
            // If cleanup itself fails, the .prepared suffix still excludes the file from discovery.
            if(!committed)prepared?.let {runCatching {java.nio.file.Files.deleteIfExists(it)}}
        }
        backupPreviews.clear();refreshLocal();requestAutomaticSync()
    }
    suspend fun recoverAutomaticBackup():Result<Unit> {
        val restored=localOperation {
            check(!engine.initialized.value) {"当前资料正常，不需要损坏恢复"}
            store.recoverAutomaticBackup();error.value=null
        }
        if(restored.isFailure)return restored
        initialize()
        return if(engine.initialized.value)Result.success(Unit) else Result.failure(IllegalStateException(error.value ?: "恢复文件已保存，仍需检查登录状态"))
    }
    fun now():ExecutionMoment=clock.now()
    private suspend fun localOperation(action:()->Unit):Result<Unit> = withContext(Dispatchers.IO) {
        try {synchronized(localLifecycle) {check(!closed) {"应用已关闭"};action()};Result.success(Unit)}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {error.value=e.message ?: "操作未保存，请重试";Result.failure(e)}
    }
    private fun publishAI(data:DesktopAIData) {
        settings.value=DesktopAISettings(data.config,data.apiKey.isNotBlank());history.value=data.history;context.value=data.context
    }
    private suspend fun mutateAI(purgeBackup:Boolean=false,change:(DesktopAIData)->DesktopAIData):Result<Unit> = withContext(Dispatchers.IO) {aiLock.withLock {
        localOperation {check(aiReady) {"本机 AI 设置尚未准备好"};publishAI(aiVault.mutate(purgeBackup,change))}
    }}
    suspend fun deleteAIHistory(ids:Set<String>):Result<Unit> = mutateAI(true) {it.copy(history=it.history.filterNot {trace->trace.id in ids})}
    suspend fun clearAIHistory():Result<Unit> = mutateAI(true) {it.copy(history=emptyList())}
    suspend fun saveAIContext(context:DesktopAIContext):Result<Unit> = mutateAI(true) {it.copy(context=context)}
    suspend fun recordAIFeedback(feedback:DesktopAIFeedback):Result<Unit> = mutateAI {it.copy(context=it.context.copy(feedback=(it.context.feedback.filterNot {old->old.requestId==feedback.requestId}+feedback).takeLast(100)))}
    suspend fun deleteAIFeedback(id:String):Result<Unit> = mutateAI(true) {it.copy(context=it.context.copy(feedback=it.context.feedback.filterNot {record->record.id==id}))}
    suspend fun saveAI(config:MobileAIConfig,key:String?):Result<Unit> = withContext(Dispatchers.IO) {aiLock.withLock {
        localOperation {
            check(aiReady) {"本机 AI 设置尚未准备好"}
            publishAI(aiVault.update(config,key))
        }
    }}
    suspend fun generateAI(input:MobileAIRequest,validateAdditional:()->Unit={}):Result<MobileAIProposal> = generate(
        validate={MobileAIContext.requireFresh(engine.state.value,input);validateAdditional()},
        call={data,record->aiClient.generate(data.config,data.apiKey,input,record)})
    suspend fun generatePlan(input:PlanningAIRequest,validateAdditional:()->Unit={}):Result<PlanningAIProposal> = generate(
        validate={PlanningAIContext.requireFresh(engine.state.value,input);validateAdditional()},
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
        val withdrawPresence=sharingAllowed()
        closed=true
        sharing.value=false;presence.clear()
        runBlocking {job.cancelAndJoin()}
        // Best-effort withdrawal is advisory; expired reports remain visibly stale on peers.
        if(withdrawPresence)runBlocking {withTimeoutOrNull(2_000) {runCatching {presence.withdraw()}}}
        engine.revoke()
        synchronized(localLifecycle) {
            try {
                if(ready.value) {
                    execution.state.value.focus?.let {execution.pause(it.taskId)}
                    execution.state.value.external.toList().forEach {execution.pause(it.taskId)}
                    reminders.cancelPausedFocus()
                    if(systemSupported && systemMutable.value.afterExitReminders)native.schedule(reminders.scheduled().sortedBy {it.atMillis}.take(1))
                }
            }
            catch(_:Exception) {error.value="退出时推进状态未能保存；重启后将保守暂停，未确认时长不计入"}
            finally {sessionVault.close();store.close()}
        }
    }
}
