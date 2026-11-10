package com.checkinassistant.data

import androidx.room.ColumnInfo
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
    val actionAt: Long? = null,
    /** v2.2.22 (DB 10)：「模拟提醒」产生的记录。不计入每日触发上限。 */
    @ColumnInfo(defaultValue = "0") val simulated: Boolean = false
)

enum class RecordStatus { COMPLETED, UNCONFIRMED, IGNORED, DEBUG }
