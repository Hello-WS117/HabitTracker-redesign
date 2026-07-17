package com.example.habittracker.data.scheduling

import com.example.habittracker.data.GenerationRequest
import com.example.habittracker.data.RuleType
import com.example.habittracker.data.SkipBlockedDaysBehavior
import com.example.habittracker.data.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

class OccurrenceGeneratorTest {
    private val generator = OccurrenceGenerator()
    private val now = LocalDateTime.of(2026, 5, 20, 12, 0)

    @Test
    fun dailyRuleGeneratesEveryDayInRequestedWindow() {
        val request = baseRequest(
            ruleType = RuleType.DAILY,
            startDate = LocalDate.of(2026, 5, 20),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 20),
            throughDate = LocalDate.of(2026, 5, 24),
            now = now,
        )

        assertEquals(
            listOf("2026-05-20", "2026-05-21", "2026-05-22", "2026-05-23", "2026-05-24"),
            generated.map { it.operationalDate.toString() },
        )
    }

    @Test
    fun everyOtherDayUsesStartDateAnchor() {
        val request = baseRequest(
            ruleType = RuleType.EVERY_X_DAYS,
            intervalDays = 2,
            startDate = LocalDate.of(2026, 5, 1),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 1),
            throughDate = LocalDate.of(2026, 5, 7),
            now = now,
        )

        assertEquals(
            listOf("2026-05-01", "2026-05-03", "2026-05-05", "2026-05-07"),
            generated.map { it.operationalDate.toString() },
        )
    }

    @Test
    fun everyXDaysUsesStartDateAnchorWhenWindowStartsLater() {
        val request = baseRequest(
            ruleType = RuleType.EVERY_X_DAYS,
            intervalDays = 3,
            startDate = LocalDate.of(2026, 5, 1),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 6),
            throughDate = LocalDate.of(2026, 5, 14),
            now = now,
        )

        assertEquals(
            listOf("2026-05-07", "2026-05-10", "2026-05-13"),
            generated.map { it.operationalDate.toString() },
        )
    }

    @Test
    fun everyXMonthsUsesCalendarMonthAnchor() {
        val request = baseRequest(
            ruleType = RuleType.EVERY_X_MONTHS,
            taskType = TaskType.LONG_TERM,
            intervalDays = 6,
            startDate = LocalDate.of(2026, 6, 15),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 6, 1),
            throughDate = LocalDate.of(2027, 6, 30),
            now = now,
        )

        assertEquals(
            listOf("2026-06-15", "2026-12-15", "2027-06-15"),
            generated.map { it.operationalDate.toString() },
        )
    }

    @Test
    fun longTermEveryXDaysAllowsOneDayInterval() {
        val request = baseRequest(
            ruleType = RuleType.EVERY_X_DAYS,
            taskType = TaskType.LONG_TERM,
            intervalDays = 1,
            startDate = LocalDate.of(2026, 6, 15),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 6, 15),
            throughDate = LocalDate.of(2026, 6, 18),
            now = now,
        )

        assertEquals(
            listOf("2026-06-15", "2026-06-16", "2026-06-17", "2026-06-18"),
            generated.map { it.operationalDate.toString() },
        )
    }

    @Test
    fun everyXWeeksUsesCalendarWeekAnchor() {
        val request = baseRequest(
            ruleType = RuleType.EVERY_X_WEEKS,
            taskType = TaskType.LONG_TERM,
            intervalDays = 2,
            startDate = LocalDate.of(2026, 6, 15),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 6, 1),
            throughDate = LocalDate.of(2026, 7, 31),
            now = now,
        )

        assertEquals(
            listOf("2026-06-15", "2026-06-29", "2026-07-13", "2026-07-27"),
            generated.map { it.operationalDate.toString() },
        )
    }

    @Test
    fun everyXYearsUsesCalendarYearAnchor() {
        val request = baseRequest(
            ruleType = RuleType.EVERY_X_YEARS,
            taskType = TaskType.LONG_TERM,
            intervalDays = 2,
            startDate = LocalDate.of(2026, 6, 15),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 1, 1),
            throughDate = LocalDate.of(2031, 12, 31),
            now = now,
        )

        assertEquals(
            listOf("2026-06-15", "2028-06-15", "2030-06-15"),
            generated.map { it.operationalDate.toString() },
        )
    }

    @Test
    fun everyXDaysCountsOnlyValidNonBlockedDays() {
        val request = baseRequest(
            ruleType = RuleType.EVERY_X_DAYS,
            intervalDays = 2,
            blockedDays = setOf(DayOfWeek.SUNDAY),
            skipBlockedDaysBehavior = SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY,
            startDate = LocalDate.of(2026, 5, 23),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 23),
            throughDate = LocalDate.of(2026, 5, 29),
            now = now,
        )

        assertEquals(
            listOf("2026-05-23", "2026-05-26", "2026-05-28"),
            generated.map { it.operationalDate.toString() },
        )
        assertFalse(generated.any { it.operationalDate.dayOfWeek == DayOfWeek.SUNDAY })
    }

    @Test
    fun everyXDaysMoveBehaviorMovesBlockedAnchorDatesToNextValidDay() {
        val request = baseRequest(
            ruleType = RuleType.EVERY_X_DAYS,
            intervalDays = 2,
            blockedDays = setOf(DayOfWeek.SUNDAY),
            skipBlockedDaysBehavior = SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY,
            startDate = LocalDate.of(2026, 5, 22),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 22),
            throughDate = LocalDate.of(2026, 5, 28),
            now = now,
        )

        assertEquals(
            listOf("2026-05-22", "2026-05-25", "2026-05-26", "2026-05-28"),
            generated.map { it.operationalDate.toString() },
        )
        assertFalse(generated.any { it.operationalDate.dayOfWeek == DayOfWeek.SUNDAY })
    }

    @Test
    fun weekdayRuleOnlyGeneratesSelectedDays() {
        val request = baseRequest(
            ruleType = RuleType.WEEKDAYS,
            weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            startDate = LocalDate.of(2026, 5, 18),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 18),
            throughDate = LocalDate.of(2026, 5, 24),
            now = now,
        )

        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            generated.map { it.operationalDate.dayOfWeek },
        )
    }

    @Test
    fun blockedSundayIsSkippedForSimpleHabit() {
        val request = baseRequest(
            ruleType = RuleType.DAILY,
            blockedDays = setOf(DayOfWeek.SUNDAY),
            skipBlockedDaysBehavior = SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY,
            startDate = LocalDate.of(2026, 5, 17),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 17),
            throughDate = LocalDate.of(2026, 5, 19),
            now = now,
        )

        assertFalse(generated.any { it.operationalDate.dayOfWeek == DayOfWeek.SUNDAY })
        assertEquals(listOf("2026-05-18", "2026-05-19"), generated.map { it.operationalDate.toString() })
    }

    @Test
    fun askWhenNeededDoesNotAutomaticallyMoveBlockedOccurrences() {
        val request = baseRequest(
            ruleType = RuleType.DAILY,
            blockedDays = setOf(DayOfWeek.SUNDAY),
            skipBlockedDaysBehavior = SkipBlockedDaysBehavior.ASK_WHEN_NEEDED,
            startDate = LocalDate.of(2026, 5, 17),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 17),
            throughDate = LocalDate.of(2026, 5, 19),
            now = now,
        )

        assertEquals(listOf("2026-05-18", "2026-05-19"), generated.map { it.operationalDate.toString() })
    }

    @Test
    fun sequenceMovesAcrossBlockedSundayWithoutDuplicatingMonday() {
        val request = baseRequest(
            ruleType = RuleType.SEQUENCE,
            taskType = TaskType.SEQUENCE_ROUTINE,
            blockedDays = setOf(DayOfWeek.SUNDAY),
            skipBlockedDaysBehavior = SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY,
            sequenceItems = listOf(11, 12, 13),
            startDate = LocalDate.of(2026, 5, 16),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 16),
            throughDate = LocalDate.of(2026, 5, 20),
            now = now,
        )

        assertEquals(
            listOf("2026-05-16", "2026-05-18", "2026-05-19", "2026-05-20"),
            generated.map { it.operationalDate.toString() },
        )
        assertEquals(listOf(11L, 12L, 13L, 11L), generated.map { it.sequenceItemId })
    }

    @Test
    fun sequenceRoutineRepeatsItemsInOrderAcrossFullCycle() {
        val request = baseRequest(
            ruleType = RuleType.SEQUENCE,
            taskType = TaskType.SEQUENCE_ROUTINE,
            sequenceItems = listOf(11, 12, 13, 14, 15),
            startDate = LocalDate.of(2026, 5, 20),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 20),
            throughDate = LocalDate.of(2026, 5, 26),
            now = now,
        )

        assertEquals(
            listOf(11L, 12L, 13L, 14L, 15L, 11L, 12L),
            generated.map { it.sequenceItemId },
        )
    }

    @Test
    fun sequenceRoutineUsesConfiguredDaysBetweenItems() {
        val request = baseRequest(
            ruleType = RuleType.SEQUENCE,
            taskType = TaskType.SEQUENCE_ROUTINE,
            intervalDays = 2,
            sequenceItems = listOf(11, 12, 13),
            startDate = LocalDate.of(2026, 5, 20),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 20),
            throughDate = LocalDate.of(2026, 5, 27),
            now = now,
        )

        assertEquals(
            listOf("2026-05-20", "2026-05-22", "2026-05-24", "2026-05-26"),
            generated.map { it.operationalDate.toString() },
        )
        assertEquals(listOf(11L, 12L, 13L, 11L), generated.map { it.sequenceItemId })
    }

    @Test
    fun generationStopsAtEndDate() {
        val request = baseRequest(
            ruleType = RuleType.DAILY,
            startDate = LocalDate.of(2026, 5, 20),
            endDate = LocalDate.of(2026, 5, 22),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 20),
            throughDate = LocalDate.of(2026, 5, 25),
            now = now,
        )

        assertEquals(
            listOf("2026-05-20", "2026-05-21", "2026-05-22"),
            generated.map { it.operationalDate.toString() },
        )
    }

    @Test
    fun movedBlockedDayDoesNotGenerateAfterEndDate() {
        val request = baseRequest(
            ruleType = RuleType.DAILY,
            blockedDays = setOf(DayOfWeek.SUNDAY),
            skipBlockedDaysBehavior = SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY,
            startDate = LocalDate.of(2026, 5, 17),
            endDate = LocalDate.of(2026, 5, 17),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 17),
            throughDate = LocalDate.of(2026, 5, 18),
            now = now,
        )

        assertTrue(generated.isEmpty())
    }

    @Test
    fun moveBlockedDayWithNoValidWeekdayGeneratesNothing() {
        val request = baseRequest(
            ruleType = RuleType.DAILY,
            blockedDays = DayOfWeek.values().toSet(),
            skipBlockedDaysBehavior = SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY,
            startDate = LocalDate.of(2026, 5, 20),
        )

        val generated = generator.generate(
            request = request,
            fromDate = LocalDate.of(2026, 5, 20),
            throughDate = LocalDate.of(2026, 5, 24),
            now = now,
        )

        assertTrue(generated.isEmpty())
        assertNull(generator.nextValidDate(LocalDate.of(2026, 5, 20), DayOfWeek.values().toSet()))
    }

    private fun baseRequest(
        ruleType: RuleType,
        taskType: TaskType = TaskType.SIMPLE_HABIT,
        intervalDays: Int? = null,
        weekdays: Set<DayOfWeek> = emptySet(),
        blockedDays: Set<DayOfWeek> = emptySet(),
        skipBlockedDaysBehavior: SkipBlockedDaysBehavior = SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY,
        sequenceItems: List<Long> = emptyList(),
        startDate: LocalDate,
        endDate: LocalDate? = null,
    ): GenerationRequest {
        return GenerationRequest(
            taskId = 1,
            recurrenceRuleId = 2,
            taskType = taskType,
            ruleType = ruleType,
            intervalDays = intervalDays,
            weekdays = weekdays,
            blockedDays = blockedDays,
            startDate = startDate,
            endDate = endDate,
            skipBlockedDaysBehavior = skipBlockedDaysBehavior,
            sequenceItems = sequenceItems,
        )
    }
}
