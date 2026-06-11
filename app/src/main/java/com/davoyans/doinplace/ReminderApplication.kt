package com.davoyans.doinplace

import android.app.Application
import com.davoyans.doinplace.notification.NotificationHelper
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
    }
}
