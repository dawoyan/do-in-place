package com.davoyans.doinplace.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.davoyans.doinplace.data.model.Task
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Schedules and cancels due-date alarm notifications for a task.
 *
 * Alarm schedule relative to due time:
 *   -120 min, -60 min (fixed early warnings)
 *   then every 15 min: -45, -30, -15, 0, +15 … +120 (capped at 2 h overdue)
 *
 * State is persisted in SharedPreferences so BootReceiver can reschedule after reboot.
 * Duplicate-free: always cancels any existing alarms before rescheduling.
 *
 * Due date/time is taken from task.activeFromDate (yyyy-MM-dd) + task.activeStartTime (HH:mm).
 * If either is blank, no alarms are scheduled (time-of-day is required for precise reminders).
 */
object DueAlarmScheduler {

    private const val PREFS_NAME = "due_alarm_state"
    const val EXTRA_MINUTES_OFFSET = "due_minutes_offset"
    const val EXTRA_DUE_TIME_MS   = "due_time_ms"

    private val FIXED_OFFSETS    = listOf(-120, -60)
    private val SEQUENCE_OFFSETS = (-45..120 step 15).toList()
    private val ALL_OFFSETS      = (FIXED_OFFSETS + SEQUENCE_OFFSETS).distinct().sorted()

    fun scheduleForTask(context: Context, task: Task) {
        // Everywhere tasks use TimeBasedTaskScheduler instead (date-only, hourly, no exact time needed)
        if (task.isEverywhere) return
        val dueMs = parseDueTimeMs(task) ?: return
        cancelForTask(context, task.id)          // clear stale alarms first

        val am  = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()

        for (minOffset in ALL_OFFSETS) {
            val triggerMs = dueMs + minOffset * 60_000L
            if (triggerMs <= now) continue
            val pi = buildPendingIntent(context, task.id, minOffset, dueMs)
            scheduleAlarm(am, triggerMs, pi)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong("task_${task.id}", dueMs).apply()
    }

    fun cancelForTask(context: Context, taskId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (minOffset in ALL_OFFSETS) {
            val i  = Intent(context, DueAlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, requestCode(taskId, minOffset), i,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) am.cancel(pi)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove("task_$taskId").apply()
    }

    /** Returns task IDs with persisted due alarms — used by BootReceiver. */
    fun getPersistedTaskIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
            .keys.filter { it.startsWith("task_") }
            .map { it.removePrefix("task_") }
            .toSet()

    fun parseDueTimeMs(task: Task): Long? {
        val date = task.activeFromDate?.takeIf { it.isNotBlank() } ?: return null
        val time = task.activeStartTime?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("$date $time")?.time
        }.getOrNull()
    }

    private fun scheduleAlarm(am: AlarmManager, triggerMs: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    private fun buildPendingIntent(
        context: Context, taskId: String, minOffset: Int, dueMs: Long
    ): PendingIntent {
        val i = Intent(context, DueAlarmReceiver::class.java)
            .putExtra(NotificationHelper.EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_MINUTES_OFFSET, minOffset)
            .putExtra(EXTRA_DUE_TIME_MS, dueMs)
        return PendingIntent.getBroadcast(
            context, requestCode(taskId, minOffset), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCode(taskId: String, minOffset: Int): Int =
        "due_${taskId}_$minOffset".hashCode()
}
