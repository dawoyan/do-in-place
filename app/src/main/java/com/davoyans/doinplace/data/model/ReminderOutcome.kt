package com.davoyans.doinplace.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminder_outcomes")
data class ReminderOutcome(
    @PrimaryKey val id: String,
    val userId: String,
    val taskId: String,
    val taskType: String,
    val placeTypeId: String?,
    val placeId: String?,
    val activityType: String,
    val isAtHome: Boolean,
    val isNight: Boolean,
    val isDriving: Boolean,
    val priority: String,
    val dueUrgency: String,
    val decision: String,
    val wasShown: Boolean,
    val wasOpened: Boolean = false,
    val wasDismissed: Boolean = false,
    val wasCompletedAfterNotification: Boolean = false,
    val wasAutoDismissedAfterLeaving: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
