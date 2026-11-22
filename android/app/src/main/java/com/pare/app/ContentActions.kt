package com.pare.app

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId

/** Only fields the mobile editor writes are compared; sync acknowledgements and unrelated history may advance. */
data class ContentEditBaseline(
    val isNew: Boolean,
    val text: String = "",
    val note: String = "",
    val quadrant: Int = 1,
    val nextAction: String = "",
    val estimatedMinutes: Int? = null,
    val lastCompletedStepId: String? = null
) {
    companion object {
        fun from(entity: Entity?): ContentEditBaseline {
            val content = entity?.content()
            return ContentEditBaseline(
                isNew = entity == null || (content == null && entity.revision == 0L && !entity.deleted),
                text = entity?.title().orEmpty(),
                note = content?.get(if (entity?.kind == "idea") "notes" else "note")?.jsonPrimitive?.contentOrNull.orEmpty(),
                quadrant = content?.get("quadrant")?.jsonPrimitive?.intOrNull ?: 1,
                nextAction = content?.get("nextAction")?.jsonPrimitive?.contentOrNull.orEmpty(),
                estimatedMinutes = content?.get("estimatedMinutes")?.jsonPrimitive?.intOrNull,
                lastCompletedStepId = (content?.get("completedSteps") as? JsonArray)?.lastOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            )
        }
    }
}

enum class StaleEditReason { UPDATED, DELETED, ALREADY_EXISTS }
class StaleContentEditException(val reason: StaleEditReason) : IllegalStateException(when (reason) {
    StaleEditReason.UPDATED -> "这条内容已在另一端更新，草稿已保留。请查看最新版本后再决定。"
    StaleEditReason.DELETED -> "这条内容已在另一端删除，草稿已保留。可取消并保留草稿，稍后再处理。"
    StaleEditReason.ALREADY_EXISTS -> "这条内容已经存在，草稿已保留。请查看最新版本，避免重复覆盖。"
})

object ContentActions {
    private fun requireUnchanged(entity: Entity?, kind: String, expected: ContentEditBaseline) {
        if (expected.isNew) {
            if (entity != null) throw StaleContentEditException(StaleEditReason.ALREADY_EXISTS)
            return
        }
        if (entity == null || entity.deleted || entity.payload == null) throw StaleContentEditException(StaleEditReason.DELETED)
        val current = ContentEditBaseline.from(entity)
        if (current.text != expected.text ||
            (kind != "inbox" && current.note != expected.note) ||
            (kind == "task" && (current.quadrant != expected.quadrant || current.nextAction != expected.nextAction || current.estimatedMinutes != expected.estimatedMinutes || current.lastCompletedStepId != expected.lastCompletedStepId))) {
            throw StaleContentEditException(StaleEditReason.UPDATED)
        }
    }

    fun save(entity:Entity?,kind:String,text:String,note:String,quadrant:Int,id:String,now:Instant,
        estimatedMinutes:Int?=entity?.content()?.get("estimatedMinutes")?.jsonPrimitive?.intOrNull,
        nextAction:String?=entity?.content()?.get("nextAction")?.jsonPrimitive?.contentOrNull,
        expected:ContentEditBaseline?=null
    ):JsonObject {
        expected?.let { requireUnchanged(entity, kind, it) }
        require(text.isNotBlank() && kind in setOf("inbox","task","idea"))
        require(quadrant in 0..3)
        val content:MutableMap<String,JsonElement> = entity?.content()?.toMutableMap() ?: mutableMapOf("id" to JsonPrimitive(id.lowercase()),"createdAt" to JsonPrimitive(now.toString()))
        content["text"]=JsonPrimitive(text)
        if(kind!="inbox") {
            val noteKey=if(kind=="idea") "notes" else "note"
            if(note.isBlank()) content.remove(noteKey) else content[noteKey]=JsonPrimitive(note)
        }
        if(kind=="task") {
            require(estimatedMinutes == null || estimatedMinutes > 0) {"预计时长无效"}
            if(estimatedMinutes == null) content.remove("estimatedMinutes") else content["estimatedMinutes"]=JsonPrimitive(estimatedMinutes)
            if(nextAction.isNullOrBlank()) {content.remove("nextAction");content.remove("definitionOfDone")} else content["nextAction"]=JsonPrimitive(nextAction.trim())
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
