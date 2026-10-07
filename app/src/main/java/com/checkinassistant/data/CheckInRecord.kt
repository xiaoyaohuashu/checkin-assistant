package com.checkinassistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "check_in_records")
data class CheckInRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pointId: Long,
    val pointName: String,
    val triggeredAt: Long,
    val year: Int,
    val month: Int,
    val day: Int,
    val weekday: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val status: String = RecordStatus.UNCONFIRMED.name,
    val reminderCount: Int = 0,
    val actionAt: Long? = null
)

enum class RecordStatus { COMPLETED, UNCONFIRMED, IGNORED, DEBUG }
