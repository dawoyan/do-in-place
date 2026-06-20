package com.davoyans.doinplace.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.data.model.TaskPriority
import com.davoyans.doinplace.util.DiagLog
import com.davoyans.doinplace.util.isRecurringTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Schedules hourly notifications for Everywhere (time-based) tasks on their due date.
 * Without a chosen time, reminders run hourly from 09:00–21:00 local time.
 * With a chosen start time, reminders begin at that exact local time and continue hourly
 * for the rest of the due date.
 * No location is used. No geofence is created.
 */
object TimeBasedTaskScheduler {

    private const val PREFS_NAME = "everywhere_alarm_state"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_SLOT_HOUR = "slot_hour"

    private const val ACTIVE_HOUR_START = 9
    private const val ACTIVE_HOUR_END = 21

    fun scheduleForTask(context: Context, task: Task) {
        if (!task.isEverywhere) return
        val dueDate = task.activeFromDate?.takeIf { it.isNotBlank() } ?: run {
            DiagLog.d("EVERYWHERE", "no location used taskId=${task.id.take(8)}")
            return
        }
        cancelForTask(context, task.id)
        scheduleHourlyAlarmsForDate(
            context = context,
            taskId = task.id,
            dueDateStr = dueDate,
            priority = task.priority,
            startTime = task.activeStartTime
        )
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("task_${task.id}", dueDate).apply()
        DiagLog.d("EVERYWHERE", "save taskId=${task.id.take(8)} dueDate=$dueDate recurrence=${task.recurrenceType}")
        DiagLog.d("EVERYWHERE", "no location used taskId=${task.id.take(8)}")
        if (task.isRecurringTask()) {
            DiagLog.d("RECURRING", "schedule next id=${task.id.take(8)} date=$dueDate")
            DiagLog.d("RECURRING", "no location used id=${task.id.take(8)}")
        }
    }

    fun scheduleHourlyAlarmsForDate(
        context: Context,
        taskId: String,
        dueDateStr: String,
        priority: TaskPriority = TaskPriority.NO_RUSH,
        startTime: String? = null
    ) {
        val parts = dueDateStr.split("-")
        if (parts.size != 3) return
        val year = parts[0].toIntOrNull() ?: return
        val month = (parts[1].toIntOrNull() ?: return) - 1
        val day = parts[2].toIntOrNull() ?: return

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()

        val startParts = startTime?.split(":")
        val selectedHour = startParts?.getOrNull(0)?.toIntOrNull()
        val selectedMinute = startParts?.getOrNull(1)?.toIntOrNull()
        val hasSpecificStartTime = selectedHour != null && selectedMinute != null

        if (hasSpecificStartTime) {
            val alarmCal = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, selectedHour!!)
                set(Calendar.MINUTE, selectedMinute!!)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            while (alarmCal.get(Calendar.DAY_OF_MONTH) == day) {
                val triggerMs = alarmCal.timeInMillis
                if (triggerMs > now) {
                    val pi = buildPendingIntent(context, taskId, alarmCal.get(Calendar.HOUR_OF_DAY))
                    scheduleAlarm(am, triggerMs, pi)
                }
                alarmCal.add(Calendar.HOUR_OF_DAY, 1)
            }
            return
        }

        for (hour in ACTIVE_HOUR_START..ACTIVE_HOUR_END) {
            val alarmCal = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val triggerMs = alarmCal.timeInMillis
            if (triggerMs <= now) continue
            val pi = buildPendingIntent(context, taskId, hour)
            scheduleAlarm(am, triggerMs, pi)
        }
    }

    fun cancelForTask(context: Context, taskId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (hour in 0..23) {
            val i = Intent(context, EverywhereReminderReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, requestCode(taskId, hour), i,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) am.cancel(pi)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove("task_$taskId").apply()
    }

    fun getPersistedTaskIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
            .keys.filter { it.startsWith("task_") }
            .map { it.removePrefix("task_") }
            .toSet()

    /** Restore Everywhere alarms after reboot. Reads from DB, no network calls. */
    fun restoreOnBoot(context: Context, uid: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val tasks = AppDatabase.get(context).taskDao().getActiveTasks(uid)
            for (task in tasks) {
                if (!task.isEverywhere) continue
                val dueDate = task.activeFromDate ?: continue
                scheduleHourlyAlarmsForDate(
                    context = context,
                    taskId = task.id,
                    dueDateStr = dueDate,
                    priority = task.priority,
                    startTime = task.activeStartTime
                )
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString("task_${task.id}", dueDate).apply()
            }
        }
    }

    private fun scheduleAlarm(am: AlarmManager, triggerMs: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    private fun buildPendingIntent(context: Context, taskId: String, hour: Int): PendingIntent {
        val i = Intent(context, EverywhereReminderReceiver::class.java)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_SLOT_HOUR, hour)
        return PendingIntent.getBroadcast(
            context, requestCode(taskId, hour), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCode(taskId: String, hour: Int): Int = "everywhere_${taskId}_$hour".hashCode()
}
