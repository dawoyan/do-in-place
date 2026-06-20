package com.davoyans.doinplace.data.repository

import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.*
import java.util.UUID

/**
 * COST BOUNDARY – TASK STATE
 * ───────────────────────────
 * Manages Task state in the local Room database and queues events for Firestore sync.
 *
 * Firestore writes happen ONLY for meaningful state transitions:
 *   created / accepted / rejected / arrived_near_place / reminded / done / cancelled / expired
 *
 * NEVER write to Firestore:
 *  - Continuous GPS coordinates
 *  - Device location history
 *  - Geofence trigger counts or timestamps on every entry
 *
 * Firestore sync is handled by SyncWorker (periodic, when online).
 */
class TaskRepository(private val db: AppDatabase) {

    fun observeAll(uid: String) = db.taskDao().observeAll(uid)

    fun observeArchived(uid: String) = db.taskDao().observeArchived(uid)

    suspend fun archiveTask(id: String) = db.taskDao().archiveTask(id)

    suspend fun getLatestUndoTarget(uid: String): Task? {
        val cancelled = db.taskDao().getLatestCancelledOrDone(uid)
        val archived  = db.taskDao().getLatestArchivedTask(uid)
        return when {
            cancelled == null -> archived
            archived  == null -> cancelled
            else -> if ((archived.archivedAt ?: 0L) > (cancelled.updatedAt ?: 0L)) archived else cancelled
        }
    }

    suspend fun restoreToActive(task: Task, actorUid: String) {
        if (task.archived) db.taskDao().unarchiveTask(task.id)
        db.taskDao().updateStatus(task.id, TaskStatus.ACTIVE.name)
        db.taskEventDao().insert(TaskEvent(
            id = UUID.randomUUID().toString(),
            taskId = task.id,
            type = TaskEventType.ACCEPTED,
            actorUserId = actorUid,
            synced = false
        ))
    }

    suspend fun clearArchivedTasks(uid: String) = db.taskDao().clearArchivedTasks(uid)

    suspend fun getById(id: String) = db.taskDao().getById(id)

    suspend fun getActiveTasks(uid: String) = db.taskDao().getActiveTasks(uid)

    suspend fun getActiveTasksForPlace(placeId: String) = db.taskDao().getActiveTasksForPlace(placeId)

    suspend fun save(task: Task) = db.taskDao().upsert(task)

    suspend fun deleteTaskPermanently(id: String) {
        db.shoppingListItemDao().deleteForTask(id)
        db.taskEventDao().deleteForTask(id)
        db.taskDao().deleteById(id)
    }

    /** Record a meaningful status change and queue a TaskEvent for later Firestore sync. */
    suspend fun updateStatus(id: String, status: TaskStatus, actorUid: String = "") {
        db.taskDao().updateStatus(id, status.name)
        db.taskEventDao().insert(TaskEvent(
            id = UUID.randomUUID().toString(),
            taskId = id,
            type = when (status) {
                TaskStatus.DONE               -> TaskEventType.DONE
                TaskStatus.CANCELLED          -> TaskEventType.CANCELLED
                TaskStatus.REJECTED           -> TaskEventType.REJECTED
                TaskStatus.EXPIRED            -> TaskEventType.EXPIRED
                TaskStatus.ACTIVE             -> TaskEventType.ACCEPTED
                TaskStatus.PENDING_ACCEPTANCE -> TaskEventType.CREATED
            },
            actorUserId = actorUid,
            synced = false
        ))
    }

    suspend fun markForceDone(id: String, actorUid: String, uncheckedCount: Int) {
        db.taskDao().updateStatus(id, TaskStatus.DONE.name)
        db.taskEventDao().insert(TaskEvent(
            id = UUID.randomUUID().toString(),
            taskId = id,
            type = TaskEventType.FORCE_DONE_INCOMPLETE,
            actorUserId = actorUid,
            reason = "unchecked=$uncheckedCount",
            synced = false
        ))
    }

    /** Log that the user arrived near the place (only if arrival sharing is on). */
    suspend fun logArrival(taskId: String, actorUid: String) {
        db.taskDao().touchReminderShown(taskId)
        db.taskEventDao().insert(TaskEvent(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            type = TaskEventType.ARRIVED_NEAR_PLACE,
            actorUserId = actorUid,
            synced = false
        ))
    }

    /** Log a reminder shown — stored locally, synced to Firestore in batch by SyncWorker. */
    suspend fun logReminded(taskId: String, actorUid: String) {
        db.taskDao().touchReminderShown(taskId)
        db.taskEventDao().insert(TaskEvent(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            type = TaskEventType.REMINDED,
            actorUserId = actorUid,
            synced = false
        ))
    }

    suspend fun updateChecklist(id: String, json: String?) = db.taskDao().updateChecklist(id, json)

    suspend fun getPendingSync() = db.taskDao().getPendingSync()

    suspend fun markSynced(id: String) = db.taskDao().markSynced(id)
}
