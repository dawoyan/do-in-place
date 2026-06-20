package com.davoyans.doinplace.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.davoyans.doinplace.engine.UsualShoppingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UsualShoppingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val placeTypeKey = intent.getStringExtra(NotificationHelper.EXTRA_PLACE_TYPE_KEY) ?: return
        val placeName = intent.getStringExtra(NotificationHelper.EXTRA_PLACE_NAME) ?: ""

        when (intent.action) {
            NotificationHelper.ACTION_USUAL_NOT_NOW -> {
                val uid = SupabaseAuthClient(context).getCurrentUserId() ?: return
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        UsualShoppingEngine(AppDatabase.get(context))
                            .suppressForToday(uid, placeTypeKey)
                    }
                }
            }
            NotificationHelper.ACTION_USUAL_CREATE -> {
                // Open the app with the suggestion dialog
                val openIntent = Intent(context, com.davoyans.doinplace.MainActivity::class.java)
                    .putExtra("open_usual_shopping", true)
                    .putExtra(NotificationHelper.EXTRA_PLACE_TYPE_KEY, placeTypeKey)
                    .putExtra(NotificationHelper.EXTRA_PLACE_NAME, placeName)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(openIntent)
            }
        }
    }
}
