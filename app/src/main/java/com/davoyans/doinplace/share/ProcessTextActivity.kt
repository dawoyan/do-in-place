package com.davoyans.doinplace.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.davoyans.doinplace.MainActivity
import com.davoyans.doinplace.util.DiagLog

class ProcessTextActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
        DiagLog.d("SHARE_ROUTER", "route=SelectedText")

        if (!text.isNullOrBlank()) {
            DiagLog.d("PREFILL", "openNewReminder sourceType=SELECTED_TEXT")
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(ShareReceiverActivity.EXTRA_PREFILL_NOTE, text)
                }
            )
        }
        finish()
    }
}
