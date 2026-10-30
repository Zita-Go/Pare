package com.pare.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Format the persisted planning day, which may differ from the device's calendar day. */
internal fun planningDayLabel(day: String): String = runCatching {
    val date = LocalDate.parse(day)
    val weekday = listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]
    "${date.monthValue}月${date.dayOfMonth}日  周$weekday"
}.getOrDefault(day)

internal fun planningZoneLabel(zone: String, now: Instant, deviceZone: ZoneId = ZoneId.systemDefault()): String? = runCatching {
    val planningOffset = ZoneId.of(zone).rules.getOffset(now)
    val difference = planningOffset.totalSeconds - deviceZone.rules.getOffset(now).totalSeconds
    if (difference == 0) null else {
        val minutes = kotlin.math.abs(difference) / 60
        val duration = buildString {
            if (minutes >= 60) append("${minutes / 60}小时")
            if (minutes % 60 != 0) append("${minutes % 60}分钟")
        }
        val offset = if (planningOffset.totalSeconds == 0) "+00:00" else planningOffset.id
        "规划时区 UTC$offset · 比本机${if (difference > 0) "快" else "慢"}$duration"
    }
}.getOrNull()

internal fun ideaDateLabel(timestamp: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
    timestamp?.let { runCatching {
        val date = Instant.parse(it).atZone(zone).toLocalDate()
        "${date.monthValue}月${date.dayOfMonth}日"
    }.getOrNull() }
