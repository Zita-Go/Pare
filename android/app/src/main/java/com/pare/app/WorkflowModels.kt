package com.pare.app

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class SupportReason(val code: String, val label: String, val guidance: String)
val supportReasons = listOf(
    SupportReason("unclear", "不知道下一步", "先找出一个能看见结果的动作，例如打开资料并写下一个问题。"),
    SupportReason("tooLarge", "任务太大", "只选一小部分：一段文字、一页资料或一个待验证的问题。写清做到哪里就停。"),
    SupportReason("lowEnergy", "精力不足", "挑一个现在做得动的小动作，例如打开文件、列三点，或整理一份材料。先安排5分钟。"),
    SupportReason("resistance", "有压力 / 抗拒", "先写出让你犹豫的一个问题，再选一个可以撤回、无需一次做好的动作。")
)

fun validTodayMinutes(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it in 1..1440 }
fun taskCanBePlanned(task: Entity?): Boolean = task != null && !task.deleted && task.content() != null &&
    !task.completed() && task.payload?.get("lifecycle")?.jsonPrimitive?.contentOrNull != "archived"
