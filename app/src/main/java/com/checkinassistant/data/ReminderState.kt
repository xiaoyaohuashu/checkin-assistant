package com.checkinassistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminder_states")
data class ReminderState(
    @PrimaryKey val pointId: Long,
    val recordId: Long,
    val reminderStartTime: Long,
    val reminderCount: Int,
    val snoozeUntil: Long? = null,
    val active: Boolean = true,
    val simulated: Boolean = false
)
