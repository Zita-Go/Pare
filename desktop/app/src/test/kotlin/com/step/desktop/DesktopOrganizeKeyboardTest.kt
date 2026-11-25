package com.step.desktop

import androidx.compose.ui.input.key.Key
import com.pare.app.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DesktopOrganizeKeyboardTest {
    @Test fun arrowsFollowVisibleInboxThenQuadrantOrderAndStopAtTheEdges() {
        val state=fixture();val keyboard=UiOrganizeKeyboard()
        val items=organizeKeyboardItems(state,true,NOW)
        assertEquals(listOf(selection("inbox",I),selection("task",A),selection("task",B),selection("task",C)),items)
        keyboard.handle(OrganizeKeyCommand.DOWN,state,items){error("Navigation must not change content")}
        assertEquals(selection("inbox",I),keyboard.selection)
        keyboard.handle(OrganizeKeyCommand.UP,state,items){error("Navigation must not change content")}
        assertEquals(selection("inbox",I),keyboard.selection)
        repeat(5){keyboard.handle(OrganizeKeyCommand.DOWN,state,items){error("Navigation must not change content")}}
        assertEquals(selection("task",C),keyboard.selection)
        assertTrue(keyboard.handle(OrganizeKeyCommand.ESCAPE,state,items){error("Escape must not edit")})
        assertNull(keyboard.selection)
        assertFalse(keyboard.handle(OrganizeKeyCommand.ESCAPE,state,items){error("Escape must not edit")})
        keyboard.handle(OrganizeKeyCommand.UP,state,items){error("Navigation must not change content")}
        assertEquals(selection("task",C),keyboard.selection)
    }

    @Test fun firstDigitOnlySelectsThenInboxConversionFollowsTheNewTaskWithoutAddingToday() {
        var state=fixture();val keyboard=UiOrganizeKeyboard();val original=state
        keyboard.handle(OrganizeKeyCommand.FOUR,state,organizeKeyboardItems(state,true,NOW)){error("First digit must only select")}
        assertEquals(original,state)
        assertEquals(selection("inbox",I),keyboard.selection)
        keyboard.handle(OrganizeKeyCommand.FOUR,state,organizeKeyboardItems(state,true,NOW)){action->
            assertTrue(action is OrganizeKeyboardAction.Edit)
            action as OrganizeKeyboardAction.Edit
            state=action.change(state)
            keyboard.acceptSaved(action,state)
        }
        val task=state.items.values.single {it.kind=="task"&&it.title()=="虚构待整理"}
        assertTrue(state.items.getValue("inbox:$I").deleted)
        assertEquals(3,task.content().number("quadrant"))
        assertEquals(selection("task",task.id),keyboard.selection)
        assertEquals(original.items.filterKeys {it.startsWith("day_plan:")},state.items.filterKeys {it.startsWith("day_plan:")})
        assertFalse(DayTaskSelection.contains(state,task.id,ContentActions.planId(state,NOW)))
    }

    @Test fun taskMoveAppendsWithoutReorderingOthersOrChangingTodayAndRejectsStaleOrder() {
        val original=fixture();val keyboard=UiOrganizeKeyboard()
        keyboard.select(selection("task",A))
        var move:OrganizeKeyboardAction.Edit?=null
        keyboard.handle(OrganizeKeyCommand.TWO,original,organizeKeyboardItems(original,false,NOW)){move=it as OrganizeKeyboardAction.Edit}
        val action=requireNotNull(move)
        val moved=action.change(original)
        assertEquals(listOf(B,C,A),OrganizeActions.tasks(moved,1).map {it.id})
        assertEquals(original.items.filterKeys {it.startsWith("day_plan:")},moved.items.filterKeys {it.startsWith("day_plan:")})
        val reordered=OrganizeActions.move(original,C,1,B,OrganizeMoveBaseline.from(original,C),NOW)
        assertThrows(IllegalStateException::class.java){action.change(reordered)}
        assertEquals(0,reordered.items.getValue("task:$A").content().number("quadrant"))
    }

    @Test fun assigningTheCurrentQuadrantDoesNotReorderItsTasks() {
        val state=fixture();val keyboard=UiOrganizeKeyboard();keyboard.select(selection("task",B))
        var after=state
        keyboard.handle(OrganizeKeyCommand.TWO,state,organizeKeyboardItems(state,false,NOW)){action->
            action as OrganizeKeyboardAction.Edit
            after=action.change(state)
        }
        assertEquals(listOf(B,C),OrganizeActions.tasks(after,1).map {it.id})
        assertEquals(state,after)
    }

    @Test fun spaceCompletesAndRestoresTheSelectedTaskButNeverCompletesInbox() {
        var state=fixture();val keyboard=UiOrganizeKeyboard()
        keyboard.select(selection("inbox",I))
        keyboard.handle(OrganizeKeyCommand.SPACE,state,organizeKeyboardItems(state,true,NOW)){error("Inbox has no completion action")}
        keyboard.select(selection("task",A))
        fun space() {keyboard.handle(OrganizeKeyCommand.SPACE,state,organizeKeyboardItems(state,true,NOW)){action->
            assertTrue(action is OrganizeKeyboardAction.Complete)
            action as OrganizeKeyboardAction.Complete
            state=CompletionActions.set(state,action.taskId,action.completed,NOW)
        }}
        space()
        assertTrue(state.items.getValue("task:$A").completed())
        keyboard.reconcile(state,organizeKeyboardItems(state,true,NOW))
        assertEquals(selection("task",A),keyboard.selection)
        space()
        assertFalse(state.items.getValue("task:$A").completed())
        assertEquals(selection("task",A),keyboard.selection)
        assertTrue(state.items.getValue("task:$A").content().array("progressSessions").isEmpty())
    }

    @Test fun syncDeletionRecoversToTheNearestVisibleItemAndHiddenInboxIsSkipped() {
        var state=fixture();val keyboard=UiOrganizeKeyboard()
        keyboard.reconcile(state,organizeKeyboardItems(state,true,NOW))
        keyboard.select(selection("task",B))
        state=StateMachine.edit(state,"task",B,null,true)
        keyboard.reconcile(state,organizeKeyboardItems(state,true,NOW))
        assertEquals(selection("task",C),keyboard.selection)
        state=StateMachine.edit(state,"task",C,null,true)
        keyboard.reconcile(state,organizeKeyboardItems(state,true,NOW))
        assertEquals(selection("task",A),keyboard.selection)
        keyboard.select(selection("inbox",I))
        keyboard.reconcile(state,organizeKeyboardItems(state,false,NOW))
        assertEquals(selection("task",A),keyboard.selection)
        state=StateMachine.edit(state,"task",A,null,true)
        keyboard.reconcile(state,organizeKeyboardItems(state,false,NOW))
        assertNull(keyboard.selection)
    }

    @Test fun inputFocusModifiersKeyUpAndUnrelatedKeysAreNeverIntercepted() {
        listOf(Key.DirectionUp,Key.DirectionDown,Key.One,Key.Two,Key.Three,Key.Four,Key.Spacebar,Key.Escape).forEach {key->
            assertNull(organizeKeyCommand(key,true,false,true))
            assertNull(organizeKeyCommand(key,true,true,false))
            assertNull(organizeKeyCommand(key,false,false,false))
        }
        assertNull(organizeKeyCommand(Key.F,true,false,false))
        assertEquals(OrganizeKeyCommand.ONE,organizeKeyCommand(Key.One,true,false,false))
        assertEquals(OrganizeKeyCommand.DOWN,organizeKeyCommand(Key.DirectionDown,true,false,false))
        val state=fixture();val keyboard=UiOrganizeKeyboard()
        assertFalse(keyboard.handle(OrganizeKeyCommand.ONE,state,organizeKeyboardItems(state,true,NOW),busy=true){error("Busy edits cannot change selection or content")})
        assertNull(keyboard.selection)
    }

    @Test fun failedInboxConversionKeepsItsSelectionAndDoesNotReplaceNewerText() {
        val shown=fixture();val keyboard=UiOrganizeKeyboard();keyboard.select(selection("inbox",I))
        var pending:OrganizeKeyboardAction.Edit?=null
        keyboard.handle(OrganizeKeyCommand.ONE,shown,organizeKeyboardItems(shown,true,NOW)){pending=it as OrganizeKeyboardAction.Edit}
        val action=requireNotNull(pending)
        val updated=StateMachine.edit(shown,"inbox",I,ContentActions.save(shown.items.getValue("inbox:$I"),"inbox","虚构同步更新","",1,I,NOW))
        assertThrows(IllegalArgumentException::class.java){action.change(updated)}
        assertEquals(selection("inbox",I),keyboard.selection)
        assertEquals("虚构同步更新",updated.items.getValue("inbox:$I").title())
        assertEquals(3,updated.items.values.count {it.kind=="task"&&!it.deleted})
    }

    private fun fixture():SyncState {
        var state=SyncState(device="fictional-organize-keyboard")
        state=StateMachine.edit(state,"inbox",I,ContentActions.save(null,"inbox","虚构待整理","",1,I,NOW))
        listOf(A,B,C).forEachIndexed {index,id->state=StateMachine.edit(state,"task",id,ContentActions.save(null,"task","虚构行动 $index","",if(index==0)0 else 1,id,NOW.minusSeconds(index.toLong())))}
        return DayTaskActions.setIncluded(state,C,true,ContentActions.planId(state,NOW),NOW)
    }
    private fun selection(kind:String,id:String)=OrganizeSelection(kind,id)
    private companion object {
        val NOW:Instant=Instant.parse("2026-11-25T03:00:00Z")
        const val I="00000000-0000-4000-8000-000000000871"
        const val A="00000000-0000-4000-8000-000000000872"
        const val B="00000000-0000-4000-8000-000000000873"
        const val C="00000000-0000-4000-8000-000000000874"
    }
}
