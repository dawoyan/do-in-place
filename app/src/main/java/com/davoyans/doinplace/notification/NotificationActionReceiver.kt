package com.davoyans.doinplace.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(NotificationHelper.EXTRA_TASK_ID) ?: return
        val db = AppDatabase.get(context)
        val scope = CoroutineScope(Dispatchers.IO)

        // Any user action means the notification was seen — clear the active flag
        NotificationHelper.clearPlaceNotifActive(context, taskId)

        when (intent.action) {
            NotificationHelper.ACTION_DONE -> {
                NotificationHelper.cancel(context, taskId)
                scope.launch {
                    db.taskDao().updateStatus(taskId, TaskStatus.DONE.name)
                    db.reminderOutcomeDao().markCompleted(taskId)
                }
            }
            NotificationHelper.ACTION_CANCEL -> {
                NotificationHelper.cancel(context, taskId)
                scope.launch {
                    db.taskDao().updateStatus(taskId, TaskStatus.CANCELLED.name)
                    db.reminderOutcomeDao().markOpened(taskId)
                }
            }
            NotificationHelper.ACTION_SNOOZE -> {
                NotificationHelper.cancel(context, taskId)
                scope.launch { db.reminderOutcomeDao().markOpened(taskId) }
                // Reschedule reminder after 30 minutes
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val snoozeIntent = Intent(context, SnoozeAlarmReceiver::class.java)
                    .putExtra(NotificationHelper.EXTRA_TASK_ID, taskId)
                val pending = PendingIntent.getBroadcast(context, taskId.hashCode(),
                    snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 30 * 60 * 1000L, pending)
            }
        }
    }
}
