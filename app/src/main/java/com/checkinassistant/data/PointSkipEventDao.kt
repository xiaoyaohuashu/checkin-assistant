package com.checkinassistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PointSkipEventDao {
    @Insert
    suspend fun insert(event: PointSkipEvent): Long

    @Query("SELECT * FROM point_skip_events WHERE createdAt >= :sinceMillis ORDER BY createdAt")
    fun observeSince(sinceMillis: Long): Flow<List<PointSkipEvent>>

    @Query("SELECT * FROM point_skip_events WHERE createdAt >= :sinceMillis ORDER BY createdAt")
    suspend fun since(sinceMillis: Long): List<PointSkipEvent>

    @Query("DELETE FROM point_skip_events WHERE reminderPointId = :pointId")
    suspend fun deleteForPoint(pointId: Long)
}
