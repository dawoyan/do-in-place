package com.davoyans.doinplace.sync

import android.content.Context
import androidx.work.*
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.PlaceMode
import com.davoyans.doinplace.data.model.ShoppingListItem
import com.davoyans.doinplace.data.model.TaskEventType
import com.davoyans.doinplace.data.model.TaskPriority
import com.davoyans.doinplace.data.model.TaskStatus
import com.davoyans.doinplace.data.model.TaskType
import java.util.UUID
import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.davoyans.doinplace.data.remote.SupabaseClient
import com.davoyans.doinplace.notification.NotificationHelper
import com.davoyans.doinplace.util.DiagLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val authClient = SupabaseAuthClient(applicationContext)
        val uid = authClient.getCurrentUserId() ?: return Result.success()
        val db = AppDatabase.get(applicationContext)
        val supabase = SupabaseClient(applicationContext)

        // Push pending task status updates first so remote tasks are up-to-date
        // before we push task events that reference them.
        for (task in db.taskDao().getPendingSync()) {
            runCatching { supabase.updateTaskStatus(task.id, task.status.name) }
                .onSuccess { db.taskDao().markSynced(task.id) }
        }

        // Push unsynced task events — only for shared tasks that exist remotely.
        // Guarded by a singleton mutex so periodic sync and syncNow() cannot race.
        taskEventsSyncMutex.withLock {
            val unsyncedEvents = db.taskEventDao().getUnsynced()
            DiagLog.d("TASK_EVENTS_SYNC", "start pendingCount=${unsyncedEvents.size}")
            var synced = 0; var failed = 0; var pending = 0
            for (event in unsyncedEvents) {
                val task = db.taskDao().getById(event.taskId)
                when {
                    task == null -> {
                        // Parent task not found locally — orphan, stop retrying.
                        DiagLog.d("TASK_EVENTS_SYNC", "skipped orphan eventId=${event.id} taskId=${event.taskId.take(8)}")
                        db.taskEventDao().markSynced(event.id)
                        synced++
                    }
                    task.createdByUserId == task.assignedToUserId -> {
                        // Self-assigned (local-only) task — never pushed to Supabase.
                        db.taskEventDao().markSynced(event.id)
                        synced++
                    }
                    task.pendingSync -> {
                        // Shared task not yet uploaded; skip and retry next cycle.
                        pending++
                    }
                    else -> {
                        // Drop stale reminder events for tasks that are no longer active.
                        if (event.type in REMINDER_ONLY_TYPES && task.status != TaskStatus.ACTIVE) {
                            DiagLog.d("TASK_EVENTS_SYNC", "skipped stale reminder eventId=${event.id} taskStatus=${task.status}")
                            db.taskEventDao().markSynced(event.id)
                            synced++
                            continue
                        }
                        // RLS requires actor_user_id = auth.uid() — skip events that cannot pass.
                        if (event.actorUserId.isBlank() || event.actorUserId != uid) {
                            DiagLog.d("TASK_EVENTS_SYNC", "skipped actorUserId mismatch eventId=${event.id} actor=${event.actorUserId.take(8)} uid=${uid.take(8)}")
                            db.taskEventDao().markSynced(event.id)
                            synced++
                            continue
                        }
                        DiagLog.d("TASK_EVENTS_SYNC", "upload eventId=${event.id} taskId=${event.taskId.take(8)} type=${event.type}")
                        runCatching { supabase.pushTaskEvent(event) }
                            .onSuccess {
                                DiagLog.d("TASK_EVENTS_SYNC", "upload success eventId=${event.id}")
                                db.taskEventDao().markSynced(event.id)
                                synced++
                            }
                            .onFailure { e ->
                                when {
                                    isDuplicateTaskEventKeyError(e) -> {
                                        DiagLog.d("TASK_EVENTS_SYNC", "duplicate remote event treated as synced eventId=${event.id}")
                                        db.taskEventDao().markSynced(event.id)
                                        synced++
                                    }
                                    isRlsDeniedError(e) -> {
                                        DiagLog.e("TASK_EVENTS_SYNC", "RLS_DENIED eventId=${event.id} taskId=${event.taskId.take(8)} type=${event.type} actor=${event.actorUserId.take(8)} taskStatus=${task.status}")
                                        db.taskEventDao().markSynced(event.id)
                                        synced++
                                    }
                                    else -> {
                                        DiagLog.d("TASK_EVENTS_SYNC", "retry later eventId=${event.id} reason=${e.message?.take(80)}")
                                        failed++
                                    }
                                }
                            }
                    }
                }
            }
            DiagLog.d("TASK_EVENTS_SYNC", "finish synced=$synced failed=$failed pending=$pending")
        }

        // Pull remote tasks and merge into local Room DB
        runCatching {
            val remoteTasks = supabase.fetchTasksForUser(uid)
            for (raw in remoteTasks) {
                val taskId = raw["id"] as? String ?: continue
                val existing = db.taskDao().getById(taskId)
                val remoteUpdated = (raw["updated_at"] as? Long) ?: 0L
                if (existing == null || remoteUpdated > existing.updatedAt) {
                    val merged = mapToTask(raw) ?: continue

                    // ── Notify: new task assigned to me (pending acceptance)
                    if (existing == null &&
                        merged.status == TaskStatus.PENDING_ACCEPTANCE &&
                        merged.assignedToUserId == uid) {
                        val fromName = runCatching {
                            supabase.lookupUserById(merged.createdByUserId)?.second
                        }.getOrNull() ?: "Someone"
                        NotificationHelper.showTaskInviteNotification(
                            applicationContext, merged.id, fromName
                        )
                    }

                    // ── Notify: task I created was accepted (PENDING → ACTIVE)
                    if (existing?.status == TaskStatus.PENDING_ACCEPTANCE &&
                        merged.status == TaskStatus.ACTIVE &&
                        merged.createdByUserId == uid) {
                        val contacts = db.trustedContactDao().getAccepted(uid)
                        val acceptorName = contacts
                            .find { it.contactUserId == merged.assignedToUserId }
                            ?.let { it.contactDisplayName.ifBlank { it.contactEmail } }
                            ?: "Your contact"
                        NotificationHelper.showTaskUpdateNotification(
                            applicationContext,
                            "$acceptorName accepted \"${merged.title}\""
                        )
                    }

                    db.taskDao().upsert(merged)

                    // Sync shopping items for shopping list tasks
                    if (merged.taskType == TaskType.SHOPPING_LIST) {
                        runCatching {
                            val remoteItems = supabase.fetchShoppingItemsForTask(taskId)
                            if (remoteItems.isNotEmpty()) {
                                val items = remoteItems.mapIndexedNotNull { i, raw ->
                                    val itemId = raw["id"] as? String ?: return@mapIndexedNotNull null
                                    ShoppingListItem(
                                        id = itemId,
                                        taskId = taskId,
                                        text = raw["text"] as? String ?: "",
                                        normalizedText = raw["normalized_text"] as? String ?: "",
                                        orderIndex = (raw["order_index"] as? Long)?.toInt() ?: i,
                                        checked = raw["checked"] as? Boolean ?: false,
                                        createdAt = (raw["created_at"] as? Long) ?: System.currentTimeMillis(),
                                        updatedAt = (raw["updated_at"] as? Long) ?: System.currentTimeMillis()
                                    )
                                }
                                db.shoppingListItemDao().upsertAll(items)
                            }
                        }
                    }
                }
            }
        }

        // Pull pending contact invites addressed to this user
        runCatching {
            val invites = supabase.fetchPendingInvitesForUser(uid)
            for (raw in invites) {
                val id     = raw["id"] as? String ?: continue
                val fromId = raw["from_user_id"] as? String ?: continue
                if (fromId == uid) continue  // skip self-invites that shouldn't exist
                val email  = raw["to_email"] as? String ?: ""
                if (db.trustedContactDao().findContact(uid, fromId) == null) {
                    val fromProfile = runCatching { supabase.lookupUserById(fromId) }.getOrNull()
                    db.trustedContactDao().upsert(
                        com.davoyans.doinplace.data.model.TrustedContact(
                            id = id,
                            userId = uid,
                            contactUserId = fromId,
                            contactEmail = fromProfile?.first ?: email,
                            contactDisplayName = fromProfile?.second ?: "",
                            status = com.davoyans.doinplace.data.model.ContactStatus.PENDING
                        )
                    )
                }
            }
        }

        return Result.success()
    }

    private fun mapToTask(raw: Map<String, Any?>): com.davoyans.doinplace.data.model.Task? {
        val id = raw["id"] as? String ?: return null
        return com.davoyans.doinplace.data.model.Task(
            id = id,
            title = raw["title"] as? String ?: "",
            description = raw["description"] as? String,
            createdByUserId  = raw["created_by_user_id"]   as? String ?: "",
            assignedToUserId = raw["assigned_to_user_id"]  as? String ?: "",
            placeName     = raw["place_name"]     as? String ?: "",
            address       = raw["address"]        as? String,
            latitude      = (raw["latitude"]      as? Double)  ?: 0.0,
            longitude     = (raw["longitude"]     as? Double)  ?: 0.0,
            radiusMeters  = (raw["radius_meters"] as? Long)?.toInt() ?: 100,
            status = runCatching {
                TaskStatus.valueOf(raw["status"] as? String ?: "ACTIVE")
            }.getOrDefault(TaskStatus.ACTIVE),
            arrivalShareAllowed = raw["arrival_share_allowed"] as? Boolean ?: false,
            activeFromDate    = raw["active_from_date"]    as? String,
            activeToDate      = raw["active_to_date"]      as? String,
            activeDaysOfWeek  = raw["active_days_of_week"] as? String,
            activeStartTime   = raw["active_start_time"]   as? String,
            activeEndTime     = raw["active_end_time"]     as? String,
            remindUntilDone   = raw["remind_until_done"]   as? Boolean ?: true,
            priority = run {
                val raw2 = raw["priority"] as? String ?: "NO_RUSH"
                when (raw2) {
                    "HIGH"               -> TaskPriority.URGENT
                    "NORMAL","LOW","EASY" -> TaskPriority.NO_RUSH
                    else -> runCatching { TaskPriority.valueOf(raw2) }.getOrDefault(TaskPriority.NO_RUSH)
                }
            },
            placeMode = runCatching {
                PlaceMode.valueOf(raw["place_mode"] as? String ?: "EXACT")
            }.getOrDefault(PlaceMode.EXACT),
            placeTypeId = raw["place_type_id"] as? String,
            placeTypeName = raw["place_type_name"] as? String,
            taskType = runCatching {
                TaskType.valueOf(raw["task_type"] as? String ?: "SIMPLE")
            }.getOrDefault(TaskType.SIMPLE),
            pendingSync = false,
            createdAt = (raw["created_at"] as? Long) ?: System.currentTimeMillis(),
            updatedAt = (raw["updated_at"] as? Long) ?: System.currentTimeMillis()
        )
    }

    private fun isDuplicateTaskEventKeyError(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return "23505" in msg && "task_events_pkey" in msg
    }

    private fun isRlsDeniedError(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return "403" in msg || "row-level security" in msg
    }

    companion object {
        private const val WORK_NAME = "do_in_place_sync"
        // Singleton mutex shared across all SyncWorker instances (periodic + one-time).
        val taskEventsSyncMutex = Mutex()

        // Event types that only make sense while a task is still ACTIVE.
        private val REMINDER_ONLY_TYPES = setOf(
            TaskEventType.REMINDED,
            TaskEventType.DUE_REMINDER_SHOWN,
            TaskEventType.PLACE_REMINDER_AUTO_DISMISSED,
            TaskEventType.ARRIVED_NEAR_PLACE
        )

        fun schedulePeriodicSync(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }

        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }
    }
}
