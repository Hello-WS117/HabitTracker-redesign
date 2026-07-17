package com.example.habittracker.data

import com.example.habittracker.data.local.ScheduledOccurrenceEntity
import java.time.LocalDate
import kotlin.math.roundToInt

class StatsCalculator {
    fun calculate(
        occurrences: List<ScheduledOccurrenceEntity>,
        currentOperationalDate: LocalDate,
    ): TaskStats {
        val past = occurrences
            .filter {
                it.operationalDate.isBefore(currentOperationalDate) ||
                    (it.operationalDate == currentOperationalDate && it.status != OccurrenceStatus.PENDING)
            }
            .sortedBy { it.operationalDate }
        val total = past.size.coerceAtLeast(1)
        val completed = past.count { it.status == OccurrenceStatus.COMPLETED }
        val skipped = past.count { it.status == OccurrenceStatus.SKIPPED }
        val missed = past.count { it.status == OccurrenceStatus.MISSED }
        val shifted = past.count {
            it.status == OccurrenceStatus.SHIFTED || (it.status == OccurrenceStatus.MISSED && it.isShifted)
        }

        return TaskStats(
            currentStreak = currentStreak(past),
            longestStreak = longestStreak(past),
            completionPercentage = percentage(completed, total),
            totalCompleted = completed,
            totalSkipped = skipped,
            totalMissed = missed,
            totalShifted = shifted,
            pastTotal = past.size,
            skipRate = percentage(skipped, total),
            missRate = percentage(missed, total),
        )
    }

    private fun currentStreak(occurrences: List<ScheduledOccurrenceEntity>): Int {
        var streak = 0
        for (occurrence in occurrences.sortedByDescending { it.operationalDate }) {
            when (occurrence.status) {
                OccurrenceStatus.COMPLETED -> streak += 1
                OccurrenceStatus.PENDING -> Unit
                else -> return streak
            }
        }
        return streak
    }

    private fun longestStreak(occurrences: List<ScheduledOccurrenceEntity>): Int {
        var current = 0
        var longest = 0
        for (occurrence in occurrences) {
            when (occurrence.status) {
                OccurrenceStatus.COMPLETED -> {
                    current += 1
                    longest = maxOf(longest, current)
                }
                OccurrenceStatus.PENDING -> Unit
                else -> current = 0
            }
        }
        return longest
    }

    private fun percentage(numerator: Int, denominator: Int): Int {
        return ((numerator.toFloat() / denominator.toFloat()) * 100).roundToInt()
    }
}
