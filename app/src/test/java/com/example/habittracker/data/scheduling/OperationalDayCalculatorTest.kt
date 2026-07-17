package com.example.habittracker.data.scheduling

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class OperationalDayCalculatorTest {
    private val calculator = OperationalDayCalculator(rolloverTime = LocalTime.of(3, 0))

    @Test
    fun beforeRolloverCountsAsPreviousOperationalDay() {
        val operationalDate = calculator.today(LocalDateTime.of(2026, 5, 20, 2, 59))

        assertEquals(LocalDate.of(2026, 5, 19), operationalDate)
    }

    @Test
    fun atRolloverCountsAsCurrentOperationalDay() {
        val operationalDate = calculator.today(LocalDateTime.of(2026, 5, 20, 3, 0))

        assertEquals(LocalDate.of(2026, 5, 20), operationalDate)
    }

    @Test
    fun customRolloverTimeControlsOperationalDayBoundary() {
        val customCalculator = OperationalDayCalculator(rolloverTime = LocalTime.of(5, 30))

        assertEquals(
            LocalDate.of(2026, 5, 19),
            customCalculator.today(LocalDateTime.of(2026, 5, 20, 5, 29)),
        )
        assertEquals(
            LocalDate.of(2026, 5, 20),
            customCalculator.today(LocalDateTime.of(2026, 5, 20, 5, 30)),
        )
    }
}
