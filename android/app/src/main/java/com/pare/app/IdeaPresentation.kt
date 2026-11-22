package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*

data class IdeaTimelineEntry(val id: String, val text: String, val kind: String, val createdAt: String)
fun ideaTimeline(idea: Entity): List<IdeaTimelineEntry> =
    (idea.content()?.get("entries") as? JsonArray).orEmpty().mapNotNull { value ->
        val entry = value as? JsonObject ?: return@mapNotNull null
        val text = (entry["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        IdeaTimelineEntry((entry["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(), text,
            (entry["kind"] as? JsonPrimitive)?.contentOrNull ?: "note",
            (entry["createdAt"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    }.sortedWith(compareByDescending<IdeaTimelineEntry> { runCatching { Instant.parse(it.createdAt) }.getOrNull() }.thenBy { it.id })

fun ideaEntryKindLabel(kind: String) = when (kind) {
    "finding" -> "发现"
    "decision" -> "决定"
    "note" -> "记录"
    else -> "探索记录"
}

fun ideaCanGrow(idea: Entity?): Boolean = idea != null && !idea.deleted &&
    idea.payload?.get("lifecycle")?.jsonPrimitive?.contentOrNull != "archived" &&
    idea.content()?.get("stage")?.jsonPrimitive?.contentOrNull != "archived"

data class IdeaContextDraft(val stage: String, val question: String, val baseline: IdeaContextBaseline)
data class IdeaEntryDraft(val id: String = uid(), val text: String = "", val kind: String = "note")
data class IdeaActionDraft(
    val id: String = uid(), val allocationId: String = uid(), val title: String = "",
    val next: String = "", val done: String = "", val quadrant: Int = 1,
    val today: Boolean = false, val minutes: String = "15", val baseline: DayAllocationBaseline
) {
    fun validationError(): String? = when {
        title.isBlank() -> "写下一个具体的行动"
        today && (minutes.toIntOrNull()?.let { it in 1..1440 } != true) -> "当天投入请填写1–1440分钟"
        else -> null
    }
}
