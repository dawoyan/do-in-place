package com.davoyans.doinplace.util

import com.davoyans.doinplace.data.model.RecurrenceType
import com.davoyans.doinplace.data.model.Task

const val RECURRING_DUE_SOON_DAYS = 7L

fun Task.isRecurringTask(): Boolean =
    isEverywhere && recurrenceType != RecurrenceType.NONE

fun Task.isStandaloneEverywhereTask(): Boolean =
    isEverywhere && recurrenceType == RecurrenceType.NONE

fun canSaveRecurringTask(
    title: String,
    note: String,
    recurrenceType: RecurrenceType,
    dayOfMonth: Int?,
    month: Int?,
    day: Int?
): Boolean {
    val hasContent = title.isNotBlank() || note.isNotBlank()
    if (!hasContent) return false
    return when (recurrenceType) {
        RecurrenceType.MONTHLY -> dayOfMonth in 1..31
        RecurrenceType.YEARLY -> month in 1..12 && day in 1..31
        RecurrenceType.NONE -> false
    }
}
