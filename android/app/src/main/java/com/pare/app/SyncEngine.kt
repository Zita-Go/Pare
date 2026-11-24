package com.pare.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

interface SyncStore {
    val snapshots:StateFlow<SyncState>
    fun read():SyncState
    fun update(transform:(SyncState)->SyncState):SyncState
}
interface SessionStore {
    fun load():Session?
    fun save(session:Session)
    fun clear()
}
interface SyncRemote {
    suspend fun login(email:String,password:String,signup:Boolean):Session?
    suspend fun refresh(session:Session):Session
    suspend fun pull(session:Session,cursor:Long):Page
    suspend fun push(session:Session,request:Push):Reply
    suspend fun logout(session:Session)
    suspend fun presence(session:Session,request:DevicePresenceRequest):DevicePresenceReply = throw DevicePresenceUnavailableException()
}
interface SyncSchedule {
    fun schedule(hasPending:Boolean)
    fun cancel()
}
enum class SyncOutcome { DONE, RETRY, SKIPPED, BLOCKED }
private class SyncRevoked:Exception()
private class SyncBoundReached:Exception()

/** One process-wide instance owns all authentication and network operations, including Worker calls. */
class SyncEngine(
    private val store:SyncStore,
    private val vault:SessionStore,
    private val remote:SyncRemote,
    private val scheduler:SyncSchedule,
    private val nowSeconds:()->Long={System.currentTimeMillis()/1000},
    private val maxPages:Int=100,
    private val maxBatches:Int=25
) {
    private val network=Mutex()
    private val policy=Any()
    private val generation=AtomicLong(0)
    @Volatile private var revoked=false
    @Volatile private var authBlocked=false
    val initialized=MutableStateFlow(false)
    val state=store.snapshots
    val session=MutableStateFlow<Session?>(null)
    val previewReady=MutableStateFlow(false)
    val busy=MutableStateFlow(false)
    val status=MutableStateFlow("正在读取本机内容")

    suspend fun initialize()=network.withLock {
        if(initialized.value) return@withLock
        try {
            val snapshot=store.read()
            session.value=vault.load()
            require(session.value==null || session.value?.owner==snapshot.owner) {"登录账号与本机内容不匹配"}
            initialized.value=true
            status.value=if(session.value==null) "本机保存 · 离线可用" else if(snapshot.enabled) "等待同步" else "同步未开启"
            reconcile()
        } catch(e:Exception) {
            scheduler.cancel()
            status.value="本机数据或登录状态读取失败，原数据已保留；请勿清除应用数据"
            throw e
        }
    }
    private fun eligible(s:SyncState=store.read())=initialized.value && !revoked && !authBlocked && s.enabled && s.owner!=null && session.value?.owner==s.owner
    /** Presence uses the same consent/auth policy without exposing credentials to callers. */
    fun canExchangePresence():Boolean = eligible()
    suspend fun exchangePresence(request:DevicePresenceRequest,expectedOwner:String?=state.value.owner,permit:()->Boolean={true}):DevicePresenceReply? {
        val requestedGeneration=generation.get()
        return network.withLock {
            if(!eligible() || requestedGeneration!=generation.get() || store.read().owner!=expectedOwner || !permit()) return@withLock null
            request.validate()
            require(request.deviceId==store.read().device) {"运行状态所属设备已改变"}
            val ticket=generation.get()
            try {
                withTimeout(60_000) {
                    val credentials=try {activeSession(ticket,manual=false)}
                    catch(e:CancellationException) {throw e}
                    catch(e:Exception) {handleFailure(e);throw e}
                    if(!permit()) return@withTimeout null
                    checkpoint(ticket,manual=false)
                    require(request.deviceId==store.read().device) {"运行状态所属设备已改变"}
                    if(!permit()) return@withTimeout null
                    val reply=remote.presence(credentials,request)
                    checkpoint(ticket,manual=false)
                    if(!permit()) return@withTimeout null
                    require(request.deviceId==store.read().device) {"运行状态所属设备已改变"}
                    reply.validate(request)
                    reply
                }
            } catch(_:SyncRevoked) {null}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {
                // A missing/failed optional RPC must never disable durable content sync.
                if(e is ApiFailure && e.code==401) handleFailure(e)
                throw e
            }
        }
    }
    private fun hasPending(s:SyncState)=s.pending!=null || s.items.values.any {it.dirty && it.conflict==null}
    private fun reconcile() = synchronized(policy) {
        val s=store.read()
        if(eligible(s)) scheduler.schedule(hasPending(s)) else scheduler.cancel()
    }
    fun edit(transform:(SyncState)->SyncState):SyncState {
        check(initialized.value) {"本机内容尚未读取完成，请稍后再试"}
        val saved=store.update(transform)
        status.value=if(eligible(saved)) "已保存 · 等待同步" else "已保存到本机"
        // A scheduling failure must never turn a committed local edit into a failed save.
        runCatching {reconcile()}
        return saved
    }
    /** Called immediately on the UI thread, before waiting for disk or any in-flight request. */
    fun revoke() = synchronized(policy) {revoked=true;generation.incrementAndGet();previewReady.value=false;scheduler.cancel()}
    suspend fun pause() {
        revoke()
        store.update {it.copy(enabled=false)}
        status.value="同步已暂停 · 变更保留在本机"
    }
    suspend fun signOut() {
        pause()
        network.withLock {
            val old=session.value
            vault.clear();session.value=null;authBlocked=false
            status.value="已退出 · 未上传变更保留并绑定原账号"
            if(old!=null) {
                try {withTimeout(40_000) {remote.logout(old)}}
                catch(_:TimeoutCancellationException) { /* Local credentials are already removed. */ }
                catch(e:CancellationException) {throw e}
                catch(_:Exception) { /* Offline logout still removes all local credentials. */ }
            }
        }
    }
    suspend fun enable()=network.withLock {
        check(initialized.value && previewReady.value) {"请先完成云端拉取预览"}
        val current=requireNotNull(session.value) {"请先登录"}
        check(!authBlocked) {"请重新登录"}
        store.update {require(it.owner==current.owner);it.copy(enabled=true)}
        revoked=false
        reconcile()
    }
    suspend fun authenticate(email:String,password:String,signup:Boolean)=network.withLock {
        check(initialized.value)
        busy.value=true
        val ticket=generation.get()
        try {
            withTimeout(120_000) {
            status.value=if(signup) "正在注册" else "正在登录"
            val next=remote.login(email,password,signup)
            if(ticket!=generation.get()) throw SyncRevoked()
            if(next==null) {status.value="请到邮箱完成验证，再回到这里登录";return@withTimeout}
            // Consent always resets at login, including a re-login to the original owner.
            store.update {StateMachine.bind(it,next.owner).copy(enabled=false)}
            vault.save(next);session.value=next;authBlocked=false;revoked=false;previewReady.value=false
            reconcile()
            status.value="正在拉取云端，尚未上传"
            pullAll(ticket,manual=true)
            status.value="已拉取预览 · 请确认合并并开启同步"
            }
        } catch(_:TimeoutCancellationException) {status.value="连接超时 · 请重试，本机变更已保留"}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {handleFailure(e)}
        finally {busy.value=false}
    }
    private suspend fun checkpoint(ticket:Long,manual:Boolean) {
        currentCoroutineContext().ensureActive()
        if(ticket!=generation.get() || authBlocked) throw SyncRevoked()
        val s=store.read()
        val current=session.value ?: throw SyncRevoked()
        require(s.owner==current.owner) {"登录账号发生变化"}
        if(!manual && !eligible(s)) throw SyncRevoked()
    }
    private suspend fun activeSession(ticket:Long,manual:Boolean):Session {
        checkpoint(ticket,manual)
        var current=requireNotNull(session.value)
        if(current.expiresAt<=nowSeconds()+60) {
            val refreshed=remote.refresh(current)
            checkpoint(ticket,manual)
            require(refreshed.owner==current.owner) {"登录账号发生变化"}
            require(refreshed.expiresAt>nowSeconds()+60) {"登录已失效，请重新登录"}
            vault.save(refreshed);session.value=refreshed;current=refreshed
        }
        return current
    }
    private suspend fun pullAll(ticket:Long,manual:Boolean) {
        var pages=0
        // Explicit previews can finish a long history; background work stays bounded.
        // Both paths retain the enclosing timeout and cancellation/consent checkpoints.
        while(manual || pages<maxPages) {
            val credentials=activeSession(ticket,manual)
            checkpoint(ticket,manual)
            val page=remote.pull(credentials,store.read().cursor)
            checkpoint(ticket,manual)
            store.update {StateMachine.pull(it,page)}
            pages++
            yield()
            checkpoint(ticket,manual)
            if(!page.more) {previewReady.value=true;return}
        }
        throw SyncBoundReached()
    }
    private suspend fun push(ticket:Long,pending:Push):Reply {
        val credentials=activeSession(ticket,manual=false)
        checkpoint(ticket,manual=false)
        val reply=remote.push(credentials,pending)
        // A sent request may have committed before pause. Keep its immutable pending operation
        // when revoked/cancelled; the next explicitly consented run can safely replay it.
        checkpoint(ticket,manual=false)
        store.update {StateMachine.accept(it,reply)}
        return reply
    }
    suspend fun sync(manual:Boolean=false):SyncOutcome=network.withLock {
        if(!initialized.value || session.value==null || authBlocked || (!manual && !eligible())) return@withLock SyncOutcome.SKIPPED
        val ticket=generation.get()
        busy.value=true;status.value="正在同步"
        try {
            withTimeout(120_000) {
                // Never observe new revisions before replaying a request whose response was lost.
                if(eligible()) store.read().pending?.let {push(ticket,it)}
                pullAll(ticket,manual)
                if(eligible()) {
                    var exhausted=true
                    for(index in 0 until maxBatches) {
                        checkpoint(ticket,manual=false)
                        val pending=store.update {StateMachine.prepare(it)}.pending
                        if(pending==null) {exhausted=false;break}
                        if(push(ticket,pending).status=="conflict") {exhausted=false;break}
                    }
                    if(exhausted && hasPending(store.read())) throw SyncBoundReached()
                    pullAll(ticket,manual)
                }
            }
            val snapshot=store.read()
            status.value=when {
                snapshot.items.values.any {it.conflict!=null} -> "需要处理冲突 · 两个版本都已保留"
                !snapshot.enabled -> "已拉取预览 · 同步未开启"
                hasPending(snapshot) -> "等待同步 · 仍有本机变更"
                else -> "已同步"
            }
            if(eligible(snapshot) && hasPending(snapshot)) SyncOutcome.RETRY else SyncOutcome.DONE
        } catch(e:TimeoutCancellationException) {status.value="等待重试 · 变更已保存在本机";SyncOutcome.RETRY}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {handleFailure(e)}
        finally {busy.value=false;runCatching {reconcile()}}
    }
    private fun handleFailure(e:Exception):SyncOutcome=when {
        e is SyncRevoked -> SyncOutcome.SKIPPED
        e is SyncBoundReached -> {
            status.value="内容较多 · 已保存当前进度，将继续同步";SyncOutcome.RETRY
        }
        e is IOException || (e is ApiFailure && (e.code==429 || e.code>=500)) -> {
            status.value="等待联网或重试 · 变更已保存在本机";SyncOutcome.RETRY
        }
        else -> {
            authBlocked=true;scheduler.cancel()
            status.value=if(e is ApiFailure && e.code in setOf(400,401,403,422)) "登录已失效或请求被拒绝，请重新登录" else "同步已停止，请检查账号或重新登录；本机变更已保留"
            SyncOutcome.BLOCKED
        }
    }
}
