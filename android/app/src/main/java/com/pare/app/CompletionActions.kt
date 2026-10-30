package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*

object CompletionActions {
    /** Desired value makes rapid repeated taps idempotent; resolve latest lifecycle inside the transaction. */
    fun set(state:SyncState,taskId:String,completed:Boolean,now:Instant):SyncState {
        val current=state.items["task:$taskId"]
        require(current!=null && !current.deleted && current.conflict==null && current.payload?.get("lifecycle")?.jsonPrimitive?.contentOrNull!="archived") {"任务不可用或存在冲突，请查看最新内容"}
        if(current.completed()==completed)return state
        val content=current.content()!!.toMutableMap()
        content["completed"]=JsonPrimitive(completed)
        content["lastProgressAt"]=JsonPrimitive(now.toString())
        if(completed)content["completedAt"]=JsonPrimitive(now.toString()) else content.remove("completedAt")
        return StateMachine.edit(state,"task",taskId,JsonObject(current.payload!!.toMutableMap().apply {put("content",JsonObject(content))}))
    }
}
