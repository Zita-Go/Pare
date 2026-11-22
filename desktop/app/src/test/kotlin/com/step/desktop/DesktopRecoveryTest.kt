package com.step.desktop

import com.pare.app.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopRecoveryTest {
    private val id="00000000-0000-0000-0000-000000000001"
    private fun runtime(home:Path)=DesktopRuntime(home,DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient())
    @Test fun largeLegalBackupDoesNotAllocateATailCopyForEveryString() {
        val memory=java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.Assume.assumeTrue(memory!=null && memory.isThreadAllocatedMemorySupported)
        val meter=requireNotNull(memory);if(!meter.isThreadAllocatedMemoryEnabled)meter.isThreadAllocatedMemoryEnabled=true
        val source=Files.createTempFile("step-large-backup",".json")
        val items=(1..500).map {number->
            val taskId="00000000-0000-0000-0000-"+number.toString().padStart(12,'0')
            val payload=fictionalTask(taskId)
            buildJsonObject {
                put("kind","task");put("id",taskId);put("deleted",false)
                put("payload",JsonObject(payload.toMutableMap().apply {put("content",JsonObject(payload.getValue("content").jsonObject.toMutableMap().apply {put("note",JsonPrimitive("fictional detail ".repeat(160)))}))}))
            }
        }
        Files.writeString(source,buildJsonObject {put("format","step-content-backup");put("version",1);put("entities",JsonArray(items));put("localTrash",JsonArray(emptyList()))}.toString())
        assertTrue(Files.size(source)>1_000_000)
        val thread=Thread.currentThread().id;val before=meter.getThreadAllocatedBytes(thread)
        val preview=DesktopBackup.preview(source,SyncState(device="fictional-device"))
        val allocated=meter.getThreadAllocatedBytes(thread)-before
        assertEquals(500,preview.entityCount)
        assertTrue("Large backup allocated $allocated bytes for ${Files.size(source)} bytes of content",allocated<Files.size(source)*64+32*1024*1024)
    }
    @Test fun deletionDoesNotSilentlyAcceptAnotherEffortEditDuringFinish()=runBlocking {
        val home=Files.createTempDirectory("step-lifecycle-race")
        val disk=DesktopExecutionPersistence(home);lateinit var r:DesktopRuntime;var injected=false
        val persistence=object:ExecutionPersistence {
            override fun load()=disk.load()
            override fun save(snapshot:ExecutionSnapshot) {
                if(snapshot.focus?.pendingFinish!=null && !injected) {
                    injected=true
                    r.engine.edit {state->val task=state.items.getValue("task:$id")
                        StateMachine.edit(state,"task",id,JsonObject(task.payload!!.toMutableMap().apply {
                            put("content",JsonObject(task.content()!!.toMutableMap().apply {put("actualMinutes",JsonPrimitive(100))}))
                        }))
                    }
                }
                disk.save(snapshot)
            }
        }
        r=DesktopRuntime(home,DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient(),persistence)
        r.use {
            r.initialize();r.edit {StateMachine.edit(it,"task",id,fictionalTask(id))}.getOrThrow();r.execute {it.startFocus(id)}.getOrThrow()
            assertTrue(r.deleteContent("task",id,LibraryBaseline.from(r.engine.state.value.items.getValue("task:$id"))).isFailure)
            assertFalse(r.engine.state.value.items.getValue("task:$id").deleted)
            assertEquals(101,r.engine.state.value.items.getValue("task:$id").content()!!["actualMinutes"]!!.jsonPrimitive.int)
        }
    }
    @Test fun archiveFinishesRunAndDeleteRetainsRecoverableEffort()=runBlocking {
        runtime(Files.createTempDirectory("step-lifecycle")).use {r->
            r.initialize();r.edit {StateMachine.edit(it,"task",id,fictionalTask(id))}.getOrThrow()
            r.execute {it.startFocus(id,5)}.getOrThrow()
            r.archiveTask(id,LibraryBaseline.from(r.engine.state.value.items.getValue("task:$id"))).getOrThrow()
            val archived=r.engine.state.value.items.getValue("task:$id")
            assertEquals("archived",archived.payload!!["lifecycle"]!!.jsonPrimitive.content)
            assertEquals(1,archived.content()!!["progressSessions"]!!.jsonArray.size)
            assertNull(r.execution.state.value.runFor(id))
            r.deleteContent("task",id,LibraryBaseline.from(archived)).getOrThrow()
            assertTrue(r.engine.state.value.items.getValue("task:$id").deleted)
            assertEquals(1,r.engine.state.value.localTrash.single().before.content()!!["progressSessions"]!!.jsonArray.size)
        }
    }
    @Test fun importingValidatedBackupPreservesLocalIdentityAndCreatesRollback()=runBlocking {
        val source=Files.createTempFile("step-import",".json")
        runtime(Files.createTempDirectory("step-export")).use {r->
            r.initialize();r.edit {StateMachine.edit(it,"task",id,fictionalTask(id))}.getOrThrow();r.exportBackup(source).getOrThrow()
        }
        val home=Files.createTempDirectory("step-import-target")
        runtime(home).use {r->
            r.initialize();val identity=r.engine.state.value.device
            val preview=r.previewBackup(source).getOrThrow()
            assertEquals(1,preview.newCount)
            r.applyBackup(preview).getOrThrow()
            assertEquals(identity,r.engine.state.value.device)
            assertTrue(r.engine.state.value.items.getValue("task:$id").dirty)
            assertTrue(Files.list(home).use {it.anyMatch {p->p.fileName.toString().startsWith("sync-rollback-")}})
        }
    }
    @Test fun importCanRestorePreviousContentThroughTheSameReviewedImportPath()=runBlocking {
        val file=Files.createTempFile("step-roundtrip-import",".json")
        val added="00000000-0000-0000-0000-000000000002"
        val trashed="00000000-0000-0000-0000-000000000003"
        runtime(Files.createTempDirectory("step-roundtrip-source")).use {source->
            source.initialize()
            source.edit {state->
                var next=state
                for(taskId in listOf(id,added,trashed))next=StateMachine.edit(next,"task",taskId,fictionalTask(taskId))
                val task=next.items.getValue("task:$id")
                next=StateMachine.edit(next,"task",id,JsonObject(task.payload!!.toMutableMap().apply {
                    put("content",JsonObject(task.content()!!.toMutableMap().apply {put("text",JsonPrimitive("Imported replacement"))}))
                }))
                LibraryActions.delete(next,"task",trashed,LibraryBaseline.from(next.items.getValue("task:$trashed")))
            }.getOrThrow()
            source.exportBackup(file).getOrThrow()
        }
        val home=Files.createTempDirectory("step-roundtrip-target")
        lateinit var restorePath:Path
        runtime(home).use {target->
            target.initialize();target.edit {StateMachine.edit(it,"task",id,fictionalTask(id))}.getOrThrow()
            target.applyBackup(target.previewBackup(file).getOrThrow()).getOrThrow()
            assertEquals("Imported replacement",target.engine.state.value.items.getValue("task:$id").title())
            assertEquals(1,target.engine.state.value.localTrash.size)
            restorePath=requireNotNull(target.lastImportBackup)
        }
        runtime(home).use {target->
            target.initialize();assertEquals(restorePath,target.lastImportBackup)
            val preview=target.previewBackup(restorePath).getOrThrow()
            assertTrue(preview.restoresPreviousImport)
            target.edit {it.copy(cursor=1)}.getOrThrow()
            assertTrue("Recovery previews must not bypass stale-state protection",target.applyBackup(preview).isFailure)
            target.applyBackup(target.previewBackup(restorePath).getOrThrow()).getOrThrow()
            assertEquals("Fictional test task",target.engine.state.value.items.getValue("task:$id").title())
            assertTrue(target.engine.state.value.items.getValue("task:$added").deleted)
            assertEquals("Recovery conservatively retains the trash brought in by the import",1,target.engine.state.value.localTrash.size)
            assertEquals(1L,target.engine.state.value.cursor)
        }
    }
    @Test fun recoveryRetainsLaterUserDeletionsAndTheirRestorationReceipts()=runBlocking {
        val source=Files.createTempFile("step-preserve-trash",".json")
        val home=Files.createTempDirectory("step-preserve-later-trash")
        val later="00000000-0000-0000-0000-000000000004"
        val copied="00000000-0000-0000-0000-000000000005"
        runtime(home).use {r->
            r.initialize();r.edit {StateMachine.edit(it,"task",id,fictionalTask(id))}.getOrThrow()
            r.exportBackup(source).getOrThrow()
            r.applyBackup(r.previewBackup(source).getOrThrow()).getOrThrow()
            val restore=requireNotNull(r.lastImportBackup)
            r.edit {state->
                var next=state
                for(taskId in listOf(later,copied)) {
                    next=StateMachine.edit(next,"task",taskId,fictionalTask(taskId))
                    next=LibraryActions.delete(next,"task",taskId,LibraryBaseline.from(next.items.getValue("task:$taskId")))
                }
                LocalTrashActions.restoreCopy(next,next.localTrash.single {it.before.id==copied}.id)
            }.getOrThrow()
            val retained=r.engine.state.value.localTrash
            val receipt=retained.single {it.before.id==copied}
            assertNotNull(receipt.restoredKey)
            r.applyBackup(r.previewBackup(restore).getOrThrow()).getOrThrow()
            assertEquals("Recovery must retain post-import trash and restoration receipts",retained,r.engine.state.value.localTrash)
            assertTrue(r.engine.state.value.items.getValue("task:$later").deleted)
            assertFalse(r.engine.state.value.items.getValue(receipt.restoredKey!!).deleted)
            r.edit {LocalTrashActions.restoreOriginal(it,retained.single {entry->entry.before.id==later}.id)}.getOrThrow()
            assertFalse(r.engine.state.value.items.getValue("task:$later").deleted)
        }
    }
    @Test fun failedImportNeverPublishesItsRecoveryPointIncludingAfterRestart()=runBlocking {
        val first=Files.createTempFile("step-successful-import",".json")
        val second=Files.createTempFile("step-failed-import",".json")
        runtime(Files.createTempDirectory("step-import-fixtures")).use {source->
            source.initialize();source.edit {StateMachine.edit(it,"task",id,fictionalTask(id))}.getOrThrow();source.exportBackup(first).getOrThrow()
            source.edit {state->val task=state.items.getValue("task:$id")
                StateMachine.edit(state,"task",id,JsonObject(task.payload!!.toMutableMap().apply {
                    put("content",JsonObject(task.content()!!.toMutableMap().apply {put("text",JsonPrimitive("Must never commit"))}))
                }))
            }.getOrThrow()
            source.exportBackup(second).getOrThrow()
        }
        val home=Files.createTempDirectory("step-recovery-point-failure")
        lateinit var successfulPoint:Path
        runtime(home).use {r->
            r.initialize();r.applyBackup(r.previewBackup(first).getOrThrow()).getOrThrow()
            successfulPoint=requireNotNull(r.lastImportBackup)
            val saved=r.engine.state.value
            val primaryBytes=Files.readAllBytes(home.resolve("sync-state.json"))
            val preview=r.previewBackup(second).getOrThrow()
            val blocked=home.resolve("sync-state.json.bak")
            Files.deleteIfExists(blocked);Files.createDirectory(blocked);Files.writeString(blocked.resolve("blocker"),"fictional obstruction")
            assertTrue(r.applyBackup(preview).isFailure)
            assertEquals(saved,r.engine.state.value)
            assertArrayEquals(primaryBytes,Files.readAllBytes(home.resolve("sync-state.json")))
            assertEquals("A failed import cannot replace the last successful recovery point",successfulPoint,r.lastImportBackup)
            assertEquals(successfulPoint,DesktopBackup.latestBeforeImport(home))
        }
        // A crash or failed cleanup may leave a prepared file; it is still not a committed point.
        val retained=DesktopStore(home).use {it.read()}
        val orphan=DesktopBackup.prepareBeforeImport(home,retained,retained)
        Files.setLastModifiedTime(orphan,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()+60_000))
        runtime(home).use {r->r.initialize();assertEquals(successfulPoint,r.lastImportBackup)}
        assertTrue(Files.exists(orphan))
    }
    @Test fun staleImportCannotOverwriteNewLocalEditsOrChangedSource()=runBlocking {
        val source=Files.createTempFile("step-import-stale",".json")
        runtime(Files.createTempDirectory("step-import-current")).use {r->
            r.initialize();r.edit {StateMachine.edit(it,"task",id,fictionalTask(id))}.getOrThrow();r.exportBackup(source).getOrThrow()
            val preview=r.previewBackup(source).getOrThrow()
            r.edit {it.copy(cursor=1)}.getOrThrow()
            assertTrue(r.applyBackup(preview).isFailure)
            val newer=r.previewBackup(source).getOrThrow()
            Files.writeString(source,"{}")
            assertTrue(r.applyBackup(newer).isFailure)
            assertEquals(1L,r.engine.state.value.cursor)
        }
    }
    @Test fun importRejectsDuplicateEntitiesAndCredentialEnvelopes()=runBlocking {
        val source=Files.createTempFile("step-import-invalid",".json")
        runtime(Files.createTempDirectory("step-invalid-target")).use {r->
            r.initialize()
            Files.writeString(source,"""{"format":"step-content-backup","version":1,"entities":[],"localTrash":[],"session":{"accessToken":"fictional"}}""")
            assertTrue(r.previewBackup(source).isFailure)
            val item=buildJsonObject {put("kind","task");put("id",id);put("deleted",false);put("payload",fictionalTask(id))}
            Files.writeString(source,buildJsonObject {put("format","step-content-backup");put("version",1);put("entities",JsonArray(listOf(item,item)));put("localTrash",JsonArray(emptyList()))}.toString())
            assertTrue(r.previewBackup(source).isFailure)
            val invalidTombstone=buildJsonObject {put("kind","profile");put("id","not-the-profile");put("deleted",true);put("payload",JsonNull)}
            Files.writeString(source,buildJsonObject {put("format","step-content-backup");put("version",1);put("entities",JsonArray(listOf(invalidTombstone)));put("localTrash",JsonArray(emptyList()))}.toString())
            assertTrue("Deleted content must still obey kind-specific identity rules",r.previewBackup(source).isFailure)
        }
    }
    @Test fun corruptSnapshotRecoveryRetainsDamagedBytesAndRestoresBackup()=runBlocking {
        val home=Files.createTempDirectory("step-recover")
        DesktopStore(home).use {store->store.update {it.copy(cursor=4)};store.update {it.copy(cursor=5)}}
        Files.writeString(home.resolve("sync-state.json"),"fictional-corruption")
        runtime(home).use {r->
            r.initialize();assertFalse(r.engine.initialized.value)
            r.recoverAutomaticBackup().getOrThrow()
            assertTrue(r.engine.initialized.value);assertEquals(4L,r.engine.state.value.cursor)
            assertTrue(Files.list(home).use {it.filter {p->p.fileName.toString().startsWith("sync-damaged-")}.anyMatch {p->Files.readString(p)=="fictional-corruption"}})
        }
    }
}
