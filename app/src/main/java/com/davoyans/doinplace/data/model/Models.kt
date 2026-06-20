package com.davoyans.doinplace.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.davoyans.doinplace.data.db.Converters

enum class TaskStatus { PENDING_ACCEPTANCE, ACTIVE, DONE, CANCELLED, REJECTED, EXPIRED }
enum class ContactStatus { PENDING, ACCEPTED, BLOCKED, PENDING_SENT }
enum class PlaceType { EXACT, MALL, DISTRICT, CUSTOM }
enum class PlaceMode { EXACT, TYPE }
enum class TaskPriority { URGENT, NO_RUSH }
enum class TaskType { SIMPLE, SHOPPING_LIST }
enum class RecurrenceType { NONE, MONTHLY, YEARLY }
enum class TaskTriggerMode { PLACE_BASED, PLACE_TYPE_BASED, EVERYWHERE_TIME_BASED }
enum class TaskEventType {
    CREATED, ACCEPTED, REJECTED, ARRIVED_NEAR_PLACE, REMINDED,
    PLACE_REMINDER_AUTO_DISMISSED,
    DUE_REMINDER_SHOWN,
    NOTIFICATION_OPENED,
    DONE, CANCELLED, EXPIRED, FORCE_DONE_INCOMPLETE
}

@Entity(tableName = "saved_places")
data class SavedPlace(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val address: String? = null,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val placeType: PlaceType = PlaceType.CUSTOM,
    val provider: String = "",
    val deleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "saved_cards",
    indices = [
        Index(value = ["name"]),
        Index(value = ["codeValue"])
    ]
)
data class SavedCardEntity(
    @PrimaryKey val id: String,
    val name: String,
    val codeType: String,
    val barcodeFormat: String? = null,
    val codeValue: String,
    val note: String? = null,
    val passwordOrPinEncrypted: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tasks")
@TypeConverters(Converters::class)
data class Task(
    @PrimaryKey val id: String,
    val title: String,
    val description: String? = null,
    val createdByUserId: String,
    val assignedToUserId: String,
    val placeId: String? = null,
    val placeName: String,
    val address: String? = null,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val status: TaskStatus = TaskStatus.ACTIVE,
    val arrivalShareAllowed: Boolean = false,
    val activeFromDate: String? = null,   // ISO date yyyy-MM-dd
    val activeToDate: String? = null,
    val activeDaysOfWeek: String? = null, // comma-separated ints 1-7
    val activeStartTime: String? = null,  // HH:mm
    val activeEndTime: String? = null,
    val remindUntilDone: Boolean = true,
    val lastTriggeredAt: Long? = null,
    val lastReminderShownAt: Long? = null,
    val photoUri: String? = null,
    val checklistJson: String? = null,
    val archived: Boolean = false,
    val archivedAt: Long? = null,
    val priority: TaskPriority = TaskPriority.NO_RUSH,
    val pendingSync: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val placeMode: PlaceMode = PlaceMode.EXACT,
    val placeTypeId: String? = null,
    val placeTypeName: String? = null,
    val taskType: TaskType = TaskType.SIMPLE,
    val isEverywhere: Boolean = false,
    val recurrenceType: RecurrenceType = RecurrenceType.NONE,
    val recurrenceDayOfMonth: Int? = null,
    val recurrenceMonth: Int? = null,
    val lastCompletedAt: Long? = null,
    val calendarEventId: String? = null
) {
    val triggerMode: TaskTriggerMode get() = when {
        isEverywhere -> TaskTriggerMode.EVERYWHERE_TIME_BASED
        placeMode == PlaceMode.TYPE -> TaskTriggerMode.PLACE_TYPE_BASED
        else -> TaskTriggerMode.PLACE_BASED
    }
}

@Entity(tableName = "task_events")
data class TaskEvent(
    @PrimaryKey val id: String,
    val taskId: String,
    val type: TaskEventType,
    val actorUserId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val placeName: String? = null,
    val placeAddress: String? = null,
    val reason: String? = null
)

@Entity(tableName = "task_learning_profiles")
data class TaskLearningProfile(
    @PrimaryKey val id: String,
    val userId: String,
    val placeKey: String,
    val normalizedPlaceName: String,
    val keyword: String,
    val highCount: Int = 0,
    val normalCount: Int = 0,
    val lowCount: Int = 0,
    val completedCount: Int = 0,
    val cancelledCount: Int = 0,
    val lastUsedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "contact_display_prefs")
data class ContactDisplayPref(
    @PrimaryKey val id: String,   // "${ownerUserId}:${contactUserId}"
    val ownerUserId: String,
    val contactUserId: String,
    val nickname: String = "",
    val iconId: String = "person",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "trusted_contacts")
data class TrustedContact(
    @PrimaryKey val id: String,
    val userId: String,
    val contactUserId: String,
    val contactEmail: String,
    val contactDisplayName: String = "",
    val status: ContactStatus = ContactStatus.PENDING,
    val canAssignTasks: Boolean = true,
    val defaultArrivalShare: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "place_type_usage")
data class PlaceTypeUsage(
    @PrimaryKey val id: String,
    val userId: String,
    val placeTypeId: String,
    val useCount: Int = 0,
    val lastUsedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "shopping_list_items")
data class ShoppingListItem(
    @PrimaryKey val id: String,
    val taskId: String,
    val text: String,
    val normalizedText: String,
    val orderIndex: Int,
    val checked: Boolean = false,
    val checkedByUserId: String? = null,
    val checkedByDisplayName: String? = null,
    val checkedAt: Long? = null,
    val updatedByUserId: String? = null,
    val syncStatus: String = "SYNCED",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val addedByUserId: String? = null,
    val addedByDisplayName: String? = null,
    val addedAt: Long? = null,
    val originColorKey: String? = null,
    val deletedAt: Long? = null,
    val deletedByUserId: String? = null
)

@Entity(tableName = "task_shares", indices = [androidx.room.Index(value = ["taskId", "sharedWithUserId"], unique = true)])
data class TaskShare(
    @PrimaryKey val id: String,
    val taskId: String,
    val ownerUserId: String,
    val sharedWithUserId: String,
    val sharedWithDisplayName: String = "",
    val permission: String = "EDIT",
    val status: String = "ACTIVE",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "shopping_place_item_orders")
data class ShoppingPlaceItemOrder(
    @PrimaryKey val id: String,
    val userId: String,
    val placeKey: String,
    val normalizedItemText: String,
    val displayText: String,
    val orderRank: Int,
    val useCount: Int = 1,
    val lastUsedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "task_place_notification_rules",
    indices = [Index(
        value = ["taskId", "exactPlaceKey", "ruleType"],
        unique = true,
        name = "index_task_place_notification_rules_taskId_exactPlaceKey_ruleType"
    )]
)
data class TaskPlaceNotificationRule(
    @PrimaryKey val id: String,
    val taskId: String,
    val exactPlaceKey: String,
    val placeId: String? = null,
    val placeName: String,
    val ruleType: String,          // SNOOZE_HERE | MUTE_HERE
    val snoozedUntil: Long? = null,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val createdByUserId: String? = null
)

@Entity(tableName = "user_task_suggestions")
data class UserTaskSuggestion(
    @PrimaryKey val id: String,
    val userId: String,
    val placeKey: String,
    val placeTypeId: String?,
    val normalizedText: String,
    val displayText: String,
    val useCount: Int = 0,
    val acceptedCount: Int = 0,
    val lastUsedAt: Long = System.currentTimeMillis(),
    /** "task" = task-title suggestions, "shopping" = shopping list item history */
    val category: String = CAT_TASK
) {
    companion object {
        const val CAT_TASK     = "task"
        const val CAT_SHOPPING = "shopping"
    }
}
