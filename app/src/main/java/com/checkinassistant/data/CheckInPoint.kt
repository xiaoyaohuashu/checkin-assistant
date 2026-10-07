package com.checkinassistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "check_in_points")
data class CheckInPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = 200,
    // Retained for database compatibility; application-level confirmation is fixed at 25 seconds.
    val dwellSeconds: Int = 25,
    val enabled: Boolean = true,
    /** Persistent per-point state; never infer this only from a process-local callback. */
    val triggerState: String = PointState.ARMED.name,
    val enteredAt: Long? = null,
    /** Minutes since midnight. Both values must be present to enable a time window. */
    val activeStartMinute: Int? = null,
    val activeEndMinute: Int? = null,
    /** Null means unlimited, preserving the behaviour of points created before this setting. */
    val dailyTriggerLimit: Int? = null,
    /** Calendar colour; null falls back to [PointColors.forPoint] so legacy points stay stable. */
    val color: Int? = null,
    /** "WEEKLY" | "ONCE" | "DAILY". Legacy points behave as DAILY. */
    val repeatMode: String = "DAILY",
    /** Bitmask for WEEKLY mode: bit0=Monday … bit6=Sunday. 127 = every day. */
    val activeDays: Int = 127,
    /** "2026-09-21"; only meaningful when repeatMode == "ONCE". */
    val onceDate: String? = null,
    /** "PENDING" | "COMPLETED" | "SKIPPED" | "EXPIRED"; only for ONCE mode. */
    val onceStatus: String? = null,
    /** Completion timestamp for ONCE mode. */
    val completedAt: Long? = null,
    /** "2026-09-21" — skip reminders entirely on this date; null = not skipped. */
    val skipUntilDate: String? = null,
    /** Start timestamp of the last window that triggered; dedupes one window to one reminder. */
    val lastTriggeredWindowStart: Long? = null
)

enum class PointState { ARMED, ENTERED, DWELL_CONFIRMING, REMINDING, COMPLETED, SNOOZED, IGNORED }
