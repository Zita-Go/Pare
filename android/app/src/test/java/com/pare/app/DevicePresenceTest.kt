package com.pare.app

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DevicePresenceTest {
    @Test fun windowsReportsKeepTheirPlatformLabelAndAndroidDefaultRemainsUnchanged() {
        val request=DevicePresenceRequest("70000000-0000-0000-0000-000000000002",1,label="Windows")
        request.validate()
        val now=Instant.parse("2026-11-22T00:00:00Z")
        DevicePresenceReply(1,now.toString(),1,listOf(DevicePresenceDevice(request.deviceId,1,"Windows",now.toString(),now.plusSeconds(90).toString(),emptyList()))).validate(request)
        assertEquals("Android",DevicePresenceRequest(request.deviceId,2).label)
    }
    private val owner = "70000000-0000-0000-0000-000000000001"
    private val device = "70000000-0000-0000-0000-000000000002"
    private val peer = "70000000-0000-0000-0000-000000000003"
    private val task = "70000000-0000-0000-0000-000000000004"
    private val run = "70000000-0000-0000-0000-000000000005"
    private val server = Instant.parse("2026-10-31T09:00:00Z")
    private fun report(state: String = "running", kind: String = "personal") = DevicePresenceRun(run, task, kind, state, server.minusSeconds(60).toString(), 60, 300)
    private fun reply(request: DevicePresenceRequest, accepted: Long = request.sequence) = DevicePresenceReply(1, server.toString(), accepted,
        listOf(DevicePresenceDevice(device, accepted, "Android", server.toString(), server.plusSeconds(90).toString(), request.runs),
            DevicePresenceDevice(peer, 1, "Mac", server.toString(), server.plusSeconds(90).toString(), listOf(report()))))
    private class MemoryStore(initial: SyncState) : SyncStore {
        override val snapshots = MutableStateFlow(initial)
        override fun read() = snapshots.value
        @Synchronized override fun update(transform: (SyncState) -> SyncState): SyncState = transform(read()).also { snapshots.value = it }
    }
    private class Vault(var value: Session?) : SessionStore {
        override fun load() = value
        override fun save(session: Session) { value = session }
        override fun clear() { value = null }
    }
    private class Schedule : SyncSchedule {
        override fun schedule(hasPending: Boolean) {}
        override fun cancel() {}
    }
    private class Clock(var moment: ExecutionMoment) : ExecutionClock { override fun now() = moment }
    private class Sequences : DevicePresenceSequenceStore {
        val values = mutableMapOf<Pair<String, String>, Long>()
        var beforeNext: (() -> Unit)? = null
        override fun next(owner: String, device: String): Long {
            beforeNext?.invoke()
            val key = owner to device
            return ((values[key] ?: 0) + 1).also { values[key] = it }
        }
        override fun advance(owner: String, device: String, accepted: Long) { values[owner to device] = maxOf(values[owner to device] ?: 0, accepted) }
    }
    private inner class RemoteApi : SyncRemote {
        val requests = mutableListOf<DevicePresenceRequest>()
        var refreshes = 0
        var refreshOwner: String? = null
        var beforeRefresh: (suspend () -> Unit)? = null
        var onPresence: suspend (Session, DevicePresenceRequest) -> DevicePresenceReply = { _, request -> reply(request) }
        var onPull: suspend (Long) -> Page = { Page(1, it, false, emptyList()) }
        override suspend fun login(email: String, password: String, signup: Boolean): Session? = error("unused")
        override suspend fun refresh(session: Session): Session { refreshes++; beforeRefresh?.invoke(); return session.copy(owner = refreshOwner ?: session.owner, accessToken = "fixture-refreshed", expiresAt = 10_000) }
        override suspend fun pull(session: Session, cursor: Long) = onPull(cursor)
        override suspend fun push(session: Session, request: Push): Reply = error("unused")
        override suspend fun logout(session: Session) {}
        override suspend fun presence(session: Session, request: DevicePresenceRequest): DevicePresenceReply { requests += request; return onPresence(session, request) }
    }
    private inner class Fixture(enabled: Boolean = true, expired: Boolean = false, sharing: Boolean = true) {
        val store = MemoryStore(SyncState(owner = owner, device = device, enabled = enabled))
        val vault = Vault(Session(owner, "fixture@example.invalid", "fixture-access", "fixture-refresh", if (expired) 0 else 10_000))
        val remote = RemoteApi()
        val engine = SyncEngine(store, vault, remote, Schedule(), nowSeconds = { 100 })
        val sequences = Sequences()
        val clock = Clock(ExecutionMoment(server.toEpochMilli(), 1_000, "boot-fixture"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        @Volatile var sharingEnabled = sharing
        val controller = DevicePresenceController(engine, sequences, clock, scope) { sharingEnabled }
        val execution = ExecutionSnapshot(owner = owner, device = device)
        suspend fun start() { engine.initialize() }
        fun close() { controller.clear(); scope.cancel() }
    }

    @Test fun requestContainsOnlyIdsStatesAndTimingAndExcludesPendingFinish() {
        val moment = ExecutionMoment(server.toEpochMilli(), 1_000, "boot")
        val focus = ExecutionRun(run, task, server.minusSeconds(60).toEpochMilli(), 60_000, targetMinutes = 5, paused = true)
        val execution = ExecutionSnapshot(owner = owner, device = device, focus = focus)
        val request = DevicePresenceRequest(device, 1, runs = DevicePresenceProjection.runs(execution, moment))
        request.validate()
        val json = wire.encodeToJsonElement(request).jsonObject
        assertEquals(setOf("p_device_id", "p_sequence", "p_label", "p_runs"), json.keys)
        assertEquals(setOf("run_id", "task_id", "kind", "state", "started_at", "elapsed_seconds", "target_seconds"), json["p_runs"]!!.jsonArray.single().jsonObject.keys)
        assertFalse(json.toString().contains("fixture-access")); assertFalse(json.toString().contains("title")); assertFalse(json.toString().contains("note"))
        assertEquals("paused", request.runs.single().state)
        assertTrue(DevicePresenceProjection.runs(execution.copy(focus = focus.copy(pendingFinish = ExecutionFinish(server.toEpochMilli(), 1))), moment).isEmpty())
    }

    @Test fun responseRejectsUnboundedLeaseDuplicateDevicesAndOlderAcceptedSequence() {
        val request = DevicePresenceRequest(device, 2)
        val good = reply(request)
        good.validate(request)
        assertThrows(IllegalArgumentException::class.java) { good.copy(acceptedSequence = 1).validate(request) }
        assertThrows(IllegalArgumentException::class.java) { good.copy(devices = good.devices + good.devices).validate(request) }
        assertThrows(IllegalArgumentException::class.java) { good.copy(devices = good.devices.map { it.copy(expiresAt = server.plusSeconds(91).toString()) }).validate(request) }
        assertThrows(IllegalArgumentException::class.java) { report().copy(elapsedSeconds = DEVICE_PRESENCE_MAX_SECONDS + 1).validate() }
    }

    @Test fun monotonicFreshnessIgnoresWallClockJumpAndExpiresAtLeaseBoundary() {
        val anchor = ExecutionMoment(server.toEpochMilli(), 10_000, "boot")
        val observation = DevicePresenceObservation(owner, device, reply(DevicePresenceRequest(device, 1)), anchor)
        assertTrue(observation.project(anchor.copy(wallMillis = 0, elapsedMillis = 99_999)).single().fresh)
        assertFalse(observation.project(anchor.copy(wallMillis = 0, elapsedMillis = 100_000)).single().fresh)
        assertFalse(observation.project(anchor.copy(bootId = "new-boot")).single().fresh)
        assertFalse(observation.project(anchor.copy(elapsedMillis = 1)).single().fresh)
    }

    @Test fun replyRequiresMatchingOwnReceiptAndAtMostThirtyTwoRetainedDevices() {
        val request = DevicePresenceRequest(device, 2)
        val good = reply(request)
        assertThrows(IllegalArgumentException::class.java) { good.copy(devices = emptyList()).validate(request) }
        assertThrows(IllegalArgumentException::class.java) { good.copy(devices = good.devices.filterNot { it.deviceId == device }).validate(request) }
        assertThrows(IllegalArgumentException::class.java) {
            good.copy(devices = good.devices.map { if (it.deviceId == device) it.copy(sequence = 3) else it }).validate(request)
        }
        assertThrows(IllegalArgumentException::class.java) {
            good.copy(devices = good.devices.map { if (it.deviceId == device) it.copy(runs = listOf(report())) else it }).validate(request)
        }
        val otherDevices = (1..32).map { index -> good.devices.last().copy(deviceId = "71000000-0000-0000-0000-${index.toString().padStart(12, '0')}") }
        good.copy(devices = listOf(good.devices.first()) + otherDevices.take(31)).validate(request)
        assertThrows(IllegalArgumentException::class.java) { good.copy(devices = listOf(good.devices.first()) + otherDevices).validate(request) }
    }

    @Test fun replyRejectsLastSeenOlderThanServerRetentionWindow() {
        val request = DevicePresenceRequest(device, 2)
        val good = reply(request)
        val boundary = server.minusSeconds(86_400)
        good.copy(devices = good.devices.map { if (it.deviceId == peer) it.copy(lastSeen = boundary.toString(), expiresAt = boundary.plusSeconds(90).toString()) else it }).validate(request)
        assertThrows(IllegalArgumentException::class.java) {
            good.copy(devices = good.devices.map { if (it.deviceId == peer) it.copy(lastSeen = boundary.minusSeconds(1).toString(), expiresAt = boundary.plusSeconds(89).toString()) else it }).validate(request)
        }
    }

    @Test fun malformedHttpReceiptsNeverPublishPeersOrClaimConfirmedSharing() = runBlocking {
        val f = Fixture(); f.start()
        try {
            val malformed: List<(DevicePresenceReply) -> DevicePresenceReply> = listOf(
                { it.copy(devices = emptyList()) },
                { it.copy(devices = it.devices.filterNot { row -> row.deviceId == device }) },
                { it.copy(devices = it.devices.map { row -> if (row.deviceId == device) row.copy(sequence = row.sequence + 1) else row }) },
                { it.copy(devices = it.devices.map { row -> if (row.deviceId == peer) row.copy(lastSeen = server.minusSeconds(86_401).toString(), expiresAt = server.minusSeconds(86_311).toString()) else row }) }
            )
            malformed.forEach { corrupt ->
                f.remote.onPresence = { _, request -> corrupt(reply(request)) }
                f.controller.poll(f.execution)
                assertTrue(f.controller.state.value.peers.isEmpty())
                assertNull(f.controller.state.value.lastConfirmedAt)
                assertFalse(f.controller.state.value.refreshing)
                assertTrue(f.controller.state.value.status.startsWith("暂未取得最新状态"))
            }
            f.remote.onPresence = { _, request -> reply(request) }
            f.controller.poll(f.execution)
            assertEquals(1, f.controller.freshPeerFocus().size)
        } finally { f.close() }
    }

    @Test fun unavailablePresenceDoesNotBlockContentSyncOrInventPeers() = runBlocking {
        val f = Fixture(); f.start()
        try {
            f.remote.onPresence = { _, _ -> throw ApiFailure(404) }
            f.controller.poll(f.execution)
            assertTrue(f.controller.state.value.peers.isEmpty())
            assertTrue(f.controller.state.value.status.contains("尚未启用"))
            assertTrue(f.engine.canExchangePresence())
            assertEquals(SyncOutcome.DONE, f.engine.sync())
        } finally { f.close() }
    }

    @Test fun pausedOrSignedOutAccountNeverSendsPresence() = runBlocking {
        val f = Fixture(enabled = false); f.start()
        try {
            f.controller.poll(f.execution)
            assertTrue(f.remote.requests.isEmpty())
            assertNull(f.engine.exchangePresence(DevicePresenceRequest(device, 1)))
            f.engine.signOut()
            assertNull(f.engine.exchangePresence(DevicePresenceRequest(device, 1)))
            assertTrue(f.remote.requests.isEmpty())
        } finally { f.close() }
    }

    @Test fun engineRefreshesCredentialsInsideMutexAndDoesNotExposeTokens() = runBlocking {
        val f = Fixture(expired = true); f.start()
        try {
            f.remote.onPresence = { session, request -> assertEquals("fixture-refreshed", session.accessToken); reply(request) }
            f.controller.poll(f.execution)
            assertEquals(1, f.remote.refreshes)
            assertEquals(1, f.controller.freshPeerFocus().size)
            assertFalse(f.controller.state.value.toString().contains("fixture-refreshed"))
        } finally { f.close() }
    }

    @Test fun pauseWhileResponseIsInFlightDiscardsItsPeers() = runBlocking {
        val f = Fixture(); f.start()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            f.remote.onPresence = { _, request -> entered.complete(Unit); release.await(); reply(request) }
            val poll = launch { f.controller.poll(f.execution) }
            entered.await(); f.engine.pause(); release.complete(Unit); poll.join()
            assertTrue(f.controller.state.value.peers.isEmpty()); assertTrue(f.controller.freshPeerFocus().isEmpty())
            assertNull(f.controller.state.value.lastConfirmedAt)
        } finally { f.close() }
    }

    @Test fun refreshForAnotherAccountNeverSavesOrUsesThoseCredentials() = runBlocking {
        val f = Fixture(expired = true); f.start()
        try {
            f.remote.refreshOwner = "different-fixture-owner"
            f.controller.poll(f.execution)
            assertTrue(f.remote.requests.isEmpty())
            assertEquals(owner, f.vault.value?.owner)
            assertEquals("fixture-access", f.vault.value?.accessToken)
            assertFalse(f.engine.canExchangePresence())
            assertTrue(f.controller.state.value.peers.isEmpty())
        } finally { f.close() }
    }

    @Test fun explicitClearInvalidatesInFlightReplyWithoutStoppingLocalRun() = runBlocking {
        val f = Fixture(); f.start()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            f.remote.onPresence = { _, request -> entered.complete(Unit); release.await(); reply(request) }
            val poll = launch { f.controller.poll(f.execution) }
            entered.await(); f.controller.clear(); release.complete(Unit); poll.join()
            assertTrue(f.controller.state.value.peers.isEmpty())
            assertEquals(f.execution, ExecutionSnapshot(owner = owner, device = device))
        } finally { f.close() }
    }

    @Test fun retryUsesNewDurablyReservedSequenceAndAdvancesToServerHighWatermark() = runBlocking {
        val f = Fixture(); f.start()
        try {
            f.remote.onPresence = { _, request ->
                assertEquals(request.sequence, f.sequences.values[owner to device])
                if (request.sequence == 1L) throw IOException("fixture offline")
                reply(request, maxOf(10, request.sequence))
            }
            f.controller.poll(f.execution); f.controller.poll(f.execution); f.controller.poll(f.execution)
            assertEquals(listOf(1L, 2L, 11L), f.remote.requests.map { it.sequence })
        } finally { f.close() }
    }

    @Test fun stalePeersRemainExplicitHistoryButNeverBlockAStartAsRunning() = runBlocking {
        val f = Fixture(); f.start()
        try {
            f.controller.poll(f.execution)
            assertEquals(1, f.controller.freshPeerFocus().size)
            f.clock.moment = f.clock.moment.copy(elapsedMillis = 91_000, wallMillis = 0)
            assertTrue(f.controller.freshPeerFocus().isEmpty())
            assertFalse(f.controller.state.value.peers.single().fresh)
            assertEquals(server.toString(), f.controller.state.value.lastConfirmedAt)
            assertEquals("running", f.controller.state.value.peers.single().runs.single().state)
        } finally { f.close() }
    }

    @Test fun changedAccountAndDeviceImmediatelyDiscardCachedIdentity() = runBlocking {
        val f = Fixture(); f.start()
        try {
            f.controller.poll(f.execution)
            f.store.update { it.copy(owner = "different-fixture-owner") }
            f.controller.refreshFreshness()
            assertTrue(f.controller.state.value.peers.isEmpty()); assertNull(f.controller.state.value.owner)
            assertNull(f.engine.exchangePresence(DevicePresenceRequest(device, 2), owner))
        } finally { f.close() }
    }

    @Test fun queuedPresenceSharesNetworkMutexAndCannotCrossConsentGeneration() = runBlocking {
        val f = Fixture(); f.start()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            f.remote.onPull = { cursor -> entered.complete(Unit); release.await(); Page(1, cursor, false, emptyList()) }
            val sync = launch { f.engine.sync() }
            entered.await()
            val presence = async { f.engine.exchangePresence(DevicePresenceRequest(device, 1), owner) }
            yield()
            assertTrue(f.remote.requests.isEmpty())
            f.engine.pause(); release.complete(Unit)
            sync.join(); assertNull(presence.await()); assertTrue(f.remote.requests.isEmpty())
        } finally { f.close() }
    }

    @Test fun separateSharingOptInCanBeOffWhileContentSyncStaysEnabled() = runBlocking {
        val f = Fixture(expired = true, sharing = false); f.start()
        try {
            f.controller.poll(f.execution)
            assertTrue(f.store.read().enabled)
            assertTrue(f.remote.requests.isEmpty()); assertEquals(0, f.remote.refreshes)
            assertTrue(f.sequences.values.isEmpty())
            assertNull(f.engine.exchangePresence(DevicePresenceRequest(device, 1), owner) { false })
            assertEquals(0, f.remote.refreshes)
        } finally { f.close() }
    }

    @Test fun changingPermitDuringTokenRefreshPreventsPresenceHttpRequest() = runBlocking {
        val f = Fixture(expired = true); f.start()
        var allowed = true
        try {
            f.remote.beforeRefresh = { allowed = false }
            assertNull(f.engine.exchangePresence(DevicePresenceRequest(device, 1), owner) { allowed })
            assertEquals(1, f.remote.refreshes)
            assertTrue(f.remote.requests.isEmpty())
            assertTrue(f.store.read().enabled)
        } finally { f.close() }
    }

    @Test fun disablingSharingPublishesEmptyRunsWithoutStoppingLocalExecutionOrContentSync() = runBlocking {
        val f = Fixture(); f.start()
        val local = f.execution.copy(focus = ExecutionRun(run, task, server.toEpochMilli(), anchor = f.clock.now(), targetMinutes = 5))
        try {
            f.controller.poll(local)
            f.sharingEnabled = false; f.controller.onSharingChanged()
            assertEquals(2, f.remote.requests.size)
            assertEquals(run, f.remote.requests.first().runs.single().runId)
            assertTrue(f.remote.requests.last().runs.isEmpty())
            assertTrue(f.remote.requests.last().sequence > f.remote.requests.first().sequence)
            assertTrue(f.store.read().enabled)
            assertEquals(run, local.focus?.id)
            assertTrue(f.controller.state.value.peers.isEmpty())
            assertTrue(f.controller.state.value.status.contains("确认本机最近报告中没有运行记录"))
        } finally { f.close() }
    }

    @Test fun queuedRunningAndWithdrawalRequestsCannotCrossRapidOffOnToggle() = runBlocking {
        val f = Fixture(); f.start()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val local = f.execution.copy(focus = ExecutionRun(run, task, server.toEpochMilli(), anchor = f.clock.now(), targetMinutes = 5))
        try {
            f.remote.onPull = { cursor -> entered.complete(Unit); release.await(); Page(1, cursor, false, emptyList()) }
            val sync = launch { f.engine.sync() }; entered.await()
            val oldPoll = launch { f.controller.poll(local) }
            while (f.sequences.values[owner to device] == null) yield()
            f.sharingEnabled = false; f.controller.onSharingChanged()
            f.sharingEnabled = true; f.controller.onSharingChanged()
            release.complete(Unit); sync.join(); oldPoll.join()
            f.controller.poll(local)
            assertEquals(1, f.remote.requests.size)
            assertEquals(run, f.remote.requests.single().runs.single().runId)
            assertEquals(1, f.controller.freshPeerFocus().size)
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun lateWithdrawalReceiptCannotReplaceReenabledSharingStatus() = runBlocking {
        val f = Fixture(); f.start()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val local = f.execution.copy(focus = ExecutionRun(run, task, server.toEpochMilli(), anchor = f.clock.now(), targetMinutes = 5))
        try {
            f.remote.onPresence = { _, request ->
                if (request.runs.isEmpty()) { entered.complete(Unit); release.await() }
                reply(request)
            }
            f.controller.poll(local)
            f.sharingEnabled = false; f.controller.onSharingChanged(); entered.await()
            f.sharingEnabled = true; f.controller.onSharingChanged()
            val fresh = launch { f.controller.poll(local) }
            release.complete(Unit); fresh.join()
            assertEquals(listOf(false, true, false), f.remote.requests.map { it.runs.isEmpty() })
            assertEquals(1, f.controller.freshPeerFocus().size)
            assertEquals("已取得其他设备最近报告的状态", f.controller.state.value.status)
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun failedWithdrawalKeepsExplicitFailureFeedbackAndNeverClaimsRemoteDeletion() = runBlocking {
        val f = Fixture(); f.start()
        try {
            f.controller.poll(f.execution)
            f.remote.onPresence = { _, _ -> throw IOException("fictional offline") }
            f.sharingEnabled = false; f.controller.onSharingChanged()
            assertTrue(f.controller.state.value.status.contains("暂未确认清空"))
            assertTrue(f.controller.state.value.peers.isEmpty())
            val failure = f.controller.state.value.status
            f.controller.poll(f.execution)
            assertEquals(failure, f.controller.state.value.status)
            assertTrue(f.store.read().enabled)
        } finally { f.close() }
    }

    @Test fun withdrawalAfterAccountRevocationNeverRefreshesCredentials() = runBlocking {
        val f = Fixture(expired = true, sharing = false); f.start()
        try {
            f.engine.revoke()
            assertTrue(f.controller.withdraw().isFailure)
            assertEquals(0, f.remote.refreshes); assertTrue(f.remote.requests.isEmpty())
            assertTrue(f.controller.state.value.status.contains("暂未确认清空"))
        } finally { f.close() }
    }

    @Test fun withdrawalRecoversServerHighWatermarkBeforeConfirmingEmptyRuns() = runBlocking {
        val f = Fixture(); f.start()
        try {
            f.sharingEnabled = false
            f.remote.onPresence = { _, request ->
                if (request.sequence == 1L) reply(request, 10).let { response -> response.copy(devices = response.devices.map { if (it.deviceId == device) it.copy(runs = listOf(report())) else it }) }
                else reply(request)
            }
            assertTrue(f.controller.withdraw().isSuccess)
            assertEquals(listOf(1L, 11L), f.remote.requests.map { it.sequence })
            assertTrue(f.remote.requests.all { it.runs.isEmpty() })
            assertTrue(f.controller.state.value.status.contains("确认本机最近报告中没有运行记录"))
        } finally { f.close() }
    }
}
