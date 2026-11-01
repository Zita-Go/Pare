package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OrganizeUndoTest {
    private val now=Instant.parse("2099-05-20T09:00:00Z")
    private fun state():SyncState {
        val task=Entity("task","sample",ContentActions.save(null,"task","虚构观察","",0,"sample",now))
        return SyncState(device="qa-undo",items=mapOf(task.key to task))
    }
    @Test fun undoRestoresPositionWhileKeepingNewNoteAndHistory() {
        val old=state();val moved=OrganizeActions.move(old,"sample",2,null,OrganizeMoveBaseline.from(old,"sample"),now.plusSeconds(20))
        val receipt=OrganizeUndo.capture(old,moved)!!;val task=moved.items.getValue("task:sample")
        val edited=libraryReplace(moved,task,task.content()!!.toMutableMap().apply {put("note",JsonPrimitive("后来补充"));put("futureField",JsonPrimitive(42))})
        val restored=receipt.apply(edited).items.getValue(task.key).content()!!
        assertEquals(old.items.getValue(task.key).content()!!["quadrant"],restored["quadrant"])
        assertEquals(JsonPrimitive("后来补充"),restored["note"]);assertEquals(JsonPrimitive(42),restored["futureField"])
        assertFalse(restored.containsKey("quadrantOrder"))
    }
    @Test fun changedPositionCompletionAndOwnerRejectUndo() {
        val old=state();val moved=OrganizeActions.move(old,"sample",2,null,OrganizeMoveBaseline.from(old,"sample"),now.plusSeconds(20));val receipt=OrganizeUndo.capture(old,moved)!!
        val again=OrganizeActions.move(moved,"sample",1,null,OrganizeMoveBaseline.from(moved,"sample"),now.plusSeconds(30))
        assertThrows(IllegalStateException::class.java){receipt.apply(again)}
        val task=moved.items.getValue("task:sample")
        val completed=libraryReplace(moved,task,task.content()!!.toMutableMap().apply {put("completed",JsonPrimitive(true))})
        assertThrows(IllegalStateException::class.java){receipt.apply(completed)}
        assertThrows(IllegalStateException::class.java){receipt.apply(moved.copy(owner="different"))}
    }
    @Test fun unchangedMoveDoesNotOfferUndo(){val old=state();assertNull(OrganizeUndo.capture(old,old))}
    @Test fun laterNeighborMoveRejectsStaleOrderButNeighborNotesDoNot() {
        fun task(id:String,q:Int,rank:Int):Entity {
            val raw=ContentActions.save(null,"task","虚构$id","",q,id,now)
            return Entity("task",id,JsonObject(raw+("content" to JsonObject(raw.getValue("content").jsonObject+("quadrantOrder" to JsonPrimitive(rank))))))
        }
        val old=SyncState(device="undo-order",items=listOf(task("a",0,0),task("b",0,1024),task("c",2,0)).associateBy {it.key})
        val moved=OrganizeActions.move(old,"a",1,null,OrganizeMoveBaseline.from(old,"a"),now)
        val receipt=OrganizeUndo.capture(old,moved)!!
        val neighbor=moved.items.getValue("task:b")
        val note=libraryReplace(moved,neighbor,neighbor.content()!!.toMutableMap().apply {put("note",JsonPrimitive("后来补充的背景"))})
        assertEquals(JsonPrimitive("后来补充的背景"),receipt.apply(note).items.getValue(neighbor.key).content()!!["note"])
        val later=OrganizeActions.move(moved,"c",0,"b",OrganizeMoveBaseline.from(moved,"c"),now.plusSeconds(10))
        assertThrows(IllegalStateException::class.java){receipt.apply(later)}
        assertEquals(1,later.items.getValue("task:a").content()!!.getValue("quadrant").jsonPrimitive.int)
    }
    @Test fun inheritedConflictGroupPreventsUndoWithoutOverwritingItsSibling() {
        val old=state();val moved=OrganizeActions.move(old,"sample",2,null,OrganizeMoveBaseline.from(old,"sample"),now.plusSeconds(20))
        val receipt=OrganizeUndo.capture(old,moved)!!
        val source=moved.items.getValue("task:sample")
        val sibling=Entity("task","peer",ContentActions.save(null,"task","虚构冲突记录","",1,"peer",now),dirty=true,group=source.group,conflictGroup="unresolved-remote-group")
        val conflicted=moved.copy(items=moved.items+(sibling.key to sibling))
        assertThrows(IllegalStateException::class.java){receipt.apply(conflicted)}
        assertEquals(2,conflicted.items.getValue(source.key).content()!!["quadrant"]!!.jsonPrimitive.int)
        assertEquals(sibling,conflicted.items.getValue(sibling.key))
    }
}
