package com.davoyans.doinplace.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.TaskPriority
import com.davoyans.doinplace.data.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SnoozeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(NotificationHelper.EXTRA_TASK_ID) ?: return
        val isRepeat = intent.getBooleanExtra("is_repeat", false)
        CoroutineScope(Dispatchers.IO).launch {
            val task = AppDatabase.get(context).taskDao().getById(taskId) ?: return@launch
            if (task.status != TaskStatus.ACTIVE) return@launch

            NotificationHelper.showPlaceReminderNotification(
                context = context,
                taskId = task.id,
                taskTitle = task.title,
                exactPlaceName = task.placeName,
                exactPlaceAddress = task.address,
                savedPlaceName = task.placeName,
                priority = task.priority
            )

            if (isRepeat) {
                scheduleNextRepeat(context, task.id, task.priority)
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "reminder_repeat_counts"

        fun scheduleFirstRepeat(context: Context, taskId: String, priority: TaskPriority) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val dayKey = "day_$taskId"
            val countKey = "count_$taskId"
            // Reset count on new day
            if (prefs.getString(dayKey, "") != today) {
                prefs.edit().putString(dayKey, today).putInt(countKey, 0).apply()
            }
            prefs.edit().putInt(countKey, 1).apply()  // we just showed the first one
            scheduleNextRepeat(context, taskId, priority)
        }

        fun scheduleNextRepeat(context: Context, taskId: String, priority: TaskPriority) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val countKey = "count_$taskId"
            val currentCount = prefs.getInt(countKey, 0)

            val (delayMs, maxCount) = when (priority) {
                TaskPriority.URGENT -> 5L * 60 * 1000 to 8
                TaskPriority.NO_RUSH   -> 15L * 60 * 1000 to 3
            }

            if (currentCount >= maxCount) return  // reached max reminders

            prefs.edit().putInt(countKey, currentCount + 1).apply()

            val i = Intent(context, SnoozeAlarmReceiver::class.java).apply {
                putExtra(NotificationHelper.EXTRA_TASK_ID, taskId)
                putExtra("is_repeat", true)
            }
            val pending = PendingIntent.getBroadcast(
                context, "repeat_$taskId".hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pending)
        }

        fun cancelRepeat(context: Context, taskId: String) {
            val i = Intent(context, SnoozeAlarmReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, "repeat_$taskId".hashCode(), i,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pending != null) {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove("count_$taskId").remove("day_$taskId").apply()
        }
    }
}
