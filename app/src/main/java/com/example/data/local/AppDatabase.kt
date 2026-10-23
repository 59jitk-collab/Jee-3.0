package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.ChapterDao
import com.example.data.local.dao.DailyScheduleDao
import com.example.data.local.dao.FocusSessionDao
import com.example.data.local.dao.MistakeEntryDao
import com.example.data.local.dao.RevisionItemDao
import com.example.data.local.dao.TestRecordDao
import com.example.data.local.dao.UserSettingsDao
import com.example.data.local.dao.XpEventDao
import com.example.data.local.entities.ChapterEntity
import com.example.data.local.entities.DailyScheduleEntity
import com.example.data.local.entities.FocusSessionEntity
import com.example.data.local.entities.MistakeEntryEntity
import com.example.data.local.entities.RevisionItemEntity
import com.example.data.local.entities.TestRecordEntity
import com.example.data.local.entities.UserSettingsEntity
import com.example.data.local.entities.XpEventEntity

@Database(
    entities = [
        ChapterEntity::class,
        DailyScheduleEntity::class,
        TestRecordEntity::class,
        MistakeEntryEntity::class,
        RevisionItemEntity::class,
        UserSettingsEntity::class,
        FocusSessionEntity::class,
        XpEventEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chapterDao(): ChapterDao
    abstract fun dailyScheduleDao(): DailyScheduleDao
    abstract fun testRecordDao(): TestRecordDao
    abstract fun mistakeEntryDao(): MistakeEntryDao
    abstract fun revisionItemDao(): RevisionItemDao
    abstract fun userSettingsDao(): UserSettingsDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun xpEventDao(): XpEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Non-destructive migration from version 3 to version 4:
         * - Preserves all existing chapters, schedules, tests, mistakes, revisions, settings, and focus sessions.
         * - Adds masteryLevel, lastStudiedDate, revisionStage, and nextRevisionDate to chapters.
         * - Adds snoozedUntilDate and notes to revision_items.
         * - Adds revisionIntervalsCsv, revisionNotificationsEnabled, studyRemindersEnabled, studyReminderHour, studyReminderMinute to user_settings.
         * - Creates xp_events table for idempotent private XP tracking.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN masteryLevel TEXT NOT NULL DEFAULT 'NOT_ASSESSED'")
                db.execSQL("ALTER TABLE chapters ADD COLUMN lastStudiedDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chapters ADD COLUMN revisionStage INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chapters ADD COLUMN nextRevisionDate TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE revision_items ADD COLUMN snoozedUntilDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE revision_items ADD COLUMN notes TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE user_settings ADD COLUMN revisionIntervalsCsv TEXT NOT NULL DEFAULT '1,3,7,21'")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN revisionNotificationsEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN studyRemindersEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN studyReminderHour INTEGER NOT NULL DEFAULT 21")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN studyReminderMinute INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `xp_events` (
                        `eventKey` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `xpAmount` INTEGER NOT NULL,
                        `dateString` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`eventKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `focus_sessions` (
                        `id` INTEGER NOT NULL,
                        `sessionStatus` TEXT NOT NULL,
                        `studyDurationMinutes` INTEGER NOT NULL,
                        `permittedBreaks` INTEGER NOT NULL,
                        `breaksRemaining` INTEGER NOT NULL,
                        `breakDurationMinutes` INTEGER NOT NULL,
                        `sessionStartTimeMillis` INTEGER NOT NULL,
                        `targetEndTimeMillis` INTEGER NOT NULL,
                        `breakStartTimeMillis` INTEGER NOT NULL,
                        `breakEndTimeMillis` INTEGER NOT NULL,
                        `pauseStartTimeMillis` INTEGER NOT NULL,
                        `totalPausedDurationMillis` INTEGER NOT NULL,
                        `blockedPackagesCsv` TEXT NOT NULL,
                        `activeTaskName` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "jee_study_os_db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
