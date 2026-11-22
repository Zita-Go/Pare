package com.step.desktop

import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DesktopTaskMetadataTest {
    private val id="00000000-0000-4000-8000-000000001551"
    private val now=Instant.parse("2026-11-22T08:00:00Z")
    private fun seed():SyncState {
        val s=SyncState()
        return StateMachine.edit(s,"task",id,ContentActions.save(null,"task","示例研究","保留背景",1,id,now,estimatedMinutes=30,nextAction="打开资料"))
    }
    @Test fun editsDeadlineEstimateAndActualWithoutRewritingTheTaskOrItsPlan() {
        var s=seed();val baseline=DesktopMetadataBaseline.from(s.items.getValue("task:$id"))
        val planId=ContentActions.planId(s,now)
        s=DayTaskActions.setIncluded(s,id,true,planId,now)
        s=TimelineActions.setSchedule(s,id,planId,now,30,TimelineScheduleBaseline.from(s,planId,id))
        val old=s.items.getValue("task:$id")
        s=StateMachine.edit(s,"task",id,JsonObject(old.payload!!.toMutableMap().apply {put("content",JsonObject(old.content()!!.toMutableMap().apply {put("note",JsonPrimitive("另一端的新备注"))}))}))
        val saved=DesktopTaskMetadata.save(s,id,baseline,60,now.plusSeconds(7200),12,now)
        val c=saved.items.getValue("task:$id").content()!!
        assertEquals(60,c["estimatedMinutes"]!!.jsonPrimitive.int)
        assertEquals(12,c["actualMinutes"]!!.jsonPrimitive.int)
        assertEquals("2026-11-22T10:00:00Z",c["dueAt"]!!.jsonPrimitive.content)
        assertEquals("另一端的新备注",c["note"]!!.jsonPrimitive.content)
        assertEquals("打开资料",c["nextAction"]!!.jsonPrimitive.content)
        assertEquals(s.items["day_plan:$planId"],saved.items["day_plan:$planId"])
    }
    @Test fun staleEstimateCannotOverwriteAConcurrentEstimate() {
        val s=seed();val baseline=DesktopMetadataBaseline.from(s.items.getValue("task:$id"))
        val other=DesktopTaskMetadata.save(s,id,baseline,90,null,null,now)
        assertThrows(IllegalStateException::class.java) {DesktopTaskMetadata.save(other,id,baseline,60,null,null,now)}
    }
    @Test fun unrelatedEstimateChangeSurvivesDeadlineOnlySave() {
        val s=seed();val baseline=DesktopMetadataBaseline.from(s.items.getValue("task:$id"))
        val other=DesktopTaskMetadata.save(s,id,baseline,90,null,null,now)
        val result=DesktopTaskMetadata.save(other,id,baseline,30,now.plusSeconds(7200),null,now)
        assertEquals(90,result.items.getValue("task:$id").content()!!["estimatedMinutes"]!!.jsonPrimitive.int)
        assertEquals("2026-11-22T10:00:00Z",result.items.getValue("task:$id").content()!!["dueAt"]!!.jsonPrimitive.content)
    }
    @Test fun invalidMinutesAndDeletedTasksAreRejected() {
        val s=seed();val baseline=DesktopMetadataBaseline.from(s.items.getValue("task:$id"))
        assertThrows(IllegalArgumentException::class.java) {DesktopTaskMetadata.save(s,id,baseline,0,null,null,now)}
        val deleted=LibraryActions.delete(s,"task",id,LibraryBaseline.from(s.items.getValue("task:$id")),now)
        assertThrows(IllegalArgumentException::class.java) {DesktopTaskMetadata.save(deleted,id,baseline,45,null,null,now)}
    }
}
