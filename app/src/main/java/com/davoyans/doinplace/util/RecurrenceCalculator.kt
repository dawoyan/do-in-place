package com.davoyans.doinplace.util

import com.davoyans.doinplace.data.model.RecurrenceType
import com.davoyans.doinplace.data.model.Task
import java.time.LocalDate
import java.time.YearMonth

object RecurrenceCalculator {

    fun firstMonthlyOccurrence(dayOfMonth: Int, from: LocalDate = LocalDate.now()): LocalDate {
        val currentMonthMax = YearMonth.of(from.year, from.month).lengthOfMonth()
        val candidate = from.withDayOfMonth(minOf(dayOfMonth, currentMonthMax))
        return if (candidate.isBefore(from)) nextMonthly(from, dayOfMonth) else candidate
    }

    fun firstYearlyOccurrence(month: Int, day: Int, from: LocalDate = LocalDate.now()): LocalDate {
        val currentYearMax = YearMonth.of(from.year, month).lengthOfMonth()
        val candidate = LocalDate.of(from.year, month, minOf(day, currentYearMax))
        return if (candidate.isBefore(from)) nextYearly(from, month, day) else candidate
    }

    fun nextMonthly(from: LocalDate, dayOfMonth: Int): LocalDate {
        val next = from.plusMonths(1)
        val maxDay = YearMonth.of(next.year, next.month).lengthOfMonth()
        return next.withDayOfMonth(minOf(dayOfMonth, maxDay))
    }

    fun nextYearly(from: LocalDate, month: Int, day: Int): LocalDate {
        val next = from.plusYears(1)
        val maxDay = YearMonth.of(next.year, month).lengthOfMonth()
        return LocalDate.of(next.year, month, minOf(day, maxDay))
    }

    fun nextOccurrenceDate(task: Task, completedAt: LocalDate = LocalDate.now()): LocalDate? {
        return when (task.recurrenceType) {
            RecurrenceType.NONE -> null
            RecurrenceType.MONTHLY -> {
                val day = task.recurrenceDayOfMonth ?: return null
                nextMonthly(completedAt, day)
            }
            RecurrenceType.YEARLY -> {
                val month = task.recurrenceMonth ?: return null
                val day = task.recurrenceDayOfMonth ?: return null
                nextYearly(completedAt, month, day)
            }
        }
    }
}
