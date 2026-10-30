package com.pare.app

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class SyncStateTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private fun payload(text: String) = buildJsonObject { put("content", buildJsonObject { put("id", id); put("text", text); put("createdAt", "2026-10-30T00:00:00Z") }) }
    private fun remote(text: String, revision: Long = 1, sequence: Long = 1, device: String = "remote") = Remote("inbox", id, revision, payload = payload(text), device = device, sequence = sequence)
    @Test fun editsAndOutboxSurviveSerializationForLostResponseRetry() {
        val edited = StateMachine.edit(SyncState(), "inbox", id, payload("offline"))
        val prepared = StateMachine.prepare(edited)
        assertTrue(prepared.items.getValue("inbox:$id").dirty)
        assertNotNull(prepared.pending)
        val restored = wire.decodeFromString<SyncState>(wire.encodeToString(prepared))
        assertEquals(prepared.pending, StateMachine.prepare(restored).pending)
    }
    @Test fun editWhileRequestInFlightIsNotLostWhenReceiptArrives() {
        val sent = StateMachine.prepare(StateMachine.edit(SyncState(), "inbox", id, payload("old")))
        val newer = StateMachine.edit(sent, "inbox", id, payload("new"))
        val result = StateMachine.accept(newer, Reply(1,"applied",1,listOf(remote("old",device=sent.device))))
        assertEquals(payload("new"), result.items.getValue("inbox:$id").payload)
        assertTrue(result.items.getValue("inbox:$id").dirty)
        assertEquals(1L, StateMachine.prepare(result).pending!!.mutations.single().baseRevision)
    }
    @Test fun remoteChangesDoNotEchoIntoOutbox() {
        val result = StateMachine.pull(SyncState(), Page(1,1,false,listOf(Commit(1,"op","remote",listOf(remote("cloud"))))))
        assertEquals(payload("cloud"), result.items.getValue("inbox:$id").payload)
        assertNull(StateMachine.prepare(result).pending)
    }
    @Test fun concurrentChangesPreserveBothVersionsUntilExplicitChoice() {
        val edited = StateMachine.edit(SyncState(), "inbox", id, payload("local"))
        val result = StateMachine.pull(edited, Page(1,1,false,listOf(Commit(1,"op","remote",listOf(remote("cloud"))))))
        assertEquals(payload("local"), result.items.getValue("inbox:$id").payload)
        assertEquals(payload("cloud"), result.items.getValue("inbox:$id").conflict!!.payload)
        assertNull(StateMachine.prepare(result).pending)
        val resolved = StateMachine.resolve(result,"inbox:$id",false)
        assertEquals(payload("cloud"), resolved.items.getValue("inbox:$id").payload)
        assertFalse(resolved.items.getValue("inbox:$id").dirty)
    }
    @Test fun deletionConflictCannotResurrectWithoutExplicitChoice() {
        val edited = StateMachine.edit(SyncState(), "inbox", id, payload("local"))
        val tombstone = remote("cloud").copy(payload=null, deletedAt="2026-10-30T00:00:00Z")
        val result = StateMachine.pull(edited, Page(1,1,false,listOf(Commit(1,"op","remote",listOf(tombstone)))))
        assertNull(StateMachine.prepare(result).pending)
        assertTrue(StateMachine.resolve(result,"inbox:$id",false).items.getValue("inbox:$id").deleted)
    }
    @Test fun foreignAccountCannotReuseLocalQueue() {
        val bound = StateMachine.bind(SyncState(),"account-a")
        assertThrows(IllegalArgumentException::class.java) { StateMachine.bind(bound,"account-b") }
    }
    @Test fun malformedPullNeverAdvancesCursor() {
        assertThrows(IllegalArgumentException::class.java) { StateMachine.pull(SyncState(),Page(1,2,false,listOf(Commit(2,"op","remote",listOf(remote("bad",sequence=2)))))) }
    }
    @Test fun forgedReceiptCannotClearPendingEdits() {
        val sent = StateMachine.prepare(StateMachine.edit(SyncState(),"inbox",id,payload("local")))
        assertThrows(IllegalArgumentException::class.java) { StateMachine.accept(sent,Reply(1,"applied",1,listOf(remote("wrong",device=sent.device)))) }
    }
    @Test fun ownPulledCommitAcknowledgesLostResponseWithoutFalseConflict() {
        val sent=StateMachine.prepare(StateMachine.edit(SyncState(),"inbox",id,payload("mine")))
        val commit=Commit(1,sent.pending!!.operation,sent.device,listOf(remote("mine",device=sent.device)))
        val applied=StateMachine.pull(sent,Page(1,1,false,listOf(commit)))
        assertNull(applied.pending)
        assertFalse(applied.items.getValue("inbox:$id").dirty)
        assertNull(applied.items.getValue("inbox:$id").conflict)
        assertEquals(1L,applied.cursor)
    }
    @Test fun conflictedMemberBlocksItsWholeAtomicGroup() {
        var s=StateMachine.edit(SyncState(),"inbox",id,payload("mine"))
        s=StateMachine.edit(s,"task","second",payload("converted"))
        s=StateMachine.group(s,setOf("inbox:$id","task:second"))
        s=StateMachine.pull(s,Page(1,1,false,listOf(Commit(1,"remote-op","remote",listOf(remote("theirs"))))))
        assertNull(StateMachine.prepare(s).pending)
        val chosen=StateMachine.resolve(s,"inbox:$id",true)
        assertNull(StateMachine.prepare(chosen).pending)
        assertEquals(2,StateMachine.prepare(StateMachine.resolve(chosen,"task:second",true)).pending!!.mutations.size)
    }
    @Test fun pushConflictPersistsBothCopiesAndRequiresNewOperationAfterChoice() {
        val sent=StateMachine.prepare(StateMachine.edit(SyncState(),"inbox",id,payload("mine")))
        val conflicted=StateMachine.accept(sent,Reply(1,"conflict",1,conflicts=listOf(Conflict("inbox",id,remote("theirs")))))
        assertNull(conflicted.pending)
        val chosen=StateMachine.prepare(StateMachine.resolve(conflicted,"inbox:$id",true))
        assertEquals(1L,chosen.pending!!.mutations.single().baseRevision)
        assertNotEquals(sent.pending!!.operation,chosen.pending!!.operation)
        assertEquals(payload("mine"),chosen.pending!!.mutations.single().payload)
    }

    @Test fun wholeRemoteCommitWaitsUntilAllChoicesAreCollected() {
        val siblingID="00000000-0000-0000-0000-000000000002"
        val siblingPayload=buildJsonObject {put("content",buildJsonObject {put("id",siblingID);put("text","sibling");put("createdAt","2026-10-30T00:00:00Z")})}
        val edited=StateMachine.edit(SyncState(),"inbox",id,payload("mine"))
        val sibling=Remote("inbox",siblingID,1,payload=siblingPayload,device="remote",sequence=1)
        val held=StateMachine.pull(edited,Page(1,1,false,listOf(Commit(1,"op","remote",listOf(remote("cloud"),sibling)))))
        assertTrue(held.items["inbox:$siblingID"]?.deleted ?: true)
        val first=StateMachine.resolve(held,"inbox:$id",false)
        assertEquals(payload("mine"),first.items.getValue("inbox:$id").payload)
        assertTrue(first.items.getValue("inbox:$siblingID").deleted)
        val restored=wire.decodeFromString<SyncState>(wire.encodeToString(first))
        val final=StateMachine.resolve(restored,"inbox:$siblingID",false)
        assertEquals(payload("cloud"),final.items.getValue("inbox:$id").payload)
        assertEquals(siblingPayload,final.items.getValue("inbox:$siblingID").payload)
    }
    @Test fun replayedConflictReceiptCannotReplaceNewerPulledVersion() {
        val sent=StateMachine.prepare(StateMachine.edit(SyncState(),"inbox",id,payload("mine")))
        val pulled=StateMachine.pull(sent,Page(1,2,false,listOf(Commit(1,"one","remote",listOf(remote("older"))),Commit(2,"two","remote",listOf(remote("newest",2,2))))))
        val accepted=StateMachine.accept(pulled,Reply(1,"conflict",1,conflicts=listOf(Conflict("inbox",id,remote("older")))))
        assertEquals(2L,accepted.items.getValue("inbox:$id").conflict!!.revision)
        assertEquals(payload("newest"),StateMachine.resolve(accepted,"inbox:$id",false).items.getValue("inbox:$id").payload)
    }
    @Test fun malformedDayPlanFailsBeforeCursorAdvance() {
        val bad=Remote("day_plan","2026-10-30@Asia/Shanghai",1,payload=wire.parseToJsonElement("""{"content":{"day_key":"2026-10-30","time_zone":"Asia/Shanghai","ordered_task_ids":"not-an-array"}}""").jsonObject,device="remote",sequence=1)
        assertThrows(IllegalArgumentException::class.java) {StateMachine.pull(SyncState(),Page(1,1,false,listOf(Commit(1,"op","remote",listOf(bad)))))}
    }
    @Test fun payloadIdentityMustMatchEnvelope() {
        val bad=remote("wrong").copy(id="00000000-0000-0000-0000-000000000002")
        assertThrows(IllegalArgumentException::class.java) {StateMachine.pull(SyncState(),Page(1,1,false,listOf(Commit(1,"op","remote",listOf(bad)))))}
    }
    @Test fun separateLargeEditsAreBatchedBelowRpcLimit() {
        var s=StateMachine.edit(SyncState(),"inbox","one",payload("a".repeat(600_000)))
        s=StateMachine.edit(s,"inbox","two",payload("b".repeat(600_000)))
        assertEquals(1,StateMachine.prepare(s).pending!!.mutations.size)
    }

    @Test fun newerRemoteChangeResetsAnIncompleteConflictGroupChoice() {
        val second="00000000-0000-0000-0000-000000000002"
        val secondPayload=buildJsonObject {put("content",buildJsonObject {put("id",second);put("text","sibling");put("createdAt","2026-10-30T00:00:00Z")})}
        val sibling=Remote("inbox",second,1,payload=secondPayload,device="remote",sequence=1)
        val local=StateMachine.edit(SyncState(),"inbox",id,payload("mine"))
        val held=StateMachine.pull(local,Page(1,1,false,listOf(Commit(1,"one","remote",listOf(remote("older"),sibling)))))
        val chosen=StateMachine.resolve(held,"inbox:$id",false)
        val changed=StateMachine.pull(chosen,Page(1,2,false,listOf(Commit(2,"two","remote",listOf(remote("latest",2,2))))))
        assertNull(changed.items.getValue("inbox:$id").conflictChoice)
        val stillHeld=StateMachine.resolve(changed,"inbox:$second",false)
        assertEquals(payload("mine"),stillHeld.items.getValue("inbox:$id").payload)
        val final=StateMachine.resolve(stillHeld,"inbox:$id",false)
        assertEquals(payload("latest"),final.items.getValue("inbox:$id").payload)
    }

    @Test fun pullHoldsCleanSiblingWhenReceiptAlreadyExposedItsConflict() {
        val second="00000000-0000-0000-0000-000000000002"
        val secondPayload=buildJsonObject {put("content",buildJsonObject {put("id",second);put("text","sibling");put("createdAt","2026-10-30T00:00:00Z")})}
        val sibling=Remote("inbox",second,1,payload=secondPayload,device="remote",sequence=1)
        val sent=StateMachine.prepare(StateMachine.edit(SyncState(),"inbox",id,payload("mine")))
        val known=StateMachine.accept(sent,Reply(1,"conflict",1,conflicts=listOf(Conflict("inbox",id,remote("cloud")))))
        val held=StateMachine.pull(known,Page(1,1,false,listOf(Commit(1,"op","remote",listOf(remote("cloud"),sibling)))))
        assertTrue(held.items["inbox:$second"]?.deleted ?: true)
        assertEquals(held.items.getValue("inbox:$id").conflictGroup,held.items.getValue("inbox:$second").conflictGroup)
    }

    @Test fun editingAfterChoiceRequiresTheUserToChooseAgain() {
        val second="00000000-0000-0000-0000-000000000002"
        val secondPayload=buildJsonObject {put("content",buildJsonObject {put("id",second);put("text","sibling");put("createdAt","2026-10-30T00:00:00Z")})}
        val sibling=Remote("inbox",second,1,payload=secondPayload,device="remote",sequence=1)
        val local=StateMachine.edit(SyncState(),"inbox",id,payload("mine"))
        val held=StateMachine.pull(local,Page(1,1,false,listOf(Commit(1,"one","remote",listOf(remote("cloud"),sibling)))))
        val chosen=StateMachine.resolve(held,"inbox:$id",false)
        val edited=StateMachine.edit(chosen,"inbox",id,payload("new local edit"))
        assertNull(edited.items.getValue("inbox:$id").conflictChoice)
        val stillHeld=StateMachine.resolve(edited,"inbox:$second",false)
        assertEquals(payload("new local edit"),stillHeld.items.getValue("inbox:$id").payload)
    }

}
