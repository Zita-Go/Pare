package com.step.desktop

import androidx.compose.ui.test.*
import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the recovery button on a persisted, partly downloaded fictional library. */
@OptIn(ExperimentalTestApi::class)
class DesktopSyncPreviewUiTest {
    @Test fun resumedPreviewKeepsLocalEditsAndRequiresConsentBeforeUploading() = runDesktopComposeUiTest(width=1280,height=900) {
        val home=Files.createTempDirectory("step-preview-resume-")
        val protector=TestProtector()
        val session=Session("fixture-owner","fixture@example.invalid","fictional-access","fictional-refresh",System.currentTimeMillis()/1000+3600)
        val localId=id(999)
        DesktopStore(home).use {store->
            store.update {original->
                val bound=StateMachine.bind(original,session.owner).copy(enabled=false)
                val downloaded=(1..153).fold(bound) {current,n->
                    StateMachine.pull(current,Page(1,n.toLong(),true,listOf(Commit(n.toLong(),"fixture-page-$n","fixture-cloud",
                        listOf(Remote("task",id(n),1,payload=fictionalTask(id(n)),device="fixture-cloud",sequence=n.toLong()))))))
                }
                StateMachine.edit(downloaded,"task",localId,fictionalTask(localId))
            }
        }
        DesktopSessionVault(home,protector).use {it.save(session)}
        val pushes=AtomicInteger();val pulls=mutableListOf<Long>()
        val remote=object:SyncRemote {
            override suspend fun login(email:String,password:String,signup:Boolean):Session?=error("Must resume without logging in again")
            override suspend fun refresh(session:Session):Session=error("Fixture session is current")
            override suspend fun pull(session:Session,cursor:Long):Page {
                synchronized(pulls){pulls.add(cursor)}
                return if(cursor==153L) Page(1,154,false,listOf(Commit(154,"fixture-last-page","fixture-cloud",
                    listOf(Remote("task",id(154),1,payload=fictionalTask(id(154)),device="fixture-cloud",sequence=154)))))
                else Page(1,cursor,false,emptyList())
            }
            override suspend fun push(session:Session,request:Push):Reply {
                pushes.incrementAndGet()
                return Reply(1,"applied",155,request.mutations.map {Remote(it.kind,it.id,1,payload=it.payload,device=request.device,sequence=155)})
            }
            override suspend fun logout(session:Session)=Unit
        }
        DesktopRuntime(home,DesktopConfig(),{_,_->},protector,remote,FixtureClock(),MobileAIClient()).use {runtime->
            runtime.initialize()
            setContent {DesktopTheme {SettingsUi(runtime,{}, {})}}
            onNodeWithText("确认合并并开启同步").performScrollTo().assertIsNotEnabled()
            onNodeWithText("继续拉取云端内容").performScrollTo().assertIsEnabled().performClick()
            waitUntil(timeoutMillis=5_000){runtime.engine.previewReady.value && !runtime.engine.busy.value}
            waitForIdle()
            assertEquals(153L,synchronized(pulls){pulls.first()})
            assertEquals(154L,runtime.engine.state.value.cursor)
            assertEquals(155,runtime.engine.state.value.items.size)
            assertTrue(runtime.engine.state.value.items.getValue("task:$localId").dirty)
            assertEquals(0,pushes.get())
            assertFalse(runtime.engine.state.value.enabled)
            onNodeWithText("确认合并并开启同步").performScrollTo().assertIsEnabled().performClick()
            waitUntil(timeoutMillis=5_000){runtime.engine.state.value.enabled}
            assertEquals(session.owner,runtime.engine.state.value.owner)
        }
    }

    private fun id(n:Int)="00000000-0000-4000-8000-"+n.toString().padStart(12,'0')
}
