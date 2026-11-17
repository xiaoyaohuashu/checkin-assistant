package com.checkinassistant.domain

import com.checkinassistant.data.CheckInPoint
import java.util.Calendar

/**
 * 「某一分钟在不在生效时间段内」的比较：含开始那一分钟，不含结束那一分钟；
 * 结束早于（或等于）开始时按跨夜处理。
 *
 * 盯位置（isActiveAtMillis）和 25 秒复核（ReminderEngine 的 isActiveAt）原来各写了一份
 * 一模一样的比较，这里合成一份。注意 PointRules.isInTimeRange 是另一套（两端都含），
 * 语义不同，不能并进来（见待处理问题 5）。
 *
 * @param start 开始时间，从 0 点起算的分钟数
 * @param end 结束时间，从 0 点起算的分钟数
 */
internal fun isMinuteInWindow(start: Int, end: Int, minuteOfDay: Int): Boolean =
    if (start < end) minuteOfDay in start until end else minuteOfDay >= start || minuteOfDay < end

/** 时间段开始前提前多久把 App 叫醒做准备：2 分钟。 */
internal const val WINDOW_WARMUP_MS = 2 * 60_000L

internal fun CheckInPoint.isActiveAtMillis(timeMillis: Long): Boolean {
    val start = activeStartMinute ?: return true
    val end = activeEndMinute ?: return true
    val calendar = Calendar.getInstance().apply { timeInMillis = timeMillis }
    val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    return isMinuteInWindow(start, end, minute)
}

/** The next time this point changes from inactive to active, assuming it is currently inactive. */
private fun CheckInPoint.nextStartAfter(now: Long): Long? {
    // Spec §5: delegate day-level eligibility (weekday mask / skip date / ONCE date)
    // to the single PointRules.isActiveDay implementation via findNextActiveTime.
    return com.checkinassistant.domain.PointRules.findNextActiveTime(this, now)
}

/** Earliest future point at which the active/inactive point set needs recalculating. */
internal fun nextMonitoringChange(points: List<CheckInPoint>, now: Long): Long? {
    return points.mapNotNull { point ->
        val start = point.activeStartMinute
        val end = point.activeEndMinute
        if (start == null || end == null) {
            // v2.2.24（问题 16）全天点：此刻正在盯，就没有「下一次变化」；
            // 不在盯（今天已处理 / 今天跳过 / 今天不是生效日 / 一次性日期还没到），就在下一个生效日的 0 点叫醒。
            // 不提前 2 分钟：提前醒来时当天还没到，只会白醒一次。
            // 注意：这里只管「到点叫醒」，不会在 0 点把「已处理 / 三轮没理」的状态改回「等待进入」。
            return@mapNotNull if (PointRules.isActiveNow(point, now)) null else PointRules.findNextActiveTime(point, now)
        }
        if (!point.isActiveAtMillis(now)) {
            // When the pre-warm alarm itself fires, `start - WINDOW_WARMUP_MS` is already
            // in the past.  Re-scheduling that past instant used to skip the whole window
            // and select tomorrow.  In that small gap, wake at the actual start instead.
            point.nextStartAfter(now)?.let { startAt ->
                (startAt - WINDOW_WARMUP_MS).takeIf { it > now } ?: startAt
            }
// 正在时间段内：下一次变化就是结束时刻。这里不看星期、不看「今天跳过」（现状）。
        } else {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, end / 60)
                set(Calendar.MINUTE, end % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)
            calendar.timeInMillis
        }
    }.filter { it > now }.minOrNull()
}

/**
 * 休眠时常驻通知上那行字（从 GeofenceMonitoringService 搬来，方便单测）。
 * 看的是：带时间段的点，以及此刻没在盯的全天点（v2.2.24，问题 16：全天点处理完后也显示「下次 明天 00:00」）。
 */
internal fun sleepingStatusText(points: List<CheckInPoint>, now: Long): String {
    val next = points.filter { (it.activeStartMinute != null && it.activeEndMinute != null) || !PointRules.isActiveNow(it, now) }
        .mapNotNull { PointRules.findNextActiveTime(it, now) }
        .minOrNull() ?: return "休眠中 · 暂无待监测的时段"
    val zone = java.time.ZoneId.systemDefault()
    val at = java.time.Instant.ofEpochMilli(next).atZone(zone)
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time = "%02d:%02d".format(at.hour, at.minute)
    val day = when (at.toLocalDate()) {
        today -> time
        today.plusDays(1) -> "明天 $time"
        else -> "${at.monthValue}月${at.dayOfMonth}日 $time"
    }
    return "休眠中 · 下次 $day 开始监测"
}
