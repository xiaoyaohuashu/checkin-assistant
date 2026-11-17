package com.checkinassistant.domain

import com.checkinassistant.TestSupport.at
import com.checkinassistant.TestSupport.point
import com.checkinassistant.TestSupport.useKualaLumpur
import com.checkinassistant.data.CheckInRecord
import com.checkinassistant.data.PointEnabledEvent
import com.checkinassistant.data.PointSkipEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 历史页统计口径（期望值手写）。v2.2.24 起「应该到的天数」只算本该提醒的日子（问题 7）；单独的跳过天数仍是旧口径（问题 20）。 */
class HistoryStatsTest {
    private lateinit var previousZone: TimeZone
    @Before fun setUp() { previousZone = useKualaLumpur() }
    @After fun tearDown() { TimeZone.setDefault(previousZone) }

    private fun cal(date: String, hm: String = "00:00"): Calendar =
        Calendar.getInstance().apply { timeInMillis = at(date, hm) }

    private val november = cal("2026-11-01")

    private fun record(pointId: Long, day: Int) = CheckInRecord(
        pointId = pointId, pointName = "p", triggeredAt = 0, year = 2026, month = 11, day = day,
        weekday = 1, hour = 8, minute = 0, second = 0
    )
    private fun toggle(pointId: Long, date: String, hm: String, enabled: Boolean) =
        PointEnabledEvent(pointId = pointId, atMillis = at(date, hm), enabled = enabled)
    private fun skip(pointId: Long, date: String) = PointSkipEvent(reminderPointId = pointId, skipDate = date, createdAt = 0)

    @Test fun countedDays_pastCurrentFutureMonth() {
        val now = cal("2026-11-12", "16:30")
        assertEquals(31, HistoryStats.countedDaysInMonth(cal("2026-10-01"), now))
        assertEquals(28, HistoryStats.countedDaysInMonth(cal("2026-02-01"), now))
        assertEquals(31, HistoryStats.countedDaysInMonth(cal("2025-12-01"), now))
        assertEquals(12, HistoryStats.countedDaysInMonth(november, now))
        assertEquals(0, HistoryStats.countedDaysInMonth(cal("2026-12-01"), now))
        assertEquals(0, HistoryStats.countedDaysInMonth(cal("2027-01-01"), now))
        assertEquals(1, HistoryStats.countedDaysInMonth(november, cal("2026-11-01", "00:00")))
    }

    @Test fun arrivedDays_distinctByDay_withinCountedDays_onlyThisPoint() {
        val records = listOf(record(1, 3), record(1, 3), record(1, 5), record(1, 13), record(2, 4))
        assertEquals(2, HistoryStats.arrivedDays(1, records, 12))
        assertEquals(3, HistoryStats.arrivedDays(1, records, 30))
        assertEquals(1, HistoryStats.arrivedDays(2, records, 12))
        assertEquals(0, HistoryStats.arrivedDays(3, records, 12))
        assertEquals(0, HistoryStats.arrivedDays(1, records, 0))
    }

    @Test fun skippedDays_distinctDates_thisMonthOnly_notTruncatedToCountedDays() {
        val skips = listOf(
            skip(1, "2026-11-03"), skip(1, "2026-11-03"), skip(1, "2026-11-20"),
            skip(1, "2026-10-31"), skip(2, "2026-11-04")
        )
        // 现状：11-20 晚于统计天数 12 也照算（待处理问题 20）
        assertEquals(2, HistoryStats.skippedDays(1, skips, november, 12))
        assertEquals(1, HistoryStats.skippedDays(2, skips, november, 12))
        assertEquals(0, HistoryStats.skippedDays(1, skips, november, 0))
        assertEquals(1, HistoryStats.skippedDays(1, skips, cal("2026-10-01"), 31))
    }

    @Test fun disabledDays_neverToggled() {
        assertEquals(0, HistoryStats.disabledDays(1, true, emptyList(), november, 12))
        assertEquals(12, HistoryStats.disabledDays(1, false, emptyList(), november, 12))
        assertEquals(0, HistoryStats.disabledDays(1, false, emptyList(), november, 0))
    }

    @Test fun disabledDays_midDayToggleCountsFromNextDay() {
        // 11-05 10:00 禁用：5 号 0 点时还是启用，从 6 号起算；11-09 08:00 重新启用：9 号 0 点仍是禁用，算到 9 号
        val events = listOf(toggle(1, "2026-11-05", "10:00", false), toggle(1, "2026-11-09", "08:00", true))
        assertEquals(4, HistoryStats.disabledDays(1, true, events, november, 12))
        // 只统计到 7 号时：6、7 两天
        assertEquals(2, HistoryStats.disabledDays(1, true, events, november, 7))
    }

    @Test fun disabledDays_toggleExactlyAtMidnight_appliesFromNextDay() {
        // 现状：正好 0 点的那次开关不早于当天 0 点，当天不算，从第二天起算
        val events = listOf(toggle(1, "2026-11-05", "00:00", false))
        assertEquals(7, HistoryStats.disabledDays(1, true, events, november, 12))
    }

    @Test fun disabledDays_ignoresOtherPoints_andUnsortedInput() {
        val events = listOf(
            toggle(1, "2026-11-09", "08:00", true), toggle(2, "2026-11-01", "01:00", false),
            toggle(1, "2026-11-05", "10:00", false)
        )
        assertEquals(4, HistoryStats.disabledDays(1, true, events, november, 12))
        assertEquals(11, HistoryStats.disabledDays(2, true, events, november, 12))
    }

    @Test fun disabledDays_disabledBeforeMonthStart_wholeMonth() {
        assertEquals(30, HistoryStats.disabledDays(1, false, emptyList(), november, 30))
        val events = listOf(toggle(1, "2026-11-10", "09:00", true))
        assertEquals(10, HistoryStats.disabledDays(1, false, events, november, 12))
    }

    // ── 问题 7（v2.2.24）：应该到的天数只算本该提醒的日子。2026-11-01 是周日。 ──

    private val weekdays = point(repeatMode = "WEEKLY", activeDays = 31) // 周一到周五
    private fun stat(
        p: com.checkinassistant.data.CheckInPoint, records: List<CheckInRecord> = emptyList(),
        events: List<PointEnabledEvent> = emptyList(), skips: List<PointSkipEvent> = emptyList(),
        counted: Int = 12, enabledAtStart: Boolean = true
    ) = HistoryStats.monthStat(p, enabledAtStart, records, events, skips, november, counted)

    @Test fun weekly_onlySelectedWeekdaysCount() {
        // 11 月 1–12 日里的工作日：2、3、4、5、6、9、10、11、12 共 9 天
        assertEquals(setOf(2, 3, 4, 5, 6, 9, 10, 11, 12), HistoryStats.scheduledDays(weekdays, november, 12))
        val s = stat(weekdays, records = listOf(record(1, 2), record(1, 9)))
        assertEquals(2, s.arrivedDays)
        assertEquals(7, s.missedDays) // 旧算法是 10（把周末也算进去了）
    }

    @Test fun weekly_disabledAndSkippedOnlyCountOnScheduledDays() {
        // 0 点处于禁用的是 6、7、8、9 日，其中只有 6（周五）、9（周一）是该提醒的日子
        val events = listOf(toggle(1, "2026-11-05", "10:00", false), toggle(1, "2026-11-09", "08:00", true))
        val s = stat(weekdays, records = listOf(record(1, 2)), events = events)
        assertEquals(2, s.disabledDays)
        assertEquals(6, s.missedDays) // 9 − 2 禁用 − 1 到达
        // 再跳过 10 日（周二，算）和 7 日（周六，本来就不该提醒，不算）
        val s2 = stat(weekdays, records = listOf(record(1, 2)), events = events,
            skips = listOf(skip(1, "2026-11-10"), skip(1, "2026-11-07")))
        assertEquals(1, s2.skippedDays)
        assertEquals(5, s2.missedDays)
    }

    @Test fun once_onlyThatDayCounts() {
        fun once(date: String) = point(repeatMode = "ONCE", onceDate = date, onceStatus = "PENDING")
        assertEquals(1, stat(once("2026-11-10")).missedDays)
        assertEquals(0, stat(once("2026-11-10"), records = listOf(record(1, 10))).missedDays)
        assertEquals(0, stat(once("2026-11-20")).missedDays) // 还没到那天
        assertEquals(0, stat(once("2026-12-03")).missedDays) // 不在这个月
    }

    @Test fun daily_sameAsBefore() {
        val s = stat(point(), records = listOf(record(1, 2), record(1, 9)))
        assertEquals(2, s.arrivedDays)
        assertEquals(10, s.missedDays)
        assertEquals(0, s.disabledDays)
        assertEquals(0, s.skippedDays)
    }

    @Test fun arrivalOnNonScheduledDay_doesNotReduceMissed_andNeverNegative() {
        // 工作日的点只在 7 日（周六）有一条记录：已到达照算 1 天，未到达仍是 9 天
        val s = stat(weekdays, records = listOf(record(1, 7)))
        assertEquals(1, s.arrivedDays)
        assertEquals(9, s.missedDays)
    }

    @Test fun futureMonth_allZero() {
        val s = stat(weekdays, records = listOf(record(1, 2)), skips = listOf(skip(1, "2026-11-03")), counted = 0)
        assertEquals(HistoryStats.MonthStat(0, 0, 0, 0), s)
    }
}
