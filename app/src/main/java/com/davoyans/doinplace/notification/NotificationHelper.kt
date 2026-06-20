package com.davoyans.doinplace.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.davoyans.doinplace.MainActivity
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.data.model.TaskPriority
import com.davoyans.doinplace.util.DiagLog
import com.davoyans.doinplace.util.MapIntentHelper
import com.davoyans.doinplace.util.PlaceLabelResolver

object NotificationHelper {
    const val CHANNEL_REMINDERS      = "reminders"
    const val CHANNEL_TASKS          = "tasks"
    const val CHANNEL_DUE_ALARMS     = "due_alarms"
    const val CHANNEL_EVERYWHERE     = "everywhere_reminders"
    const val CHANNEL_ASSIGNMENTS    = "task_assignments"
    const val CHANNEL_USUAL_SHOPPING = "usual_shopping"

    const val ACTION_USUAL_CREATE = "com.davoyans.doinplace.USUAL_CREATE"
    const val ACTION_USUAL_NOT_NOW = "com.davoyans.doinplace.USUAL_NOT_NOW"
    const val EXTRA_PLACE_TYPE_KEY = "place_type_key"
    const val EXTRA_PLACE_NAME = "place_name"

    const val ACTION_DONE        = "com.davoyans.doinplace.ACTION_DONE"
    const val ACTION_SNOOZE      = "com.davoyans.doinplace.ACTION_SNOOZE"
    const val ACTION_CANCEL      = "com.davoyans.doinplace.ACTION_CANCEL"
    const val ACTION_SNOOZE_HERE = "com.davoyans.doinplace.ACTION_SNOOZE_HERE"
    const val ACTION_MUTE_HERE   = "com.davoyans.doinplace.ACTION_MUTE_HERE"
    const val EXTRA_TASK_ID      = "task_id"
    const val EXTRA_OPEN_ROUTE   = "open_route"
    const val EXTRA_EXACT_PLACE_KEY = "exact_place_key"
    const val ROUTE_TASK         = "task"
    const val ROUTE_SHOPPING_LIST = "shopping-list"
    const val ROUTE_CONTACTS     = "contacts"

    // Stable notification IDs (place, due, and everywhere are separate so they can coexist)
    fun placeNotifId(taskId: String)      = taskId.hashCode()
    fun dueNotifId(taskId: String)        = "due_$taskId".hashCode()
    fun everywhereNotifId(taskId: String) = "everywhere_$taskId".hashCode()

    private const val PREFS_ACTIVE = "active_place_notifs"

    private fun buildOpenIntent(
        context: Context,
        route: String,
        taskId: String? = null
    ): Intent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData(
            if (!taskId.isNullOrBlank()) {
                Uri.parse("doinplace://$route/$taskId")
            } else {
                Uri.parse("doinplace://$route")
            }
        )
        .putExtra(EXTRA_OPEN_ROUTE, route)
        .apply {
            if (!taskId.isNullOrBlank()) putExtra(EXTRA_TASK_ID, taskId)
        }
        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun buildOpenPendingIntent(
        context: Context,
        requestCode: Int,
        route: String,
        taskId: String? = null
    ): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        buildOpenIntent(context, route, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

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
        listOf(CHANNEL_REMINDERS, CHANNEL_TASKS, CHANNEL_DUE_ALARMS, CHANNEL_EVERYWHERE, CHANNEL_ASSIGNMENTS, CHANNEL_USUAL_SHOPPING).forEach {
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
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_EVERYWHERE, "Time-based Reminders", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Hourly reminders for Everywhere (time-based) tasks on their due date"
            setSound(defaultSound, audioAttrs)
            enableVibration(true)
            vibrationPattern = vibPat
        })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ASSIGNMENTS, "Do In Place Updates", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "New assignments and shared lists from your connections"
            setSound(defaultSound, audioAttrs)
            enableVibration(true)
            vibrationPattern = vibPat
        })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_USUAL_SHOPPING, "Usual Shopping", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Suggestions based on your shopping habits"
            setSound(defaultSound, audioAttrs)
            enableVibration(true)
            vibrationPattern = vibPat
        })
    }

    fun showUsualShoppingNotification(context: Context, placeTypeKey: String, placeName: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = "usual_$placeTypeKey".hashCode()

        val createIntent = Intent(context, UsualShoppingActionReceiver::class.java)
            .setAction(ACTION_USUAL_CREATE)
            .putExtra(EXTRA_PLACE_TYPE_KEY, placeTypeKey)
            .putExtra(EXTRA_PLACE_NAME, placeName)
        val createPending = PendingIntent.getBroadcast(
            context, notifId + 1, createIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notNowIntent = Intent(context, UsualShoppingActionReceiver::class.java)
            .setAction(ACTION_USUAL_NOT_NOW)
            .putExtra(EXTRA_PLACE_TYPE_KEY, placeTypeKey)
        val notNowPending = PendingIntent.getBroadcast(
            context, notifId + 2, notNowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = buildOpenIntent(context, ROUTE_CONTACTS)
            .putExtra("open_usual_shopping", true)
            .putExtra(EXTRA_PLACE_TYPE_KEY, placeTypeKey)
            .putExtra(EXTRA_PLACE_NAME, placeName)
        val openPending = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_USUAL_SHOPPING)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("Usual shopping")
            .setContentText("You usually buy these things from $placeName. Create a list?")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("You usually buy these things from $placeName. Would you like me to make that items list for you?"))
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_add, "Create list", createPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Not now", notNowPending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .build()
        nm.notify(notifId, notif)
    }

    fun showPlaceReminderNotification(
        context: Context,
        taskId: String,
        taskTitle: String,
        exactPlaceName: String? = null,
        exactPlaceAddress: String? = null,
        savedPlaceName: String? = null,
        providerPlaceName: String? = null,
        placeTypeName: String? = null,
        priority: TaskPriority = TaskPriority.NO_RUSH,
        placeLat: Double? = null,
        placeLng: Double? = null,
        exactPlaceKey: String? = null
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val resolvedPlace = PlaceLabelResolver.resolve(
            exactPlaceName = exactPlaceName,
            exactPlaceAddress = exactPlaceAddress,
            savedPlaceName = savedPlaceName,
            providerPlaceName = providerPlaceName,
            placeTypeName = placeTypeName
        )

        fun pendingAction(action: String): PendingIntent {
            val i = Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_PLACE_NAME, resolvedPlace.primaryName)
                .apply { if (exactPlaceKey != null) putExtra(EXTRA_EXACT_PLACE_KEY, exactPlaceKey) }
            return PendingIntent.getBroadcast(context, taskId.hashCode() + action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val openPending = buildOpenPendingIntent(
            context = context,
            requestCode = taskId.hashCode(),
            route = ROUTE_TASK,
            taskId = taskId
        )

        val titlePrefix = when (priority) {
            TaskPriority.URGENT  -> "Urgent: "
            TaskPriority.NO_RUSH -> "Reminder: "
        }
        val notifPriority = when (priority) {
            TaskPriority.URGENT  -> NotificationCompat.PRIORITY_MAX
            TaskPriority.NO_RUSH -> NotificationCompat.PRIORITY_HIGH
        }

        val bodyText = resolvedPlace.notificationLine

        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("$titlePrefix$taskTitle")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(openPending)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_send, "Done", pendingAction(ACTION_DONE))
            .setPriority(notifPriority)
            .setVibrate(longArrayOf(0, 300, 100, 300))

        if (exactPlaceKey != null) {
            builder.addAction(android.R.drawable.ic_menu_recent_history, "Snooze here", pendingAction(ACTION_SNOOZE_HERE))
            builder.addAction(android.R.drawable.ic_delete, "Mute here", pendingAction(ACTION_MUTE_HERE))
        } else {
            builder.addAction(android.R.drawable.ic_menu_recent_history, "Snooze", pendingAction(ACTION_SNOOZE))
            builder.addAction(android.R.drawable.ic_delete, "Cancel task", pendingAction(ACTION_CANCEL))
        }

        if (placeLat != null && placeLng != null) {
            val mapsIntent = MapIntentHelper.buildIntent(
                latitude = placeLat,
                longitude = placeLng,
                name = resolvedPlace.primaryName,
                address = resolvedPlace.address
            )
            val mapsPending = PendingIntent.getActivity(
                context, (taskId + "_maps").hashCode(), mapsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_dialog_map, "Navigate", mapsPending)
        }

        markPlaceNotifActive(context, taskId)
        nm.notify(placeNotifId(taskId), builder.build())
    }

    fun showTaskInviteNotification(
        context: Context,
        taskId: String,
        fromName: String,
        isShoppingList: Boolean = false
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val route = if (isShoppingList) ROUTE_SHOPPING_LIST else ROUTE_TASK
        val deepLink = "doinplace://$route/$taskId"
        val pending = buildOpenPendingIntent(context, "invite_$taskId".hashCode(), route, taskId)
        DiagLog.d("NOTIFY_BUILD", "type=TASK_ASSIGNED deepLink=$deepLink taskId=${taskId.take(8)}")
        val notification = NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (isShoppingList) "Shopping list from $fromName" else "Task from $fromName")
            .setContentText("Needs your response")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify("invite_$taskId".hashCode(), notification)
        DiagLog.d("LOCAL_NOTIFICATION", "type=TASK_ASSIGNED shown=true taskId=${taskId.take(8)}")
    }

    fun showSharedTaskNotification(
        context: Context,
        taskId: String,
        taskTitle: String,
        fromName: String,
        isShoppingList: Boolean = false
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val route = if (isShoppingList) ROUTE_SHOPPING_LIST else ROUTE_TASK
        val deepLink = "doinplace://$route/$taskId"
        val pending = buildOpenPendingIntent(context, "shared_$taskId".hashCode(), route, taskId)
        DiagLog.d("NOTIFY_BUILD", "type=TASK_SHARED deepLink=$deepLink taskId=${taskId.take(8)}")
        val notification = NotificationCompat.Builder(context, CHANNEL_ASSIGNMENTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (isShoppingList) "Shared shopping list" else "Shared task")
            .setContentText("Shared by $fromName")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify("shared_$taskId".hashCode(), notification)
        DiagLog.d("LOCAL_NOTIFICATION", "type=TASK_SHARED shown=true taskId=${taskId.take(8)}")
    }

    fun showConnectionAcceptedNotification(context: Context, fromName: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val deepLink = "doinplace://contacts"
        val pending = buildOpenPendingIntent(context, "conn_accepted_$fromName".hashCode(), ROUTE_CONTACTS)
        DiagLog.d("NOTIFY_BUILD", "type=CONNECTION_ACCEPTED deepLink=$deepLink")
        val notification = NotificationCompat.Builder(context, CHANNEL_ASSIGNMENTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Connection accepted")
            .setContentText("$fromName accepted your connection request")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify("conn_accepted_$fromName".hashCode(), notification)
        DiagLog.d("LOCAL_NOTIFICATION", "type=CONNECTION_ACCEPTED shown=true")
    }

    fun showConnectionRequestNotification(context: Context, fromName: String, fromEmail: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val deepLink = "doinplace://contacts"
        val pending = buildOpenPendingIntent(context, "conn_req_$fromEmail".hashCode(), ROUTE_CONTACTS)
        DiagLog.d("NOTIFY_BUILD", "type=CONNECTION_REQUEST deepLink=$deepLink")
        val primary = fromEmail.takeIf { it.isNotBlank() } ?: fromName
        val bodyText = "$primary wants to connect"
        val notification = NotificationCompat.Builder(context, CHANNEL_ASSIGNMENTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Connection request")
            .setContentText(bodyText)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify("conn_req_$fromEmail".hashCode(), notification)
        DiagLog.d("LOCAL_NOTIFICATION", "type=CONNECTION_REQUEST shown=true")
    }

    fun showTaskUpdateNotification(context: Context, message: String, taskId: String? = null, notificationType: String = "TASK_UPDATED") {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Task update")
            .setContentText(message)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (!taskId.isNullOrBlank()) {
            val deepLink = "doinplace://task/$taskId"
            builder.setContentIntent(
                buildOpenPendingIntent(context, "task_update_$taskId".hashCode(), ROUTE_TASK, taskId)
            )
            DiagLog.d("NOTIFY_BUILD", "type=$notificationType deepLink=$deepLink taskId=${taskId.take(8)}")
        }
        nm.notify(System.currentTimeMillis().toInt(), builder.build())
        DiagLog.d("LOCAL_NOTIFICATION", "type=$notificationType shown=true taskId=${taskId?.take(8) ?: ""}")
    }

    fun showEverywhereReminderNotification(context: Context, task: Task) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun pendingAction(action: String): PendingIntent {
            val i = Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, task.id)
            return PendingIntent.getBroadcast(
                context, task.id.hashCode() + action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val openPending = buildOpenPendingIntent(
            context,
            "ew_open_${task.id}".hashCode(),
            ROUTE_TASK,
            task.id
        )
        val dueLabel = buildDueLabel(task)
        val notifPriority = when (task.priority) {
            TaskPriority.URGENT  -> NotificationCompat.PRIORITY_MAX
            TaskPriority.NO_RUSH -> NotificationCompat.PRIORITY_HIGH
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_EVERYWHERE)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(task.title)
            .setContentText(dueLabel)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$dueLabel — tap to mark done."))
            .setContentIntent(openPending)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_send, "Done", pendingAction(ACTION_DONE))
            .setPriority(notifPriority)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .build()
        nm.notify(everywhereNotifId(task.id), notification)
    }

    fun cancel(context: Context, taskId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(placeNotifId(taskId))
        nm.cancel(dueNotifId(taskId))
        nm.cancel(everywhereNotifId(taskId))
        clearPlaceNotifActive(context, taskId)
        context.getSharedPreferences("everywhere_notif_state", Context.MODE_PRIVATE)
            .edit().remove("last_hour_$taskId").apply()
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

        val openPending = buildOpenPendingIntent(
            context,
            "due_open_$taskId".hashCode(),
            ROUTE_TASK,
            taskId
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

private fun buildDueLabel(task: Task): String {
    val dueDate = task.activeFromDate?.takeIf { it.isNotBlank() } ?: return "Due today"
    val dueTime = task.activeStartTime?.takeIf { it.isNotBlank() }
    return if (dueTime != null) "Due: $dueDate at $dueTime" else "Due: $dueDate"
}
