package com.davoyans.doinplace.engine

import android.content.Context
import android.location.Location
import com.davoyans.doinplace.data.model.UserActivityType

/**
 * Returns the user's current activity type.
 *
 * Primary source: last recognized activity stored by ActivityRecognitionWorker (if set up).
 * Fallback: speed-based inference from the last known location.
 */
class ActivityContextProvider(private val context: Context) {
    companion object {
        const val PREFS_ACTIVITY        = "smart_reminder_activity"
        const val KEY_ACTIVITY_TYPE     = "activity_type"
        const val KEY_ACTIVITY_CONF     = "activity_confidence"
        const val KEY_ACTIVITY_TIME     = "activity_time_ms"
        private const val MAX_AGE_MS    = 10 * 60_000L  // recognized activity valid for 10 min
        private const val SPEED_DRIVING = 8f            // m/s ≈ 29 km/h
        private const val SPEED_MOVING  = 1f            // m/s
    }

    fun getActivityType(location: Location?): Pair<UserActivityType, Int> {
        val p   = context.getSharedPreferences(PREFS_ACTIVITY, Context.MODE_PRIVATE)
        val age = System.currentTimeMillis() - p.getLong(KEY_ACTIVITY_TIME, 0L)
        if (age < MAX_AGE_MS) {
            val name = p.getString(KEY_ACTIVITY_TYPE, null)
            val conf = p.getInt(KEY_ACTIVITY_CONF, 0)
            if (name != null) {
                val type = runCatching { UserActivityType.valueOf(name) }.getOrNull()
                if (type != null) return type to conf
            }
        }
        return inferFromSpeed(location)
    }

    fun storeRecognizedActivity(type: UserActivityType, confidence: Int) {
        context.getSharedPreferences(PREFS_ACTIVITY, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVITY_TYPE, type.name)
            .putInt(KEY_ACTIVITY_CONF, confidence)
            .putLong(KEY_ACTIVITY_TIME, System.currentTimeMillis())
            .apply()
    }

    private fun inferFromSpeed(location: Location?): Pair<UserActivityType, Int> {
        val speed = location?.speed ?: 0f
        return when {
            speed >= SPEED_DRIVING -> UserActivityType.IN_VEHICLE to 70
            speed >= SPEED_MOVING  -> UserActivityType.ON_FOOT    to 60
            else                   -> UserActivityType.STILL      to 50
        }
    }
}
