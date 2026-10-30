package com.pare.app

import kotlinx.serialization.json.*

/** Presentation ordering never depends on database/map insertion order. Today keeps its explicit plan order. */
fun stableEntities(items: Collection<Entity>): List<Entity> = items.sortedWith(
    compareBy<Entity> { it.content()?.get("createdAt")?.jsonPrimitive?.contentOrNull ?: "" }
        .thenBy { it.key }
)

fun ideaStageLabel(stage: String?): String = when (stage) {
    null, "seed" -> "萌芽"
    "exploring" -> "探索"
    "building" -> "推进"
    "parked" -> "暂放"
    "archived" -> "归档"
    else -> "其他阶段"
}

val quadrantLabels = listOf("重要 · 紧急", "重要 · 不紧急", "紧急 · 不重要", "留待以后")

data class EditorDraft(
    val entityId: String,
    val kind: String,
    val text: String = "",
    val note: String = "",
    val quadrant: Int = 1,
    val nextAction: String = "",
    val estimate: String = "",
    val addToToday: Boolean = false,
    val isNew: Boolean = true
) {
    fun editBaseline() = ContentEditBaseline(isNew, text, note, quadrant, nextAction, estimate.toIntOrNull())

    fun validationError(): String? = when {
        text.isBlank() -> "先写下一点内容"
        kind == "task" && estimate.isNotBlank() && (estimate.toIntOrNull()?.let { it > 0 } != true) -> "预计时长请填写有效的正整数分钟数，或留空"
        else -> null
    }
    companion object {
        fun from(entity: Entity) = EditorDraft(
            entityId = entity.id, kind = entity.kind, text = entity.title(),
            note = entity.content()?.get(if (entity.kind == "idea") "notes" else "note")?.jsonPrimitive?.contentOrNull.orEmpty(),
            quadrant = entity.content()?.get("quadrant")?.jsonPrimitive?.intOrNull ?: 1,
            nextAction = entity.content()?.get("nextAction")?.jsonPrimitive?.contentOrNull.orEmpty(),
            estimate = entity.content()?.get("estimatedMinutes")?.jsonPrimitive?.intOrNull?.toString().orEmpty(),
            isNew = false
        )
    }
}

enum class DraftOpenAction { OPEN_REQUESTED, RESUME_EXISTING, CONFIRM_SWITCH }
fun draftOpenAction(current: EditorDraft, requested: EditorDraft): DraftOpenAction = when {
    current.entityId.isEmpty() -> DraftOpenAction.OPEN_REQUESTED
    current.entityId == requested.entityId && current.kind == requested.kind -> DraftOpenAction.RESUME_EXISTING
    else -> DraftOpenAction.CONFIRM_SWITCH
}

/** A failure retains the editor and its draft. Only acknowledged local persistence closes it. */
data class EditorSaveState(val saving: Boolean = false, val error: String? = null, val saved: Boolean = false, val staleContent: Boolean = false) {
    fun begin(draft: EditorDraft): EditorSaveState = draft.validationError()?.let { copy(error = it) }
        ?: EditorSaveState(saving = true)
    fun finish(result: Result<Unit>): EditorSaveState = if (result.isSuccess) EditorSaveState(saved = true)
        else result.exceptionOrNull().let { failure ->
            if (failure is StaleContentEditException) EditorSaveState(error = failure.message, staleContent = true)
            else EditorSaveState(error = "暂时无法保存，草稿已保留。请重试。")
        }
}

data class ConflictField(val label: String, val value: String)
fun conflictFields(kind: String, payload: JsonObject?, deleted: Boolean, taskTitles: Map<String, String> = emptyMap()): List<ConflictField> {
    if (deleted || payload == null) return listOf(ConflictField("状态", "已删除"))
    val content = payload["content"] as? JsonObject ?: return listOf(ConflictField("状态", "内容暂不可用"))
    fun string(key: String) = content[key]?.jsonPrimitive?.contentOrNull
    val fields = mutableListOf<ConflictField>()
    when (kind) {
        "task", "inbox", "idea" -> {
            fields += ConflictField("内容", string("text").orEmpty())
            if (kind != "inbox") fields += ConflictField("备注", string(if (kind == "idea") "notes" else "note")?.ifBlank { null } ?: "无")
            if (kind == "task") {
                fields += ConflictField("下一步", string("nextAction")?.ifBlank { null } ?: "未填写")
                fields += ConflictField("停止标准", string("definitionOfDone")?.ifBlank { null } ?: "未填写")
                val blockers = content["blockerEntries"] as? JsonArray ?: JsonArray(emptyList())
                fields += ConflictField("卡点记录", if (blockers.isEmpty()) "暂无" else "共 ${blockers.size} 条\n" + blockers.takeLast(5).joinToString("\n") {
                    val entry = it.jsonObject
                    val reason = entry["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val label = supportReasons.firstOrNull { it.code == reason }?.label ?: reason
                    "${entry["at"]?.jsonPrimitive?.contentOrNull.orEmpty()} · $label"
                })
                fields += ConflictField("预计时长", string("estimatedMinutes")?.let { "$it 分钟" } ?: "未填写")
                fields += ConflictField("状态", if (content["completed"]?.jsonPrimitive?.booleanOrNull == true) "已完成" else "待完成")
                fields += ConflictField("优先级", quadrantLabels.getOrElse(content["quadrant"]?.jsonPrimitive?.intOrNull ?: 1) { "未分类" })
                if (payload["lifecycle"]?.jsonPrimitive?.contentOrNull == "archived") fields += ConflictField("归档", "已归档")
            }
            if (kind == "idea") {
                fields += ConflictField("阶段", ideaStageLabel(string("stage")))
                fields += ConflictField("探索问题", string("nextQuestion")?.ifBlank { null } ?: "未填写")
                val entries = content["entries"] as? JsonArray ?: JsonArray(emptyList())
                fields += ConflictField("探索记录", if (entries.isEmpty()) "暂无" else "共 ${entries.size} 条，以下为最近至多5条\n" + entries.takeLast(5).joinToString("\n") {
                    val entry = it.jsonObject
                    "${ideaEntryKindLabel(entry["kind"]?.jsonPrimitive?.contentOrNull ?: "note")}：${entry["text"]?.jsonPrimitive?.contentOrNull.orEmpty().take(600)}"
                })
                fields += ConflictField("关联行动", (content["linkedTaskIDs"] as? JsonArray)?.joinToString("、") {
                    taskTitles[it.jsonPrimitive.content] ?: it.jsonPrimitive.content
                }?.ifBlank { null } ?: "暂无")
            }
        }
        "day_plan" -> {
            fields += ConflictField("日期", string("day_key").orEmpty())
            fields += ConflictField("时区", string("time_zone").orEmpty())
            fields += ConflictField("今日任务", (content["ordered_task_ids"] as? JsonArray)?.joinToString("、") { taskTitles[it.jsonPrimitive.content] ?: it.jsonPrimitive.content } ?: "无")
            fields += ConflictField("今日重点", string("daily_win_task_id")?.let { taskTitles[it] ?: it } ?: "未设置")
            fields += ConflictField("当天投入", (content["task_plans"] as? JsonArray)?.mapNotNull { value ->
                val plan = value.jsonObject
                val allocation = plan["allocation"] as? JsonObject ?: return@mapNotNull null
                val id = plan["task_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                "${taskTitles[id] ?: id}：${if (allocation["kind"]?.jsonPrimitive?.contentOrNull == "check") "检查" else "自己推进"} ${allocation["planned_minutes"]?.jsonPrimitive?.contentOrNull ?: "未定"} 分钟"
            }?.joinToString("\n")?.ifBlank { null } ?: "尚未安排")
        }
        "profile" -> {
            fields += ConflictField("规划时区", string("planningTimeZone") ?: "未设置")
            fields += ConflictField("背景与偏好", content.toString())
        }
        else -> fields += ConflictField("内容", content.toString())
    }
    return fields
}
