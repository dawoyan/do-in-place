package com.davoyans.doinplace.data.db

import androidx.room.*
import com.davoyans.doinplace.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderOutcomeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(outcome: ReminderOutcome)

    @Query("""UPDATE reminder_outcomes SET wasOpened = 1
              WHERE id IN (SELECT id FROM reminder_outcomes WHERE taskId = :taskId AND wasShown = 1
                           ORDER BY createdAt DESC LIMIT 1)""")
    suspend fun markOpened(taskId: String)

    @Query("""UPDATE reminder_outcomes SET wasCompletedAfterNotification = 1, wasOpened = 1
              WHERE id IN (SELECT id FROM reminder_outcomes WHERE taskId = :taskId AND wasShown = 1
                           ORDER BY createdAt DESC LIMIT 1)""")
    suspend fun markCompleted(taskId: String)

    // Repeated night NO_RUSH notifications ignored for this place type → learning penalty
    @Query("""SELECT COUNT(*) FROM reminder_outcomes
              WHERE userId = :uid AND placeTypeId = :typeId
              AND isNight = 1 AND priority = 'NO_RUSH' AND wasShown = 1 AND wasOpened = 0""")
    suspend fun countIgnoredNightlyForType(uid: String, typeId: String): Int

    // Daytime completions for this place type → learning boost
    @Query("""SELECT COUNT(*) FROM reminder_outcomes
              WHERE userId = :uid AND placeTypeId = :typeId
              AND isNight = 0 AND wasCompletedAfterNotification = 1""")
    suspend fun countCompletedDaytimeForType(uid: String, typeId: String): Int

    @Query("DELETE FROM reminder_outcomes WHERE userId = :uid")
    suspend fun clearAll(uid: String)
}

@Dao
interface SavedCardDao {
    @Query("SELECT * FROM saved_cards ORDER BY lower(name) ASC, createdAt DESC")
    fun observeAll(): Flow<List<SavedCardEntity>>

    @Query("SELECT * FROM saved_cards WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<SavedCardEntity?>

    @Query("SELECT * FROM saved_cards WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SavedCardEntity?

    @Query("SELECT * FROM saved_cards WHERE codeValue = :codeValue LIMIT 1")
    suspend fun findByCodeValue(codeValue: String): SavedCardEntity?

    @Upsert
    suspend fun upsert(card: SavedCardEntity)

    @Query("DELETE FROM saved_cards WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ShoppingListItemDao {
    @Query("SELECT * FROM shopping_list_items WHERE taskId = :taskId AND deletedAt IS NULL ORDER BY orderIndex ASC")
    fun observeForTask(taskId: String): Flow<List<ShoppingListItem>>

    @Query("SELECT * FROM shopping_list_items WHERE taskId = :taskId AND deletedAt IS NULL ORDER BY orderIndex ASC")
    suspend fun getForTask(taskId: String): List<ShoppingListItem>

    @Query("SELECT * FROM shopping_list_items WHERE taskId = :taskId ORDER BY orderIndex ASC")
    suspend fun getForTaskIncludingDeleted(taskId: String): List<ShoppingListItem>

    @Upsert
    suspend fun upsert(item: ShoppingListItem)

    @Upsert
    suspend fun upsertAll(items: List<ShoppingListItem>)

    @Query("UPDATE shopping_list_items SET checked = :checked, updatedAt = :now WHERE id = :id")
    suspend fun updateChecked(id: String, checked: Boolean, now: Long = System.currentTimeMillis())

    @Query("""UPDATE shopping_list_items SET checked = :checked,
        checkedByUserId = :checkedByUserId, checkedByDisplayName = :checkedByDisplayName,
        checkedAt = :checkedAt, updatedByUserId = :updatedByUserId,
        updatedAt = :now, syncStatus = 'PENDING_UPDATE' WHERE id = :id""")
    suspend fun updateCheckedWithUser(
        id: String, checked: Boolean,
        checkedByUserId: String?, checkedByDisplayName: String?, checkedAt: Long?,
        updatedByUserId: String?, now: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM shopping_list_items WHERE syncStatus = 'PENDING_UPDATE'")
    suspend fun getPendingSync(): List<ShoppingListItem>

    @Query("UPDATE shopping_list_items SET syncStatus = 'SYNCED' WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM shopping_list_items WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM shopping_list_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE shopping_list_items SET deletedAt = :now, deletedByUserId = :userId, syncStatus = 'PENDING_UPDATE', updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, userId: String, now: Long = System.currentTimeMillis())

    @Query("""UPDATE shopping_list_items SET text = :text, normalizedText = :normalizedText,
        updatedByUserId = :updatedByUserId, updatedAt = :now, syncStatus = 'PENDING_UPDATE' WHERE id = :id""")
    suspend fun updateText(id: String, text: String, normalizedText: String, updatedByUserId: String?, now: Long = System.currentTimeMillis())

    @Query("""
        SELECT * FROM shopping_list_items WHERE deletedAt IS NULL AND taskId IN
        (SELECT id FROM tasks WHERE (assignedToUserId = :uid OR createdByUserId = :uid) AND archived = 0)
        ORDER BY taskId, orderIndex ASC
    """)
    fun observeAllForUser(uid: String): Flow<List<ShoppingListItem>>

    @Query("""
        SELECT * FROM shopping_list_items WHERE taskId IN
        (SELECT taskId FROM task_shares WHERE sharedWithUserId = :uid AND status = 'ACTIVE')
        ORDER BY taskId, orderIndex ASC
    """)
    fun observeAllForSharedWithUser(uid: String): Flow<List<ShoppingListItem>>
}

@Dao
interface TaskShareDao {
    @Query("SELECT * FROM task_shares WHERE ownerUserId = :uid OR sharedWithUserId = :uid")
    fun observeAll(uid: String): Flow<List<TaskShare>>

    @Query("SELECT * FROM task_shares WHERE taskId = :taskId")
    suspend fun getForTask(taskId: String): List<TaskShare>

    @Query("SELECT * FROM task_shares WHERE sharedWithUserId = :uid AND status = 'ACTIVE'")
    suspend fun getSharedWithMe(uid: String): List<TaskShare>

    @Query("SELECT * FROM task_shares WHERE ownerUserId = :uid AND taskId = :taskId")
    suspend fun getByTaskForOwner(uid: String, taskId: String): List<TaskShare>

    @Upsert
    suspend fun upsert(share: TaskShare)

    @Upsert
    suspend fun upsertAll(shares: List<TaskShare>)

    @Query("DELETE FROM task_shares WHERE taskId = :taskId AND sharedWithUserId = :sharedWithUserId")
    suspend fun delete(taskId: String, sharedWithUserId: String)

    @Query("SELECT * FROM task_shares WHERE taskId = :taskId AND sharedWithUserId = :uid AND status = 'ACTIVE' LIMIT 1")
    suspend fun findShare(taskId: String, uid: String): TaskShare?
}

@Dao
interface ShoppingPlaceItemOrderDao {
    @Query("SELECT * FROM shopping_place_item_orders WHERE userId = :uid AND placeKey = :placeKey ORDER BY orderRank ASC")
    suspend fun getForPlace(uid: String, placeKey: String): List<ShoppingPlaceItemOrder>

    @Query("SELECT COUNT(*) FROM shopping_place_item_orders WHERE userId = :uid AND placeKey = :placeKey")
    suspend fun countForPlace(uid: String, placeKey: String): Int

    @Upsert
    suspend fun upsert(order: ShoppingPlaceItemOrder)

    @Query("DELETE FROM shopping_place_item_orders WHERE userId = :uid AND placeKey = :placeKey AND normalizedItemText = :norm")
    suspend fun deleteEntry(uid: String, placeKey: String, norm: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE (assignedToUserId = :uid OR createdByUserId = :uid) AND archived = 0 ORDER BY createdAt DESC")
    fun observeAll(uid: String): Flow<List<Task>>

    @Query("""SELECT t.* FROM tasks t
        INNER JOIN task_shares s ON s.taskId = t.id
        WHERE s.sharedWithUserId = :uid AND s.status = 'ACTIVE' AND t.archived = 0
        ORDER BY t.createdAt DESC""")
    fun observeSharedWithMe(uid: String): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE (assignedToUserId = :uid OR createdByUserId = :uid) AND archived = 1 ORDER BY archivedAt DESC")
    fun observeArchived(uid: String): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): Task?

    @Query("SELECT * FROM tasks WHERE status = 'ACTIVE' AND assignedToUserId = :uid")
    suspend fun getActiveTasks(uid: String): List<Task>

    @Query("SELECT * FROM tasks WHERE assignedToUserId = :uid AND taskType = 'SHOPPING_LIST' AND archived = 0")
    suspend fun getAssignedShoppingTasks(uid: String): List<Task>

    @Query("SELECT * FROM tasks WHERE createdByUserId = :uid AND taskType = 'SHOPPING_LIST' AND archived = 0")
    suspend fun getOwnedShoppingTasks(uid: String): List<Task>

    @Query("SELECT * FROM tasks WHERE pendingSync = 1")
    suspend fun getPendingSync(): List<Task>

    @Upsert
    suspend fun upsert(task: Task)

    @Query("UPDATE tasks SET status = :status, updatedAt = :now, pendingSync = 1 WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE tasks SET arrivalShareAllowed = :allowed, updatedAt = :now WHERE id = :id")
    suspend fun updateArrivalShare(id: String, allowed: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE tasks SET lastReminderShownAt = :at, pendingSync = 0 WHERE id = :id")
    suspend fun touchReminderShown(id: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE tasks SET pendingSync = 0 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE tasks SET checklistJson = :json, updatedAt = :now WHERE id = :id")
    suspend fun updateChecklist(id: String, json: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE tasks SET archived = 1, archivedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun archiveTask(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE tasks SET archived = 0, archivedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun unarchiveTask(id: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM tasks WHERE (assignedToUserId = :uid OR createdByUserId = :uid) AND archived = 0 AND status IN ('CANCELLED','DONE','REJECTED','EXPIRED') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestCancelledOrDone(uid: String): Task?

    @Query("SELECT * FROM tasks WHERE (assignedToUserId = :uid OR createdByUserId = :uid) AND archived = 1 ORDER BY archivedAt DESC LIMIT 1")
    suspend fun getLatestArchivedTask(uid: String): Task?

    @Query("SELECT id FROM tasks WHERE (assignedToUserId = :uid OR createdByUserId = :uid) AND archived = 1")
    suspend fun getArchivedIds(uid: String): List<String>

    @Query("DELETE FROM tasks WHERE (assignedToUserId = :uid OR createdByUserId = :uid) AND archived = 1")
    suspend fun clearArchivedTasks(uid: String)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM tasks WHERE placeId = :placeId AND status = 'ACTIVE'")
    suspend fun getActiveTasksForPlace(placeId: String): List<Task>
}

@Dao
interface SavedPlaceDao {
    @Query("SELECT * FROM saved_places WHERE userId = :uid AND deleted = 0 ORDER BY name ASC")
    fun observeAll(uid: String): Flow<List<SavedPlace>>

    @Query("SELECT * FROM saved_places WHERE userId = :uid AND deleted = 0 ORDER BY name ASC")
    suspend fun getAll(uid: String): List<SavedPlace>

    @Query("SELECT * FROM saved_places WHERE id = :id")
    suspend fun getById(id: String): SavedPlace?

    @Upsert
    suspend fun upsert(place: SavedPlace)

    @Query("UPDATE saved_places SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long = System.currentTimeMillis())
}

@Dao
interface TaskEventDao {
    @Query("SELECT * FROM task_events WHERE taskId = :taskId ORDER BY createdAt ASC")
    fun observeForTask(taskId: String): Flow<List<TaskEvent>>

    @Query("SELECT * FROM task_events WHERE synced = 0 ORDER BY createdAt ASC")
    suspend fun getUnsynced(): List<TaskEvent>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: TaskEvent)

    @Query("UPDATE task_events SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM task_events WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)
}

@Dao
interface TrustedContactDao {
    @Query("SELECT * FROM trusted_contacts WHERE userId = :uid AND contactUserId != :uid ORDER BY createdAt DESC")
    fun observeAll(uid: String): Flow<List<TrustedContact>>

    @Query("SELECT * FROM trusted_contacts WHERE userId = :uid AND contactUserId != :uid")
    suspend fun getAllForUser(uid: String): List<TrustedContact>

    @Query("SELECT * FROM trusted_contacts WHERE userId = :uid AND status = 'ACCEPTED' AND contactUserId != :uid")
    suspend fun getAccepted(uid: String): List<TrustedContact>

    @Query("SELECT * FROM trusted_contacts WHERE userId = :uid AND contactUserId = :contactId LIMIT 1")
    suspend fun findContact(uid: String, contactId: String): TrustedContact?

    @Upsert
    suspend fun upsert(contact: TrustedContact)

    @Query("UPDATE trusted_contacts SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM trusted_contacts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM trusted_contacts WHERE userId = :uid AND contactUserId = :contactUserId")
    suspend fun deleteByContactUserId(uid: String, contactUserId: String)

    @Query("DELETE FROM trusted_contacts WHERE userId = :uid AND contactUserId = :uid")
    suspend fun deleteSelfContacts(uid: String)

    @Query("DELETE FROM trusted_contacts WHERE userId = :uid AND contactEmail != '' AND lower(contactEmail) = lower(:email)")
    suspend fun deleteSelfContactsByEmail(uid: String, email: String)
}

@Dao
interface TaskLearningProfileDao {
    @Query("SELECT * FROM task_learning_profiles WHERE userId = :uid AND placeKey = :placeKey AND keyword = :keyword LIMIT 1")
    suspend fun get(uid: String, placeKey: String, keyword: String): TaskLearningProfile?

    @Query("SELECT * FROM task_learning_profiles WHERE userId = :uid AND placeKey = :placeKey")
    suspend fun getForPlace(uid: String, placeKey: String): List<TaskLearningProfile>

    @Upsert
    suspend fun upsert(profile: TaskLearningProfile)

    @Query("DELETE FROM task_learning_profiles WHERE userId = :uid")
    suspend fun clearAll(uid: String)
}

@Dao
interface ContactDisplayPrefDao {
    @Query("SELECT * FROM contact_display_prefs WHERE ownerUserId = :ownerUid")
    fun observeAll(ownerUid: String): Flow<List<ContactDisplayPref>>

    @Query("SELECT * FROM contact_display_prefs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ContactDisplayPref?

    @Upsert
    suspend fun upsert(pref: ContactDisplayPref)

    @Query("DELETE FROM contact_display_prefs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PlaceTypeUsageDao {
    @Query("SELECT * FROM place_type_usage WHERE userId = :uid ORDER BY lastUsedAt DESC, useCount DESC")
    fun observeAll(uid: String): Flow<List<PlaceTypeUsage>>

    @Query("SELECT * FROM place_type_usage WHERE userId = :uid AND placeTypeId = :typeId LIMIT 1")
    suspend fun get(uid: String, typeId: String): PlaceTypeUsage?

    @Upsert
    suspend fun upsert(usage: PlaceTypeUsage)
}

@Dao
interface UserTaskSuggestionDao {
    // ── Task-title suggestions (existing) ────────────────────────────────────
    @Query("""
        SELECT * FROM user_task_suggestions
        WHERE userId = :uid AND placeKey = :placeKey AND category = 'task'
        ORDER BY acceptedCount DESC, useCount DESC, lastUsedAt DESC
    """)
    suspend fun getForPlaceKey(uid: String, placeKey: String): List<UserTaskSuggestion>

    @Query("""
        SELECT * FROM user_task_suggestions
        WHERE userId = :uid AND placeTypeId = :typeId AND placeKey = '' AND category = 'task'
        ORDER BY acceptedCount DESC, useCount DESC, lastUsedAt DESC
    """)
    suspend fun getForPlaceType(uid: String, typeId: String): List<UserTaskSuggestion>

    @Query("""
        SELECT * FROM user_task_suggestions
        WHERE userId = :uid AND placeKey = :placeKey AND normalizedText = :norm AND category = 'task'
        LIMIT 1
    """)
    suspend fun getByPlaceKeyAndText(uid: String, placeKey: String, norm: String): UserTaskSuggestion?

    @Query("""
        SELECT * FROM user_task_suggestions
        WHERE userId = :uid AND placeTypeId = :typeId AND placeKey = '' AND normalizedText = :norm AND category = 'task'
        LIMIT 1
    """)
    suspend fun getByPlaceTypeAndText(uid: String, typeId: String, norm: String): UserTaskSuggestion?

    // ── Shopping-item history ─────────────────────────────────────────────────
    /** Items used at this exact place, most frequent first. */
    @Query("""
        SELECT * FROM user_task_suggestions
        WHERE userId = :uid AND placeKey = :placeKey AND category = 'shopping'
        ORDER BY useCount DESC, acceptedCount DESC, lastUsedAt DESC
        LIMIT :limit
    """)
    suspend fun getShoppingForPlace(uid: String, placeKey: String, limit: Int = 40): List<UserTaskSuggestion>

    /** Items used for this place type (no specific place), most frequent first. */
    @Query("""
        SELECT * FROM user_task_suggestions
        WHERE userId = :uid AND placeTypeId = :typeId AND placeKey = '' AND category = 'shopping'
        ORDER BY useCount DESC, acceptedCount DESC, lastUsedAt DESC
        LIMIT :limit
    """)
    suspend fun getShoppingForType(uid: String, typeId: String, limit: Int = 40): List<UserTaskSuggestion>

    /** All shopping items for user, most frequent then most recent. */
    @Query("""
        SELECT * FROM user_task_suggestions
        WHERE userId = :uid AND category = 'shopping'
        ORDER BY useCount DESC, lastUsedAt DESC
        LIMIT :limit
    """)
    suspend fun getShoppingFrequent(uid: String, limit: Int = 30): List<UserTaskSuggestion>

    @Query("""
        SELECT * FROM user_task_suggestions
        WHERE userId = :uid AND placeKey = :placeKey AND normalizedText = :norm AND category = 'shopping'
        LIMIT 1
    """)
    suspend fun getShoppingByPlaceAndText(uid: String, placeKey: String, norm: String): UserTaskSuggestion?

    @Query("""
        SELECT * FROM user_task_suggestions
        WHERE userId = :uid AND placeTypeId = :typeId AND placeKey = '' AND normalizedText = :norm AND category = 'shopping'
        LIMIT 1
    """)
    suspend fun getShoppingByTypeAndText(uid: String, typeId: String, norm: String): UserTaskSuggestion?

    @Query("""
        SELECT * FROM user_task_suggestions
        WHERE userId = :uid AND placeKey = '' AND (placeTypeId IS NULL OR placeTypeId = '') AND normalizedText = :norm AND category = 'shopping'
        LIMIT 1
    """)
    suspend fun getShoppingGeneralByText(uid: String, norm: String): UserTaskSuggestion?

    @Query("SELECT COUNT(*) FROM user_task_suggestions WHERE userId = :uid AND category = 'shopping'")
    suspend fun countShopping(uid: String): Int

    /** Keep only the top :keep most-used/recent shopping items; prune the rest. */
    @Query("""
        DELETE FROM user_task_suggestions
        WHERE userId = :uid AND category = 'shopping'
          AND id NOT IN (
            SELECT id FROM user_task_suggestions
            WHERE userId = :uid AND category = 'shopping'
            ORDER BY useCount DESC, lastUsedAt DESC
            LIMIT :keep
          )
    """)
    suspend fun pruneShopping(uid: String, keep: Int = 300)

    // ── Shared ───────────────────────────────────────────────────────────────
    @Upsert
    suspend fun upsert(suggestion: UserTaskSuggestion)

    @Query("DELETE FROM user_task_suggestions WHERE userId = :uid")
    suspend fun clearAll(uid: String)
}

@Dao
interface UsualShoppingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: com.davoyans.doinplace.data.model.UsualShoppingSession)

    @Query("SELECT * FROM usual_shopping_sessions WHERE userId = :uid AND placeTypeKey = :key ORDER BY completedAt DESC")
    suspend fun getSessionsForPlaceType(uid: String, key: String): List<com.davoyans.doinplace.data.model.UsualShoppingSession>

    @Upsert
    suspend fun upsertItemStats(stats: com.davoyans.doinplace.data.model.UsualShoppingItemStats)

    @Query("SELECT * FROM usual_shopping_item_stats WHERE userId = :uid AND placeTypeKey = :key ORDER BY buyCount DESC")
    suspend fun getItemStats(uid: String, key: String): List<com.davoyans.doinplace.data.model.UsualShoppingItemStats>

    @Query("SELECT * FROM usual_shopping_item_stats WHERE userId = :uid AND placeTypeKey = :key AND normalizedItem = :item LIMIT 1")
    suspend fun getItemStat(uid: String, key: String, item: String): com.davoyans.doinplace.data.model.UsualShoppingItemStats?

    @Upsert
    suspend fun upsertSuppression(suppression: com.davoyans.doinplace.data.model.UsualShoppingSuppression)

    @Query("SELECT * FROM usual_shopping_suppressions WHERE userId = :uid AND placeTypeKey = :key LIMIT 1")
    suspend fun getSuppression(uid: String, key: String): com.davoyans.doinplace.data.model.UsualShoppingSuppression?
}

@Dao
interface TaskPlaceNotificationRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: com.davoyans.doinplace.data.model.TaskPlaceNotificationRule)

    @Query("SELECT * FROM task_place_notification_rules WHERE taskId = :taskId AND exactPlaceKey = :key AND ruleType = :type LIMIT 1")
    suspend fun getRule(taskId: String, key: String, type: String): com.davoyans.doinplace.data.model.TaskPlaceNotificationRule?

    @Query("SELECT * FROM task_place_notification_rules WHERE taskId = :taskId ORDER BY createdAt DESC")
    fun observeForTask(taskId: String): Flow<List<com.davoyans.doinplace.data.model.TaskPlaceNotificationRule>>

    @Query("UPDATE task_place_notification_rules SET active = 0, updatedAt = :now WHERE id = :id")
    suspend fun deactivateRule(id: String, now: Long = System.currentTimeMillis())
}

@Dao
interface FoodHealthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tags: List<com.davoyans.doinplace.data.model.FoodHealthTag>)

    @Query("SELECT * FROM food_health_tags WHERE normalizedName = :name AND language = :lang LIMIT 1")
    suspend fun getTag(name: String, lang: String): com.davoyans.doinplace.data.model.FoodHealthTag?

    @Query("SELECT * FROM food_health_tags WHERE normalizedName = :name LIMIT 1")
    suspend fun getTagAnyLanguage(name: String): com.davoyans.doinplace.data.model.FoodHealthTag?

    @Upsert
    suspend fun upsertOverride(override: com.davoyans.doinplace.data.model.UserFoodHealthOverride)

    @Query("SELECT * FROM user_food_health_overrides WHERE userId = :uid AND normalizedName = :name LIMIT 1")
    suspend fun getOverride(uid: String, name: String): com.davoyans.doinplace.data.model.UserFoodHealthOverride?

    @Query("SELECT COUNT(*) FROM food_health_tags")
    suspend fun getTagCount(): Int
}
