package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MobileWorkflowTest {
    private val now=Instant.parse("2026-10-30T04:00:00Z")
    private val id="00000000-0000-4000-8000-000000000006"
    private fun task(vararg extra:Pair<String,JsonElement>):Entity {
        val payload=ContentActions.save(null,"task","虚构行动","",1,id,now.minusSeconds(10*86400))
        return Entity("task",id,JsonObject(payload.toMutableMap().apply {
            put("content",JsonObject(payload.getValue("content").jsonObject.toMutableMap().apply {putAll(extra)}))
        }))
    }
    @Test fun reminderPreservesOtherFieldsAndRejectsStaleOrClosedTask() {
        val task=task("nextAction" to JsonPrimitive("保留下一步"))
        val original=SyncState(items=mapOf(task.key to task))
        val base=TaskReminderBaseline.from(task)
        val saved=TaskReminderActions.save(original,id,base,now.plusSeconds(600),"weekly",now)
        assertEquals("保留下一步",saved.items.getValue(task.key).content()!!["nextAction"]!!.jsonPrimitive.content)
        assertEquals("weekly",saved.items.getValue(task.key).content()!!["recurrence"]!!.jsonPrimitive.content)
        assertThrows(StaleContentEditException::class.java) {TaskReminderActions.save(saved,id,base,now.plusSeconds(1200),null,now)}
        val closed=original.copy(items=mapOf(task.key to task.copy(deleted=true,payload=null)))
        assertThrows(IllegalArgumentException::class.java) {TaskReminderActions.save(closed,id,base,now.plusSeconds(600),null,now)}
        assertThrows(IllegalArgumentException::class.java) {TaskReminderActions.save(original,id,base,now.minusSeconds(1),null,now)}
        val cleared=TaskReminderActions.save(saved,id,TaskReminderBaseline.from(saved.items.getValue(task.key)),null,null,now)
        assertNull(cleared.items.getValue(task.key).content()!!["remindAt"])
        assertNull(cleared.items.getValue(task.key).content()!!["recurrence"])
    }
    @Test fun completionIsIdempotentAndDoesNotModifyRemotelyArchivedTasks() {
        val task=task("futureField" to JsonPrimitive("retained"))
        val initial=SyncState(items=mapOf(task.key to task))
        val done=CompletionActions.set(initial,id,true,now)
        assertEquals(done,CompletionActions.set(done,id,true,now.plusSeconds(30)))
        assertEquals("retained",done.items.getValue(task.key).content()!!["futureField"]!!.jsonPrimitive.content)
        val archived=task.copy(payload=JsonObject(task.payload!!.toMutableMap().apply {put("lifecycle",JsonPrimitive("archived"))}))
        assertThrows(IllegalArgumentException::class.java) {CompletionActions.set(initial.copy(items=mapOf(task.key to archived)),id,true,now)}
    }
    @Test fun reviewUsesActualRecentRecordsAndKeepsExternalTimeSeparate() {
        fun record(minutesKey:String,minutes:Int,at:Instant)=buildJsonObject {put("id",uid());put("endedAt",at.toString());put(minutesKey,minutes)}
        val task=task("completed" to JsonPrimitive(true),"completedAt" to JsonPrimitive(now.minusSeconds(60).toString()),
            "estimatedMinutes" to JsonPrimitive(900),"actualMinutes" to JsonPrimitive(800),
            "progressSessions" to JsonArray(listOf(record("minutes",5,now.minusSeconds(300)),record("minutes",99,now.minusSeconds(9*86400)))),
            "parallelRunSessions" to JsonArray(listOf(record("elapsedMinutes",50,now.minusSeconds(60)))),
            "blockerEntries" to JsonArray(listOf(buildJsonObject {put("id",uid());put("at",now.minusSeconds(3600).toString());put("reason","lowEnergy")})))
        val review=MobileReview.calculate(SyncState(items=mapOf(task.key to task)),now)
        assertEquals(1,review.completedTasks.size)
        assertEquals(5L,review.focusMinutes)
        assertEquals(50L,review.externalMinutes)
        assertEquals(1,review.blockerCounts["lowEnergy"])
        assertTrue(review.attention.isEmpty())
    }
    @Test fun reviewPrioritizesExplicitDueIdeaAndNeverHintsArchivedOrFutureRecords() {
        val task=task()
        val ideaId=uid()
        val payload=ContentActions.save(null,"idea","虚构长期想法","",1,ideaId,now.minusSeconds(9*86400))
        val idea=Entity("idea",ideaId,JsonObject(payload.toMutableMap().apply {put("content",JsonObject(payload.getValue("content").jsonObject.toMutableMap().apply {put("reviewAt",JsonPrimitive(now.minusSeconds(1).toString()))}))}))
        val review=MobileReview.calculate(SyncState(items=mapOf(task.key to task,idea.key to idea)),now)
        assertEquals("idea",review.attention.first().kind)
        assertEquals(2,review.attention.size)
        val future=task("completed" to JsonPrimitive(true),"completedAt" to JsonPrimitive(now.plusSeconds(60).toString()))
        assertTrue(MobileReview.calculate(SyncState(items=mapOf(future.key to future)),now).completedTasks.isEmpty())
    }
}
