package com.davoyans.doinplace.util

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.LocalDate

class RecurrenceCalculatorTest {

    @Test
    fun monthlyCreationUsesCurrentMonthWhenStillUpcoming() {
        val actual = RecurrenceCalculator.firstMonthlyOccurrence(
            dayOfMonth = 15,
            from = LocalDate.of(2026, 6, 10)
        )

        assertEquals(LocalDate.of(2026, 6, 15), actual)
    }

    @Test
    fun monthlyCreationFallsBackToLastDayOfMonth() {
        val actual = RecurrenceCalculator.firstMonthlyOccurrence(
            dayOfMonth = 31,
            from = LocalDate.of(2026, 2, 10)
        )

        assertEquals(LocalDate.of(2026, 2, 28), actual)
    }

    @Test
    fun yearlyCreationKeepsThisYearWhenStillUpcoming() {
        val actual = RecurrenceCalculator.firstYearlyOccurrence(
            month = 9,
            day = 1,
            from = LocalDate.of(2026, 6, 14)
        )

        assertEquals(LocalDate.of(2026, 9, 1), actual)
    }

    @Test
    fun yearlyCreationFallsBackFromLeapDay() {
        val actual = RecurrenceCalculator.firstYearlyOccurrence(
            month = 2,
            day = 29,
            from = LocalDate.of(2027, 1, 10)
        )

        assertEquals(LocalDate.of(2027, 2, 28), actual)
    }
}
