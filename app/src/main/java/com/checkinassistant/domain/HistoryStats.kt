package com.checkinassistant.domain

import com.checkinassistant.data.CheckInPoint
import com.checkinassistant.data.CheckInRecord
import com.checkinassistant.data.PointEnabledEvent
import com.checkinassistant.data.PointSkipEvent
import java.util.Calendar

/**
 * 历史页「打卡统计」的算法（纯计算）。查数据库、取「现在」的时间都由历史页在原位置做完再传进来。
 * v2.2.24（问题 7）新口径，见 [monthStat]：「应该到的天数」只算这个提醒点按重复方式本该提醒的日子
 * （按星期的只算选中的星期几，一次性的只算那一天），再减去禁用和跳过的天数。
 * 单独的 [skippedDays] 仍按整月算、不按「到今天为止」截断（待处理问题 20，页面已不再直接用它）。
 */
internal object HistoryStats {
    /** 这个月统计到第几天：过去的月份算整月，当月算到今天，未来的月份算 0 天。 */
    fun countedDaysInMonth(monthStart: Calendar, now: Calendar): Int {
        val monthComparison = compareValues(
            monthStart.get(Calendar.YEAR) * 12 + monthStart.get(Calendar.MONTH),
            now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)
        )
        return when {
            monthComparison < 0 -> monthStart.getActualMaximum(Calendar.DAY_OF_MONTH)
            monthComparison == 0 -> now.get(Calendar.DAY_OF_MONTH)
            else -> 0
        }
    }

    /**
     * 这个月 1 号到第 [countedDays] 天里，这个提醒点按重复方式「本该提醒」的是哪几天（v2.2.24，问题 7）。
     * 按星期的只留选中的星期几；一次性的只留它的那一天（不在统计范围内就是空）；每天的（和旧数据）全留。
     */
    fun scheduledDays(point: CheckInPoint, monthStart: Calendar, countedDays: Int): Set<Int> {
        if (countedDays <= 0) return emptySet()
        val year = monthStart.get(Calendar.YEAR)
        val month = monthStart.get(Calendar.MONTH) + 1
        return (1..countedDays).filter { day ->
            val date = java.time.LocalDate.of(year, month, day)
            when (point.repeatMode) {
                "WEEKLY" -> PointRules.matchesActiveDays(point, date)
                "ONCE" -> date.toString() == point.onceDate
                else -> true
            }
        }.toSet()
    }

    /** 这个月里点过「今天跳过」的是哪几天（不按统计天数截断）。 */
    fun skippedDaySet(pointId: Long, skips: List<PointSkipEvent>, monthStart: Calendar): Set<Int> {
        val month = "%04d-%02d".format(monthStart.get(Calendar.YEAR), monthStart.get(Calendar.MONTH) + 1)
        return skips.asSequence()
            .filter { it.reminderPointId == pointId && it.skipDate.startsWith(month) }
            .mapNotNull { it.skipDate.substringAfterLast('-').toIntOrNull() }
            .toSet()
    }

    /** 这个月里点过「今天跳过」的天数（同一天点多次只算一天）。 */
    fun skippedDays(pointId: Long, skips: List<PointSkipEvent>, monthStart: Calendar, countedDays: Int): Int {
        if (countedDays <= 0) return 0
        val month = "%04d-%02d".format(monthStart.get(Calendar.YEAR), monthStart.get(Calendar.MONTH) + 1)
        return skips.asSequence()
            .filter { it.reminderPointId == pointId && it.skipDate.startsWith(month) }
            .map { it.skipDate }
            .distinct()
            .count()
    }

    /**
     * 这个月里「当天 0 点时处于禁用状态」的是哪几天。当天中途禁用的，从第二天开始算。
     * @param enabledAtMonthStart 月初之前最后一次开关的状态；从没开关过算启用。
     */
    fun disabledDaySet(
        pointId: Long,
        enabledAtMonthStart: Boolean,
        events: List<PointEnabledEvent>,
        monthStart: Calendar,
        countedDays: Int
    ): Set<Int> {
        var enabled = enabledAtMonthStart
        val changes = events.filter { it.pointId == pointId }.sortedBy { it.atMillis }
        var nextChange = 0
        val disabled = mutableSetOf<Int>()
        for (day in 1..countedDays) {
            val dayStart = (monthStart.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, day) }.timeInMillis
            while (nextChange < changes.size && changes[nextChange].atMillis < dayStart) {
                enabled = changes[nextChange].enabled
                nextChange++
            }
            if (!enabled) disabled += day
        }
        return disabled
    }

    /** 同 [disabledDaySet]，只要天数。 */
    fun disabledDays(
        pointId: Long,
        enabledAtMonthStart: Boolean,
        events: List<PointEnabledEvent>,
        monthStart: Calendar,
        countedDays: Int
    ): Int = disabledDaySet(pointId, enabledAtMonthStart, events, monthStart, countedDays).size

    /** 已到达的是哪几天：统计范围内有记录的日子。 */
    fun arrivedDaySet(pointId: Long, records: List<CheckInRecord>, countedDays: Int): Set<Int> =
        records.asSequence()
            .filter { it.pointId == pointId && it.day <= countedDays }
            .map { it.day }
            .toSet()

    /** 已到达天数：统计范围内有记录的日子，按天去重。 */
    fun arrivedDays(pointId: Long, records: List<CheckInRecord>, countedDays: Int): Int =
        arrivedDaySet(pointId, records, countedDays).size

    /** 统计卡上一个提醒点的四个数。 */
    data class MonthStat(val arrivedDays: Int, val missedDays: Int, val disabledDays: Int, val skippedDays: Int)

    /**
     * 一个提醒点这个月的统计（v2.2.24，问题 7）：
     * - 本该提醒的日子 = [scheduledDays]；
     * - 已禁用天数 = 本该提醒的日子里，当天 0 点处于禁用的；
     * - 已跳过天数 = 本该提醒的日子里点过跳过的（已经算作禁用的那天不重复算）；
     * - 应该到的日子 = 本该提醒的日子 − 禁用的 − 跳过的；
     * - 未到达天数 = 应该到的日子里没有记录的天数（不该提醒的日子有记录，不会把未到达减成负数）；
     * - 已到达天数口径不变：统计范围内有记录的天数。
     */
    fun monthStat(
        point: CheckInPoint,
        enabledAtMonthStart: Boolean,
        records: List<CheckInRecord>,
        events: List<PointEnabledEvent>,
        skips: List<PointSkipEvent>,
        monthStart: Calendar,
        countedDays: Int
    ): MonthStat {
        val scheduled = scheduledDays(point, monthStart, countedDays)
        val disabled = disabledDaySet(point.id, enabledAtMonthStart, events, monthStart, countedDays) intersect scheduled
        val skipped = (skippedDaySet(point.id, skips, monthStart) intersect scheduled) - disabled
        val expected = scheduled - disabled - skipped
        val arrived = arrivedDaySet(point.id, records, countedDays)
        return MonthStat(arrived.size, (expected - arrived).size, disabled.size, skipped.size)
    }
}
