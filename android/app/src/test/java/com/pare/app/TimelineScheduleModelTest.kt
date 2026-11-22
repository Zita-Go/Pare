package com.pare.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Android-retained form coverage stays outside the shared JVM domain suite. */
class TimelineScheduleModelTest {
    private val taskId="00000000-0000-4000-8000-000000000091"
    private val peerId="00000000-0000-4000-8000-000000000092"
    private val day="2099-05-20@UTC"
    private val target="2099-05-21@UTC"
    private val now=Instant.parse("2099-05-20T09:00:00Z")
    private fun plan(id:String,task:String=taskId,start:Instant=now):Entity=Entity("day_plan",id,buildJsonObject {put("futureEnvelope","keep");put("content",buildJsonObject {
        put("day_key",id.substringBefore('@'));put("time_zone",id.substringAfter('@'));put("ordered_task_ids",JsonArray(listOf(JsonPrimitive(task))));put("daily_win_task_id",task)
        put("futurePlan","keep");put("task_plans",buildJsonArray {add(buildJsonObject {put("task_id",task);put("scheduled_at",start.toString());put("scheduled_minutes",20);put("schedule_reminder_lead_minutes",5)
            put("unknownEntry","preserved");put("allocation",buildJsonObject {put("id","00000000-0000-4000-8000-000000000093");put("kind","check");put("planned_minutes",7)})
        })})
    })})
    private fun state():SyncState {
        val task=Entity("task",taskId,ContentActions.save(null,"task","Fictional move","",1,taskId,now))
        val peer=Entity("task",peerId,ContentActions.save(null,"task","Fictional other appointment","",1,peerId,now))
        return SyncState(device="fictional-timeline-move",items=listOf(task,peer,plan(day),plan(target,peerId,now.plusSeconds(86400))).associateBy {it.key})
    }
    @Test fun dateFormRetainsNewTaskIdAndCapturesExistingTargetWhenSelected() {
        val s=state();val model=TimelineScheduleModel()
        model.initialize(s,TimelineDay.fromPlanId(day),null,now)
        val id=model.taskId;model.title="Fictional new task"
        model.selectDate(s,LocalDate.parse("2099-05-23"))
        assertEquals(id,model.taskId);assertEquals("2099-05-23@UTC",model.expected!!.planId);assertNull(model.moveExpected)
        assertEquals(Instant.parse("2099-05-23T09:00:00Z"),model.startInstant())
        model.clear();model.initialize(s,TimelineDay.fromPlanId(day),s.items["task:$taskId"])
        model.selectDate(s,LocalDate.parse("2099-05-21"))
        assertEquals(day,model.expected!!.planId);assertEquals(target,model.moveExpected!!.targetPlanId)
        model.selectDate(s,LocalDate.parse("2099-05-20"));assertNull(model.moveExpected)
    }
}
