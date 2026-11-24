package com.pare.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SyncEngineTest {
    private val id="00000000-0000-0000-0000-000000000001"
    private fun payload(id:String=this.id,text:String="fictional note")=buildJsonObject {put("content",buildJsonObject {put("id",id);put("createdAt","2026-10-30T00:00:00Z");put("text",text)})}
    private class MemoryStore(initial:SyncState):SyncStore {
        override val snapshots=MutableStateFlow(initial)
        @Synchronized override fun read()=snapshots.value
        @Synchronized override fun update(transform:(SyncState)->SyncState):SyncState=transform(read()).also {snapshots.value=it}
    }
    private class MemorySession(var value:Session?):SessionStore {
        override fun load()=value
        override fun save(session:Session) {value=session}
        override fun clear() {value=null}
    }
    private class Schedule:SyncSchedule {
        var enabled=false; var pending=false
        override fun schedule(hasPending:Boolean) {enabled=true;pending=hasPending}
        override fun cancel() {enabled=false;pending=false}
    }
    private open class RemoteApi:SyncRemote {
        var refreshes=0;var pulls=0;var logouts=0;val pushes=mutableListOf<Push>();val events=mutableListOf<String>()
        var loseFirst=false;var refreshOwner="owner";var beforePush:(suspend ()->Unit)?=null
        override suspend fun login(email:String,password:String,signup:Boolean):Session?=error("unused")
        override suspend fun refresh(session:Session):Session {refreshes++;delay(20);return session.copy(owner=refreshOwner,expiresAt=10000)}
        override suspend fun pull(session:Session,cursor:Long):Page {pulls++;events.add("pull");delay(10);return Page(1,cursor,false,emptyList())}
        override suspend fun push(session:Session,request:Push):Reply {
            beforePush?.invoke();events.add("push");pushes.add(request)
            if(loseFirst && pushes.size==1) throw IOException("lost response")
            return Reply(1,"applied",1,request.mutations.map {Remote(it.kind,it.id,1,payload=it.payload,deletedAt=if(it.deleted) "2026-10-30T00:00:00Z" else null,device=request.device,sequence=1)})
        }
        override suspend fun logout(session:Session) {logouts++}
    }
    private class Fixture(enabled:Boolean=true,expired:Boolean=false) {
        val store=MemoryStore(SyncState(owner="owner",enabled=enabled))
        val vault=MemorySession(Session("owner","test@example.invalid","access","refresh",if(expired) 0 else 10000))
        val remote=RemoteApi();val schedule=Schedule()
        val engine=SyncEngine(store,vault,remote,schedule,nowSeconds={100})
    }
    private inner class PagedRemote(var total:Long):RemoteApi() {
        val requestedCursors=mutableListOf<Long>()
        var beforePull:(suspend (Long)->Unit)?=null
        override suspend fun login(email:String,password:String,signup:Boolean)=Session("owner","test@example.invalid","access","refresh",10000)
        override suspend fun pull(session:Session,cursor:Long):Page {
            requestedCursors.add(cursor);pulls++
            beforePull?.invoke(cursor)
            if(cursor==total) return Page(1,cursor,false,emptyList())
            val sequence=cursor+1
            val remoteID="00000000-0000-0000-0000-"+sequence.toString().padStart(12,'0')
            val item=Remote("inbox",remoteID,1,payload=payload(remoteID),device="remote",sequence=sequence)
            return Page(1,sequence,sequence<total,listOf(Commit(sequence,"operation-$sequence","remote",listOf(item))))
        }
    }
    @Test fun simultaneousForegroundAndWorkerRefreshAndUploadOnce()=runBlocking {
        val f=Fixture(expired=true)
        f.store.update {StateMachine.edit(it,"inbox",id,payload())}
        f.engine.initialize()
        coroutineScope {repeat(4) {launch(Dispatchers.Default) {f.engine.sync()}}}
        assertEquals(1,f.remote.refreshes);assertEquals(1,f.remote.pushes.size)
        assertFalse(f.store.read().items.getValue("inbox:$id").dirty)
        assertEquals("access",f.vault.value!!.accessToken)
    }
    @Test fun lostResponseReplaysIdenticalOperationBeforePulling()=runBlocking {
        val f=Fixture();f.remote.loseFirst=true
        f.store.update {StateMachine.edit(it,"inbox",id,payload())};f.engine.initialize()
        assertEquals(SyncOutcome.RETRY,f.engine.sync())
        val pending=f.store.read().pending!!
        assertTrue(f.schedule.pending)
        val beforeRetry=f.remote.events.size
        assertEquals(SyncOutcome.DONE,f.engine.sync())
        assertEquals("push",f.remote.events[beforeRetry])
        assertEquals(listOf(pending,pending),f.remote.pushes)
        assertNull(f.store.read().pending)
    }
    @Test fun disabledAccountAllowsManualPreviewButNeverUploads()=runBlocking {
        val f=Fixture(enabled=false)
        f.store.update {StateMachine.edit(it,"inbox",id,payload())};f.engine.initialize()
        assertEquals(SyncOutcome.SKIPPED,f.engine.sync())
        assertEquals(0,f.remote.pulls)
        f.engine.sync(manual=true)
        assertTrue(f.engine.previewReady.value);assertTrue(f.remote.pushes.isEmpty())
        assertFalse(f.schedule.enabled)
    }
    @Test fun firstLoginPullsAll153CommitPagesBeforeOfferingConsent()=runBlocking {
        val store=MemoryStore(SyncState());val vault=MemorySession(null);val schedule=Schedule()
        val localID="00000000-0000-0000-0000-999999999999"
        store.update {StateMachine.edit(it,"inbox",localID,payload(localID,"local fictional draft before login"))}
        val remote=PagedRemote(153)
        val engine=SyncEngine(store,vault,remote,schedule,nowSeconds={100});engine.initialize()
        engine.authenticate("test@example.invalid","fictional-password",signup=false)
        assertEquals(153L,store.read().cursor);assertEquals(154,store.read().items.size)
        assertTrue(store.read().items.getValue("inbox:$localID").dirty)
        assertTrue(engine.previewReady.value);assertFalse(store.read().enabled)
        assertTrue(remote.pushes.isEmpty());assertFalse(schedule.enabled);assertFalse(engine.busy.value)
    }
    @Test fun restartedDisabledAccountContinuesSavedCursorToEndWithoutUploadingLocalEdits()=runBlocking {
        val f=Fixture(enabled=false);val remote=PagedRemote(206)
        var offline=true
        remote.beforePull={cursor -> if(offline && cursor==53L) throw IOException("fictional interruption")}
        val first=SyncEngine(f.store,f.vault,remote,f.schedule,nowSeconds={100});first.initialize()
        assertEquals(SyncOutcome.RETRY,first.sync(manual=true));assertEquals(53L,f.store.read().cursor)
        val local=payload(text="local fictional revision after interrupted preview")
        first.edit {StateMachine.edit(it,"inbox",id,local)}

        val restarted=SyncEngine(f.store,f.vault,remote,f.schedule,nowSeconds={100});restarted.initialize()
        assertFalse(restarted.previewReady.value);offline=false
        val resumedAt=remote.requestedCursors.size
        assertEquals(SyncOutcome.DONE,restarted.sync(manual=true))
        assertEquals(53L,remote.requestedCursors[resumedAt]);assertEquals(206L,f.store.read().cursor)
        assertEquals(206,f.store.read().items.size);assertTrue(restarted.previewReady.value)
        assertEquals(local,f.store.read().items.getValue("inbox:$id").payload)
        assertTrue(f.store.read().items.getValue("inbox:$id").dirty)
        assertFalse(f.store.read().enabled);assertTrue(remote.pushes.isEmpty());assertFalse(f.schedule.enabled)
    }
    @Test fun manualEnabledSyncFinishesNewPagesArrivingAfterInitialPull()=runBlocking {
        val f=Fixture();val remote=PagedRemote(0)
        remote.beforePull={if(remote.pulls==2) remote.total=153}
        val engine=SyncEngine(f.store,f.vault,remote,f.schedule,nowSeconds={100});engine.initialize()
        assertEquals(SyncOutcome.DONE,engine.sync(manual=true))
        assertEquals(153L,f.store.read().cursor);assertEquals(153,f.store.read().items.size)
        assertTrue(engine.previewReady.value);assertTrue(f.store.read().enabled)
    }
    @Test fun revokeBetweenImmediatePagesStopsManualPreviewBeforeNextRequest()=runBlocking {
        val f=Fixture(enabled=false);val remote=PagedRemote(153);val firstPage=CompletableDeferred<Unit>()
        remote.beforePull={firstPage.complete(Unit)}
        val engine=SyncEngine(f.store,f.vault,remote,f.schedule,nowSeconds={100});engine.initialize()
        val running=async {engine.sync(manual=true)}
        val stopping=launch {firstPage.await();engine.revoke()}
        assertEquals(SyncOutcome.SKIPPED,running.await());stopping.join()
        assertEquals(1L,f.store.read().cursor);assertEquals(1,remote.pulls)
        assertFalse(engine.previewReady.value);assertFalse(f.store.read().enabled)
        assertTrue(remote.pushes.isEmpty());assertFalse(f.schedule.enabled)
    }
    @Test fun signOutBetweenImmediatePagesStopsManualPreviewAndClearsSession()=runBlocking {
        val f=Fixture(enabled=false);val remote=PagedRemote(153);val firstPage=CompletableDeferred<Unit>()
        remote.beforePull={firstPage.complete(Unit)}
        val engine=SyncEngine(f.store,f.vault,remote,f.schedule,nowSeconds={100});engine.initialize()
        val running=async {engine.sync(manual=true)}
        val stopping=launch {firstPage.await();engine.signOut()}
        assertEquals(SyncOutcome.SKIPPED,running.await());stopping.join()
        assertEquals(1L,f.store.read().cursor);assertEquals(1,remote.pulls)
        assertFalse(engine.previewReady.value);assertNull(f.vault.value);assertNull(engine.session.value)
        assertFalse(f.store.read().enabled);assertTrue(remote.pushes.isEmpty());assertFalse(f.schedule.enabled)
    }
    @Test fun cancellationBetweenImmediatePagesKeepsSavedCursorWithoutFinishingPreview()=runBlocking {
        val f=Fixture(enabled=false);val remote=PagedRemote(153);val firstPage=CompletableDeferred<Unit>()
        remote.beforePull={firstPage.complete(Unit)}
        val engine=SyncEngine(f.store,f.vault,remote,f.schedule,nowSeconds={100});engine.initialize()
        val running=launch {engine.sync(manual=true)}
        val stopping=launch {firstPage.await();running.cancel()}
        running.join();stopping.join()
        assertEquals(1L,f.store.read().cursor);assertEquals(1,remote.pulls)
        assertFalse(engine.previewReady.value);assertFalse(engine.busy.value)
        assertFalse(f.store.read().enabled);assertTrue(remote.pushes.isEmpty())
    }
    @Test fun pauseDuringPullStopsTheNextUploadAndCancelsScheduling()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val f=Fixture();f.store.update {StateMachine.edit(it,"inbox",id,payload())}
        val remote=object:RemoteApi() {override suspend fun pull(session:Session,cursor:Long):Page {entered.complete(Unit);release.await();return Page(1,cursor,false,emptyList())}}
        val engine=SyncEngine(f.store,f.vault,remote,f.schedule,nowSeconds={100});engine.initialize()
        val running=launch {engine.sync()};entered.await()
        engine.pause();release.complete(Unit);running.join()
        assertTrue(remote.pushes.isEmpty());assertFalse(f.store.read().enabled);assertFalse(f.schedule.enabled)
        assertTrue(f.store.read().items.getValue("inbox:$id").dirty)
    }
    @Test fun changedOwnerFromRefreshNeverSavesTokensOrSendsContent()=runBlocking {
        val f=Fixture(expired=true);f.remote.refreshOwner="different-owner";f.engine.initialize()
        assertEquals(SyncOutcome.BLOCKED,f.engine.sync())
        assertEquals("owner",f.vault.value!!.owner)
        assertEquals(0,f.remote.pulls);assertTrue(f.remote.pushes.isEmpty())
    }
    @Test fun signedOutQueuedWorkerCannotUploadAndKeepsOutbox()=runBlocking {
        val f=Fixture();f.store.update {StateMachine.prepare(StateMachine.edit(it,"inbox",id,payload()))}
        val pending=f.store.read().pending;f.engine.initialize();f.engine.signOut()
        assertEquals(SyncOutcome.SKIPPED,f.engine.sync());assertNull(f.vault.value)
        assertEquals(pending,f.store.read().pending);assertFalse(f.schedule.enabled)
        assertEquals(1,f.remote.logouts)
    }
    @Test fun expiredRefreshFailureBlocksBackgroundRetriesUntilLogin()=runBlocking {
        val f=Fixture(expired=true)
        val remote=object:RemoteApi() {override suspend fun refresh(session:Session):Session {throw ApiFailure(401)}}
        val engine=SyncEngine(f.store,f.vault,remote,f.schedule,nowSeconds={100});engine.initialize()
        assertEquals(SyncOutcome.BLOCKED,engine.sync());assertFalse(f.schedule.enabled)
        assertEquals(SyncOutcome.SKIPPED,engine.sync());assertEquals(0,remote.pulls)
    }
    @Test fun offlinePullRetriesWithoutPreparingAnUnobservedUpload()=runBlocking {
        val f=Fixture();var offline=true
        f.store.update {StateMachine.edit(it,"inbox",id,payload())}
        val remote=object:RemoteApi() {override suspend fun pull(session:Session,cursor:Long):Page {
            if(offline) throw IOException("offline")
            return super.pull(session,cursor)
        }}
        val engine=SyncEngine(f.store,f.vault,remote,f.schedule,nowSeconds={100});engine.initialize()
        assertEquals(SyncOutcome.RETRY,engine.sync());assertTrue(f.schedule.pending)
        assertNull(f.store.read().pending);assertTrue(remote.pushes.isEmpty())
        offline=false
        assertEquals(SyncOutcome.DONE,engine.sync());assertFalse(f.store.read().items.getValue("inbox:$id").dirty)
    }
    @Test fun pullingManyPagesYieldsForTheNextWorkerWithoutUploadingBeforePreviewCompletes()=runBlocking {
        val f=Fixture();var requests=0
        val remote=object:RemoteApi() {override suspend fun pull(session:Session,cursor:Long):Page {
            requests++
            val remoteID="00000000-0000-0000-0000-"+requests.toString().padStart(12,'0')
            val item=Remote("inbox",remoteID,1,payload=payload(remoteID),device="remote",sequence=cursor+1)
            return Page(1,cursor+1,true,listOf(Commit(cursor+1,"operation-$requests","remote",listOf(item))))
        }}
        val engine=SyncEngine(f.store,f.vault,remote,f.schedule,nowSeconds={100},maxPages=2);engine.initialize()
        assertEquals(SyncOutcome.RETRY,engine.sync());assertEquals(2L,f.store.read().cursor)
        assertEquals(2,requests);assertFalse(engine.previewReady.value);assertTrue(remote.pushes.isEmpty())
        assertTrue(engine.status.value.contains("继续"));assertFalse(engine.status.value.contains("联网"))
        assertTrue(f.schedule.enabled)
    }
    @Test fun cancellationDuringPushRetainsImmutableRequestForLaterRetry()=runBlocking {
        val f=Fixture();val entered=CompletableDeferred<Unit>()
        f.store.update {StateMachine.edit(it,"inbox",id,payload())}
        f.remote.beforePush={entered.complete(Unit);awaitCancellation()};f.engine.initialize()
        val running=launch {f.engine.sync()};entered.await()
        val pending=f.store.read().pending!!;running.cancelAndJoin()
        assertEquals(pending,f.store.read().pending);assertFalse(f.engine.busy.value)
        f.remote.beforePush=null
        assertEquals(SyncOutcome.DONE,f.engine.sync());assertEquals(pending,f.remote.pushes.single())
    }
    @Test fun localEditDoesNotWaitForNetworkAndItsNewTextSurvivesOldReceipt()=runBlocking {
        val f=Fixture();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.store.update {StateMachine.edit(it,"inbox",id,payload())}
        f.remote.beforePush={entered.complete(Unit);release.await()};f.engine.initialize()
        val running=launch {f.engine.sync()};entered.await()
        val newer=payload(text="new fictional draft")
        f.engine.edit {StateMachine.edit(it,"inbox",id,newer)}
        // Pause after the old request started; cancellation must retain both its immutable request and the new edit.
        f.engine.pause();release.complete(Unit);running.join()
        assertEquals(newer,f.store.read().items.getValue("inbox:$id").payload)
        assertEquals(payload(),f.store.read().pending!!.mutations.single().payload)
        assertTrue(f.store.read().items.getValue("inbox:$id").dirty)
    }

}
