package com.davoyans.doinplace.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

data class QrDisplayWindowState(
    val screenBrightness: Float,
    val keepScreenOn: Boolean
)

object QrDisplayWindowPolicy {
    const val MAX_SCREEN_BRIGHTNESS = 1.0f

    fun displayState(previous: QrDisplayWindowState): QrDisplayWindowState =
        previous.copy(
            screenBrightness = MAX_SCREEN_BRIGHTNESS,
            keepScreenOn = true
        )

    fun restoreState(previous: QrDisplayWindowState): QrDisplayWindowState = previous
}

@Composable
fun MaxBrightnessWhileVisible() {
    val window = LocalContext.current.findActivity()?.window

    DisposableEffect(window) {
        if (window == null) {
            onDispose { }
        } else {
            val previous = snapshot(window)
            apply(window, QrDisplayWindowPolicy.displayState(previous))
            onDispose {
                apply(window, QrDisplayWindowPolicy.restoreState(previous))
            }
        }
    }
}

private fun snapshot(window: Window): QrDisplayWindowState = QrDisplayWindowState(
    screenBrightness = window.attributes.screenBrightness,
    keepScreenOn = (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
)

private fun apply(window: Window, state: QrDisplayWindowState) {
    if (state.keepScreenOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    window.attributes = WindowManager.LayoutParams().apply {
        copyFrom(window.attributes)
        screenBrightness = state.screenBrightness
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
