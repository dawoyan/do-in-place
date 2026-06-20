package com.davoyans.doinplace.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.TaskPlaceNotificationRule
import com.davoyans.doinplace.data.model.TaskStatus
import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.davoyans.doinplace.util.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(NotificationHelper.EXTRA_TASK_ID) ?: return
        val db = AppDatabase.get(context)
        val scope = CoroutineScope(Dispatchers.IO)

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
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val snoozeIntent = Intent(context, SnoozeAlarmReceiver::class.java)
                    .putExtra(NotificationHelper.EXTRA_TASK_ID, taskId)
                val pending = PendingIntent.getBroadcast(context, taskId.hashCode(),
                    snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 30 * 60 * 1000L, pending)
            }
            NotificationHelper.ACTION_SNOOZE_HERE -> {
                NotificationHelper.cancel(context, taskId)
                val exactPlaceKey = intent.getStringExtra(NotificationHelper.EXTRA_EXACT_PLACE_KEY) ?: return
                val placeName = intent.getStringExtra(NotificationHelper.EXTRA_PLACE_NAME) ?: ""
                val endOfToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.timeInMillis
                val uid = SupabaseAuthClient(context).getCurrentUserId() ?: ""
                scope.launch {
                    db.taskPlaceNotificationRuleDao().upsertRule(
                        TaskPlaceNotificationRule(
                            id = UUID.randomUUID().toString(),
                            taskId = taskId,
                            exactPlaceKey = exactPlaceKey,
                            placeName = placeName,
                            ruleType = "SNOOZE_HERE",
                            snoozedUntil = endOfToday,
                            active = true,
                            createdByUserId = uid
                        )
                    )
                    DiagLog.d("PLACE_NOTIFY", "SNOOZE_HERE saved taskId=${taskId.take(8)} until=$endOfToday")
                    db.reminderOutcomeDao().markOpened(taskId)
                }
            }
            NotificationHelper.ACTION_MUTE_HERE -> {
                NotificationHelper.cancel(context, taskId)
                val exactPlaceKey = intent.getStringExtra(NotificationHelper.EXTRA_EXACT_PLACE_KEY) ?: return
                val placeName = intent.getStringExtra(NotificationHelper.EXTRA_PLACE_NAME) ?: ""
                val uid = SupabaseAuthClient(context).getCurrentUserId() ?: ""
                scope.launch {
                    db.taskPlaceNotificationRuleDao().upsertRule(
                        TaskPlaceNotificationRule(
                            id = UUID.randomUUID().toString(),
                            taskId = taskId,
                            exactPlaceKey = exactPlaceKey,
                            placeName = placeName,
                            ruleType = "MUTE_HERE",
                            active = true,
                            createdByUserId = uid
                        )
                    )
                    DiagLog.d("PLACE_NOTIFY", "MUTE_HERE saved taskId=${taskId.take(8)} key=$exactPlaceKey")
                    db.reminderOutcomeDao().markOpened(taskId)
                }
            }
        }
    }
}
