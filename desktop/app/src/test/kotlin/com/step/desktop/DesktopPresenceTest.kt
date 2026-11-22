package com.step.desktop

import com.pare.app.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlinx.coroutines.*

class DesktopPresenceTest {
    @Test fun sharingIsScopedToIdentityAndSequenceSurvivesRestart() {
        val home=Files.createTempDirectory("step-presence-ledger")
        val first=DesktopPresenceStore(home)
        assertFalse(first.enabled("owner-a","device-a"))
        first.set("owner-a","device-a",true)
        assertFalse(first.enabled("owner-b","device-a"))
        assertFalse(first.enabled("owner-a","device-b"))
        assertEquals(1L,first.next("owner-a","device-a"))
        first.advance("owner-a","device-a",17)
        val reopened=DesktopPresenceStore(home)
        assertTrue(reopened.enabled("owner-a","device-a"))
        assertEquals(18L,reopened.next("owner-a","device-a"))
    }
    @Test fun runtimeDoesNotPublishUntilOptInAndUsesWindowsIdentity()=runBlocking {
        val home=Files.createTempDirectory("step-presence-runtime");val protector=TestProtector()
        val owner="70000000-0000-0000-0000-000000000001"
        DesktopStore(home).use {it.update {s->s.copy(owner=owner,enabled=true)}}
        DesktopSessionVault(home,protector).save(Session(owner,"fictional@example.invalid","fictional-access","fictional-refresh",4_000_000_000))
        val requests=java.util.concurrent.CopyOnWriteArrayList<DevicePresenceRequest>()
        val remote=object:SyncRemote by NoNetwork {
            override suspend fun pull(session:Session,cursor:Long)=Page(1,cursor,false,emptyList())
            override suspend fun presence(session:Session,request:DevicePresenceRequest):DevicePresenceReply {
                requests+=request
                val now=java.time.Instant.now()
                return DevicePresenceReply(1,now.toString(),request.sequence,listOf(DevicePresenceDevice(request.deviceId,request.sequence,request.label,now.toString(),now.plusSeconds(90).toString(),request.runs)))
            }
        }
        DesktopRuntime(home,DesktopConfig(),{_,_->},protector,remote,FixtureClock(),MobileAIClient()).use {r->
            r.initialize();r.presence.poll(r.execution.state.value);assertTrue(requests.isEmpty())
            r.setPresenceSharing(true).getOrThrow()
            withTimeout(2_000) {while(requests.isEmpty())delay(10)}
            assertEquals("Windows",requests.first().label)
            r.setPresenceSharing(false).getOrThrow()
            assertFalse(r.presenceSharingEnabled.value)
        }
    }
}
