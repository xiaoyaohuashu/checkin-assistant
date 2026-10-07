package com.checkinassistant.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only log of "skip today" actions, so skipped days can be excluded from
 * monthly stats the same way disabled windows are. ONCE points write here too.
 */
@Entity(tableName = "point_skip_events", indices = [Index("reminderPointId")])
data class PointSkipEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderPointId: Long,
    /** "2026-09-21" — the local date that was skipped. */
    val skipDate: String,
    val createdAt: Long
)
