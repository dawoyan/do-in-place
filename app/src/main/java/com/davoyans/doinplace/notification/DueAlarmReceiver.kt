package com.davoyans.doinplace.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.PlaceMode
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.data.model.TaskEvent
import com.davoyans.doinplace.data.model.TaskEventType
import com.davoyans.doinplace.data.model.TaskType
import com.davoyans.doinplace.data.model.TaskStatus
import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.davoyans.doinplace.engine.ContextAwareReminderEngine
import com.davoyans.doinplace.util.PlaceLabelResolver
import com.davoyans.doinplace.util.ReminderItemFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class DueAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId    = intent.getStringExtra(NotificationHelper.EXTRA_TASK_ID) ?: return
        val minOffset = intent.getIntExtra(DueAlarmScheduler.EXTRA_MINUTES_OFFSET, Int.MIN_VALUE)
        if (minOffset == Int.MIN_VALUE) return

        CoroutineScope(Dispatchers.IO).launch {
            val db   = AppDatabase.get(context)
            val task = db.taskDao().getById(taskId) ?: return@launch
            if (task.status != TaskStatus.ACTIVE) return@launch
            if (task.taskType == TaskType.SHOPPING_LIST) {
                val reminderItems = ReminderItemFilter.activeItems(task.id, db.shoppingListItemDao().getForTaskIncludingDeleted(task.id))
                if (reminderItems.isEmpty()) return@launch
            }

            // Context-aware gate (no location for due-date alarms — uses time/priority rules only)
            val engine   = ContextAwareReminderEngine(context, db)
            val snapshot = engine.buildSnapshot(null)
            val decision = engine.evaluate(task, snapshot)
            val uid      = SupabaseAuthClient(context).getCurrentUserId() ?: ""
            engine.recordOutcome(task, snapshot, engine.computeDueUrgency(task, snapshot.nowMillis), decision, uid)
            if (!decision.shouldNotify) return@launch

            val placeText = buildPlaceText(task)

            NotificationHelper.showDueAlarmNotification(
                context   = context,
                taskId    = taskId,
                taskTitle = task.title,
                minOffset = minOffset,
                placeText = placeText,
                priority  = task.priority
            )

            // Log to task event history
            val timeDesc = offsetToDescription(minOffset)
            db.taskEventDao().insert(TaskEvent(
                id          = UUID.randomUUID().toString(),
                taskId      = taskId,
                type        = TaskEventType.DUE_REMINDER_SHOWN,
                actorUserId = uid,
                reason      = timeDesc,
                placeName   = task.placeName.takeIf { it.isNotBlank() },
                placeAddress = task.address?.takeIf { it.isNotBlank() && it != task.placeName },
                synced      = false
            ))
        }
    }

    private fun buildPlaceText(task: Task): String? = when (task.placeMode) {
        PlaceMode.EXACT -> {
            val resolved = PlaceLabelResolver.resolve(
                exactPlaceName = task.placeName,
                exactPlaceAddress = task.address,
                savedPlaceName = task.placeName
            )
            if (resolved.address != null) "${resolved.primaryName}, ${resolved.address}" else resolved.primaryName
        }
        PlaceMode.TYPE -> {
            // Live nearby search not feasible from a BroadcastReceiver in Doze mode;
            // use stored type name as descriptor.
            (task.placeTypeName?.takeIf { it.isNotBlank() }
                ?: task.placeName.takeIf { it.isNotBlank() })
                ?.let { "any $it nearby" }
        }
    }

    private fun offsetToDescription(minOffset: Int): String = when {
        minOffset == -120 -> "due in 2 hours"
        minOffset == -60  -> "due in 1 hour"
        minOffset <  0    -> "due in ${-minOffset} minutes"
        minOffset == 0    -> "due now"
        minOffset <= 60   -> "overdue by $minOffset minutes"
        else              -> {
            val h = minOffset / 60
            val m = minOffset % 60
            if (m == 0) "overdue by ${h}h" else "overdue by ${h}h ${m}m"
        }
    }
}
