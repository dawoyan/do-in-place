package com.davoyans.doinplace.data.firebase

import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.davoyans.doinplace.data.remote.SupabaseClient
import com.davoyans.doinplace.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class ReminderFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["type"]) {
            "new_task" -> {
                val taskId = data["taskId"] ?: return
                val from = data["fromName"] ?: "Someone"
                NotificationHelper.showTaskInviteNotification(this, taskId, from)
            }
            "task_done" -> {
                val taskTitle = data["taskTitle"] ?: "A task"
                val byName = data["byName"] ?: "Assignee"
                NotificationHelper.showTaskUpdateNotification(this, "$byName completed: $taskTitle")
            }
            "task_rejected" -> {
                val taskTitle = data["taskTitle"] ?: "A task"
                val byName = data["byName"] ?: "Assignee"
                NotificationHelper.showTaskUpdateNotification(this, "$byName rejected: $taskTitle")
            }
            "arrived_near_place" -> {
                val taskTitle = data["taskTitle"] ?: "task place"
                val byName = data["byName"] ?: "Assignee"
                NotificationHelper.showTaskUpdateNotification(this, "$byName arrived near the place for: $taskTitle")
            }
            "task_cancelled" -> {
                val taskTitle = data["taskTitle"] ?: "A task"
                NotificationHelper.showTaskUpdateNotification(this, "Task cancelled: $taskTitle")
            }
        }
    }

    override fun onNewToken(token: String) {
        // Store FCM token in Supabase so server-side code can send push to this device.
        val uid = SupabaseAuthClient(this).getCurrentUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { SupabaseClient(this@ReminderFcmService).updateFcmToken(uid, token) }
        }
    }
}
