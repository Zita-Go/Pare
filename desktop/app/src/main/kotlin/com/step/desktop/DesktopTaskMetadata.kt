package com.step.desktop

import com.pare.app.*
import kotlinx.serialization.json.*
import java.time.Instant

internal data class DesktopMetadataBaseline(val estimate:JsonElement?,val metadata:TaskMetadataBaseline) {
    companion object {fun from(task:Entity)=DesktopMetadataBaseline(task.content()?.get("estimatedMinutes"),TaskMetadataBaseline.from(task))}
}
internal object DesktopTaskMetadata {
    fun save(state:SyncState,id:String,expected:DesktopMetadataBaseline,estimate:Int?,due:Instant?,actual:Int?,now:Instant):SyncState {
        require(estimate==null || estimate in 1..1_000_000) {"预计时长请填写 1–1000000 分钟，或留空清除"}
        val task=state.items["task:$id"]
        require(task!=null && !task.deleted && task.payload!=null) {"任务已不可用，草稿仍保留"}
        require(task.conflict==null && task.conflictGroup.isEmpty() && state.items.values.none {task.group.isNotEmpty()&&it.group==task.group&&it.conflict!=null}) {"请先处理同步冲突"}
        fun normalized(v:JsonElement?)=v?.takeUnless {it==JsonNull}
        val proposed=estimate?.let(::JsonPrimitive)
        val changed=proposed!=normalized(expected.estimate)
        if(changed&&normalized(task.content()?.get("estimatedMinutes"))!=normalized(expected.estimate))throw StaleContentEditException(StaleEditReason.UPDATED)
        var result=TaskMetadataActions.save(state,id,expected.metadata,due,actual,now)
        if(changed) {
            val latest=result.items.getValue(task.key)
            val content=latest.content()!!.toMutableMap().apply {if(proposed==null)remove("estimatedMinutes")else put("estimatedMinutes",proposed)}
            val payload=JsonObject(latest.payload!!.toMutableMap().apply {put("content",JsonObject(content))})
            PayloadValidation.validate("task",id,payload)
            result=StateMachine.edit(result,"task",id,payload)
        }
        return result
    }
}
