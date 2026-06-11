package com.davoyans.doinplace.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.davoyans.doinplace.MainActivity
import com.davoyans.doinplace.ocr.ScreenshotTextExtractor
import com.davoyans.doinplace.ocr.isLatinDominant
import com.davoyans.doinplace.ui.theme.RemindInPlaceTheme
import com.davoyans.doinplace.util.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val mime = intent?.type ?: ""
        DiagLog.d("SHARE_ROUTER", "action=$action mime=$mime")

        if (action == Intent.ACTION_SEND && mime.startsWith("image/")) {
            handleImageShare()
        } else {
            finish()
        }
    }

    private fun handleImageShare() {
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        if (uri == null) {
            Toast.makeText(
                this,
                "Could not read this image. Try saving it and selecting it from Screenshot text.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        setContent {
            RemindInPlaceTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("Extracting text…")
                        }
                    }
                }
            }
        }

        DiagLog.d("SHARE_ROUTER", "route=ImageOcr")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ScreenshotTextExtractor().extractText(this@ShareReceiverActivity, uri)
            }
            result.fold(
                onSuccess = { text ->
                    when {
                        text.isBlank() ->
                            Toast.makeText(this@ShareReceiverActivity, "No readable text found in this image.", Toast.LENGTH_LONG).show()
                        !isLatinDominant(text) ->
                            Toast.makeText(this@ShareReceiverActivity, "Only English/Latin text can be recognized for now.", Toast.LENGTH_LONG).show()
                        else -> {
                            DiagLog.d("PREFILL", "openNewReminder sourceType=ANDROID_SHARE_IMAGE")
                            startActivity(
                                Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    putExtra(EXTRA_PREFILL_NOTE, text)
                                }
                            )
                        }
                    }
                },
                onFailure = {
                    Toast.makeText(this@ShareReceiverActivity, "Could not extract text from this image.", Toast.LENGTH_LONG).show()
                }
            )
            finish()
        }
    }

    companion object {
        const val EXTRA_PREFILL_NOTE = "com.davoyans.doinplace.PREFILL_NOTE"
    }
}
