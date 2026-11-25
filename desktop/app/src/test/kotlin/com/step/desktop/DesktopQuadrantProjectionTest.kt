package com.step.desktop

import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DesktopQuadrantProjectionTest {
    @Test fun todayCompletionAppearsAfterActiveTasksWithoutChangingTheirOrderOrPersistedState() {
        val before=fixture();val state=CompletionActions.set(before,B,true,NOW)
        val frozen=wire.encodeToString(state)
        assertEquals(listOf(A,C,B),DesktopQuadrantProjection.tasks(state,0,NOW).map {it.id})
        assertEquals(listOf(A,C),OrganizeActions.tasks(state,0).map {it.id})
        assertFalse(taskCanBePlanned(state.items.getValue("task:$B")))
        assertEquals(frozen,wire.encodeToString(state))
        val reopened=CompletionActions.set(state,B,false,NOW)
        assertEquals(listOf(A,B,C),DesktopQuadrantProjection.tasks(reopened,0,NOW).map {it.id})
    }
    @Test fun planningMidnightHidesCompletionAndRetainsSearchHistoryAndUnfinishedTasks() {
        val state=CompletionActions.set(fixture(),B,true,NOW)
        val midnight=Instant.parse("2031-04-10T16:00:00Z")
        assertTrue(DesktopQuadrantProjection.tasks(state,0,midnight.minusNanos(1)).any {it.id==B})
        assertEquals(listOf(A,C),DesktopQuadrantProjection.tasks(state,0,midnight).map {it.id})
        assertEquals(listOf(B),LibrarySearch.find(state,scope=LibraryScope.COMPLETED).map {it.id})
        assertFalse(LibrarySearch.archived(state.items.getValue("task:$B")))
        assertTrue(state.items.getValue("task:$B").completed())
    }
    @Test fun lateSyncedYesterdayCompletionDoesNotReappearButTodayCompletionDoes() {
        var state=CompletionActions.set(fixture(),A,true,NOW.minusSeconds(86400))
        state=CompletionActions.set(state,B,true,NOW)
        assertEquals(listOf(C,B),DesktopQuadrantProjection.tasks(state,0,NOW).map {it.id})
        assertEquals(setOf(A,B),LibrarySearch.find(state,scope=LibraryScope.COMPLETED).map {it.id}.toSet())
    }
    @Test fun unknownCompletionDateRemainsInHistoryRatherThanInventingACompletionDay() {
        val original=CompletionActions.set(fixture(),B,true,NOW)
        for(value in listOf<JsonElement?>(null,JsonPrimitive("invalid"))) {
            val task=original.items.getValue("task:$B")
            val content=task.content()!!.toMutableMap().apply {if(value==null)remove("completedAt")else put("completedAt",value)}
            val replacement=task.copy(payload=JsonObject(task.payload!!.toMutableMap().apply {put("content",JsonObject(content))}))
            val state=original.copy(items=original.items+(task.key to replacement))
            assertFalse(DesktopQuadrantProjection.tasks(state,0,NOW).any {it.id==B})
            assertTrue(LibrarySearch.find(state,scope=LibraryScope.COMPLETED).any {it.id==B})
        }
    }
    @Test fun archivedAndDeletedRecordsStayHiddenAndDisplayDoesNotChangeMoveBaselines() {
        var state=CompletionActions.set(fixture(),B,true,NOW)
        val baseline=OrganizeMoveBaseline.from(state,A)
        DesktopQuadrantProjection.tasks(state,0,NOW)
        assertEquals(baseline,OrganizeMoveBaseline.from(state,A))
        val archived=LibraryActions.archive(state,B,LibraryBaseline.from(state.items.getValue("task:$B")))
        assertFalse(DesktopQuadrantProjection.tasks(archived,0,NOW).any {it.id==B})
        state=StateMachine.edit(state,"task",B,null,true)
        assertFalse(DesktopQuadrantProjection.tasks(state,0,NOW).any {it.id==B})
    }
    @Test fun keyboardDropsTheSelectionWhenTheCompletedTaskLeavesAtMidnight() {
        val state=CompletionActions.set(fixture(),B,true,NOW)
        val keyboard=UiOrganizeKeyboard();keyboard.select(OrganizeSelection("task",B))
        val visibleToday=DesktopQuadrantProjection.tasks(state,0,NOW).map {OrganizeSelection("task",it.id)}
        keyboard.reconcile(state,visibleToday)
        assertEquals(OrganizeSelection("task",B),keyboard.selection)
        val nextDay=DesktopQuadrantProjection.tasks(state,0,NOW.plusSeconds(86400)).map {OrganizeSelection("task",it.id)}
        keyboard.reconcile(state,nextDay)
        assertNotEquals(OrganizeSelection("task",B),keyboard.selection)
        assertTrue(keyboard.selection in nextDay)
    }
    private fun fixture():SyncState {
        var state=SyncState(device="fictional-completion-retention")
        state=PreferencesActions.saveProfile(state,ProfileDraft(background="虚构跨日验收"),ProfileBaseline.from(null),ZoneId.of("Asia/Shanghai"))
        listOf(A,B,C).forEachIndexed {i,id->state=StateMachine.edit(state,"task",id,ContentActions.save(null,"task","虚构完成 $i","",0,id,NOW.minusSeconds(i.toLong()+3600)))}
        return state
    }
    private companion object {
        val NOW:Instant=Instant.parse("2031-04-10T15:59:00Z")
        const val A="00000000-0000-4000-8000-000000008201"
        const val B="00000000-0000-4000-8000-000000008202"
        const val C="00000000-0000-4000-8000-000000008203"
    }
}
