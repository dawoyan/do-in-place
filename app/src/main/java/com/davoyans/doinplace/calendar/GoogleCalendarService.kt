package com.davoyans.doinplace.calendar

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.davoyans.doinplace.data.model.RecurrenceType
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.util.DiagLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GoogleCalendarService {

    /**
     * Inserts an all-day "done" event into the user's primary calendar.
     * Requires WRITE_CALENDAR permission. Returns false if permission is absent or insert fails.
     */
    fun insertDoneEvent(context: Context, task: Task, completedAtMs: Long): Boolean {
        return try {
            val calendarId = getPrimaryCalendarId(context) ?: run {
                DiagLog.d("CALENDAR_DONE", "failed taskId=${task.id.take(8)} reason=no_calendar_found")
                return false
            }

            val completedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .apply { timeZone = TimeZone.getDefault() }
                .format(Date(completedAtMs))

            // All-day event: store as UTC midnight → midnight+1day
            val utcSdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val utcCal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                time = utcSdf.parse(completedDate)!!
            }
            val dtStart = utcCal.timeInMillis
            val dtEnd = dtStart + 24 * 60 * 60 * 1000L

            val desc = buildString {
                appendLine("Completed from Do In Place.")
                if (!task.description.isNullOrBlank()) {
                    appendLine()
                    appendLine("Task note: ${task.description}")
                }
                if (!task.activeFromDate.isNullOrBlank()) appendLine("Due date: ${task.activeFromDate}")
                appendLine("Completed: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(completedAtMs))}")
                if (task.recurrenceType != RecurrenceType.NONE) {
                    appendLine("Recurrence: ${task.recurrenceType.name.lowercase().replaceFirstChar { it.uppercase() }}")
                }
            }.trim()

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, "Done: ${task.title}")
                put(CalendarContract.Events.DESCRIPTION, desc)
                put(CalendarContract.Events.DTSTART, dtStart)
                put(CalendarContract.Events.DTEND, dtEnd)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = uri?.lastPathSegment?.toLongOrNull()
            if (eventId != null) {
                DiagLog.d("CALENDAR_DONE", "inserted taskId=${task.id.take(8)} eventId=$eventId")
                true
            } else {
                DiagLog.d("CALENDAR_DONE", "failed taskId=${task.id.take(8)} reason=null_uri")
                false
            }
        } catch (e: SecurityException) {
            DiagLog.d("CALENDAR_DONE", "failed taskId=${task.id.take(8)} reason=no_permission")
            false
        } catch (e: Exception) {
            DiagLog.d("CALENDAR_DONE", "failed taskId=${task.id.take(8)} reason=${e.message?.take(60)}")
            false
        }
    }

    private fun getPrimaryCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection,
                "${CalendarContract.Calendars.ACCOUNT_TYPE} != ?",
                arrayOf("com.android.exchange"),
                "${CalendarContract.Calendars.IS_PRIMARY} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
                else null
            }
        } catch (e: Exception) {
            DiagLog.d("CALENDAR_DONE", "query failed reason=${e.message?.take(40)}")
            null
        }
    }
}
