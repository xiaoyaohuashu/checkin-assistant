package com.checkinassistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CheckInPoint::class, CheckInRecord::class, ReminderState::class, PointEnabledEvent::class, PointSkipEvent::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun checkInPointDao(): CheckInPointDao
    abstract fun recordDao(): RecordDao
    abstract fun reminderDao(): ReminderDao
    abstract fun pointEnabledEventDao(): PointEnabledEventDao
    abstract fun pointSkipEventDao(): PointSkipEventDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "checkin-assistant.db"
            ).addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { instance = it }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE check_in_points ADD COLUMN activeStartMinute INTEGER")
                database.execSQL("ALTER TABLE check_in_points ADD COLUMN activeEndMinute INTEGER")
                database.execSQL("ALTER TABLE check_in_points ADD COLUMN dailyTriggerLimit INTEGER")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE check_in_points ADD COLUMN color INTEGER")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `point_enabled_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`pointId` INTEGER NOT NULL, " +
                        "`atMillis` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL)"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_point_enabled_events_pointId` ON `point_enabled_events` (`pointId`)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE check_in_points ADD COLUMN repeatMode TEXT NOT NULL DEFAULT 'DAILY'")
                database.execSQL("ALTER TABLE check_in_points ADD COLUMN activeDays INTEGER NOT NULL DEFAULT 127")
                database.execSQL("ALTER TABLE check_in_points ADD COLUMN onceDate TEXT")
                database.execSQL("ALTER TABLE check_in_points ADD COLUMN onceStatus TEXT")
                database.execSQL("ALTER TABLE check_in_points ADD COLUMN completedAt INTEGER")
                database.execSQL("ALTER TABLE check_in_points ADD COLUMN skipUntilDate TEXT")
                database.execSQL("ALTER TABLE check_in_points ADD COLUMN lastTriggeredWindowStart INTEGER")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `point_skip_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`reminderPointId` INTEGER NOT NULL, " +
                        "`skipDate` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_point_skip_events_reminderPointId` ON `point_skip_events` (`reminderPointId`)")
            }
        }
    }
}
