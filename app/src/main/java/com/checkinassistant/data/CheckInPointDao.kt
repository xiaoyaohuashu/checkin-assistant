package com.checkinassistant.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckInPointDao {
    @Query("SELECT * FROM check_in_points ORDER BY id DESC")
    fun observeAll(): Flow<List<CheckInPoint>>

    @Query("SELECT * FROM check_in_points WHERE id = :id") suspend fun get(id: Long): CheckInPoint?
    @Query("SELECT * FROM check_in_points WHERE enabled = 1") suspend fun enabledPoints(): List<CheckInPoint>
    /** v6: full-table snapshot for backup export. */
    @Query("SELECT * FROM check_in_points ORDER BY id") suspend fun getAllOnce(): List<CheckInPoint>
    @Query("UPDATE check_in_points SET triggerState = 'ENTERED', enteredAt = :enteredAt WHERE id = :id AND triggerState = 'ARMED'")
    suspend fun markEnteredIfArmed(id: Long, enteredAt: Long): Int
    @Query("UPDATE check_in_points SET triggerState = 'ARMED', enteredAt = NULL WHERE id = :id AND triggerState = 'ENTERED'")
    suspend fun rearmIfEntered(id: Long): Int
    @Query("UPDATE check_in_points SET triggerState = 'ARMED', enteredAt = NULL WHERE id = :id AND triggerState != 'ARMED'")
    suspend fun rearmAfterExit(id: Long): Int
    /** Toggling also resets the state machine, so a re-enabled point never resumes half-entered. */
    @Query("UPDATE check_in_points SET enabled = :enabled, triggerState = 'ARMED', enteredAt = NULL WHERE id = :id")
    suspend fun setEnabledAndRearm(id: Long, enabled: Boolean): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(point: CheckInPoint): Long

    @Update
    suspend fun update(point: CheckInPoint)

    @Delete
    suspend fun delete(point: CheckInPoint)
}
