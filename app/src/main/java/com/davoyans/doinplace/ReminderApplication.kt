package com.davoyans.doinplace

import android.app.Application
import android.content.Context
import com.davoyans.doinplace.notification.NotificationHelper
import com.davoyans.doinplace.notification.WalkReminderWorker
import com.davoyans.doinplace.sync.SyncWorker
import com.google.firebase.FirebaseApp

class ReminderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply saved language before any Activity is created
        val lang = getSharedPreferences("dip_prefs", MODE_PRIVATE)
            .getString("app_language", "system") ?: "system"
        AppLocaleManager.applyLanguage(lang)

        FirebaseApp.initializeApp(this)
        NotificationHelper.createChannels(this)
        SyncWorker.schedulePeriodicSync(this)
        val walkEnabled = getSharedPreferences("dip_prefs", Context.MODE_PRIVATE)
            .getBoolean(WalkReminderWorker.PREF_WALK_REMINDER, false)
        if (walkEnabled) WalkReminderWorker.schedule(this)
    }
}
