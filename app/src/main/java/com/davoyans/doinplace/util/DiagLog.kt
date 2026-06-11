package com.davoyans.doinplace.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lightweight in-process diagnostic log. Entries are shown in Settings → debug info
 * so they can be copied and shared without adb.
 */
object DiagLog {
    private const val TAG = "DIP_DIAG"
    private const val MAX = 80
    private val entries = CopyOnWriteArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun d(section: String, msg: String) {
        val line = "${fmt.format(Date())} [$section] $msg"
        Log.d(TAG, line)
        append(line)
    }

    fun e(section: String, msg: String, t: Throwable? = null) {
        val line = "${fmt.format(Date())} [$section] ERROR: $msg${t?.let { " — ${it.javaClass.simpleName}: ${it.message?.take(120)}" } ?: ""}"
        Log.e(TAG, line, t)
        append(line)
    }

    private fun append(line: String) {
        entries.add(line)
        if (entries.size > MAX) entries.removeAt(0)
    }

    fun dump(): String = entries.joinToString("\n")

    fun clear() = entries.clear()
}
