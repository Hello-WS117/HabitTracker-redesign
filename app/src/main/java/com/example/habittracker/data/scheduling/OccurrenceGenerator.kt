package com.example.habittracker.data.scheduling

import com.example.habittracker.data.GenerationRequest
import com.example.habittracker.data.RuleType
import com.example.habittracker.data.SkipBlockedDaysBehavior
import com.example.habittracker.data.TaskType
import com.example.habittracker.data.local.ScheduledOccurrenceEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class OccurrenceGenerator {
    fun generate(
        request: GenerationRequest,
        fromDate: LocalDate,
        throughDate: LocalDate,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<ScheduledOccurrenceEntity> {
        if (throughDate.isBefore(fromDate)) return emptyList()
        if (request.ruleType == RuleType.SEQUENCE) {
            return generateSequence(request, fromDate, throughDate, now)
        }

        val occurrences = mutableListOf<ScheduledOccurrenceEntity>()
        var cursor = maxOf(fromDate, request.startDate)

        while (!cursor.isAfter(throughDate)) {
            val withinEndDate = request.endDate == null || !cursor.isAfter(request.endDate)
            if (withinEndDate && matchesRule(request, cursor)) {
                val scheduledDate = dateAfterBlockedDayRule(
                    date = cursor,
                    blockedDays = request.blockedDays,
                    behavior = request.skipBlockedDaysBehavior,
                )
                val withinGeneratedRange = scheduledDate != null &&
                    !scheduledDate.isAfter(throughDate) &&
                    (request.endDate == null || !scheduledDate.isAfter(request.endDate))
                if (withinGeneratedRange) {
                    occurrences.add(
                        ScheduledOccurrenceEntity(
                            taskId = request.taskId,
                            recurrenceRuleId = request.recurrenceRuleId,
                            scheduledDate = scheduledDate!!,
                            operationalDate = scheduledDate,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
            }
            cursor = cursor.plusDays(1)
        }

        return occurrences.distinctBy { Triple(it.recurrenceRuleId, it.operationalDate, it.sequenceItemId) }
    }

    private fun generateSequence(
        request: GenerationRequest,
        fromDate: LocalDate,
        throughDate: LocalDate,
        now: LocalDateTime,
    ): List<ScheduledOccurrenceEntity> {
        val occurrences = mutableListOf<ScheduledOccurrenceEntity>()
        var cursor = maxOf(fromDate, request.startDate)
        var sequenceIndex = request.sequenceStartIndex
        val spacingDays = (request.intervalDays ?: 1).coerceAtLeast(1)

        while (!cursor.isAfter(throughDate)) {
            val withinEndDate = request.endDate == null || !cursor.isAfter(request.endDate)
            if (withinEndDate) {
                val scheduledDate = when (request.skipBlockedDaysBehavior) {
                    SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY -> nextValidDate(cursor, request.blockedDays)
                    SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY,
                    SkipBlockedDaysBehavior.ASK_WHEN_NEEDED,
                    -> cursor.takeIf { it.dayOfWeek !in request.blockedDays }
                }
                val withinGeneratedRange = scheduledDate != null &&
                    !scheduledDate.isAfter(throughDate) &&
                    (request.endDate == null || !scheduledDate.isAfter(request.endDate))
                if (withinGeneratedRange) {
                    occurrences.add(
                        ScheduledOccurrenceEntity(
                            taskId = request.taskId,
                            recurrenceRuleId = request.recurrenceRuleId,
                            scheduledDate = scheduledDate!!,
                            operationalDate = scheduledDate,
                            sequenceItemId = request.sequenceItems.getOrNull(sequenceIndex % request.sequenceItems.size.coerceAtLeast(1)),
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    sequenceIndex += 1
                    cursor = scheduledDate.plusDays(spacingDays.toLong())
                } else {
                    cursor = cursor.plusDays(1)
                }
            } else {
                cursor = cursor.plusDays(1)
            }
        }

        return occurrences.distinctBy { Triple(it.recurrenceRuleId, it.operationalDate, it.sequenceItemId) }
    }

    fun nextValidDate(startDate: LocalDate, blockedDays: Set<DayOfWeek>): LocalDate? {
        var cursor = startDate
        repeat(7) {
            if (cursor.dayOfWeek !in blockedDays) return cursor
            cursor = cursor.plusDays(1)
        }
        return null
    }

    private fun matchesRule(request: GenerationRequest, date: LocalDate): Boolean {
        return when (request.ruleType) {
            RuleType.DAILY -> true
            RuleType.EVERY_X_DAYS -> matchesEveryXDays(request, date)
            RuleType.EVERY_X_WEEKS -> matchesEveryXWeeks(request, date)
            RuleType.EVERY_X_MONTHS -> matchesEveryXMonths(request, date)
            RuleType.EVERY_X_YEARS -> matchesEveryXYears(request, date)
            RuleType.WEEKDAYS -> date.dayOfWeek in request.weekdays
            RuleType.SEQUENCE -> true
        }
    }

    private fun matchesEveryXDays(request: GenerationRequest, date: LocalDate): Boolean {
        if (request.taskType == TaskType.LONG_TERM) {
            if (date.isBefore(request.startDate)) return false
            val interval = request.intervalDays?.coerceAtLeast(1) ?: 1
            return ChronoUnit.DAYS.between(request.startDate, date) % interval == 0L
        }
        if (request.skipBlockedDaysBehavior == SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY) {
            if (date.isBefore(request.startDate)) return false
            val interval = request.intervalDays?.coerceAtLeast(2) ?: 2
            return ChronoUnit.DAYS.between(request.startDate, date) % interval == 0L
        }
        return matchesEveryXValidDays(request, date)
    }

    private fun matchesEveryXWeeks(request: GenerationRequest, date: LocalDate): Boolean {
        if (date.isBefore(request.startDate)) return false
        val interval = request.intervalDays?.coerceAtLeast(1) ?: 1
        val daysBetween = ChronoUnit.DAYS.between(request.startDate, date)
        return daysBetween >= 0 &&
            daysBetween % 7L == 0L &&
            (daysBetween / 7L) % interval == 0L
    }

    private fun matchesEveryXMonths(request: GenerationRequest, date: LocalDate): Boolean {
        if (date.isBefore(request.startDate)) return false
        val interval = request.intervalDays?.coerceAtLeast(1) ?: 1
        val monthOffset = (date.year - request.startDate.year) * 12L + (date.monthValue - request.startDate.monthValue)
        return monthOffset >= 0 &&
            monthOffset % interval == 0L &&
            request.startDate.plusMonths(monthOffset) == date
    }

    private fun matchesEveryXYears(request: GenerationRequest, date: LocalDate): Boolean {
        if (date.isBefore(request.startDate)) return false
        val interval = request.intervalDays?.coerceAtLeast(1) ?: 1
        val yearOffset = date.year - request.startDate.year
        return yearOffset >= 0 &&
            yearOffset % interval == 0 &&
            request.startDate.plusYears(yearOffset.toLong()) == date
    }

    private fun matchesEveryXValidDays(request: GenerationRequest, date: LocalDate): Boolean {
        if (date.isBefore(request.startDate) || date.dayOfWeek in request.blockedDays) return false
        val interval = request.intervalDays?.coerceAtLeast(2) ?: 2
        var validDayOffset = 0
        var cursor = request.startDate
        while (!cursor.isAfter(date)) {
            if (cursor.dayOfWeek !in request.blockedDays) {
                if (cursor == date) return validDayOffset % interval == 0
                validDayOffset += 1
            }
            cursor = cursor.plusDays(1)
        }
        return false
    }

    private fun dateAfterBlockedDayRule(
        date: LocalDate,
        blockedDays: Set<DayOfWeek>,
        behavior: SkipBlockedDaysBehavior,
    ): LocalDate? {
        if (date.dayOfWeek !in blockedDays) return date
        return when (behavior) {
            SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY,
            SkipBlockedDaysBehavior.ASK_WHEN_NEEDED,
            -> null
            SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY -> nextValidDate(date.plusDays(1), blockedDays)
        }
    }

}
