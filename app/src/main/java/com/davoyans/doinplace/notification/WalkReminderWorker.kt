package com.davoyans.doinplace.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.davoyans.doinplace.MainActivity
import com.davoyans.doinplace.engine.ActivityContextProvider
import java.util.Calendar
import java.util.concurrent.TimeUnit

class WalkReminderWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("dip_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_WALK_REMINDER, false)) return Result.success()

        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < 8 || hour >= 22) return Result.success()   // quiet hours

        val todayKey = "walk_reminder_day_${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(now)}"
        val countToday = prefs.getInt(todayKey, 0)
        if (countToday >= 3) return Result.success()           // max 3/day

        val lastAt = prefs.getLong(PREF_LAST_REMINDER_AT, 0L)
        if (now - lastAt < COOLDOWN_MS) return Result.success() // cooldown

        val actPrefs = applicationContext.getSharedPreferences(
            ActivityContextProvider.PREFS_ACTIVITY, Context.MODE_PRIVATE
        )
        val actType = actPrefs.getString(ActivityContextProvider.KEY_ACTIVITY_TYPE, null)
        val actTime = actPrefs.getLong(ActivityContextProvider.KEY_ACTIVITY_TIME, 0L)

        if (actType != "STILL") {
            prefs.edit().putLong(PREF_STILL_SINCE, 0L).apply()
            return Result.success()
        }

        var stillSince = prefs.getLong(PREF_STILL_SINCE, 0L)
        if (stillSince == 0L) {
            stillSince = actTime.takeIf { it > 0L } ?: now
            prefs.edit().putLong(PREF_STILL_SINCE, stillSince).apply()
        }
        if (now - stillSince < STILL_THRESHOLD_MS) return Result.success()

        showNotification()
        prefs.edit()
            .putLong(PREF_LAST_REMINDER_AT, now)
            .putLong(PREF_STILL_SINCE, 0L)
            .putInt(todayKey, countToday + 1)
            .apply()
        return Result.success()
    }

    private fun showNotification() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val openIntent = Intent(applicationContext, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPending = PendingIntent.getActivity(
            applicationContext, NOTIF_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(applicationContext, WalkReminderActionReceiver::class.java)
            .setAction(ACTION_MUTE_TODAY)
        val mutePending = PendingIntent.getBroadcast(
            applicationContext, 1, muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeIntent = Intent(applicationContext, WalkReminderActionReceiver::class.java)
            .setAction(ACTION_REMIND_LATER)
        val snoozePending = PendingIntent.getBroadcast(
            applicationContext, 2, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_WALK)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Healthy life")
            .setContentText("Maybe stand or walk for a few minutes?")
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_send, "Done", openPending)
            .addAction(android.R.drawable.ic_menu_recent_history, "Remind later", snoozePending)
            .addAction(android.R.drawable.ic_delete, "Mute today", mutePending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_WALK) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WALK, "Healthy Life Reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Gentle walk and stand reminders" }
        )
    }

    companion object {
        const val CHANNEL_WALK         = "walk_reminder"
        const val PREF_WALK_REMINDER   = "walk_reminder_enabled"
        const val PREF_STILL_SINCE     = "walk_still_since_ms"
        const val PREF_LAST_REMINDER_AT = "walk_last_reminder_at"
        const val ACTION_MUTE_TODAY    = "com.davoyans.doinplace.WALK_MUTE_TODAY"
        const val ACTION_REMIND_LATER  = "com.davoyans.doinplace.WALK_REMIND_LATER"
        private const val NOTIF_ID        = 77001
        private const val STILL_THRESHOLD_MS = 60 * 60 * 1000L   // 60 min
        private const val COOLDOWN_MS        = 90 * 60 * 1000L   // 90 min

        private const val WORK_NAME = "walk_reminder_worker"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WalkReminderWorker>(15, TimeUnit.MINUTES).build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun muteToday(context: Context) {
            val prefs = context.getSharedPreferences("dip_prefs", Context.MODE_PRIVATE)
            val key = "walk_reminder_day_${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(System.currentTimeMillis())}"
            prefs.edit().putInt(key, 99).apply()
        }

        fun remindLater(context: Context) {
            context.getSharedPreferences("dip_prefs", Context.MODE_PRIVATE)
                .edit().putLong(PREF_LAST_REMINDER_AT, System.currentTimeMillis()).apply()
        }
    }
}
