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
        Task::class, SavedPlace::class, SavedCardEntity::class, TaskEvent::class, TrustedContact::class,
        TaskLearningProfile::class, ContactDisplayPref::class,
        PlaceTypeUsage::class, UserTaskSuggestion::class,
        ShoppingListItem::class, ShoppingPlaceItemOrder::class,
        ReminderOutcome::class, TaskShare::class,
        UsualShoppingSession::class, UsualShoppingItemStats::class, UsualShoppingSuppression::class,
        FoodHealthTag::class, UserFoodHealthOverride::class,
        TaskPlaceNotificationRule::class
    ],
    version = 24,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun savedCardDao(): SavedCardDao
    abstract fun taskEventDao(): TaskEventDao
    abstract fun trustedContactDao(): TrustedContactDao
    abstract fun taskLearningProfileDao(): TaskLearningProfileDao
    abstract fun contactDisplayPrefDao(): ContactDisplayPrefDao
    abstract fun placeTypeUsageDao(): PlaceTypeUsageDao
    abstract fun userTaskSuggestionDao(): UserTaskSuggestionDao
    abstract fun shoppingListItemDao(): ShoppingListItemDao
    abstract fun shoppingPlaceItemOrderDao(): ShoppingPlaceItemOrderDao
    abstract fun reminderOutcomeDao(): ReminderOutcomeDao
    abstract fun taskShareDao(): TaskShareDao
    abstract fun usualShoppingDao(): UsualShoppingDao
    abstract fun foodHealthDao(): FoodHealthDao
    abstract fun taskPlaceNotificationRuleDao(): TaskPlaceNotificationRuleDao

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

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN isEverywhere INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN recurrenceType TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN recurrenceDayOfMonth INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN recurrenceMonth INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN lastCompletedAt INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN calendarEventId TEXT")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shopping_list_items ADD COLUMN checkedByUserId TEXT")
                db.execSQL("ALTER TABLE shopping_list_items ADD COLUMN checkedByDisplayName TEXT")
                db.execSQL("ALTER TABLE shopping_list_items ADD COLUMN checkedAt INTEGER")
                db.execSQL("ALTER TABLE shopping_list_items ADD COLUMN updatedByUserId TEXT")
                db.execSQL("ALTER TABLE shopping_list_items ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'SYNCED'")
                createTaskSharesExact(db)
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val exists = db.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf("task_shares")
                ).use { it.moveToFirst() }

                if (exists) {
                    db.execSQL("DROP TABLE IF EXISTS task_shares_new")
                    db.execSQL("""
                        CREATE TABLE task_shares_new (
                            id TEXT NOT NULL PRIMARY KEY,
                            taskId TEXT NOT NULL,
                            ownerUserId TEXT NOT NULL,
                            sharedWithUserId TEXT NOT NULL,
                            sharedWithDisplayName TEXT NOT NULL,
                            permission TEXT NOT NULL,
                            status TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                    """.trimIndent())
                    db.execSQL("""
                        INSERT OR REPLACE INTO task_shares_new (
                            id, taskId, ownerUserId, sharedWithUserId,
                            sharedWithDisplayName, permission, status, createdAt, updatedAt
                        )
                        SELECT
                            id, taskId, ownerUserId, sharedWithUserId,
                            COALESCE(sharedWithDisplayName, ''),
                            COALESCE(permission, 'EDIT'),
                            COALESCE(status, 'ACTIVE'),
                            createdAt, updatedAt
                        FROM task_shares
                    """.trimIndent())
                    db.execSQL("DROP TABLE task_shares")
                    db.execSQL("ALTER TABLE task_shares_new RENAME TO task_shares")
                } else {
                    createTaskSharesExact(db)
                }
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_task_shares_taskId_sharedWithUserId
                    ON task_shares(taskId, sharedWithUserId)
                """.trimIndent())
            }
        }

        // Fixed: no DEFAULT 0 on numeric columns; Room-generated index names.
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `usual_shopping_sessions` (
                    `id` TEXT NOT NULL, `userId` TEXT NOT NULL, `taskId` TEXT NOT NULL,
                    `placeTypeKey` TEXT NOT NULL, `placeName` TEXT NOT NULL,
                    `completedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""")

                db.execSQL("""CREATE TABLE IF NOT EXISTS `usual_shopping_item_stats` (
                    `id` TEXT NOT NULL, `userId` TEXT NOT NULL, `placeTypeKey` TEXT NOT NULL,
                    `normalizedItem` TEXT NOT NULL, `displayItem` TEXT NOT NULL,
                    `buyCount` INTEGER NOT NULL, `suggestedCount` INTEGER NOT NULL,
                    `acceptedCount` INTEGER NOT NULL, `dismissCount` INTEGER NOT NULL,
                    `lastBoughtAt` INTEGER NOT NULL, `lastDismissedAt` INTEGER NOT NULL,
                    `suppressedUntil` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_usual_shopping_item_stats_userId_placeTypeKey_normalizedItem`
                    ON `usual_shopping_item_stats` (`userId`, `placeTypeKey`, `normalizedItem`)""")

                db.execSQL("""CREATE TABLE IF NOT EXISTS `usual_shopping_suppressions` (
                    `id` TEXT NOT NULL, `userId` TEXT NOT NULL, `placeTypeKey` TEXT NOT NULL,
                    `suppressedUntil` INTEGER NOT NULL, `lastShownAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`))""")
                db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_usual_shopping_suppressions_userId_placeTypeKey`
                    ON `usual_shopping_suppressions` (`userId`, `placeTypeKey`)""")

                // subcategory column not included here — MIGRATION_17_18 adds it
                db.execSQL("""CREATE TABLE IF NOT EXISTS `food_health_tags` (
                    `id` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, `language` TEXT NOT NULL,
                    `healthTag` TEXT NOT NULL, `suggestion` TEXT, PRIMARY KEY(`id`))""")
                db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_food_health_tags_normalizedName_language`
                    ON `food_health_tags` (`normalizedName`, `language`)""")

                db.execSQL("""CREATE TABLE IF NOT EXISTS `user_food_health_overrides` (
                    `id` TEXT NOT NULL, `userId` TEXT NOT NULL, `normalizedName` TEXT NOT NULL,
                    `healthTag` TEXT NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_user_food_health_overrides_userId_normalizedName`
                    ON `user_food_health_overrides` (`userId`, `normalizedName`)""")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE food_health_tags ADD COLUMN subcategory TEXT")
            }
        }

        // Repairs devices that installed the broken MIGRATION_16_17 schema:
        // usual_shopping_item_stats had DEFAULT 0 on numeric columns and wrong index names.
        // All four tables from that migration had short idx_* names instead of Room names.
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rebuild usual_shopping_item_stats only if old bad index is present
                val statsHasBadIndex = db.query(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_usual_item'"
                ).use { it.moveToFirst() }

                if (statsHasBadIndex) {
                    db.execSQL("DROP INDEX IF EXISTS `idx_usual_item`")
                    db.execSQL("""CREATE TABLE IF NOT EXISTS `usual_shopping_item_stats_new` (
                        `id` TEXT NOT NULL, `userId` TEXT NOT NULL, `placeTypeKey` TEXT NOT NULL,
                        `normalizedItem` TEXT NOT NULL, `displayItem` TEXT NOT NULL,
                        `buyCount` INTEGER NOT NULL, `suggestedCount` INTEGER NOT NULL,
                        `acceptedCount` INTEGER NOT NULL, `dismissCount` INTEGER NOT NULL,
                        `lastBoughtAt` INTEGER NOT NULL, `lastDismissedAt` INTEGER NOT NULL,
                        `suppressedUntil` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                    db.execSQL("""INSERT OR REPLACE INTO `usual_shopping_item_stats_new`
                        (`id`,`userId`,`placeTypeKey`,`normalizedItem`,`displayItem`,
                         `buyCount`,`suggestedCount`,`acceptedCount`,`dismissCount`,
                         `lastBoughtAt`,`lastDismissedAt`,`suppressedUntil`)
                        SELECT `id`,`userId`,`placeTypeKey`,`normalizedItem`,`displayItem`,
                         `buyCount`,`suggestedCount`,`acceptedCount`,`dismissCount`,
                         `lastBoughtAt`,`lastDismissedAt`,`suppressedUntil`
                        FROM `usual_shopping_item_stats`""")
                    db.execSQL("DROP TABLE `usual_shopping_item_stats`")
                    db.execSQL("ALTER TABLE `usual_shopping_item_stats_new` RENAME TO `usual_shopping_item_stats`")
                }
                db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_usual_shopping_item_stats_userId_placeTypeKey_normalizedItem`
                    ON `usual_shopping_item_stats` (`userId`, `placeTypeKey`, `normalizedItem`)""")

                // Fix usual_shopping_suppressions index name
                db.execSQL("DROP INDEX IF EXISTS `idx_usual_sup`")
                db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_usual_shopping_suppressions_userId_placeTypeKey`
                    ON `usual_shopping_suppressions` (`userId`, `placeTypeKey`)""")

                // Fix food_health_tags index name
                db.execSQL("DROP INDEX IF EXISTS `idx_food_tag`")
                db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_food_health_tags_normalizedName_language`
                    ON `food_health_tags` (`normalizedName`, `language`)""")

                // Fix user_food_health_overrides index name
                db.execSQL("DROP INDEX IF EXISTS `idx_food_override`")
                db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_user_food_health_overrides_userId_normalizedName`
                    ON `user_food_health_overrides` (`userId`, `normalizedName`)""")
            }
        }

        // Adds addedByUserId/addedByDisplayName/addedAt/originColorKey to shopping_list_items.
        // All nullable → no SQL DEFAULT needed → no Room schema mismatch.
        // Also creates task_place_notification_rules with exact Room-generated index name.
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `addedByUserId` TEXT")
                db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `addedByDisplayName` TEXT")
                db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `addedAt` INTEGER")
                db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `originColorKey` TEXT")

                db.execSQL("""CREATE TABLE IF NOT EXISTS `task_place_notification_rules` (
                    `id` TEXT NOT NULL,
                    `taskId` TEXT NOT NULL,
                    `exactPlaceKey` TEXT NOT NULL,
                    `placeId` TEXT,
                    `placeName` TEXT NOT NULL,
                    `ruleType` TEXT NOT NULL,
                    `snoozedUntil` INTEGER,
                    `active` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `createdByUserId` TEXT,
                    PRIMARY KEY(`id`))""")
                db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_task_place_notification_rules_taskId_exactPlaceKey_ruleType`
                    ON `task_place_notification_rules` (`taskId`, `exactPlaceKey`, `ruleType`)""")
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `deletedAt` INTEGER")
                db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `deletedByUserId` TEXT")
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_cards` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `codeType` TEXT NOT NULL,
                        `barcodeFormat` TEXT,
                        `codeValue` TEXT NOT NULL,
                        `note` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_saved_cards_name`
                    ON `saved_cards` (`name`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_saved_cards_codeValue`
                    ON `saved_cards` (`codeValue`)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `saved_cards` ADD COLUMN `passwordOrPinEncrypted` TEXT")
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `rawText` TEXT")
                db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `canonicalName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE `shopping_list_items` SET `canonicalName` = `text` WHERE trim(`canonicalName`) = ''")
                db.execSQL("UPDATE `shopping_list_items` SET `rawText` = `text` WHERE `rawText` IS NULL OR trim(`rawText`) = ''")
            }
        }

        private fun createTaskSharesExact(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS task_shares (
                    id TEXT NOT NULL PRIMARY KEY,
                    taskId TEXT NOT NULL,
                    ownerUserId TEXT NOT NULL,
                    sharedWithUserId TEXT NOT NULL,
                    sharedWithDisplayName TEXT NOT NULL,
                    permission TEXT NOT NULL,
                    status TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS index_task_shares_taskId_sharedWithUserId
                ON task_shares(taskId, sharedWithUserId)
            """.trimIndent())
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "remind_in_place.db"
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
                MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24
            ).build().also { INSTANCE = it }
        }
    }
}
