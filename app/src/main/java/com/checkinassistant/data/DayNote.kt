package com.checkinassistant.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * v2.2.15: 日期备注。一天一条，比如「休息，考勤算上班」，月底对考勤时翻历史页就能看到。
 * date 用本地日期 yyyy-MM-dd，和 point_skip_events.skipDate 同一格式。
 */
@Entity(tableName = "day_notes")
data class DayNote(
    @PrimaryKey val date: String,
    val note: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface DayNoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(note: DayNote)
    @Query("DELETE FROM day_notes WHERE date = :date") suspend fun delete(date: String)
    @Query("SELECT * FROM day_notes WHERE date = :date") suspend fun get(date: String): DayNote?

    /** prefix 形如 "2026-11-"，取当月全部备注。 */
    @Query("SELECT * FROM day_notes WHERE date LIKE :prefix || '%' ORDER BY date")
    fun observeMonth(prefix: String): Flow<List<DayNote>>
    @Query("SELECT * FROM day_notes ORDER BY date") suspend fun getAllOnce(): List<DayNote>
}
