package com.step.desktop

import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DesktopPersistenceTest {
    @Test fun completeSnapshotRetainsPendingCursorConflictsAndUnknownFields() {
        val home=Files.createTempDirectory("step-store-test")
        val payload=buildJsonObject {put("futureField","fictional")}
        val entity=Entity("task","fictional-task",payload,revision=4,dirty=true,
            conflict=Remote("task","fictional-task",5,payload=payload,device="other",sequence=9),group="edit-group")
        val initial=SyncState(owner="fictional-owner",device="fictional-device",cursor=9,
            items=mapOf(entity.key to entity),pending=Push("pending-operation","fictional-device",listOf(Mutation("task",entity.id,4,payload=payload))),enabled=true)
        DesktopStore(home).use {it.update {initial}}
        DesktopStore(home).use {assertEquals(initial,it.read())}
    }

    @Test fun corruptPrimaryDoesNotResetOrOverwriteRecoveryBackup() {
        val home=Files.createTempDirectory("step-corrupt-test")
        DesktopStore(home).use {store->store.update {it.copy(cursor=2)};store.update {it.copy(cursor=3)}}
        val backup=Files.readAllBytes(home.resolve("sync-state.json.bak"))
        Files.writeString(home.resolve("sync-state.json"),"{broken")
        DesktopStore(home).use {store->
            assertThrows(Exception::class.java) {store.read()}
            assertThrows(Exception::class.java) {store.update {SyncState()}}
        }
        assertEquals("{broken",Files.readString(home.resolve("sync-state.json")))
        assertArrayEquals(backup,Files.readAllBytes(home.resolve("sync-state.json.bak")))
    }

    @Test fun secondWriterIsRejectedUntilFirstCloses() {
        val home=Files.createTempDirectory("step-lock-test")
        DesktopStore(home).use {assertThrows(IllegalStateException::class.java) {DesktopStore(home)}}
        DesktopStore(home).use {assertNotNull(it.read())}
    }

    @Test fun failedAtomicWriteDoesNotPublishUnsavedState() {
        val home=Files.createTempDirectory("step-write-test")
        DesktopStore(home).use {store->
            val initial=store.read()
            Files.createDirectory(home.resolve("sync-state.json.bak"))
            Files.writeString(home.resolve("sync-state.json.bak/blocked"),"fixture")
            assertThrows(Exception::class.java) {store.update {it.copy(cursor=8)}}
            assertEquals(initial,store.snapshots.value)
        }
    }
}
