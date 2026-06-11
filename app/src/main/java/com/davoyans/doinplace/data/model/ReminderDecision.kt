package com.davoyans.doinplace.data.model

enum class ReminderDecisionReason {
    NEARBY_EXACT_PLACE,
    NEARBY_PLACE_TYPE,
    DUE_SOON,
    URGENT_DUE_SOON,
    SUPPRESSED_AT_HOME,
    SUPPRESSED_QUIET_HOURS,
    SUPPRESSED_NOT_NEAR_PLACE,
    SUPPRESSED_LOW_SCORE,
    SUPPRESSED_STALE_LOCATION
}

enum class NotificationMode {
    PLACE_REMINDER,
    DUE_REMINDER,
    COMBINED_PLACE_AND_DUE_REMINDER
}

data class ReminderDecision(
    val shouldNotify: Boolean,
    val reason: ReminderDecisionReason,
    val score: Int,
    val suppressReason: String? = null,
    val matchedPlaceName: String? = null,
    val matchedPlaceAddress: String? = null,
    val matchedPlaceDistanceMeters: Float? = null,
    val notificationMode: NotificationMode = NotificationMode.PLACE_REMINDER
)
