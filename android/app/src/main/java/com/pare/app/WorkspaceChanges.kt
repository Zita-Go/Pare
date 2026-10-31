package com.pare.app

import kotlinx.serialization.json.*

/** Validate compound workspace edits before the repository commits anything. */
object WorkspaceChanges {
    fun edit(engine:SyncEngine,execution:ExecutionController,ready:Boolean,change:(SyncState)->SyncState):SyncState = synchronized(execution) {
        engine.edit {before ->
            val active=(listOfNotNull(execution.state.value.focus)+execution.state.value.external).map {it.taskId}.toSet()
            checked(before,change(before),active,ready)
        }
    }
    fun checked(before:SyncState,after:SyncState,activeTaskIDs:Set<String>,executionReady:Boolean):SyncState {
        val changed=(before.items.keys+after.items.keys).filter {before.items[it]?.payload!=after.items[it]?.payload || before.items[it]?.deleted!=after.items[it]?.deleted}
        for(key in changed) {
            val old=before.items[key];val next=after.items[key]
            if(old?.kind=="task" && !old.deleted && (next==null || next.deleted ||
                (old.payload?.get("lifecycle")!=JsonPrimitive("archived") && next.payload?.get("lifecycle")==JsonPrimitive("archived")))) {
                require(executionReady) {"本机计时状态尚未确认，任务暂未归档或删除"}
                require(old.id !in activeTaskIDs) {"这件事还有专注或等待记录，请先结束记录，再归档或删除；内容未改变"}
            }
        }
        val groups=changed.mapNotNull {after.items[it]?.takeIf {it.dirty}?.let {e->e.group.ifEmpty {e.key}}}.toSet()
        for(group in groups) {
            val items=after.items.values.filter {it.dirty && it.group.ifEmpty {it.key}==group}
            require(items.size<=100) {"这次关联修改超过同步上限，尚未保存"}
            val mutations=items.map {Mutation(it.kind,it.id,it.revision,deleted=it.deleted,payload=it.payload)}
            require(wire.encodeToString(mutations).toByteArray(Charsets.UTF_8).size<=1_048_576) {"这次关联内容过大，尚未保存"}
        }
        return after
    }
}
