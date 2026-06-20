package com.davoyans.doinplace.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.davoyans.doinplace.geofence.LocationReminderManager
import com.davoyans.doinplace.notification.DueAlarmScheduler
import com.davoyans.doinplace.notification.TimeBasedTaskScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Re-registers geofences and due-date alarms after reboot. No network calls. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val uid = SupabaseAuthClient(context).getCurrentUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            LocationReminderManager(context).restoreOnBoot(uid)
            val activeTasks = AppDatabase.get(context).taskDao().getActiveTasks(uid)
            for (task in activeTasks) {
                if (task.isEverywhere) {
                    TimeBasedTaskScheduler.scheduleForTask(context, task)
                } else {
                    DueAlarmScheduler.scheduleForTask(context, task)
                }
            }
        }
    }
}
