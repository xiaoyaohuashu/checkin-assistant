package com.checkinassistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PointEnabledEventDao {
    @Insert
    suspend fun insert(event: PointEnabledEvent): Long

    @Query("SELECT * FROM point_enabled_events WHERE atMillis >= :sinceMillis ORDER BY atMillis")
    fun observeSince(sinceMillis: Long): Flow<List<PointEnabledEvent>>

    /** The state a point was in before a period starts; null when it was never toggled. */
    @Query("SELECT * FROM point_enabled_events WHERE pointId = :pointId AND atMillis < :beforeMillis ORDER BY atMillis DESC LIMIT 1")
    suspend fun latestBefore(pointId: Long, beforeMillis: Long): PointEnabledEvent?
}
