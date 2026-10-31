package com.pare.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ExecutionControllerTest {
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    private val c="00000000-0000-0000-0000-000000000003"
    private class Clock:ExecutionClock {
        var time=ExecutionMoment(1_793_318_400_000,10_000,"boot-one")
        override fun now()=time
        fun advance(ms:Long) {time=time.copy(wallMillis=time.wallMillis+ms,elapsedMillis=time.elapsedMillis+ms)}
    }
    private class Local:ExecutionPersistence {
        var saved:ExecutionSnapshot?=null
        var failClear=false
        override fun load()=saved
        override fun save(snapshot:ExecutionSnapshot) {
            if(failClear && snapshot.focus==null) throw IOException("simulated power loss")
            saved=snapshot
        }
    }
    private class Store(initial:SyncState):SyncStore {
        override val snapshots=MutableStateFlow(initial)
        override fun read()=snapshots.value
        override fun update(transform:(SyncState)->SyncState)=transform(read()).also {snapshots.value=it}
    }
    private fun fixture():Triple<SyncEngine,Clock,Local> = runBlocking {
        var s=SyncState(device="fictional-device")
        for(id in listOf(a,b,c)) s=StateMachine.edit(s,"task",id,buildJsonObject {
            put("lifecycle","active");put("unknownEnvelope","retain")
            put("content",buildJsonObject {
                put("id",id);put("text","fictional task");put("createdAt","2026-10-30T00:00:00Z")
                put("quadrant",1);put("completed",false);put("actualMinutes",7);put("futureField","retain")
            })
        })
        val engine=SyncEngine(Store(s),object:SessionStore {
            override fun load():Session?=null
            override fun save(session:Session)=Unit
            override fun clear()=Unit
        },object:SyncRemote {
            override suspend fun login(email:String,password:String,signup:Boolean):Session?=error("network forbidden")
            override suspend fun refresh(session:Session):Session=error("network forbidden")
            override suspend fun pull(session:Session,cursor:Long):Page=error("network forbidden")
            override suspend fun push(session:Session,request:Push):Reply=error("network forbidden")
            override suspend fun logout(session:Session)=Unit
        },object:SyncSchedule {override fun schedule(hasPending:Boolean)=Unit;override fun cancel()=Unit})
        engine.initialize()
        Triple(engine,Clock(),Local())
    }
    private fun Triple<SyncEngine,Clock,Local>.controller()=ExecutionController(first,third,second).also {it.initialize()}
    private fun SyncEngine.task(id:String)=state.value.items.getValue("task:$id")

    @Test fun exportSyntheticExecutionAndReminderContractForMacDecoder() {
        val f=fixture();val controller=f.controller()
        controller.startFocus(a,5);controller.startExternal(b,10)
        f.second.advance(120_000);controller.finish(a);controller.finish(b)
        val now=java.time.Instant.ofEpochMilli(f.second.time.wallMillis)
        f.first.edit {TaskReminderActions.save(it,c,TaskReminderBaseline.from(it.items.getValue("task:$c")),now.plusSeconds(600),"weekly",now)}
        val payloads=JsonObject(f.first.state.value.items.filterValues {!it.deleted}.mapValues {it.value.payload!!})
        f.first.state.value.items.values.forEach {PayloadValidation.validate(it.kind,it.id,it.payload!!)}
        java.io.File(System.getProperty("java.io.tmpdir"),"pare-android-execution-wire.json").writeText(payloads.toString())
    }

    @Test fun onePersonalFocusAllowsMultipleExternalRunsButNeverSameTask() {
        val f=fixture();val execution=f.controller()
        execution.startFocus(a,5);execution.startExternal(b,15);execution.startExternal(c)
        assertEquals(2,execution.state.value.external.size)
        assertThrows(IllegalStateException::class.java) {execution.startFocus(b,5)}
        assertThrows(IllegalStateException::class.java) {execution.startExternal(a)}
        assertEquals(a,execution.state.value.focus!!.taskId)
    }
    @Test fun pauseResumeAndRepeatedPauseExcludeWaitingAndPreserveUnknownContent() {
        val f=fixture();val execution=f.controller()
        execution.startFocus(a,10);f.second.advance(61_000);execution.pause(a)
        f.second.advance(600_000);execution.pause(a);execution.resume(a)
        f.second.advance(59_000);execution.finish(a)
        val task=f.first.task(a);val content=task.content()!!
        assertNull(execution.state.value.focus)
        assertEquals(9,content["actualMinutes"]!!.jsonPrimitive.int)
        assertEquals(2,content["progressSessions"]!!.jsonArray.single().jsonObject["minutes"]!!.jsonPrimitive.int)
        assertEquals("retain",content["futureField"]!!.jsonPrimitive.content)
        assertEquals("retain",task.payload!!["unknownEnvelope"]!!.jsonPrimitive.content)
        assertFalse(task.completed());PayloadValidation.validate("task",a,task.payload!!)
    }
    @Test fun externalFinishDoesNotInflatePersonalTimeOrCompleteTask() {
        val f=fixture();val execution=f.controller()
        execution.startExternal(a,5);f.second.advance(121_000);execution.pause(a)
        f.second.advance(600_000);execution.resume(a);f.second.advance(59_000);execution.finish(a)
        val content=f.first.task(a).content()!!
        assertEquals(7,content["actualMinutes"]!!.jsonPrimitive.int)
        assertEquals(3,content["parallelRunSessions"]!!.jsonArray.single().jsonObject["elapsedMinutes"]!!.jsonPrimitive.int)
        assertEquals("completed",content["parallelRunSessions"]!!.jsonArray.single().jsonObject["reason"]!!.jsonPrimitive.content)
        assertFalse(f.first.task(a).completed());assertTrue(execution.state.value.external.isEmpty())
    }
    @Test fun processRestartRecoversSameBootAndTargetOnlyPausesForConfirmation() {
        val f=fixture();f.controller().startFocus(a,5);f.second.advance(360_000)
        val restored=f.controller();val run=restored.state.value.focus!!
        assertTrue(run.paused);assertTrue(run.due)
        assertEquals(300_000,run.accumulatedMillis)
        assertFalse(f.first.task(a).completed());assertNull(f.first.task(a).content()!!["progressSessions"])
        restored.finish(a)
        assertEquals(12,f.first.task(a).content()!!["actualMinutes"]!!.jsonPrimitive.int)
    }
    @Test fun clockJumpCannotInflateFocusAndRebootConservativelyPauses() {
        val f=fixture();val execution=f.controller();execution.startFocus(a,10)
        f.second.advance(60_000);execution.reconcile()
        f.second.time=f.second.time.copy(wallMillis=f.second.time.wallMillis+86_400_000)
        execution.pause(a)
        assertEquals(60_000,execution.state.value.focus!!.accumulatedMillis)
        execution.resume(a);f.second.time=f.second.time.copy(elapsedMillis=500,bootId="boot-two")
        val restored=f.controller()
        assertTrue(restored.state.value.focus!!.paused)
        assertEquals(60_000,restored.state.value.focus!!.accumulatedMillis)
    }
    @Test fun crashAfterHistoryAppendReplaysSameSessionWithoutDuplicateActualTime() {
        val f=fixture();val execution=f.controller();execution.startFocus(a,5);f.second.advance(61_000)
        f.third.failClear=true
        assertThrows(IOException::class.java) {execution.finish(a)}
        assertNotNull(f.third.saved!!.focus!!.pendingFinish)
        f.third.failClear=false
        val restored=f.controller()
        assertNull(restored.state.value.focus)
        assertEquals(9,f.first.task(a).content()!!["actualMinutes"]!!.jsonPrimitive.int)
        assertEquals(1,f.first.task(a).content()!!["progressSessions"]!!.jsonArray.size)
    }
    @Test fun cancelAndDeletedOrRemoteCompletedTaskNeverCreateHistoryOrResurrect() {
        val f=fixture();val execution=f.controller();execution.startFocus(a,5);execution.cancel(a)
        assertNull(f.first.task(a).content()!!["progressSessions"])
        execution.startFocus(a,5);execution.startExternal(b)
        f.first.edit {s ->
            var n=StateMachine.edit(s,"task",a,null,true)
            val old=n.items.getValue("task:$b")
            val remotePayload=JsonObject(old.payload!!.toMutableMap().apply {
                put("content",JsonObject(old.content()!!.toMutableMap().apply {put("completed",JsonPrimitive(true))}))
            })
            n.copy(items=n.items+(old.key to old.copy(conflict=Remote("task",b,2,payload=remotePayload,device="another-device",sequence=2))))
        }
        execution.reconcile()
        assertNull(execution.state.value.focus);assertTrue(execution.state.value.external.isEmpty())
        assertTrue(f.first.task(a).deleted);assertNull(f.first.task(b).content()!!["parallelRunSessions"])
    }
    @Test fun failedLocalSaveNeverPublishesUnpersistedRunningState() {
        val f=fixture();val broken=object:ExecutionPersistence {
            override fun load():ExecutionSnapshot?=null
            override fun save(snapshot:ExecutionSnapshot) {if(snapshot.focus!=null) throw IOException("disk full")}
        }
        val execution=ExecutionController(f.first,broken,f.second);execution.initialize()
        assertThrows(IOException::class.java) {execution.startFocus(a,5)}
        assertNull(execution.state.value.focus)
    }
    @Test fun runningReminderDeadlineTracksWallClockChangesWithoutMovingOnEveryLateRead() {
        val f=fixture();val execution=f.controller();execution.startFocus(a,5)
        val run=execution.state.value.focus!!
        val original=f.second.time.wallMillis
        f.second.advance(360_000)
        f.second.time=f.second.time.copy(wallMillis=f.second.time.wallMillis+3_600_000)
        assertEquals(original+3_900_000,run.currentFocusDeadlineMillis(f.second.time))
        f.second.advance(60_000)
        assertEquals(original+3_900_000,run.currentFocusDeadlineMillis(f.second.time))
        assertNull(run.currentFocusDeadlineMillis(f.second.time.copy(bootId="new-boot")))
        execution.reconcile()
        assertEquals(original+3_900_000,execution.state.value.focus!!.currentFocusDeadlineMillis(f.second.time))
    }
    @Test fun invalidPersistedLedgerFailsWithoutSilentlyClearingEvidence() {
        val f=fixture();f.controller().startFocus(a,5)
        val run=f.third.saved!!.focus!!
        f.third.saved=f.third.saved!!.copy(external=listOf(run.copy(id=uid(),targetMinutes=null)))
        val corrupt=f.third.saved
        assertThrows(IllegalArgumentException::class.java) {f.controller()}
        assertEquals(corrupt,f.third.saved)
    }
    @Test fun reminderGenerationChangesOnlyForExplicitResumeNotCheckpointOrDeadline() {
        val f=fixture();val execution=f.controller();execution.startFocus(a,5)
        val generation=execution.state.value.focus!!.deadlineGeneration
        f.second.advance(1_000);execution.reconcile()
        assertEquals(generation,execution.state.value.focus!!.deadlineGeneration)
        execution.pause(a);execution.resume(a)
        assertEquals(generation+1,execution.state.value.focus!!.deadlineGeneration)
        f.second.advance(300_000);execution.reconcile()
        assertEquals(generation+1,execution.state.value.focus!!.deadlineGeneration)
        assertTrue(execution.state.value.focus!!.due)
    }

    @Test(timeout=10_000) fun startingFocusCannotSlipBetweenArchiveCheckAndCommit() {
        val f=fixture();val execution=f.controller()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val startAttempt=CountDownLatch(1);val started=CountDownLatch(1)
        val archiveError=AtomicReference<Throwable?>();val startError=AtomicReference<Throwable?>()
        val archive=Thread {
            try {
                WorkspaceChanges.edit(f.first,execution,true) {before ->
                    entered.countDown()
                    check(release.await(5,TimeUnit.SECONDS)) {"test archive gate timed out"}
                    LibraryActions.archive(before,a,LibraryBaseline.from(before.items.getValue("task:$a")))
                }
            } catch(error:Throwable) {archiveError.set(error)}
        }
        val start=Thread {
            startAttempt.countDown()
            try {execution.startFocus(a,5)} catch(error:Throwable) {startError.set(error)}
            finally {started.countDown()}
        }
        try {
            archive.start();assertTrue(entered.await(3,TimeUnit.SECONDS))
            start.start();assertTrue(startAttempt.await(3,TimeUnit.SECONDS))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(start.state!=Thread.State.BLOCKED && started.count>0 && System.nanoTime()<deadline) Thread.yield()
            assertEquals("The start must wait on the same execution monitor until archive commits",Thread.State.BLOCKED,start.state)
            assertEquals(1L,started.count)
        } finally {
            release.countDown();archive.join(3_000);if(start.state!=Thread.State.NEW)start.join(3_000)
        }
        assertFalse(archive.isAlive);assertFalse(start.isAlive);assertNull(archiveError.get())
        assertTrue(startError.get() is IllegalStateException)
        assertEquals(JsonPrimitive("archived"),f.first.task(a).payload?.get("lifecycle"))
        assertNull(execution.state.value.focus);assertNull(f.third.saved?.focus)
        assertEquals(7,f.first.task(a).content()?.get("actualMinutes")?.jsonPrimitive?.int)
    }
}
