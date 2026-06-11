package com.davoyans.doinplace.data.model

enum class UserActivityType {
    UNKNOWN, STILL, WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE, ON_FOOT
}

// Ordinal order matters: higher = more urgent (used for >= comparisons)
enum class DueUrgency {
    NO_DUE_DATE, DUE_LATER, DUE_WITHIN_2_HOURS, DUE_WITHIN_1_HOUR, DUE_WITHIN_15_MINUTES, OVERDUE
}

data class ContextSnapshot(
    val nowMillis: Long,
    val localHour: Int,
    val isDaytime: Boolean,
    val isNight: Boolean,
    val isQuietHours: Boolean,
    val currentLocation: android.location.Location?,
    val locationAgeMs: Long?,
    val locationAccuracyMeters: Float?,
    val isAtHome: Boolean,
    val activityType: UserActivityType,
    val activityConfidence: Int,
    val isMoving: Boolean,
    val isDriving: Boolean
)
