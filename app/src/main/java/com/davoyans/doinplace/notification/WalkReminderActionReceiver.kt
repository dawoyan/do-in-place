package com.davoyans.doinplace.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WalkReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WalkReminderWorker.ACTION_MUTE_TODAY   -> WalkReminderWorker.muteToday(context)
            WalkReminderWorker.ACTION_REMIND_LATER -> WalkReminderWorker.remindLater(context)
        }
    }
}
