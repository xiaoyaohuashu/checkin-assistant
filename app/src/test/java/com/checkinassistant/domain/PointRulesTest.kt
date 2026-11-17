package com.checkinassistant.domain

import com.checkinassistant.TestSupport.at
import com.checkinassistant.TestSupport.point
import com.checkinassistant.TestSupport.useKualaLumpur
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/** 重复模式、临时跳过、时间段（两端都含的那一套）、窗口去重、一次性提醒。2026-11-09 是周一。 */
class PointRulesTest {
    private lateinit var previousZone: TimeZone
    @Before fun setUp() { previousZone = useKualaLumpur() }
    @After fun tearDown() { TimeZone.setDefault(previousZone) }

    private val mon = "2026-11-09"
    private val tue = "2026-11-10"

    // ── isInTimeRange：两端都含 ──
    @Test fun timeRange_normalWindow_includesBothEnds() {
        val p = point(start = "09:00", end = "18:00")
        assertFalse(PointRules.isInTimeRange(p, at(mon, "08:59")))
        assertTrue(PointRules.isInTimeRange(p, at(mon, "09:00")))
        assertTrue(PointRules.isInTimeRange(p, at(mon, "18:00")))
        assertTrue(PointRules.isInTimeRange(p, at(mon, "18:00", second = 59)))
        assertFalse(PointRules.isInTimeRange(p, at(mon, "18:01")))
    }

    @Test fun timeRange_overnightWindow() {
        val p = point(start = "23:00", end = "01:00")
        assertFalse(PointRules.isInTimeRange(p, at(mon, "22:59")))
        assertTrue(PointRules.isInTimeRange(p, at(mon, "23:00")))
        assertTrue(PointRules.isInTimeRange(p, at(tue, "00:30")))
        assertTrue(PointRules.isInTimeRange(p, at(tue, "01:00")))
        assertFalse(PointRules.isInTimeRange(p, at(tue, "01:01")))
    }

    @Test fun timeRange_noWindowOrHalfWindow_alwaysTrue() {
        assertTrue(PointRules.isInTimeRange(point(), at(mon, "03:00")))
        assertTrue(PointRules.isInTimeRange(point(start = "09:00"), at(mon, "03:00")))
        assertTrue(PointRules.isInTimeRange(point(end = "09:00"), at(mon, "23:00")))
    }

    @Test fun timeRange_startEqualsEnd_onlyThatMinute() {
        val p = point(start = "10:00", end = "10:00")
        assertTrue(PointRules.isInTimeRange(p, at(mon, "10:00")))
        assertFalse(PointRules.isInTimeRange(p, at(mon, "10:01")))
        assertFalse(PointRules.isInTimeRange(p, at(mon, "09:59")))
    }

    // ── getWindowStart ──
    @Test fun windowStart_cases() {
        assertEquals(at(mon, "00:00"), PointRules.getWindowStart(point(), at(mon, "15:20")))
        assertEquals(at(mon, "09:00"), PointRules.getWindowStart(point(start = "09:00", end = "18:00"), at(mon, "10:00")))
        // 还没到开始时间，也返回当天的开始时刻
        assertEquals(at(mon, "09:00"), PointRules.getWindowStart(point(start = "09:00", end = "18:00"), at(mon, "07:00")))
        val overnight = point(start = "23:00", end = "01:00")
        assertEquals(at(mon, "23:00"), PointRules.getWindowStart(overnight, at(mon, "23:30")))
        assertEquals(at(mon, "23:00"), PointRules.getWindowStart(overnight, at(tue, "00:30")))
        // 现状：跨夜时间段在中午（不在时间段内）时，返回的是昨天的开始时刻
        assertEquals(at(mon, "23:00"), PointRules.getWindowStart(overnight, at(tue, "12:00")))
        assertEquals(at(mon, "09:00"), PointRules.getWindowStart(point(start = "09:00"), at(mon, "03:00")))
    }

    // ── isActiveDay：重复模式与跳过 ──
    @Test fun activeDay_weekly_bitmask() {
        val mondayOnly = point(repeatMode = "WEEKLY", activeDays = 1)
        assertTrue(PointRules.isActiveDay(mondayOnly, LocalDate.parse(mon)))
        assertFalse(PointRules.isActiveDay(mondayOnly, LocalDate.parse(tue)))
        val sundayOnly = point(repeatMode = "WEEKLY", activeDays = 64)
        assertTrue(PointRules.isActiveDay(sundayOnly, LocalDate.parse("2026-11-15")))
        assertFalse(PointRules.isActiveDay(sundayOnly, LocalDate.parse("2026-11-14")))
        assertFalse(PointRules.isActiveDay(point(repeatMode = "WEEKLY", activeDays = 0), LocalDate.parse(mon)))
    }

    @Test fun activeDay_once() {
        val d = LocalDate.parse(mon)
        assertTrue(PointRules.isActiveDay(point(repeatMode = "ONCE", onceDate = mon, onceStatus = "PENDING"), d))
        assertTrue(PointRules.isActiveDay(point(repeatMode = "ONCE", onceDate = mon, onceStatus = null), d))
        assertFalse(PointRules.isActiveDay(point(repeatMode = "ONCE", onceDate = mon, onceStatus = "COMPLETED"), d))
        // 旧值 SKIPPED / EXPIRED 当作待提醒
        assertTrue(PointRules.isActiveDay(point(repeatMode = "ONCE", onceDate = mon, onceStatus = "SKIPPED"), d))
        assertTrue(PointRules.isActiveDay(point(repeatMode = "ONCE", onceDate = mon, onceStatus = "EXPIRED"), d))
        assertFalse(PointRules.isActiveDay(point(repeatMode = "ONCE", onceDate = tue, onceStatus = "PENDING"), d))
        assertFalse(PointRules.isActiveDay(point(repeatMode = "ONCE", onceDate = null, onceStatus = "PENDING"), d))
    }

    @Test fun activeDay_dailyAndUnknownMode_ignoreWeekdayMask() {
        val d = LocalDate.parse(tue)
        assertTrue(PointRules.isActiveDay(point(repeatMode = "DAILY", activeDays = 1), d))
        assertTrue(PointRules.isActiveDay(point(repeatMode = "SOMETHING_OLD", activeDays = 0), d))
    }

    @Test fun activeDay_skipOnlyBlocksThatDate() {
        val p = point(skipUntilDate = mon)
        assertFalse(PointRules.isActiveDay(p, LocalDate.parse(mon)))
        assertTrue(PointRules.isActiveDay(p, LocalDate.parse(tue)))
        assertTrue(PointRules.isActiveDay(p, LocalDate.parse("2026-11-08")))
    }

    // ── isActiveNow ──
    @Test fun activeNow_disabled() {
        assertFalse(PointRules.isActiveNow(point(enabled = false), at(mon, "10:00")))
        assertTrue(PointRules.isActiveNow(point(), at(mon, "10:00")))
    }

    @Test fun activeNow_timeWindow_endMinuteStillActive() {
        val p = point(start = "09:00", end = "18:00")
        assertFalse(PointRules.isActiveNow(p, at(mon, "08:59")))
        assertTrue(PointRules.isActiveNow(p, at(mon, "09:00")))
        // 与 isActiveAtMillis 的差别：这里结束那一分钟仍算在内（待处理问题 5）
        assertTrue(PointRules.isActiveNow(p, at(mon, "18:00")))
        assertFalse(PointRules.isActiveNow(p, at(mon, "18:01")))
    }

    @Test fun activeNow_overnight_judgedByWindowStartDay() {
        // 周二 00:30 属于周一 23:00 开始的时间段
        assertFalse(PointRules.isActiveNow(point(start = "23:00", end = "01:00", skipUntilDate = mon), at(tue, "00:30")))
        assertTrue(PointRules.isActiveNow(point(start = "23:00", end = "01:00", skipUntilDate = tue), at(tue, "00:30")))
        val mondayOnly = point(start = "23:00", end = "01:00", repeatMode = "WEEKLY", activeDays = 1)
        assertTrue(PointRules.isActiveNow(mondayOnly, at(tue, "00:30")))
        assertFalse(PointRules.isActiveNow(mondayOnly, at(tue, "23:30")))
    }

    @Test fun activeNow_windowDedup() {
        val window = point(start = "09:00", end = "18:00", lastTriggeredWindowStart = at(mon, "09:00"))
        assertFalse(PointRules.isActiveNow(window, at(mon, "10:00")))
        assertTrue(PointRules.isActiveNow(window, at(tue, "10:00")))
        // 没设时间段：一天算一个时间段
        val wholeDay = point(lastTriggeredWindowStart = at(mon, "00:00"))
        assertFalse(PointRules.isActiveNow(wholeDay, at(mon, "15:00")))
        assertFalse(PointRules.isActiveNow(wholeDay, at(mon, "23:59")))
        assertTrue(PointRules.isActiveNow(wholeDay, at(tue, "00:00")))
    }

    @Test fun activeNow_skipToday() {
        assertFalse(PointRules.isActiveNow(point(skipUntilDate = mon), at(mon, "10:00")))
        assertTrue(PointRules.isActiveNow(point(skipUntilDate = mon), at(tue, "10:00")))
    }

    // ── 触发后 / 确认后 / 跳过 / 重置 ──
    @Test fun onTriggered_onlyWritesWindowMarker() {
        val once = point(repeatMode = "ONCE", onceDate = mon, onceStatus = "PENDING", start = "09:00", end = "18:00")
        val after = PointRules.onTriggered(once, at(mon, "10:00"))
        assertEquals(once.copy(lastTriggeredWindowStart = at(mon, "09:00")), after)
        val daily = point()
        assertEquals(daily.copy(lastTriggeredWindowStart = at(mon, "00:00")), PointRules.onTriggered(daily, at(mon, "10:00")))
    }

    // ── 问题 2（v2.2.24）：恢复已完成的一次性点 ──
    @Test fun restoreOnce_backToPendingArmedEnabled() {
        val done = point(repeatMode = "ONCE", onceDate = mon, onceStatus = "COMPLETED", enabled = false,
            lastTriggeredWindowStart = at(mon, "00:00"))
            .copy(completedAt = at(mon, "09:00"), triggerState = "COMPLETED", enteredAt = at(mon, "08:59"))
        // 恢复前：当天也不会提醒
        assertFalse(PointRules.isActiveNow(done, at(mon, "10:00")))
        val restored = PointRules.restoreOnce(done)
        assertTrue(restored.enabled)
        assertEquals("PENDING", restored.onceStatus)
        assertEquals(null, restored.completedAt)
        assertEquals(null, restored.lastTriggeredWindowStart)
        assertEquals("ARMED", restored.triggerState)
        assertEquals(null, restored.enteredAt)
        assertEquals(mon, restored.onceDate)
        // 恢复后：当天又会提醒了
        assertTrue(PointRules.isActiveNow(restored, at(mon, "10:00")))
    }

    @Test fun isOnceDatePast_onlyWhenDateBeforeToday() {
        val today = java.time.LocalDate.parse(tue)
        assertTrue(PointRules.isOnceDatePast(point(repeatMode = "ONCE", onceDate = mon), today))
        assertFalse(PointRules.isOnceDatePast(point(repeatMode = "ONCE", onceDate = tue), today))
        assertFalse(PointRules.isOnceDatePast(point(repeatMode = "ONCE", onceDate = "2026-11-11"), today))
        assertFalse(PointRules.isOnceDatePast(point(repeatMode = "ONCE", onceDate = "乱码"), today))
        assertFalse(PointRules.isOnceDatePast(point(repeatMode = "ONCE", onceDate = null), today))
    }

    @Test fun onArrivedConfirmed_onceIsDisabledAndCompleted() {
        val now = at(mon, "10:00", second = 7)
        val once = point(repeatMode = "ONCE", onceDate = mon, onceStatus = "PENDING")
        val expected = once.copy(lastTriggeredWindowStart = at(mon, "00:00"), enabled = false, onceStatus = "COMPLETED", completedAt = now)
        assertEquals(expected, PointRules.onArrivedConfirmed(once, now))
        assertEquals(expected, PointRules.onArrivedConfirmed(once, now, acknowledged = false))
    }

    @Test fun onArrivedConfirmed_dailyAndWeeklyOnlyWriteMarker() {
        val now = at(mon, "10:00")
        val daily = point(start = "09:00", end = "18:00")
        assertEquals(daily.copy(lastTriggeredWindowStart = at(mon, "09:00")), PointRules.onArrivedConfirmed(daily, now))
        val weekly = point(repeatMode = "WEEKLY", activeDays = 1)
        assertEquals(weekly.copy(lastTriggeredWindowStart = at(mon, "00:00")), PointRules.onArrivedConfirmed(weekly, now))
    }

    @Test fun onSkipToday_rearmOnce_resetWindowDedup() {
        val p = point(repeatMode = "ONCE", onceDate = mon, onceStatus = "COMPLETED", lastTriggeredWindowStart = 5L)
            .copy(completedAt = 9L, enabled = false)
        assertEquals(p.copy(skipUntilDate = mon), PointRules.onSkipToday(p, LocalDate.parse(mon)))
        assertEquals(p.copy(onceStatus = "PENDING", completedAt = null, lastTriggeredWindowStart = null), PointRules.rearmOnce(p))
        assertEquals(p.copy(lastTriggeredWindowStart = null), PointRules.resetWindowDedup(p))
    }

    // ── findNextActiveTime ──
    @Test fun nextActive_daily() {
        val p = point(start = "09:00", end = "18:00")
        assertEquals(at(mon, "09:00"), PointRules.findNextActiveTime(p, at(mon, "08:00")))
        // 严格大于 from：正好等于开始时刻时取下一天
        assertEquals(at(tue, "09:00"), PointRules.findNextActiveTime(p, at(mon, "09:00")))
        assertEquals(at(tue, "09:00"), PointRules.findNextActiveTime(p, at(mon, "10:00")))
    }

    @Test fun nextActive_skipTodayGoesToTomorrow() {
        val p = point(start = "09:00", end = "18:00", skipUntilDate = mon)
        assertEquals(at(tue, "09:00"), PointRules.findNextActiveTime(p, at(mon, "08:00")))
    }

    @Test fun nextActive_weeklyFindsNextMatchingWeekday() {
        val p = point(start = "09:00", end = "18:00", repeatMode = "WEEKLY", activeDays = 1)
        assertEquals(at("2026-11-16", "09:00"), PointRules.findNextActiveTime(p, at(mon, "10:00")))
        assertEquals(at("2026-11-16", "09:00"), PointRules.findNextActiveTime(p, at(tue, "08:00")))
    }

    @Test fun nextActive_onceWithin14Days() {
        val from = at(mon, "10:00")
        fun once(date: String, status: String = "PENDING") =
            point(start = "09:00", end = "18:00", repeatMode = "ONCE", onceDate = date, onceStatus = status)
        assertEquals(at("2026-11-22", "09:00"), PointRules.findNextActiveTime(once("2026-11-22"), from))
        assertNull(PointRules.findNextActiveTime(once("2026-11-23"), from))
        assertNull(PointRules.findNextActiveTime(once("2026-11-12", "COMPLETED"), from))
        assertNull(PointRules.findNextActiveTime(once(mon), from)) // 今天的开始时刻已过
    }

    @Test fun nextActive_wholeDayPointUsesMidnight() {
        assertEquals(at(tue, "00:00"), PointRules.findNextActiveTime(point(), at(mon, "10:00")))
        assertEquals(at(tue, "00:00"), PointRules.findNextActiveTime(point(), at(mon, "00:00")))
    }

    // ── formatActiveDays ──
    @Test fun formatActiveDays_cases() {
        assertEquals("未选择", PointRules.formatActiveDays(0))
        assertEquals("每天", PointRules.formatActiveDays(127))
        assertEquals("周一~周五", PointRules.formatActiveDays(31))
        assertEquals("周六、周日", PointRules.formatActiveDays(96))
        assertEquals("周一~周三", PointRules.formatActiveDays(7))
        assertEquals("周一、周二", PointRules.formatActiveDays(3))
        assertEquals("周一、周三", PointRules.formatActiveDays(5))
        assertEquals("周一、周三~周五", PointRules.formatActiveDays(1 + 4 + 8 + 16))
        assertEquals("周一、周二、周四~周日", PointRules.formatActiveDays(1 + 2 + 8 + 16 + 32 + 64))
        assertEquals("未选择", PointRules.formatActiveDays(128))
    }
}
