package com.davoyans.doinplace.engine

import android.content.SharedPreferences

class QuietHoursPolicy(private val prefs: SharedPreferences) {
    companion object {
        const val KEY_QUIET_START = "smart_quiet_start_hour"
        const val KEY_QUIET_END   = "smart_quiet_end_hour"
        private const val DEFAULT_QUIET_START = 22
        private const val DEFAULT_QUIET_END   = 8
        private const val DAYTIME_START = 8
        private const val DAYTIME_END   = 21
    }

    val quietStartHour: Int get() = prefs.getInt(KEY_QUIET_START, DEFAULT_QUIET_START)
    val quietEndHour: Int   get() = prefs.getInt(KEY_QUIET_END,   DEFAULT_QUIET_END)

    // 22:00–08:00 wraps midnight; start > end always true in default config
    fun isQuietHours(hour: Int): Boolean {
        val s = quietStartHour; val e = quietEndHour
        return if (s > e) hour >= s || hour < e else hour in s until e
    }

    fun isNight(hour: Int): Boolean = hour >= DAYTIME_END || hour < DAYTIME_START

    fun isDaytime(hour: Int): Boolean = hour in DAYTIME_START until DAYTIME_END
}
