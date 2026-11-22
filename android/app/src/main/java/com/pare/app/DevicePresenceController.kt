package com.pare.app

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

/** Application-local observer. Remote reports never mutate the execution controller. */
class DevicePresenceController(
    private val engine: SyncEngine,
    private val sequences: DevicePresenceSequenceStore,
    private val clock: ExecutionClock,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val sharingAllowed: () -> Boolean = { true }
) {
    private val pollLock = Mutex()
    private val guard = Any()
    private val generation = AtomicLong(0)
    private var observation: DevicePresenceObservation? = null
    private var expiryJob: Job? = null
    private val mutableState = MutableStateFlow(idleState())
    val state: StateFlow<DevicePresenceState> = mutableState

    private fun identityAllowed(owner: String, device: String): Boolean = engine.canExchangePresence() &&
        engine.state.value.owner == owner && engine.state.value.device == device
    private fun permitted(owner: String, device: String): Boolean = sharingAllowed() && identityAllowed(owner, device)
    private fun idleState() = DevicePresenceState(status = if (sharingAllowed()) "开启账号同步后，可查看其他设备最近报告的运行状态" else "跨端运行状态共享未开启")

    fun clear() = synchronized(guard) {
        generation.incrementAndGet(); observation = null
        expiryJob?.cancel(); expiryJob = null
        mutableState.value = idleState()
    }

    /** Call synchronously after persisting every toggle, before launching any new poll.
     * The generation fence invalidates old running reports and old withdrawal jobs alike. */
    fun onSharingChanged() {
        val pending = synchronized(guard) {
            clear()
            if (sharingAllowed()) null else {
                val current = engine.state.value
                mutableState.value = idleState().copy(status = "已关闭本机共享，正在确认清空最近报告", refreshing = true)
                Triple(current.owner, current.device, generation.get())
            }
        }
        pending?.let { (owner, device, ticket) -> scope.launch { withdrawFor(owner, device, ticket) } }
    }

    /** Retry clearing an already-disabled share. Never changes the content-sync setting. */
    suspend fun withdraw(): Result<Unit> {
        val pending = synchronized(guard) {
            if (sharingAllowed()) null else {
                clear()
                val current = engine.state.value
                mutableState.value = idleState().copy(status = "已关闭本机共享，正在确认清空最近报告", refreshing = true)
                Triple(current.owner, current.device, generation.get())
            }
        } ?: return Result.failure(IllegalStateException("共享已重新开启，未清空新状态"))
        return withdrawFor(pending.first, pending.second, pending.third)
    }

    private suspend fun withdrawFor(owner: String?, device: String, ticket: Long): Result<Unit> {
        fun currentChoice() = ticket == generation.get() && !sharingAllowed()
        fun canSend() = currentChoice() && owner != null && identityAllowed(owner, device)
        fun failedMessage() = "已关闭本机共享，暂未确认清空。其他设备可能显示最近报告，报告过期后会标记失效"
        try {
            check(canSend()) { "当前无法确认清空运行报告" }
            val currentOwner = requireNotNull(owner)
            // A recovered server high-watermark may require one additional fresh sequence.
            repeat(2) {
                check(canSend()) { "共享选择或账号已改变，未清空新状态" }
                val request = DevicePresenceRequest(device, sequences.next(currentOwner, device))
                val reply = engine.exchangePresence(request, currentOwner, ::canSend)
                check(reply != null && canSend()) { "尚未确认清空运行报告" }
                sequences.advance(currentOwner, device, reply.acceptedSequence)
                if (reply.devices.single { it.deviceId == device }.runs.isEmpty()) {
                    synchronized(guard) {
                        if (currentChoice()) mutableState.value = idleState().copy(status = "已关闭共享，并确认本机最近报告中没有运行记录")
                    }
                    return Result.success(Unit)
                }
            }
            error("服务端状态已再次更新，尚未确认清空")
        } catch (e: CancellationException) {
            synchronized(guard) { if (currentChoice()) mutableState.value = idleState().copy(status = failedMessage()) }
            throw e
        } catch (e: Exception) {
            synchronized(guard) { if (currentChoice()) mutableState.value = idleState().copy(status = failedMessage()) }
            return Result.failure(e)
        }
    }

    fun refreshFreshness() = synchronized(guard) {
        val current = observation
        if (current != null) {
            if (!permitted(current.owner, current.device)) { clear(); return@synchronized }
            mutableState.value = mutableState.value.copy(peers = current.project(clock.now()))
        } else if (!engine.canExchangePresence() && sharingAllowed()) {
            mutableState.value = idleState()
        }
    }

    /** Re-evaluate monotonic expiry at the moment a local start decision is made. */
    fun freshPeerFocus(): List<DevicePresencePeerRun> {
        refreshFreshness()
        return state.value.freshPeerFocus()
    }

    suspend fun poll(execution: ExecutionSnapshot) {
        if (!pollLock.tryLock()) return
        try {
            if (!sharingAllowed()) {
                synchronized(guard) { if (observation != null) clear() }
                return
            }
            val current = engine.state.value
            val owner = current.owner
            if (owner == null || !permitted(owner, current.device)) { clear(); return }
            if (execution.owner != owner || execution.device != current.device) {
                clear()
                mutableState.value = DevicePresenceState(owner, current.device, status = "本机运行状态尚未准备好，暂未共享")
                return
            }
            val ticket = generation.get()
            val moment = clock.now()
            synchronized(guard) {
                if (mutableState.value.owner != owner || mutableState.value.device != current.device) {
                    observation = null; expiryJob?.cancel()
                    mutableState.value = DevicePresenceState(owner, current.device)
                }
                mutableState.value = mutableState.value.copy(owner = owner, device = current.device, refreshing = true)
            }
            try {
                execution.validate()
                val request = DevicePresenceRequest(current.device, sequences.next(owner, current.device), runs = DevicePresenceProjection.runs(execution, moment))
                // The sequence is on disk before the HTTP operation starts.
                val reply = engine.exchangePresence(request, owner) { ticket == generation.get() && permitted(owner, current.device) }
                if (ticket != generation.get() || !permitted(owner, current.device)) { refreshFreshness(); return }
                if (reply == null) { clear(); return }
                reply.validate(request)
                sequences.advance(owner, current.device, reply.acceptedSequence)
                synchronized(guard) {
                    if (ticket != generation.get() || !permitted(owner, current.device)) return@synchronized
                    val observed = DevicePresenceObservation(owner, current.device, reply, moment)
                    observation = observed
                    mutableState.value = DevicePresenceState(owner, current.device, observed.project(clock.now()),
                        status = if (reply.acceptedSequence > request.sequence) "已读取其他设备状态；本机状态将在下次更新确认" else "已取得其他设备最近报告的状态",
                        lastConfirmedAt = reply.serverNow)
                    expiryJob?.cancel()
                    expiryJob = scope.launch {
                        while (isActive) {
                            delay(1_000)
                            refreshFreshness()
                            if (state.value.peers.none { it.fresh }) break
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ticket == generation.get() && permitted(owner, current.device)) synchronized(guard) {
                    if (ticket != generation.get() || !permitted(owner, current.device)) return@synchronized
                    val unavailable = e is DevicePresenceUnavailableException || (e is ApiFailure && e.code in setOf(400, 404))
                    val peers = observation?.project(clock.now()).orEmpty()
                    mutableState.value = mutableState.value.copy(peers = peers, refreshing = false,
                        status = if (unavailable) "跨端运行状态服务尚未启用；本机计时仍可使用" else "暂未取得最新状态；仅保留最近确认的记录")
                } else refreshFreshness()
            } finally {
                synchronized(guard) {
                    if (ticket == generation.get()) mutableState.value = mutableState.value.copy(refreshing = false)
                }
            }
        } finally { pollLock.unlock() }
    }
}
