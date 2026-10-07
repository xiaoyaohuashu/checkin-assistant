package com.checkinassistant.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Append-only log of enable/disable changes, so disabled days can be excluded from stats. */
@Entity(tableName = "point_enabled_events", indices = [Index("pointId")])
data class PointEnabledEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pointId: Long,
    val atMillis: Long,
    val enabled: Boolean
)
