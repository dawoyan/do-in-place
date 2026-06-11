package com.davoyans.doinplace.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.davoyans.doinplace.MainActivity
import com.davoyans.doinplace.data.model.TaskPriority

object NotificationHelper {
    const val CHANNEL_REMINDERS  = "reminders"
    const val CHANNEL_TASKS      = "tasks"
    const val CHANNEL_DUE_ALARMS = "due_alarms"

    const val ACTION_DONE = "com.davoyans.doinplace.ACTION_DONE"
    const val ACTION_SNOOZE = "com.davoyans.doinplace.ACTION_SNOOZE"
    const val ACTION_CANCEL = "com.davoyans.doinplace.ACTION_CANCEL"
    const val EXTRA_TASK_ID = "task_id"

    // Stable notification IDs (place and due are separate so both can coexist)
    fun placeNotifId(taskId: String) = taskId.hashCode()
    fun dueNotifId(taskId: String)   = "due_$taskId".hashCode()

    private const val PREFS_ACTIVE = "active_place_notifs"

    // Called when a place reminder notification is posted
    fun markPlaceNotifActive(context: Context, taskId: String) {
        context.getSharedPreferences(PREFS_ACTIVE, Context.MODE_PRIVATE)
            .edit().putLong(taskId, System.currentTimeMillis()).apply()
    }

    // Called when the user acts on the notification (action button or cancel)
    fun clearPlaceNotifActive(context: Context, taskId: String) {
        context.getSharedPreferences(PREFS_ACTIVE, Context.MODE_PRIVATE)
            .edit().remove(taskId).apply()
    }

    // Returns true if a place reminder is still outstanding (not actioned, not auto-dismissed)
    fun isPlaceNotifActive(context: Context, taskId: String): Boolean =
        context.getSharedPreferences(PREFS_ACTIVE, Context.MODE_PRIVATE).contains(taskId)

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val vibPat = longArrayOf(0, 300, 100, 300)
        // Delete existing channels so updated vibration settings take effect on upgrade.
        listOf(CHANNEL_REMINDERS, CHANNEL_TASKS, CHANNEL_DUE_ALARMS).forEach {
            nm.deleteNotificationChannel(it)
        }
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_REMINDERS, "Place Reminders", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications when you arrive near a task place"
            setSound(defaultSound, audioAttrs)
            enableVibration(true)
            vibrationPattern = vibPat
        })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_TASKS, "Task Updates", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shared task status and invitations"
            setSound(defaultSound, audioAttrs)
            enableVibration(true)
            vibrationPattern = vibPat
        })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_DUE_ALARMS, "Due Date Reminders", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts before and after a task's scheduled due time"
            setSound(defaultSound, audioAttrs)
            enableVibration(true)
            vibrationPattern = vibPat
        })
    }

    fun showPlaceReminderNotification(
        context: Context,
        taskId: String,
        taskTitle: String,
        placeName: String,
        priority: TaskPriority = TaskPriority.NO_RUSH,
        placeLat: Double? = null,
        placeLng: Double? = null
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun pendingAction(action: String): PendingIntent {
            val i = Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, taskId)
            return PendingIntent.getBroadcast(context, taskId.hashCode() + action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val openIntent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TASK_ID, taskId)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPending = PendingIntent.getActivity(context, taskId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val titlePrefix = when (priority) {
            TaskPriority.URGENT -> "Urgent: "
            TaskPriority.NO_RUSH   -> "Reminder: "
        }
        val notifPriority = when (priority) {
            TaskPriority.URGENT -> NotificationCompat.PRIORITY_MAX
            TaskPriority.NO_RUSH   -> NotificationCompat.PRIORITY_HIGH
        }

        val bodyText = "You're near $placeName"

        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("$titlePrefix$taskTitle")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(openPending)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_send, "Done", pendingAction(ACTION_DONE))
            .addAction(android.R.drawable.ic_menu_recent_history, "Snooze", pendingAction(ACTION_SNOOZE))
            .addAction(android.R.drawable.ic_delete, "Cancel task", pendingAction(ACTION_CANCEL))
            .setPriority(notifPriority)
            .setVibrate(longArrayOf(0, 300, 100, 300))

        if (placeLat != null && placeLng != null) {
            val mapsUri = android.net.Uri.parse("geo:$placeLat,$placeLng?q=$placeLat,$placeLng")
            val mapsIntent = Intent(Intent.ACTION_VIEW, mapsUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val mapsPending = PendingIntent.getActivity(
                context, (taskId + "_maps").hashCode(), mapsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_dialog_map, "Navigate", mapsPending)
        }

        markPlaceNotifActive(context, taskId)
        nm.notify(placeNotifId(taskId), builder.build())
    }

    fun showTaskInviteNotification(context: Context, taskId: String, fromName: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TASK_ID, taskId)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, "invite_$taskId".hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("New Assignment from $fromName")
            .setContentText("Tap to view and accept the reminder")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify("invite_$taskId".hashCode(), notification)
    }

    fun showTaskUpdateNotification(context: Context, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Task update")
            .setContentText(message)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    fun cancel(context: Context, taskId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(placeNotifId(taskId))
        nm.cancel(dueNotifId(taskId))
        clearPlaceNotifActive(context, taskId)
    }

    /** Dismiss only the place reminder notification (used by auto-dismiss on geofence EXIT). */
    fun cancelPlaceNotif(context: Context, taskId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(placeNotifId(taskId))
        clearPlaceNotifActive(context, taskId)
    }

    /**
     * Notification fired by DueAlarmReceiver at scheduled due-date intervals.
     * All alarms for the same task share a single notification ID so each new alarm
     * updates the existing notification rather than stacking new ones.
     *
     * Notification text includes:
     *   - EXACT tasks: task.placeName and task.address
     *   - TYPE tasks: stored type name (live nearby search not feasible in a BroadcastReceiver)
     */
    fun showDueAlarmNotification(
        context: Context,
        taskId: String,
        taskTitle: String,
        minOffset: Int,
        placeText: String?,
        priority: TaskPriority = TaskPriority.NO_RUSH
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun pendingAction(action: String): PendingIntent {
            val i = Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, taskId)
            return PendingIntent.getBroadcast(
                context, taskId.hashCode() + action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val openIntent = Intent(context, com.davoyans.doinplace.MainActivity::class.java)
            .putExtra(EXTRA_TASK_ID, taskId)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPending = PendingIntent.getActivity(
            context, "due_open_$taskId".hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeDesc = when {
            minOffset == -120 -> "in 2 hours"
            minOffset == -60  -> "in 1 hour"
            minOffset <  0    -> "in ${-minOffset} minutes"
            minOffset == 0    -> "now"
            minOffset <= 60   -> "$minOffset minutes ago"
            else -> {
                val h = minOffset / 60
                val m = minOffset % 60
                if (m == 0) "${h}h ago" else "${h}h ${m}m ago"
            }
        }
        val dueWord = if (minOffset > 0) "was due" else "is due"
        val bodyText = buildString {
            append("$taskTitle $dueWord $timeDesc")
            if (!placeText.isNullOrBlank()) append(" — $placeText")
        }

        val notifPriority = when (priority) {
            TaskPriority.URGENT  -> NotificationCompat.PRIORITY_MAX
            TaskPriority.NO_RUSH -> NotificationCompat.PRIORITY_HIGH
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_DUE_ALARMS)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(taskTitle)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(openPending)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_send, "Done", pendingAction(ACTION_DONE))
            .setPriority(notifPriority)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .build()

        nm.notify(dueNotifId(taskId), notification)
    }
}
