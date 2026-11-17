package com.checkinassistant.domain

import com.checkinassistant.TestSupport.at
import com.checkinassistant.TestSupport.point
import com.checkinassistant.TestSupport.useKualaLumpur
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** 盯位置用的时间段判断（含开始、不含结束）和「下一次该醒来的时刻」。真值表是手写的，不靠被测代码算。 */
class MonitoringWindowTest {
    private lateinit var previousZone: TimeZone
    @Before fun setUp() { previousZone = useKualaLumpur() }
    @After fun tearDown() { TimeZone.setDefault(previousZone) }

    private val mon = "2026-11-09"
    private val tue = "2026-11-10"

    @Test fun activeAtMillis_normalWindow_startInclusiveEndExclusive() {
        val p = point(start = "09:00", end = "18:00")
        assertEquals(false, p.isActiveAtMillis(at(mon, "08:59")))
        assertEquals(true, p.isActiveAtMillis(at(mon, "09:00")))
        assertEquals(true, p.isActiveAtMillis(at(mon, "17:59", second = 59)))
        assertEquals(false, p.isActiveAtMillis(at(mon, "18:00")))
        assertEquals(false, p.isActiveAtMillis(at(mon, "23:00")))
    }

    @Test fun activeAtMillis_overnightWindow() {
        val p = point(start = "22:00", end = "06:00")
        assertEquals(false, p.isActiveAtMillis(at(mon, "21:59")))
        assertEquals(true, p.isActiveAtMillis(at(mon, "22:00")))
        assertEquals(true, p.isActiveAtMillis(at(tue, "00:00")))
        assertEquals(true, p.isActiveAtMillis(at(tue, "05:59")))
        assertEquals(false, p.isActiveAtMillis(at(tue, "06:00")))
        assertEquals(false, p.isActiveAtMillis(at(tue, "12:00")))
    }

    @Test fun activeAtMillis_noWindowOrHalfWindow_alwaysTrue() {
        assertEquals(true, point().isActiveAtMillis(at(mon, "03:00")))
        assertEquals(true, point(start = "09:00").isActiveAtMillis(at(mon, "03:00")))
        assertEquals(true, point(end = "09:00").isActiveAtMillis(at(mon, "23:00")))
    }

    @Test fun activeAtMillis_startEqualsEnd_alwaysTrue() {
        // 现状：开始 = 结束的旧数据在这里当成全天（待处理问题 21）
        val p = point(start = "10:00", end = "10:00")
        assertEquals(true, p.isActiveAtMillis(at(mon, "10:00")))
        assertEquals(true, p.isActiveAtMillis(at(mon, "09:59")))
        assertEquals(true, p.isActiveAtMillis(at(mon, "23:00")))
    }

    @Test fun nextChange_noPointsOrWholeDayPointBeingWatched_isNull() {
        assertNull(nextMonitoringChange(emptyList(), at(mon, "08:00")))
        // 全天点此刻正在盯：没有「下一次变化」
        assertNull(nextMonitoringChange(listOf(point(), point(id = 2, start = "09:00")), at(mon, "08:00")))
    }

    // ── 问题 16（v2.2.24）：没在盯的全天点，要在下一个生效日 0 点叫醒 ──

    private val monMidnight get() = at(mon, "00:00")

    @Test fun wholeDay_doneToday_wakesAtNextMidnight() {
        val done = point().copy(lastTriggeredWindowStart = monMidnight)
        assertEquals(at(tue, "00:00"), nextMonitoringChange(listOf(done), at(mon, "10:00")))
    }

    @Test fun wholeDay_skippedToday_wakesAtNextMidnight() {
        val skipped = point(skipUntilDate = mon)
        assertEquals(at(tue, "00:00"), nextMonitoringChange(listOf(skipped), at(mon, "10:00")))
    }

    @Test fun wholeDayWeekly_wakesOnNextSelectedWeekday() {
        // 只选周三（第 3 位 = 4）；周一 10:00 时 → 周三 0:00
        val wed = point(repeatMode = "WEEKLY", activeDays = 4)
        assertEquals(at("2026-11-11", "00:00"), nextMonitoringChange(listOf(wed), at(mon, "10:00")))
    }

    @Test fun wholeDayOnce_futureDate_wakesOnThatDay() {
        val once = point(repeatMode = "ONCE", onceDate = "2026-11-12", onceStatus = "PENDING")
        assertEquals(at("2026-11-12", "00:00"), nextMonitoringChange(listOf(once), at(mon, "10:00")))
    }

    @Test fun wholeDayOnce_completed_isNull() {
        val done = point(repeatMode = "ONCE", onceDate = "2026-11-12", onceStatus = "COMPLETED")
        assertNull(nextMonitoringChange(listOf(done), at(mon, "10:00")))
    }

    @Test fun wholeDayAndWindowed_takesEarliest() {
        val points = listOf(
            point(id = 1).copy(lastTriggeredWindowStart = monMidnight),
            point(id = 2, start = "09:00", end = "10:00")
        )
        // 周二 0:00（全天点）早于周二 08:58（带时间段的点）
        assertEquals(at(tue, "00:00"), nextMonitoringChange(points, at(mon, "20:00")))
    }

    @Test fun wholeDay_threeDayLoop_wakesEveryMidnightAndIsWatchedAgain() {
        // 闭环：每天处理完 → 排到次日 0 点 → 0 点时又在盯了 → 再处理完……连走三天
        val days = listOf(mon, tue, "2026-11-11", "2026-11-12")
        var p = point()
        for (i in 0 until 3) {
            val t = at(days[i], "10:00")
            p = PointRules.onArrivedConfirmed(p, t)
            val nextMidnight = at(days[i + 1], "00:00")
            val next = nextMonitoringChange(listOf(p), t)
            assertEquals(nextMidnight, next)
            assertEquals(true, next!! > t)
            assertEquals(true, PointRules.isActiveNow(p, nextMidnight))
            assertNull(nextMonitoringChange(listOf(p), nextMidnight))
        }
    }

    @Test fun sleepingText_wholeDayDone_showsTomorrowMidnight() {
        val done = point().copy(lastTriggeredWindowStart = monMidnight)
        assertEquals("休眠中 · 下次 明天 00:00 开始监测", sleepingStatusText(listOf(done), at(mon, "10:00")))
    }

    @Test fun sleepingText_wholeDayBeingWatched_hasNoNextTime() {
        assertEquals("休眠中 · 暂无待监测的时段", sleepingStatusText(listOf(point()), at(mon, "10:00")))
    }

    @Test fun sleepingText_windowedPoint_sameAsBefore() {
        val p = listOf(point(start = "09:00", end = "18:00"))
        assertEquals("休眠中 · 下次 09:00 开始监测", sleepingStatusText(p, at(mon, "08:00")))
        assertEquals("休眠中 · 下次 明天 09:00 开始监测", sleepingStatusText(p, at(mon, "19:00")))
    }

    @Test fun nextChange_beforeWindow_wakesTwoMinutesEarly() {
        val p = listOf(point(start = "09:00", end = "18:00"))
        assertEquals(at(mon, "08:58"), nextMonitoringChange(p, at(mon, "08:00")))
        assertEquals(at(mon, "08:58"), nextMonitoringChange(p, at(mon, "08:57", second = 59)))
    }

    @Test fun nextChange_insideWarmupGap_wakesAtActualStart() {
        val p = listOf(point(start = "09:00", end = "18:00"))
        // 预热闹钟触发的那一刻：「开始前 2 分钟」已不在未来，改取开始时刻本身
        assertEquals(at(mon, "09:00"), nextMonitoringChange(p, at(mon, "08:58")))
        assertEquals(at(mon, "09:00"), nextMonitoringChange(p, at(mon, "08:59", second = 30)))
    }

    @Test fun nextChange_insideWindow_isWindowEnd() {
        val p = listOf(point(start = "09:00", end = "18:00"))
        assertEquals(at(mon, "18:00"), nextMonitoringChange(p, at(mon, "09:00")))
        assertEquals(at(mon, "18:00"), nextMonitoringChange(p, at(mon, "17:59", second = 59)))
        val overnight = listOf(point(start = "22:00", end = "06:00"))
        assertEquals(at(tue, "06:00"), nextMonitoringChange(overnight, at(mon, "23:30")))
        assertEquals(at(tue, "06:00"), nextMonitoringChange(overnight, at(tue, "05:00")))
    }

    @Test fun nextChange_afterWindow_isTomorrowWarmup() {
        val p = listOf(point(start = "09:00", end = "18:00"))
        assertEquals(at(tue, "08:58"), nextMonitoringChange(p, at(mon, "18:00")))
        assertEquals(at(tue, "08:58"), nextMonitoringChange(p, at(mon, "21:00")))
    }

    @Test fun nextChange_multiplePoints_takesEarliest() {
        val points = listOf(
            point(id = 1, start = "09:00", end = "18:00"),
            point(id = 2, start = "07:00", end = "08:00"),
            point(id = 3)
        )
        assertEquals(at(mon, "06:58"), nextMonitoringChange(points, at(mon, "06:00")))
        assertEquals(at(mon, "08:00"), nextMonitoringChange(points, at(mon, "07:30")))
        assertEquals(at(mon, "08:58"), nextMonitoringChange(points, at(mon, "08:10")))
    }

    @Test fun nextChange_insideWindow_ignoresWeekdayAndSkip() {
        // 现状：在时间段内时不看星期、不看跳过，直接取结束时刻
        val mondayOnly = listOf(point(start = "09:00", end = "18:00", repeatMode = "WEEKLY", activeDays = 1))
        assertEquals(at(tue, "18:00"), nextMonitoringChange(mondayOnly, at(tue, "10:00")))
        // 时间段外才按星期找下一个生效日
        assertEquals(at("2026-11-16", "08:58"), nextMonitoringChange(mondayOnly, at(tue, "19:00")))
    }

    @Test fun nextChange_completedOnce_outsideWindow_isNull() {
        val done = listOf(point(start = "09:00", end = "18:00", repeatMode = "ONCE", onceDate = "2026-11-12", onceStatus = "COMPLETED"))
        assertNull(nextMonitoringChange(done, at(mon, "08:00")))
    }
}
