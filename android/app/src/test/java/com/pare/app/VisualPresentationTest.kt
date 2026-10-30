package com.pare.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class VisualPresentationTest {
    @Test fun planningDateKeepsAccountDayAndChineseWeekday() {
        assertEquals("10月30日  周五", planningDayLabel("2026-10-30"))
        assertEquals("1月1日  周日", planningDayLabel("2023-01-01"))
    }

    @Test fun equivalentCurrentOffsetsDoNotAddTimezoneNoise() {
        val now = Instant.parse("2026-10-30T12:00:00Z")
        assertNull(planningZoneLabel("Asia/Shanghai", now, ZoneId.of("Asia/Shanghai")))
        assertNull(planningZoneLabel("Asia/Shanghai", now, ZoneId.of("+08:00")))
    }

    @Test fun differentZoneExplainsOffsetIncludingPartialHours() {
        val now = Instant.parse("2026-10-30T12:00:00Z")
        assertEquals("规划时区 UTC+05:30 · 比本机慢2小时30分钟",
            planningZoneLabel("Asia/Kolkata", now, ZoneId.of("Asia/Shanghai")))
        assertEquals("规划时区 UTC+08:00 · 比本机快8小时",
            planningZoneLabel("Asia/Shanghai", now, ZoneId.of("UTC")))
    }

    @Test fun timezoneOffsetUsesPlanningInstantAcrossDaylightSaving() {
        assertEquals("规划时区 UTC-04:00 · 比本机慢12小时",
            planningZoneLabel("America/New_York", Instant.parse("2026-10-30T12:00:00Z"), ZoneId.of("Asia/Shanghai")))
        assertEquals("规划时区 UTC-05:00 · 比本机慢13小时",
            planningZoneLabel("America/New_York", Instant.parse("2026-11-02T12:00:00Z"), ZoneId.of("Asia/Shanghai")))
    }

    @Test fun ideaDateUsesLocalDayAcrossMidnight() {
        assertEquals("10月31日", ideaDateLabel("2026-10-30T20:00:00Z", ZoneId.of("Asia/Shanghai")))
        assertNull(ideaDateLabel(null))
        assertNull(ideaDateLabel("not-a-date"))
    }
}
