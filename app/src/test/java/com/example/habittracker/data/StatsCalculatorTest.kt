package com.example.habittracker.data

import com.example.habittracker.data.local.ScheduledOccurrenceEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class StatsCalculatorTest {
    private val calculator = StatsCalculator()
    private val now = LocalDateTime.of(2026, 5, 20, 12, 0)

    @Test
    fun statsSeparateCompletedSkippedAndMissedCounts() {
        val occurrences = listOf(
            occurrence(LocalDate.of(2026, 5, 16), OccurrenceStatus.COMPLETED),
            occurrence(LocalDate.of(2026, 5, 17), OccurrenceStatus.SKIPPED),
            occurrence(LocalDate.of(2026, 5, 18), OccurrenceStatus.MISSED),
            occurrence(LocalDate.of(2026, 5, 19), OccurrenceStatus.COMPLETED),
        )

        val stats = calculator.calculate(occurrences, LocalDate.of(2026, 5, 20))

        assertEquals(2, stats.totalCompleted)
        assertEquals(1, stats.totalSkipped)
        assertEquals(1, stats.totalMissed)
        assertEquals(50, stats.completionPercentage)
        assertEquals(25, stats.skipRate)
        assertEquals(25, stats.missRate)
        assertEquals(1, stats.currentStreak)
        assertEquals(1, stats.longestStreak)
    }

    @Test
    fun currentPendingOccurrenceDoesNotReduceHistoricalCompletionRate() {
        val occurrences = listOf(
            occurrence(LocalDate.of(2026, 5, 19), OccurrenceStatus.COMPLETED),
            occurrence(LocalDate.of(2026, 5, 20), OccurrenceStatus.PENDING),
        )

        val stats = calculator.calculate(occurrences, LocalDate.of(2026, 5, 20))

        assertEquals(1, stats.totalCompleted)
        assertEquals(100, stats.completionPercentage)
    }

    @Test
    fun shiftedOccurrencesAreCountedSeparately() {
        val occurrences = listOf(
            occurrence(LocalDate.of(2026, 5, 18), OccurrenceStatus.SHIFTED),
            occurrence(LocalDate.of(2026, 5, 19), OccurrenceStatus.PENDING, isShifted = true),
        )

        val stats = calculator.calculate(occurrences, LocalDate.of(2026, 5, 20))

        assertEquals(1, stats.totalShifted)
        assertEquals(2, stats.pastTotal)
        assertEquals(0, stats.totalCompleted)
        assertEquals(0, stats.completionPercentage)
    }

    @Test
    fun missedShiftedOccurrencesCountAsBothMissedAndShifted() {
        val occurrences = listOf(
            occurrence(LocalDate.of(2026, 5, 19), OccurrenceStatus.MISSED, isShifted = true),
        )

        val stats = calculator.calculate(occurrences, LocalDate.of(2026, 5, 20))

        assertEquals(1, stats.totalMissed)
        assertEquals(1, stats.totalShifted)
        assertEquals(100, stats.missRate)
    }

    @Test
    fun longestStreakCanBeLongerThanCurrentStreak() {
        val occurrences = listOf(
            occurrence(LocalDate.of(2026, 5, 14), OccurrenceStatus.COMPLETED),
            occurrence(LocalDate.of(2026, 5, 15), OccurrenceStatus.COMPLETED),
            occurrence(LocalDate.of(2026, 5, 16), OccurrenceStatus.COMPLETED),
            occurrence(LocalDate.of(2026, 5, 17), OccurrenceStatus.MISSED),
            occurrence(LocalDate.of(2026, 5, 18), OccurrenceStatus.COMPLETED),
            occurrence(LocalDate.of(2026, 5, 19), OccurrenceStatus.COMPLETED),
            occurrence(LocalDate.of(2026, 5, 20), OccurrenceStatus.PENDING),
        )

        val stats = calculator.calculate(occurrences, LocalDate.of(2026, 5, 20))

        assertEquals(2, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
    }

    private fun occurrence(
        date: LocalDate,
        status: OccurrenceStatus,
        isShifted: Boolean = false,
    ): ScheduledOccurrenceEntity {
        return ScheduledOccurrenceEntity(
            taskId = 1,
            recurrenceRuleId = 1,
            scheduledDate = date,
            operationalDate = date,
            status = status,
            isShifted = isShifted,
            createdAt = now,
            updatedAt = now,
        )
    }
}
