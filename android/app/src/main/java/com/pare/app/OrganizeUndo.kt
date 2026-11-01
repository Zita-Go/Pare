package com.pare.app

import kotlinx.serialization.json.*

/** Restore only fields changed by the move; later notes and history are never replaced. */
data class OrganizeUndo(val owner:String?,val device:String,val before:Map<String,JsonObject>,val after:Map<String,JsonObject>,val afterOrder:Map<Int,List<OrganizeTaskPosition>>) {
    fun apply(state:SyncState):SyncState {
        check(state.owner==owner && state.device==device){"资料库已改变，无法撤销这次移动"}
        afterOrder.forEach {(quadrant,expected)->check(order(state,quadrant)==expected){"象限内容或顺序已再次调整，未覆盖新的位置"}}
        val tasks=after.mapValues {(id,expected)->
            val task=libraryCurrent(state,"task",id)
            check(!task.completed() && !LibrarySearch.archived(task)){"任务状态已改变，未撤销移动"}
            check(position(task)==expected){"任务位置已再次修改，未覆盖新的调整"}
            task
        }
        var result=state
        before.forEach {(id,previous)->
            val task=tasks.getValue(id)
            val content=task.content()!!.toMutableMap()
            fields.forEach {field->previous[field]?.let {content[field]=it} ?: content.remove(field)}
            result=libraryReplace(result,task,content)
        }
        result=StateMachine.group(result,before.keys.map {"task:$it"}.toSet())
        val groups=before.keys.mapNotNull {result.items["task:$it"]?.group}.toSet()
        check(result.items.values.filter {it.dirty && it.group in groups}.none {it.conflict!=null || it.conflictGroup.isNotEmpty()}) {"相关内容有同步冲突，未撤销移动"}
        return WorkspaceChanges.checked(state,result,emptySet(),true)
    }
    companion object {
        private val fields=setOf("quadrant","quadrantOrder","lastProgressAt")
        private fun position(task:Entity)=JsonObject(task.content().orEmpty().filterKeys {it in fields})
        private fun order(state:SyncState,quadrant:Int)=OrganizeActions.tasks(state,quadrant).map {
            OrganizeTaskPosition(it.id,quadrant,it.content()?.get("quadrantOrder"),it.content()?.get("createdAt"))
        }
        fun capture(old:SyncState,new:SyncState):OrganizeUndo? {
            val tasks=old.items.values.filter {it.kind=="task" && new.items[it.key]!=null && position(it)!=position(new.items.getValue(it.key))}
            if(tasks.isEmpty())return null
            val quadrants=tasks.flatMap {listOf(it,new.items.getValue(it.key))}.map {it.content()?.timelineInt("quadrant") ?: 1}.toSet()
            return OrganizeUndo(old.owner,old.device,tasks.associate {it.id to position(it)},tasks.associate {it.id to position(new.items.getValue(it.key))},quadrants.associateWith {order(new,it)})
        }
    }
}
