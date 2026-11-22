package com.step.desktop

import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.nio.file.Files

class DesktopIntelligencePolicyTest {
    private val now=Instant.parse("2026-11-22T08:00:00Z")
    private val planId="2026-11-22@UTC"
    private val taskId="11111111-1111-4111-8111-111111111111"
    private fun taskState():SyncState {
        val payload=ContentActions.save(null,"task","虚构任务","",1,taskId,now.minusSeconds(8*86400))
        return StateMachine.edit(SyncState(device="fixture"),"task",taskId,payload)
    }

    @Test fun stalledHintExplainsItsEvidenceAndExcludesArchivedOrCompletedTasks() {
        val state=taskState()
        val hint=DesktopHintPolicy.candidates(state,planId,now).single()
        assertEquals("task",hint.kind)
        assertEquals(taskId,hint.subjectId)
        assertTrue(hint.reason.contains("7"))
        assertEquals(now.minusSeconds(8*86400).toString(),hint.evidence)
        val completed=CompletionActions.set(state,taskId,true,now)
        assertTrue(DesktopHintPolicy.candidates(completed,planId,now).isEmpty())
        val task=state.items.getValue("task:$taskId")
        val archived=state.copy(items=state.items+(task.key to task.copy(payload=JsonObject(task.payload!!+("lifecycle" to JsonPrimitive("archived"))))))
        assertTrue(DesktopHintPolicy.candidates(archived,planId,now).isEmpty())
    }

    @Test fun ignoringEvidenceAndSnoozingSurviveReloadWithoutSavingTaskText() {
        val hint=DesktopHintPolicy.candidates(taskState(),planId,now).single()
        val directory=Files.createTempDirectory("step-hint-test-")
        val store=DesktopHintStore(directory)
        var memory=DesktopHintMemory()
        memory=DesktopHintPolicy.snooze(memory,hint,now.plusSeconds(3600))
        store.save(memory)
        assertNull(DesktopHintPolicy.select(listOf(hint),store.load(),now))
        assertEquals(hint,DesktopHintPolicy.select(listOf(hint),store.load(),now.plusSeconds(3601)))
        memory=DesktopHintPolicy.ignore(memory,hint)
        store.save(memory)
        assertNull(DesktopHintPolicy.select(listOf(hint),store.load(),now.plusSeconds(86400)))
        assertEquals(hint.copy(evidence="new-evidence"),DesktopHintPolicy.select(listOf(hint.copy(evidence="new-evidence")),store.load(),now.plusSeconds(86400)))
        assertFalse(Files.readString(directory.resolve("intelligence-hints.json")).contains("虚构任务"))
    }

    @Test fun seenCooldownIsGlobalAndFutureClockNeverCreatesAHintStorm() {
        val hint=DesktopHintPolicy.candidates(taskState(),planId,now).single()
        val memory=DesktopHintPolicy.shown(DesktopHintMemory(),now)
        assertNull(DesktopHintPolicy.select(listOf(hint),memory,now.plusSeconds(30)))
        assertNull(DesktopHintPolicy.select(listOf(hint),memory,now.minusSeconds(30)))
        assertEquals(hint,DesktopHintPolicy.select(listOf(hint),memory,now.plusSeconds(24*3600)))
    }

    @Test fun overloadUsesExplicitPersonalAllocationAndConfiguredCapacity() {
        var state=taskState()
        state=PreferencesActions.saveProfile(state,ProfileDraft(notes="虚构测试时区"),ProfileBaseline.from(null),ZoneId.of("UTC"))
        state=DayTaskActions.setIncluded(state,taskId,true,planId,now)
        state=DayPlanActions.allocate(state,taskId,TodayAllocationDraft(planId,"personal",40,"22222222-2222-4222-8222-222222222222",DayAllocationBaseline.from(state,taskId,now)),now)
        assertTrue(DesktopHintPolicy.candidates(state,planId,now).none {it.kind=="overload"})
        val overload=DesktopHintPolicy.candidates(state,planId,now,dailyCapacityMinutes=20).first {it.kind=="overload"}
        assertTrue(overload.reason.contains("40"))
        assertTrue(overload.reason.contains("20"))
    }
}
