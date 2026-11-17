package com.checkinassistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * v2.2.25：备份导出 / 导入专用。只在 [AndroidBackupStore] 里、并且只在事务里调用。
 * 其它地方不要用（整表删除只属于「覆盖全部导入」）。
 */
@Dao
interface BackupDao {
    @Query("SELECT * FROM check_in_points ORDER BY id") suspend fun points(): List<CheckInPoint>
    /** 调试日志（status = DEBUG）不进备份。 */
    @Query("SELECT * FROM check_in_records WHERE status != 'DEBUG' ORDER BY id") suspend fun records(): List<CheckInRecord>
    @Query("SELECT * FROM day_notes ORDER BY date") suspend fun dayNotes(): List<DayNote>
    @Query("SELECT * FROM point_skip_events ORDER BY id") suspend fun skipEvents(): List<PointSkipEvent>
    @Query("SELECT * FROM point_enabled_events ORDER BY id") suspend fun enabledEvents(): List<PointEnabledEvent>
    @Query("SELECT * FROM reminder_states ORDER BY pointId") suspend fun reminderStates(): List<ReminderState>

    @Query("DELETE FROM check_in_points") suspend fun deletePoints()
    /** 整表删除：调试日志也一起清掉（导入确认框里会提醒）。 */
    @Query("DELETE FROM check_in_records") suspend fun deleteRecords()
    @Query("DELETE FROM day_notes") suspend fun deleteDayNotes()
    @Query("DELETE FROM point_skip_events") suspend fun deleteSkipEvents()
    @Query("DELETE FROM point_enabled_events") suspend fun deleteEnabledEvents()
    @Query("DELETE FROM reminder_states") suspend fun deleteReminderStates()

    // 主键冲突直接抛异常，让整个导入事务回滚。reminder_states 不导入（见 BackupImportRules）。
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPoints(items: List<CheckInPoint>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRecords(items: List<CheckInRecord>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertDayNotes(items: List<DayNote>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSkipEvents(items: List<PointSkipEvent>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEnabledEvents(items: List<PointEnabledEvent>)
}
