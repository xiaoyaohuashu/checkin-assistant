package com.checkinassistant.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Insert suspend fun insert(record: CheckInRecord): Long
    @Update suspend fun update(record: CheckInRecord)
    @Query("SELECT * FROM check_in_records WHERE id = :id") suspend fun get(id: Long): CheckInRecord?
    // 2.2.21：列表查询放进事务。记录表很大时游标会分段读取，期间若有删除/插入，
    // 后一段会读到不存在的行，Room 拿到 null 的 pointName 直接崩（2.2.20 闪退）。
    @Transaction @Query("SELECT * FROM check_in_records ORDER BY triggeredAt DESC") fun observeAll(): Flow<List<CheckInRecord>>
    /** v6: full-table snapshot for backup export. */
    @Transaction @Query("SELECT * FROM check_in_records ORDER BY triggeredAt DESC") suspend fun getAllOnce(): List<CheckInRecord>

    /** v5+: newest record for a point — drives the "已到达，未确认" badge. */
    @Query("SELECT * FROM check_in_records WHERE pointId = :pointId ORDER BY triggeredAt DESC LIMIT 1")
    suspend fun latestForPoint(pointId: Long): CheckInRecord?
    @Transaction @Query("SELECT * FROM check_in_records WHERE triggeredAt >= :start AND triggeredAt < :end ORDER BY triggeredAt DESC") fun observeMonth(start: Long, end: Long): Flow<List<CheckInRecord>>
    @Transaction @Query("SELECT * FROM check_in_records WHERE status = :status AND triggeredAt >= :start AND triggeredAt < :end ORDER BY triggeredAt DESC")
    fun observeMonthByStatus(start: Long, end: Long, status: String): Flow<List<CheckInRecord>>
    @Transaction @Query("SELECT * FROM check_in_records WHERE status = :status ORDER BY triggeredAt DESC")
    fun observeByStatus(status: String): Flow<List<CheckInRecord>>
    @Transaction @Query("SELECT * FROM check_in_records WHERE status = :status ORDER BY triggeredAt DESC")
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
