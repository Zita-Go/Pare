package com.step.desktop

import com.pare.app.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class FixtureClock:ExecutionClock {
    var time=ExecutionMoment(1_793_318_400_000,10_000,"fictional-process")
    override fun now()=time
}
internal object NoNetwork:SyncRemote {
    override suspend fun login(email:String,password:String,signup:Boolean):Session?=error("Network forbidden")
    override suspend fun refresh(session:Session):Session=error("Network forbidden")
    override suspend fun pull(session:Session,cursor:Long):Page=error("Network forbidden")
    override suspend fun push(session:Session,request:Push):Reply=error("Network forbidden")
    override suspend fun logout(session:Session)=Unit
}
private const val taskId="00000000-0000-0000-0000-000000000001"
internal fun fictionalTask(id:String=taskId,remindAt:String?=null)=buildJsonObject {
    put("lifecycle","active");put("content",buildJsonObject {
        put("id",id);put("text","Fictional test task");put("createdAt","2026-10-30T00:00:00Z")
        put("quadrant",1);put("completed",false)
        remindAt?.let {put("remindAt",it)}
    })
}

class DesktopRuntimeTest {
    @Test fun closeRetainsExclusiveLockUntilBlockedInitializationStopsWriting() {
        val home=Files.createTempDirectory("step-start-close-test")
        val shared=DesktopStore(home).use {store->store.update {StateMachine.edit(it,"task",taskId,fictionalTask())}}
        val clock=FixtureClock()
        val disk=DesktopExecutionPersistence(home)
        disk.save(ExecutionSnapshot(device=shared.device,focus=ExecutionRun(
            "00000000-0000-0000-0000-000000000002",taskId,clock.time.wallMillis,
            accumulatedMillis=30_000,anchor=clock.time.copy(bootId="previous-process"),targetMinutes=5)))
        val loading=CountDownLatch(1);val releaseLoad=CountDownLatch(1)
        val closing=CountDownLatch(1);val closed=CountDownLatch(1)
        val persistence=object:ExecutionPersistence {
            override fun load():ExecutionSnapshot? {
                loading.countDown()
                check(releaseLoad.await(5,TimeUnit.SECONDS)) {"Fixture release timed out"}
                return disk.load()
            }
            override fun save(snapshot:ExecutionSnapshot) {
                check(closed.count!=0L) {"Initialization wrote after close completed"}
                disk.save(snapshot)
            }
        }
        val runtime=DesktopRuntime(home,DesktopConfig(),{_,_->},TestProtector(),NoNetwork,clock,MobileAIClient(),persistence)
        val workers=Executors.newFixedThreadPool(2)
        val startup=workers.submit {runBlocking {runtime.initialize()}}
        try {
            assertTrue(loading.await(3,TimeUnit.SECONDS))
            val shutdown=workers.submit {closing.countDown();runtime.close();closed.countDown()}
            assertTrue(closing.await(3,TimeUnit.SECONDS))
            assertFalse("Close must wait for the blocked initialization before unlocking",closed.await(250,TimeUnit.MILLISECONDS))
            assertThrows(IllegalStateException::class.java) {DesktopStore(home).close()}
            releaseLoad.countDown()
            shutdown.get(3,TimeUnit.SECONDS)
            val cancellation=runCatching {startup.get(3,TimeUnit.SECONDS)}.exceptionOrNull()
            assertTrue(cancellation?.cause is kotlinx.coroutines.CancellationException)
            assertNull(runtime.error.value)
            DesktopStore(home).use {assertEquals(shared.device,it.read().device)}
            assertTrue(disk.load()!!.focus!!.paused)
        } finally {
            releaseLoad.countDown()
            runCatching {startup.get(3,TimeUnit.SECONDS)}
            runtime.close();workers.shutdownNow()
        }
    }
    @Test fun enabledRuntimeAutomaticallyCommitsOfflineQueueAndCursorTogether()=runBlocking {
        val home=Files.createTempDirectory("step-auto-sync-test");val protector=TestProtector()
        val session=Session("fictional-owner","fictional@example.invalid","fictional-token","fictional-refresh",4_000_000_000)
        DesktopStore(home).use {store->store.update {
            StateMachine.edit(it.copy(owner=session.owner,enabled=true),"task",taskId,fictionalTask())
        }}
        DesktopSessionVault(home,protector).save(session)
        val remote=object:SyncRemote {
            var commit:Commit?=null
            override suspend fun login(email:String,password:String,signup:Boolean):Session?=error("not requested")
            override suspend fun refresh(session:Session)=error("not needed")
            override suspend fun pull(session:Session,cursor:Long):Page {
                val next=commit?.takeIf {it.sequence>cursor}
                return Page(1,next?.sequence ?: cursor,false,listOfNotNull(next))
            }
            override suspend fun push(session:Session,request:Push):Reply {
                val entities=request.mutations.map {Remote(it.kind,it.id,it.baseRevision+1,payload=it.payload,device=request.device,sequence=1)}
                commit=Commit(1,request.operation,request.device,entities)
                return Reply(1,"applied",1,entities)
            }
            override suspend fun logout(session:Session)=Unit
        }
        DesktopRuntime(home,DesktopConfig(),{_,_->},protector,remote,FixtureClock(),MobileAIClient()).use {r->
            r.initialize()
            withTimeout(3_000) {while(r.engine.state.value.cursor!=1L) delay(10)}
            assertFalse(r.engine.state.value.items.getValue("task:$taskId").dirty)
        }
        DesktopStore(home).use {store->
            assertEquals(1L,store.read().cursor);assertNull(store.read().pending)
            assertFalse(store.read().items.getValue("task:$taskId").dirty)
        }
    }
    @Test fun abruptRestartPausesFocusWithoutCountingOfflineTime()=runBlocking {
        val home=Files.createTempDirectory("step-abrupt-restart-test")
        val clock=FixtureClock()
        DesktopStore(home).use {it.update {s->StateMachine.edit(s,"task",taskId,fictionalTask())}}
        val shared=DesktopStore(home).use {it.read()}
        DesktopExecutionPersistence(home).save(ExecutionSnapshot(device=shared.device,
            focus=ExecutionRun("00000000-0000-0000-0000-000000000002",taskId,clock.time.wallMillis,
                accumulatedMillis=30_000,anchor=clock.time,targetMinutes=5)))
        clock.time=clock.time.copy(wallMillis=clock.time.wallMillis+3_600_000,elapsedMillis=1_000,bootId="next-process")
        DesktopRuntime(home,DesktopConfig(),{_,_->},TestProtector(),NoNetwork,clock,MobileAIClient()).use {r->
            r.initialize()
            assertTrue(r.execution.state.value.focus!!.paused)
            assertEquals(30_000L,r.execution.state.value.focus!!.elapsedMillis(clock.now()))
        }
    }
    @Test fun restartPausesFocusAndDoesNotCountTimeOutsideProcess()=runBlocking {
        val home=Files.createTempDirectory("step-restart-test");val protector=TestProtector();val clock=FixtureClock()
        fun runtime()=DesktopRuntime(home,DesktopConfig(),{_,_->},protector,NoNetwork,clock,MobileAIClient())
        runtime().use {r->
            r.initialize();r.edit {StateMachine.edit(it,"task",taskId,fictionalTask())}.getOrThrow()
            r.execute {it.startFocus(taskId,5)}.getOrThrow()
            clock.time=clock.time.copy(wallMillis=clock.time.wallMillis+60_000,elapsedMillis=70_000)
        }
        clock.time=clock.time.copy(wallMillis=clock.time.wallMillis+3_600_000,elapsedMillis=3_670_000,bootId="next-process")
        runtime().use {r->r.initialize();assertTrue(r.execution.state.value.focus!!.paused)
            assertEquals(60_000L,r.execution.state.value.focus!!.elapsedMillis(clock.now()))}
    }

    @Test fun reminderDeliveredOnceAcrossProcessRestart() {
        val home=Files.createTempDirectory("step-reminder-test");val clock=FixtureClock()
        val time=clock.now().wallMillis
        val state=StateMachine.edit(SyncState(device="test"),"task",taskId,fictionalTask(remindAt=Instant.ofEpochMilli(time+1_000).toString()))
        val events=mutableListOf<String>()
        val reminders=DesktopReminders(home) {title,_->events.add(title)}
        reminders.refresh(state,ExecutionSnapshot(device="test"),clock.now())
        clock.time=clock.time.copy(wallMillis=time+2_000)
        reminders.refresh(state,ExecutionSnapshot(device="test"),clock.now())
        DesktopReminders(home) {title,_->events.add(title)}.refresh(state,ExecutionSnapshot(device="test"),clock.now())
        assertEquals(1,events.size)
    }

    @Test fun focusDeadlineIsDeliveredOnceEvenWhenAlreadyDueAtRefresh() {
        val home=Files.createTempDirectory("step-focus-toast-test")
        val clock=FixtureClock();val deadline=clock.time.wallMillis-1_000
        val state=StateMachine.edit(SyncState(device="test"),"task",taskId,fictionalTask())
        val execution=ExecutionSnapshot(device="test",focus=ExecutionRun(
            "00000000-0000-0000-0000-000000000002",taskId,deadline-60_000,
            accumulatedMillis=60_000,targetMinutes=1,paused=true,due=true,deadlineAtMillis=deadline))
        val events=mutableListOf<String>()
        DesktopReminders(home) {title,_->events.add(title)}.refresh(state,execution,clock.now())
        DesktopReminders(home) {title,_->events.add(title)}.refresh(state,execution,clock.now())
        assertEquals(listOf("专注已到点"),events)
    }

    @Test fun malformedReminderLedgerIsPreservedRatherThanReset() {
        val home=Files.createTempDirectory("step-reminder-corruption-test")
        Files.writeString(home.resolve("reminders.json"),"{}")
        assertThrows(Exception::class.java) {
            DesktopReminders(home) {_,_->}.refresh(SyncState(),ExecutionSnapshot(),FixtureClock().now())
        }
        assertEquals("{}",Files.readString(home.resolve("reminders.json")))
    }

    @Test fun exportExcludesSessionAIAndPendingTransport()=runBlocking {
        val home=Files.createTempDirectory("step-export-test");val protector=TestProtector()
        DesktopRuntime(home,DesktopConfig(),{_,_->},protector,NoNetwork,FixtureClock(),MobileAIClient()).use {r->
            r.initialize();r.edit {StateMachine.edit(it,"task",taskId,fictionalTask())}.getOrThrow()
            r.saveAI(MobileAIConfig("https://fixture.invalid/chat","model"),"fictional-api-secret").getOrThrow()
            val destination=Files.createTempFile("step-export-test",".json")
            r.exportBackup(destination).getOrThrow()
            val json=Files.readString(destination)
            assertTrue(json.contains("Fictional test task"));assertFalse(json.contains("fictional-api-secret"))
            assertFalse(json.contains("refreshToken"));assertFalse(json.contains("pending"));assertFalse(json.contains("history"))
        }
    }

    @Test fun idleRuntimeNeverCallsAIAndExplicitGenerationUsesRedactedLocalHistory()=runBlocking {
        val home=Files.createTempDirectory("step-ai-request-test");var calls=0
        val client=MobileAIClient {_,_,body->
            calls++
            MobileAIResponse(500,"")
        }
        DesktopRuntime(home,DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),client).use {r->
            r.initialize();r.edit {StateMachine.edit(it,"task",taskId,fictionalTask())}.getOrThrow()
            r.saveAI(MobileAIConfig("https://fixture.invalid/chat","model"),"fictional-api-secret").getOrThrow()
            assertEquals(0,calls)
            val input=MobileAIContext.prepare(r.engine.state.value,taskId,MobileAIOptions(),emptyList())
            assertTrue(r.generateAI(input).isFailure);assertEquals(1,calls)
            assertEquals(1,r.aiHistory.value.size)
            assertFalse(r.aiHistory.value.single().input.contains("fictional-api-secret"))
        }
    }
}
