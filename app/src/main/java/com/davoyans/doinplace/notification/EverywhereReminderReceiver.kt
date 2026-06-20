package com.davoyans.doinplace.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.TaskType
import com.davoyans.doinplace.data.model.TaskStatus
import com.davoyans.doinplace.util.DiagLog
import com.davoyans.doinplace.util.ReminderItemFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/** Fires for each scheduled hour slot on an Everywhere task's due date. */
class EverywhereReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(TimeBasedTaskScheduler.EXTRA_TASK_ID) ?: return
        val slotHour = intent.getIntExtra(TimeBasedTaskScheduler.EXTRA_SLOT_HOUR, -1)

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val db = AppDatabase.get(context)
            val task = db.taskDao().getById(taskId) ?: run {
                DiagLog.d("TIME_REMINDER", "skipped taskId=${taskId.take(8)} reason=task_not_found")
                return@launch
            }
            if (task.status != TaskStatus.ACTIVE) {
                DiagLog.d("TIME_REMINDER", "skipped taskId=${taskId.take(8)} reason=task_not_active")
                return@launch
            }
            if (!task.isEverywhere) return@launch
            if (task.taskType == TaskType.SHOPPING_LIST) {
                val reminderItems = ReminderItemFilter.activeItems(task.id, db.shoppingListItemDao().getForTaskIncludingDeleted(task.id))
                if (reminderItems.isEmpty()) return@launch
            }

            // Guard: max one notification per hour per task
            val prefs = context.getSharedPreferences("everywhere_notif_state", Context.MODE_PRIVATE)
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val lastHour = prefs.getInt("last_hour_$taskId", -1)
            if (lastHour == currentHour) {
                DiagLog.d("TIME_REMINDER", "skipped taskId=${taskId.take(8)} reason=already_notified_this_hour")
                return@launch
            }

            DiagLog.d("TIME_REMINDER", "due taskId=${taskId.take(8)} active=true hourlyAllowed=true")
            NotificationHelper.showEverywhereReminderNotification(context, task)
            prefs.edit().putInt("last_hour_$taskId", currentHour).apply()
        }
    }
}
