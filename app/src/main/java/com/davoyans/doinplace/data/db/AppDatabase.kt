package com.davoyans.doinplace.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.davoyans.doinplace.data.model.*

@Database(
    entities = [
        Task::class, SavedPlace::class, TaskEvent::class, TrustedContact::class,
        TaskLearningProfile::class, ContactDisplayPref::class,
        PlaceTypeUsage::class, UserTaskSuggestion::class,
        ShoppingListItem::class, ShoppingPlaceItemOrder::class,
        ReminderOutcome::class
    ],
    version = 13,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun taskEventDao(): TaskEventDao
    abstract fun trustedContactDao(): TrustedContactDao
    abstract fun taskLearningProfileDao(): TaskLearningProfileDao
    abstract fun contactDisplayPrefDao(): ContactDisplayPrefDao
    abstract fun placeTypeUsageDao(): PlaceTypeUsageDao
    abstract fun userTaskSuggestionDao(): UserTaskSuggestionDao
    abstract fun shoppingListItemDao(): ShoppingListItemDao
    abstract fun shoppingPlaceItemOrderDao(): ShoppingPlaceItemOrderDao
    abstract fun reminderOutcomeDao(): ReminderOutcomeDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_places ADD COLUMN provider TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN checklistJson TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN archivedAt INTEGER")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN photoUri TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN priority TEXT NOT NULL DEFAULT 'NORMAL'")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS task_learning_profiles (
                        id TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL,
                        placeKey TEXT NOT NULL,
                        normalizedPlaceName TEXT NOT NULL,
                        keyword TEXT NOT NULL,
                        highCount INTEGER NOT NULL DEFAULT 0,
                        normalCount INTEGER NOT NULL DEFAULT 0,
                        lowCount INTEGER NOT NULL DEFAULT 0,
                        completedCount INTEGER NOT NULL DEFAULT 0,
                        cancelledCount INTEGER NOT NULL DEFAULT 0,
                        lastUsedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS contact_display_prefs (
                        id TEXT NOT NULL PRIMARY KEY,
                        ownerUserId TEXT NOT NULL,
                        contactUserId TEXT NOT NULL,
                        nickname TEXT NOT NULL DEFAULT '',
                        iconId TEXT NOT NULL DEFAULT 'person',
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN placeMode TEXT NOT NULL DEFAULT 'EXACT'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN placeTypeId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN placeTypeName TEXT")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS place_type_usage (
                        id TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL,
                        placeTypeId TEXT NOT NULL,
                        useCount INTEGER NOT NULL DEFAULT 0,
                        lastUsedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_task_suggestions (
                        id TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL,
                        placeKey TEXT NOT NULL,
                        placeTypeId TEXT,
                        normalizedText TEXT NOT NULL,
                        displayText TEXT NOT NULL,
                        useCount INTEGER NOT NULL DEFAULT 0,
                        acceptedCount INTEGER NOT NULL DEFAULT 0,
                        lastUsedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN taskType TEXT NOT NULL DEFAULT 'SIMPLE'")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS shopping_list_items (
                        id TEXT NOT NULL PRIMARY KEY,
                        taskId TEXT NOT NULL,
                        text TEXT NOT NULL,
                        normalizedText TEXT NOT NULL,
                        orderIndex INTEGER NOT NULL,
                        checked INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS shopping_place_item_orders (
                        id TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL,
                        placeKey TEXT NOT NULL,
                        normalizedItemText TEXT NOT NULL,
                        displayText TEXT NOT NULL,
                        orderRank INTEGER NOT NULL,
                        useCount INTEGER NOT NULL DEFAULT 1,
                        lastUsedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE tasks SET priority = 'URGENT' WHERE priority = 'HIGH'")
                db.execSQL("UPDATE tasks SET priority = 'EASY' WHERE priority IN ('NORMAL', 'LOW')")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE tasks SET priority = 'NO_RUSH' WHERE priority = 'EASY'")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_task_suggestions ADD COLUMN category TEXT NOT NULL DEFAULT 'task'")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE task_events ADD COLUMN placeName TEXT")
                db.execSQL("ALTER TABLE task_events ADD COLUMN placeAddress TEXT")
                db.execSQL("ALTER TABLE task_events ADD COLUMN reason TEXT")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS reminder_outcomes (
                        id TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL,
                        taskId TEXT NOT NULL,
                        taskType TEXT NOT NULL,
                        placeTypeId TEXT,
                        placeId TEXT,
                        activityType TEXT NOT NULL,
                        isAtHome INTEGER NOT NULL DEFAULT 0,
                        isNight INTEGER NOT NULL DEFAULT 0,
                        isDriving INTEGER NOT NULL DEFAULT 0,
                        priority TEXT NOT NULL,
                        dueUrgency TEXT NOT NULL,
                        decision TEXT NOT NULL,
                        wasShown INTEGER NOT NULL DEFAULT 0,
                        wasOpened INTEGER NOT NULL DEFAULT 0,
                        wasDismissed INTEGER NOT NULL DEFAULT 0,
                        wasCompletedAfterNotification INTEGER NOT NULL DEFAULT 0,
                        wasAutoDismissedAfterLeaving INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "remind_in_place.db"
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13
            ).build().also { INSTANCE = it }
        }
    }
}
