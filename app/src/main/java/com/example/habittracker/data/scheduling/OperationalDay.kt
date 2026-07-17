package com.example.habittracker.data.scheduling

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class OperationalDayCalculator(
    private val rolloverTime: LocalTime = LocalTime.of(3, 0),
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun today(now: LocalDateTime = LocalDateTime.now(clock)): LocalDate {
        return if (now.toLocalTime().isBefore(rolloverTime)) {
            now.toLocalDate().minusDays(1)
        } else {
            now.toLocalDate()
        }
    }

    fun operationalDateForCompletion(
        scheduledOperationalDate: LocalDate,
        completedAt: LocalDateTime = LocalDateTime.now(clock),
    ): LocalDate {
        val completionOperationalDate = today(completedAt)
        return if (completionOperationalDate == scheduledOperationalDate) {
            scheduledOperationalDate
        } else {
            scheduledOperationalDate
        }
    }
}
