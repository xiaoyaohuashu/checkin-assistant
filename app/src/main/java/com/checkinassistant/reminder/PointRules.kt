package com.checkinassistant.reminder

import com.checkinassistant.data.CheckInPoint
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The single source of truth for "is this point eligible right now / on a given day".
 *
 * Per spec §4.1, the weekday filter, skip check and repeat-mode validity each exist
 * ONLY here (inside [isActiveDay]); every other caller must delegate to these
 * functions instead of reimplementing them.
 */
object PointRules {

    // ── date/time helpers ─────────────────────────────────────────────────

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun toLocalDate(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate()

    fun dateOf(millis: Long): LocalDate = toLocalDate(millis)

    /** "2026-09-21" */
    fun dateString(date: LocalDate): String = date.toString()

    fun minutesOfDay(millis: Long): Int {
        val time = Instant.ofEpochMilli(millis).atZone(zone())
        return time.hour * 60 + time.minute
    }

    /** Millis of `date` at `minutes` since midnight. */
    fun atMinutes(date: LocalDate, minutes: Int): Long =
        date.atStartOfDay(zone()).toInstant().toEpochMilli() + minutes * 60_000L

    // ── §4.2 time-range membership ────────────────────────────────────────

    fun isInTimeRange(point: CheckInPoint, now: Long): Boolean {
        val start = point.activeStartMinute ?: return true
        val end = point.activeEndMinute ?: return true
        val nowMinutes = minutesOfDay(now)
        return if (start <= end) {
            nowMinutes in start..end
        } else {
            nowMinutes >= start || nowMinutes <= end
        }
    }

    // ── §4.3 window start (handles overnight windows) ─────────────────────

    /**
     * Start of the currently-running window. For an overnight window (23:00–01:00)
     * observed during the early-morning segment, the start belongs to yesterday.
     */
    fun getWindowStart(point: CheckInPoint, now: Long): Long {
        val start = point.activeStartMinute ?: run {
            // No window configured: the whole current day is one window.
            val dayStart = toLocalDate(now).atStartOfDay(zone()).toInstant().toEpochMilli()
            return dayStart
        }
        val end = point.activeEndMinute
        val today = toLocalDate(now)
        val nowMinutes = minutesOfDay(now)
        return if (end == null || start <= end) {
            atMinutes(today, start)
        } else {
            if (nowMinutes >= start) atMinutes(today, start) else atMinutes(today.minusDays(1), start)
        }
    }

    // ── §4.4 the ONE eligibility entry point ──────────────────────────────

    /** Skip check — shared by [isActiveDay]. */
    fun isSkippedOn(point: CheckInPoint, date: LocalDate): Boolean =
        point.skipUntilDate == dateString(date)

    /** Weekday bitmask check — shared by [isActiveDay]. */
    fun matchesActiveDays(point: CheckInPoint, date: LocalDate): Boolean {
        // DayOfWeek.MONDAY=1 … SUNDAY=7 → bit0..bit6.
        val dayBit = date.dayOfWeek.value - 1
        return (point.activeDays shr dayBit) and 1 == 1
    }

    /**
     * "Does this point have reminder eligibility on `date`?" — time-of-day is NOT
     * considered here. The only place the weekday/skip/repeat-mode filters live.
     */
    fun isActiveDay(point: CheckInPoint, date: LocalDate): Boolean {
        // 1. Skip check.
        if (isSkippedOn(point, date)) return false
        // 2. Repeat-mode validity.
        when (point.repeatMode) {
            "ONCE" -> {
                // v4: only two states. Legacy SKIPPED/EXPIRED values are treated as
                // PENDING (spec note 5), so only COMPLETED blocks a trigger.
                if (point.onceStatus == "COMPLETED") return false
                if (dateString(date) != point.onceDate) return false
            }
            "WEEKLY" -> {
                if (!matchesActiveDays(point, date)) return false
            }
            // "DAILY" and any legacy value: always eligible.
        }
        return true
    }

    // ── §4.5 now-check composing the above ────────────────────────────────

    fun isActiveNow(point: CheckInPoint, now: Long): Boolean {
        if (!point.enabled) return false
        // Judge eligibility by the WINDOW-START date so overnight windows
        // belong to the day the window began (spec §2.2).
        if (!isActiveDay(point, dateOf(getWindowStart(point, now)))) return false
        if (!isInTimeRange(point, now)) return false
        // Window dedup: one window, one reminder.
        if (point.lastTriggeredWindowStart != null &&
            point.lastTriggeredWindowStart == getWindowStart(point, now)
        ) return false
        return true
    }

    // ── §4.6 post-trigger state ───────────────────────────────────────────

    /**
     * v4 §1.3 改动点1: triggering only records the window-dedup marker — the ONCE
     * status flips to COMPLETED solely via [onArrivedConfirmed] (user taps the button).
     */
    fun onTriggered(point: CheckInPoint, now: Long): CheckInPoint =
        point.copy(lastTriggeredWindowStart = getWindowStart(point, now))

    /** v4 §1.3 改动点2: user tapped "我已到达". */
    /**
     * v5+: [acknowledged] distinguishes the two ways an ONCE point reaches COMPLETED:
     * user tapped "我已到达" (true) vs all reminder rounds exhausted (false — the
     * geolocation fact stands, but the user never responded). The list renders the
     * latter as "已到达，未确认" based on the record status; onceStatus stays
     * "COMPLETED" for both, so no schema change is needed.
     */
    fun onArrivedConfirmed(point: CheckInPoint, now: Long, acknowledged: Boolean = true): CheckInPoint {
        val base = point.copy(lastTriggeredWindowStart = getWindowStart(point, now))
        return if (base.repeatMode == "ONCE") {
            // Auto-disable + mark completed; the user re-enables via the switch.
            base.copy(enabled = false, onceStatus = "COMPLETED", completedAt = now)
        } else {
            base
        }
    }

    /** Returns the updated entity; caller persists it and writes the skip event. */
    fun onSkipToday(point: CheckInPoint, today: LocalDate): CheckInPoint =
        // v4: ONCE no longer carries a SKIPPED state — skipping just suppresses today.
        point.copy(skipUntilDate = dateString(today))

    // ── §4.8 next eligible start ──────────────────────────────────────────

    /** First instant strictly after `from` at which the point's window begins on an eligible day. */
    fun findNextActiveTime(point: CheckInPoint, from: Long): Long? {
        val start = point.activeStartMinute
        var cursor = toLocalDate(from)
        // Search two weeks out: covers full-week masks, skips and ONCE dates alike.
        repeat(14) {
            // Delegate eligibility to the single isActiveDay implementation.
            if (isActiveDay(point, cursor)) {
                if (start == null) {
                    // Whole-day point: any instant on an eligible day is "active".
                    val candidate = atMinutes(cursor, 0)
                    if (candidate > from) return candidate
                } else {
                    val candidate = atMinutes(cursor, start)
                    if (candidate > from) return candidate
                }
            }
            cursor = cursor.plusDays(1)
        }
        return null
    }

    // ── §4.9 state resets after manual edits ──────────────────────────────

    /** Window/weekday/mode changed: force re-evaluation of the current window. */
    fun resetWindowDedup(point: CheckInPoint): CheckInPoint =
        point.copy(lastTriggeredWindowStart = null)

    /** onceDate changed, or user restored a finished/skipped ONCE point: re-arm it. */
    fun rearmOnce(point: CheckInPoint): CheckInPoint = point.copy(
        onceStatus = "PENDING",
        completedAt = null,
        lastTriggeredWindowStart = null
    )

    // ── §6.3 summary formatting ───────────────────────────────────────────

    private val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

    /** Bitmask → human summary, e.g. 31 → "周一~周五", 96 → "周六、周日". */
    fun formatActiveDays(activeDays: Int): String {
        if (activeDays == 0) return "未选择"
        if (activeDays == 127) return "每天"
        if (activeDays == 31) return "周一~周五"
        if (activeDays == 96) return "周六、周日"
        val selected = (0..6).filter { (activeDays shr it) and 1 == 1 }
        if (selected.isEmpty()) return "未选择"
        val parts = mutableListOf<String>()
        var i = 0
        while (i < selected.size) {
            var j = i
            while (j + 1 < selected.size && selected[j + 1] == selected[j] + 1) j++
            val len = j - i + 1
            val a = selected[i]; val b = selected[j]
            parts += if (len >= 3) "周${DAY_NAMES[a]}~周${DAY_NAMES[b]}" else {
                (a..b).joinToString("、") { "周${DAY_NAMES[it]}" }
            }
            i = j + 1
        }
        return parts.joinToString("、")
    }
}
