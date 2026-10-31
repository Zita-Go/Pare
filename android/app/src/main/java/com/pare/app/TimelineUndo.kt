package com.pare.app

import kotlinx.serialization.json.*

/** A drag may change several day plans during legacy migration. Undo only the exact saved result. */
data class TimelineUndo(val owner:String?,val device:String,val before:Map<String,Entity?>,val after:Map<String,Entity?>) {
    fun apply(state:SyncState):SyncState {
        require(state.owner==owner && state.device==device) {"资料库已改变，无法撤销这次安排"}
        for((key,expected) in after) {
            val current=state.items[key]
            require(current?.conflict==null && current?.deleted==expected?.deleted && current?.payload==expected?.payload) {"安排已再次修改，未覆盖新的内容"}
        }
        var next=state
        for((key,old) in before) {
            val id=key.substringAfter(':')
            // A newly created plan is reset to an empty live plan. A tombstone would
            // suppress the restored legacy source and forbid arranging this date again.
            if(old==null) next=StateMachine.edit(next,"day_plan",id,buildJsonObject {
                put("content",buildJsonObject {put("day_key",id.substringBefore('@'));put("time_zone",id.substringAfter('@'))
                    put("ordered_task_ids",JsonArray(emptyList()));put("task_plans",JsonArray(emptyList()))})
            })
            else next=StateMachine.edit(next,"day_plan",id,old.payload,deleted=old.deleted)
        }
        return StateMachine.group(next,before.keys)
    }
    companion object {
        fun capture(before:SyncState,after:SyncState):TimelineUndo? {
            val keys=(before.items.keys+after.items.keys).filter {it.startsWith("day_plan:") && before.items[it]?.payload!=after.items[it]?.payload}.toSet()
            return keys.takeIf {it.isNotEmpty()}?.let {TimelineUndo(before.owner,before.device,it.associateWith(before.items::get),it.associateWith(after.items::get))}
        }
    }
}
