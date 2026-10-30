package com.pare.app

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId

object ContentActions {
    fun save(entity:Entity?,kind:String,text:String,note:String,quadrant:Int,id:String,now:Instant):JsonObject {
        require(text.isNotBlank() && kind in setOf("inbox","task","idea"))
        require(quadrant in 0..3)
        val content:MutableMap<String,JsonElement> = entity?.content()?.toMutableMap() ?: mutableMapOf("id" to JsonPrimitive(id.lowercase()),"createdAt" to JsonPrimitive(now.toString()))
        content["text"]=JsonPrimitive(text)
        if(kind!="inbox") {
            val noteKey=if(kind=="idea") "notes" else "note"
            if(note.isBlank()) content.remove(noteKey) else content[noteKey]=JsonPrimitive(note)
        }
        if(kind=="task") {
            content["quadrant"]=JsonPrimitive(quadrant)
            content.putIfAbsent("completed",JsonPrimitive(false))
            content["lastProgressAt"]=JsonPrimitive(now.toString())
            listOf("isToday","todayOrder","isDailyWin","scheduledAt","scheduledMinutes","scheduleReminderLeadMinutes","lastImplementationTriggeredAt").forEach {content.remove(it)}
        }
        if(kind=="idea") {
            content["updatedAt"]=JsonPrimitive(now.toString())
            content.putIfAbsent("stage",JsonPrimitive("seed"))
            content.putIfAbsent("entries",JsonArray(emptyList()))
            content.putIfAbsent("linkedTaskIDs",JsonArray(emptyList()))
        }
        val result=entity?.payload?.toMutableMap() ?: mutableMapOf()
        result["content"]=JsonObject(content)
        if(kind=="task") result.putIfAbsent("lifecycle",JsonPrimitive("active"))
        return JsonObject(result)
    }
    fun planId(state:SyncState,now:Instant):String {
        val profile=state.items["profile:profile"]?.takeUnless {it.deleted}
        val accountZone=profile?.content()?.get("planningTimeZone")?.jsonPrimitive?.contentOrNull
        val fallback=state.items.values.firstOrNull {it.kind=="day_plan" && !it.deleted}?.content()?.get("time_zone")?.jsonPrimitive?.contentOrNull
        val zone=runCatching {ZoneId.of(accountZone ?: fallback ?: ZoneId.systemDefault().id)}.getOrDefault(ZoneId.systemDefault())
        return "${now.atZone(zone).toLocalDate()}@${zone.id}"
    }
}

fun Entity.content() = payload?.get("content") as? JsonObject
fun Entity.title() = content()?.get("text")?.jsonPrimitive?.contentOrNull ?: id
fun Entity.completed() = content()?.get("completed")?.jsonPrimitive?.booleanOrNull ?: false
