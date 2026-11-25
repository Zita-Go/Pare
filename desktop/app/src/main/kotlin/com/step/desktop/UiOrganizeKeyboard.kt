package com.step.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import com.pare.app.*
import java.time.Instant

internal data class OrganizeSelection(val kind:String,val id:String) {
    val key get()="$kind:$id"
}
internal enum class OrganizeKeyCommand { UP,DOWN,ONE,TWO,THREE,FOUR,SPACE,ESCAPE }
internal sealed interface OrganizeKeyboardAction {
    data class Edit(val selectionAfter:OrganizeSelection,val change:(SyncState)->SyncState):OrganizeKeyboardAction
    data class Complete(val taskId:String,val completed:Boolean):OrganizeKeyboardAction
}

/** Match the visible inbox and the matrix's row-major quadrant order; identity includes entity kind. */
internal fun organizeKeyboardItems(state:SyncState,inboxVisible:Boolean):List<OrganizeSelection> = buildList {
    if(inboxVisible)state.items.values.filter {it.kind=="inbox"&&!it.deleted}
        .sortedByDescending {it.content().str("createdAt")}
        .forEach {add(OrganizeSelection("inbox",it.id))}
    (0..3).forEach {quadrant->OrganizeActions.tasks(state,quadrant).forEach {add(OrganizeSelection("task",it.id))}}
}

internal fun organizeKeyCommand(key:Key,keyDown:Boolean,hasModifiers:Boolean,inputFocused:Boolean):OrganizeKeyCommand? {
    if(!keyDown||hasModifiers||inputFocused)return null
    return when(key) {
        Key.DirectionUp->OrganizeKeyCommand.UP
        Key.DirectionDown->OrganizeKeyCommand.DOWN
        Key.One->OrganizeKeyCommand.ONE
        Key.Two->OrganizeKeyCommand.TWO
        Key.Three->OrganizeKeyCommand.THREE
        Key.Four->OrganizeKeyCommand.FOUR
        Key.Spacebar->OrganizeKeyCommand.SPACE
        Key.Escape->OrganizeKeyCommand.ESCAPE
        else->null
    }
}

internal class UiOrganizeKeyboard {
    var selection by mutableStateOf<OrganizeSelection?>(null);private set
    private var previousVisible=emptyList<OrganizeSelection>()
    private var anchorIndex=0

    fun select(item:OrganizeSelection?) {
        selection=item
        item?.let {previousVisible.indexOf(it)}?.takeIf {it>=0}?.let {anchorIndex=it}
    }

    /** A completed selection is retained so Space can immediately restore it; deleted items never are. */
    fun reconcile(state:SyncState,visible:List<OrganizeSelection>) {
        selection?.let {selected->
            val index=visible.indexOf(selected)
            if(index>=0)anchorIndex=index
            else {
                val item=state.items[selected.key]
                val retainedCompletion=selected.kind=="task"&&item!=null&&!item.deleted&&item.completed()&&!LibrarySearch.archived(item)
                if(!retainedCompletion)selection=visible.getOrNull(anchorIndex.coerceAtMost(visible.lastIndex))
            }
        }
        previousVisible=visible
    }

    fun acceptSaved(action:OrganizeKeyboardAction.Edit,state:SyncState) {
        val item=state.items[action.selectionAfter.key]
        if(item!=null&&!item.deleted)select(action.selectionAfter)
    }

    /** Navigation is immediate. Persisted changes are deferred through the caller's single UiWork. */
    fun handle(command:OrganizeKeyCommand,shown:SyncState,visible:List<OrganizeSelection>,busy:Boolean=false,onAction:(OrganizeKeyboardAction)->Unit):Boolean {
        if(busy)return false
        reconcile(shown,visible)
        when(command) {
            OrganizeKeyCommand.UP,OrganizeKeyCommand.DOWN->{
                val delta=if(command==OrganizeKeyCommand.UP)-1 else 1
                val index=selection?.let {visible.indexOf(it)}?:-1
                val next=when {
                    selection==null->if(delta>0)0 else visible.lastIndex
                    index>=0->index+delta
                    else->anchorIndex+if(delta<0)-1 else 0
                }
                select(visible.getOrNull(next.coerceIn(0,visible.lastIndex.coerceAtLeast(0))))
            }
            OrganizeKeyCommand.ESCAPE->{
                if(selection==null)return false
                select(null)
            }
            OrganizeKeyCommand.SPACE->{
                val selected=selection
                if(selected?.kind=="task")shown.items[selected.key]?.takeUnless {it.deleted}?.let {task->
                    onAction(OrganizeKeyboardAction.Complete(task.id,!task.completed()))
                }
            }
            else->{
                val selected=selection
                if(selected==null) {select(visible.firstOrNull());return true}
                val item=shown.items[selected.key]?:return true
                val quadrant=when(command) {OrganizeKeyCommand.ONE->0;OrganizeKeyCommand.TWO->1;OrganizeKeyCommand.THREE->2;else->3}
                val now=Instant.now()
                if(selected.kind=="inbox") {
                    val taskId=uid()
                    onAction(OrganizeKeyboardAction.Edit(OrganizeSelection("task",taskId)) {current->
                        organizeInbox(current,item,quadrant,false,taskId,now)
                    })
                } else if(!item.completed()&&(item.content().number("quadrant")?:1)!=quadrant) {
                    onAction(OrganizeKeyboardAction.Edit(selected) {current->
                        // Capture the shown order, not the potentially newer state at persistence time.
                        OrganizeActions.move(current,item.id,quadrant,null,OrganizeMoveBaseline.from(shown,item.id),now)
                    })
                }
            }
        }
        return true
    }

    fun processKey(event:KeyEvent,shown:SyncState,visible:List<OrganizeSelection>,work:UiWork,inputFocused:Boolean=false):Boolean {
        val command=organizeKeyCommand(event.key,event.type==KeyEventType.KeyDown,
            event.isCtrlPressed||event.isAltPressed||event.isMetaPressed||event.isShiftPressed,inputFocused)?:return false
        return handle(command,shown,visible,work.busy) {action->
            when(action) {
                is OrganizeKeyboardAction.Edit->work.editSnapshot({acceptSaved(action,it)},action.change)
                // Runtime completion settles an active run before marking the parent complete.
                is OrganizeKeyboardAction.Complete->work.complete(action.taskId,action.completed)
            }
        }
    }
}

/** Attach to a focusable workspace. Input owners report focus rather than sacrificing text shortcuts. */
internal fun Modifier.organizeKeyboard(keyboard:UiOrganizeKeyboard,state:SyncState,visible:List<OrganizeSelection>,work:UiWork,inputFocused:Boolean=false):Modifier =
    onKeyEvent {keyboard.processKey(it,state,visible,work,inputFocused)}
