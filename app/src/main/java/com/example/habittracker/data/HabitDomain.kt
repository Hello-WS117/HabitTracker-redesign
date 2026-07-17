package com.example.habittracker.data

import java.time.DayOfWeek
import java.time.LocalDate

enum class TaskType {
    SIMPLE_HABIT,
    QUICK_ONE_TIME,
    INTERVAL,
    WEEKDAY_BASED,
    SEQUENCE_ROUTINE,
    LONG_TERM,
}

enum class TaskTimeOfDay {
    GENERAL,
    MORNING,
    NOON,
    EVENING,
}

enum class RuleType {
    DAILY,
    EVERY_X_DAYS,
    EVERY_X_WEEKS,
    EVERY_X_MONTHS,
    EVERY_X_YEARS,
    WEEKDAYS,
    SEQUENCE,
}

enum class SkipBlockedDaysBehavior {
    SKIP_BLOCKED_DAY,
    MOVE_TO_NEXT_VALID_DAY,
    ASK_WHEN_NEEDED,
}

enum class NoActionBehavior {
    MARK_MISSED,
    AUTO_SKIP,
    AUTO_PUSH,
}

enum class ExerciseRequirement {
    REQUIRED,
    CONDITIONAL,
}

enum class ExerciseCheckStatus {
    PENDING,
    COMPLETED,
    NOT_NEEDED,
}

enum class PhaseAdvanceMode {
    AUTOMATIC,
    MANUAL,
}

enum class RoutinePhaseStatus {
    UPCOMING,
    ACTIVE,
    COMPLETED,
}

enum class LongTermRecurrenceAnchor {
    COMPLETION_DATE,
    DUE_DATE,
}

enum class CycleRestartBehavior {
    OFF,
    SUGGEST_RESTART,
    AUTO_RESTART,
}

enum class CycleRestartTiming {
    TODAY,
    TOMORROW,
    NEXT_VALID_DAY,
}

enum class OccurrenceStatus {
    PENDING,
    COMPLETED,
    SKIPPED,
    MISSED,
    SHIFTED,
}

enum class LogAction {
    COMPLETED,
    SKIPPED,
    MARKED_MISSED,
    SHIFTED_FORWARD,
    RESTORED_FROM_BACKUP,
    EDITED,
}

data class TaskStats(
    val currentStreak: Int,
    val longestStreak: Int,
    val completionPercentage: Int,
    val totalCompleted: Int,
    val totalSkipped: Int,
    val totalMissed: Int,
    val totalShifted: Int,
    val pastTotal: Int,
    val skipRate: Int,
    val missRate: Int,
)

data class GenerationRequest(
    val taskId: Long,
    val recurrenceRuleId: Long,
    val taskType: TaskType,
    val ruleType: RuleType,
    val intervalDays: Int?,
    val weekdays: Set<DayOfWeek>,
    val blockedDays: Set<DayOfWeek>,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val skipBlockedDaysBehavior: SkipBlockedDaysBehavior,
    val sequenceItems: List<Long> = emptyList(),
    val sequenceStartIndex: Int = 0,
)
