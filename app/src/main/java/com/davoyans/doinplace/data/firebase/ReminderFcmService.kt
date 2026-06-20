package com.davoyans.doinplace.data.firebase

import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.davoyans.doinplace.data.remote.SupabaseClient
import com.davoyans.doinplace.data.model.TaskType
import com.davoyans.doinplace.notification.NotificationHelper
import com.davoyans.doinplace.sync.SyncWorker
import com.davoyans.doinplace.util.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class ReminderFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val actorUserId = data["actorUserId"] ?: data["actor_user_id"] ?: ""
        val uid = SupabaseAuthClient(this).getCurrentUserId() ?: ""
        val taskId = data["taskId"] ?: ""
        val taskType = data["taskType"] ?: data["task_type"] ?: TaskType.SIMPLE.name
        val isShoppingList = taskType == TaskType.SHOPPING_LIST.name
        val fromName = data["fromName"]?.takeIf { it.isNotBlank() }
            ?: data["actorName"]?.takeIf { it.isNotBlank() }
            ?: data["byName"]?.takeIf { it.isNotBlank() }
            ?: data["actorEmail"]?.takeIf { it.isNotBlank() }
            ?: "Someone"
        DiagLog.d("FCM_RECEIVE", "user=${uid.take(8)} type=${data["type"]} taskId=${taskId.take(8)} actor=${actorUserId.take(8)}")
        when (data["type"]) {
            "new_task" -> {
                if (actorUserId.isNotBlank() && actorUserId == uid) {
                    DiagLog.d("FCM_RECEIVE", "skip_self type=TASK_ASSIGNED user=${uid.take(8)}")
                    return
                }
                if (taskId.isNotBlank()) {
                    NotificationHelper.showTaskInviteNotification(
                        context = this,
                        taskId = taskId,
                        fromName = fromName,
                        isShoppingList = isShoppingList
                    )
                    DiagLog.d(
                        "ASSIGN_RECEIVE",
                        "user=${uid.take(8)} taskId=${taskId.take(8)} type=$taskType status=PENDING_ACCEPTANCE source=FCM"
                    )
                }
                SyncWorker.syncNow(this)
            }
            "task_done" -> {
                val taskTitle = data["taskTitle"] ?: "A task"
                NotificationHelper.showTaskUpdateNotification(this, "$fromName completed: $taskTitle", taskId, "TASK_COMPLETED")
            }
            "task_rejected" -> {
                val taskTitle = data["taskTitle"] ?: "A task"
                NotificationHelper.showTaskUpdateNotification(this, "$fromName rejected: $taskTitle", taskId, "TASK_REJECTED")
            }
            "arrived_near_place" -> {
                val taskTitle = data["taskTitle"] ?: "task place"
                NotificationHelper.showTaskUpdateNotification(this, "$fromName arrived near the place for: $taskTitle", taskId, "ARRIVAL_SHARING")
            }
            "task_cancelled" -> {
                val taskTitle = data["taskTitle"] ?: "A task"
                NotificationHelper.showTaskUpdateNotification(this, "Task cancelled: $taskTitle", taskId, "TASK_CANCELLED")
            }
            "task_shared" -> {
                if (taskId.isNotBlank()) {
                    NotificationHelper.showSharedTaskNotification(
                        context = this,
                        taskId = taskId,
                        taskTitle = data["taskTitle"] ?: "Shared task",
                        fromName = fromName,
                        isShoppingList = isShoppingList
                    )
                }
                SyncWorker.syncNow(this)
            }
            "shopping_sync" -> {
                SyncWorker.syncNow(this)
            }
            "connection_accepted" -> {
                DiagLog.d("CONTACTS_NOTIFY", "connection_accepted from=$fromName")
                SyncWorker.syncNow(this)
                NotificationHelper.showConnectionAcceptedNotification(this, fromName)
            }
            "connection_request" -> {
                val fromEmail = data["actorEmail"] ?: ""
                DiagLog.d("CONTACTS_NOTIFY", "connection_request from=$fromName email=${fromEmail.take(20)}")
                SyncWorker.syncNow(this)
                NotificationHelper.showConnectionRequestNotification(this, fromName, fromEmail)
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
