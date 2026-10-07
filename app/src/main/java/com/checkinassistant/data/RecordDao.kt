package com.checkinassistant.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Insert suspend fun insert(record: CheckInRecord): Long
    @Update suspend fun update(record: CheckInRecord)
    @Query("SELECT * FROM check_in_records WHERE id = :id") suspend fun get(id: Long): CheckInRecord?
    @Query("SELECT * FROM check_in_records ORDER BY triggeredAt DESC") fun observeAll(): Flow<List<CheckInRecord>>
    /** v6: full-table snapshot for backup export. */
    @Query("SELECT * FROM check_in_records ORDER BY triggeredAt DESC") suspend fun getAllOnce(): List<CheckInRecord>

    /** v5+: newest record for a point — drives the "已到达，未确认" badge. */
    @Query("SELECT * FROM check_in_records WHERE pointId = :pointId ORDER BY triggeredAt DESC LIMIT 1")
    suspend fun latestForPoint(pointId: Long): CheckInRecord?
    @Query("SELECT * FROM check_in_records WHERE triggeredAt >= :start AND triggeredAt < :end ORDER BY triggeredAt DESC") fun observeMonth(start: Long, end: Long): Flow<List<CheckInRecord>>
    @Query("SELECT * FROM check_in_records WHERE status = :status AND triggeredAt >= :start AND triggeredAt < :end ORDER BY triggeredAt DESC")
    fun observeMonthByStatus(start: Long, end: Long, status: String): Flow<List<CheckInRecord>>
    @Query("SELECT * FROM check_in_records WHERE status = :status ORDER BY triggeredAt DESC")
    fun observeByStatus(status: String): Flow<List<CheckInRecord>>
    @Query("SELECT * FROM check_in_records WHERE status = :status ORDER BY triggeredAt DESC")
    suspend fun getByStatus(status: String): List<CheckInRecord>
    @Query("DELETE FROM check_in_records WHERE status = :status AND triggeredAt < :before")
    suspend fun deleteByStatusBefore(status: String, before: Long): Int
    @Query("SELECT COUNT(*) FROM check_in_records WHERE pointId = :pointId AND year = :year AND month = :month AND day = :day")
    suspend fun triggeredCountForDay(pointId: Long, year: Int, month: Int, day: Int): Int
}

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(state: ReminderState)
    @Query("SELECT * FROM reminder_states WHERE pointId = :pointId") suspend fun get(pointId: Long): ReminderState?
    @Query("SELECT * FROM reminder_states WHERE active = 1") suspend fun active(): List<ReminderState>
    @Query("UPDATE reminder_states SET active = 0 WHERE pointId = :pointId") suspend fun deactivate(pointId: Long)
}
