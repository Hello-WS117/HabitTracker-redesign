package com.example.habittracker.backup

import com.example.habittracker.data.CycleRestartBehavior
import com.example.habittracker.data.CycleRestartTiming
import com.example.habittracker.data.ExerciseCheckStatus
import com.example.habittracker.data.ExerciseRequirement
import com.example.habittracker.data.LogAction
import com.example.habittracker.data.NoActionBehavior
import com.example.habittracker.data.OccurrenceStatus
import com.example.habittracker.data.PhaseAdvanceMode
import com.example.habittracker.data.RoutinePhaseStatus
import com.example.habittracker.data.RuleType
import com.example.habittracker.data.SkipBlockedDaysBehavior
import com.example.habittracker.data.TaskTimeOfDay
import com.example.habittracker.data.TaskType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

object BackupValidator {
    fun validate(backup: HabitBackupV1): String? {
        if (backup.schemaVersion != BACKUP_SCHEMA_VERSION) {
            return "Unsupported backup schema ${backup.schemaVersion}"
        }
        if (backup.tasks.any { it.id <= 0 || it.name.isBlank() }) {
            return "Backup contains an invalid task"
        }
        if (duplicateIds(backup.tasks.map { it.id })) return "Backup contains duplicate task IDs"
        if (duplicateIds(backup.recurrenceRules.map { it.id })) return "Backup contains duplicate recurrence rule IDs"
        if (duplicateIds(backup.scheduledOccurrences.map { it.id })) return "Backup contains duplicate occurrence IDs"
        if (duplicateIds(backup.completionLogs.map { it.id })) return "Backup contains duplicate log IDs"
        if (duplicateIds(backup.workoutSequences.map { it.id })) return "Backup contains duplicate sequence IDs"
        if (duplicateIds(backup.sequenceItems.map { it.id })) return "Backup contains duplicate sequence item IDs"
        if (duplicateIds(backup.sequenceExercises.map { it.id })) return "Backup contains duplicate exercise IDs"
        if (duplicateIds(backup.occurrenceExerciseChecks.map { it.id })) return "Backup contains duplicate exercise check IDs"
        if (duplicateIds(backup.routinePlans.map { it.id })) return "Backup contains duplicate routine plan IDs"
        if (duplicateIds(backup.routinePhases.map { it.id })) return "Backup contains duplicate routine phase IDs"
        if (duplicateIds(backup.cycleGroups.map { it.id })) return "Backup contains duplicate cycle IDs"
        if (duplicateIds(backup.cycleTaskMemberships.map { it.id })) return "Backup contains duplicate cycle membership IDs"
        if (duplicateIds(backup.cycleLogs.map { it.id })) return "Backup contains duplicate cycle log IDs"
        if (
            backup.scheduledOccurrences
                .groupBy {
                    OccurrenceScheduleKey(
                        taskId = it.taskId,
                        recurrenceRuleId = it.recurrenceRuleId,
                        operationalDate = it.operationalDate,
                        sequenceItemId = it.sequenceItemId,
                    )
                }
                .any { it.value.size > 1 }
        ) {
            return "Backup contains duplicate scheduled occurrences"
        }
        if (
            backup.scheduledOccurrences
                .groupBy { it.recurrenceRuleId to it.operationalDate }
                .any { it.value.size > 1 }
        ) {
            return "Backup contains duplicate scheduled occurrences"
        }
        if (backup.recurrenceRules.any { it.id <= 0 }) return "Backup contains an invalid recurrence rule ID"
        if (backup.scheduledOccurrences.any { it.id <= 0 }) return "Backup contains an invalid occurrence ID"
        if (backup.completionLogs.any { it.id <= 0 }) return "Backup contains an invalid log ID"
        if (backup.workoutSequences.any { it.id <= 0 }) return "Backup contains an invalid sequence ID"
        if (backup.sequenceItems.any { it.id <= 0 || it.position < 0 }) return "Backup contains an invalid sequence item"
        if (backup.sequenceExercises.any { it.id <= 0 || it.position < 0 || it.name.isBlank() }) {
            return "Backup contains an invalid exercise"
        }
        if (backup.occurrenceExerciseChecks.any { it.id <= 0 }) return "Backup contains an invalid exercise check"
        if (backup.routinePlans.any { it.id <= 0 || it.name.isBlank() }) return "Backup contains an invalid routine plan"
        if (backup.routinePhases.any { it.id <= 0 || it.position < 0 || it.minimumDays < 1 }) {
            return "Backup contains an invalid routine phase"
        }
        if (backup.cycleGroups.any { it.id <= 0 || it.name.isBlank() }) return "Backup contains an invalid cycle"
        if (backup.cycleTaskMemberships.any { it.id <= 0 || it.startOffsetDays < 0 }) {
            return "Backup contains an invalid cycle membership"
        }
        if (backup.cycleLogs.any { it.id <= 0 || it.note.isBlank() }) return "Backup contains an invalid cycle log"

        parseValidationError(backup)?.let { return it }

        val taskIds = backup.tasks.map { it.id }.toSet()
        val cycleIds = backup.cycleGroups.map { it.id }.toSet()
        val taskTypeById = backup.tasks.associate { it.id to it.taskType }
        val ruleIds = backup.recurrenceRules.map { it.id }.toSet()
        val ruleTaskIdById = backup.recurrenceRules.associate { it.id to it.taskId }
        val occurrenceIds = backup.scheduledOccurrences.map { it.id }.toSet()
        val occurrenceById = backup.scheduledOccurrences.associateBy { it.id }
        val occurrenceTaskIdById = backup.scheduledOccurrences.associate { it.id to it.taskId }
        val sequenceIds = backup.workoutSequences.map { it.id }.toSet()
        val sequenceTaskIdById = backup.workoutSequences.associate { it.id to it.taskId }
        val sequenceTaskIds = backup.workoutSequences.map { it.taskId }.toSet()
        val sequenceItemsBySequenceId = backup.sequenceItems.groupBy { it.sequenceId }
        val sequenceItemIdsReferencedByOccurrences = backup.scheduledOccurrences.mapNotNull { it.sequenceItemId }.toSet()
        val sequenceItemTaskIdById = backup.sequenceItems.mapNotNull { item ->
            sequenceTaskIdById[item.sequenceId]?.let { taskId -> item.id to taskId }
        }.toMap()
        val sequenceItemIds = backup.sequenceItems.map { it.id }.toSet()
        val sequenceItemById = backup.sequenceItems.associateBy { it.id }
        val sequenceExerciseIds = backup.sequenceExercises.map { it.id }.toSet()
        val sequenceExerciseById = backup.sequenceExercises.associateBy { it.id }
        val routinePlanIds = backup.routinePlans.map { it.id }.toSet()

        if (backup.cycleGroups.any { it.durationDays < 1 || it.resetThresholdPercent !in 1..100 }) {
            return "Backup contains an invalid cycle threshold"
        }
        if (backup.cycleTaskMemberships.groupBy { it.taskId }.any { it.value.size > 1 }) {
            return "Backup contains a task in more than one cycle"
        }
        if (backup.cycleTaskMemberships.any { it.cycleGroupId !in cycleIds || it.taskId !in taskIds }) {
            return "Backup contains a cycle membership without a cycle or task"
        }
        if (backup.cycleLogs.any { it.cycleGroupId !in cycleIds }) return "Backup contains a cycle log without a cycle"
        if (backup.recurrenceRules.any { it.durationDays != null && it.durationDays < 1 }) {
            return "Backup contains an invalid duration"
        }
        if (backup.recurrenceRules.any { it.autoRestartResetThresholdPercent !in 1..100 }) {
            return "Backup contains an invalid auto-restart threshold"
        }
        if (
            backup.recurrenceRules.any {
                it.autoRestartBehavior != CycleRestartBehavior.OFF.name &&
                    it.durationDays == null
            }
        ) {
            return "Backup contains auto-restart settings without cycle timing"
        }
        if (backup.recurrenceRules.any { it.intervalDays != null && it.intervalDays < 1 }) {
            return "Backup contains an invalid recurrence interval"
        }
        if (
            backup.recurrenceRules.any {
                taskTypeById[it.taskId] == TaskType.LONG_TERM.name &&
                    it.ruleType in LONG_TERM_RULE_TYPE_NAMES &&
                    it.intervalDays == null
            }
        ) {
            return "Backup contains a long-term rule without an interval"
        }
        if (backup.recurrenceRules.any { it.ruleType in INTERVAL_RULE_TYPE_NAMES && it.intervalDays == null }) {
            return "Backup contains an interval rule without an interval"
        }
        if (
            backup.recurrenceRules.any {
                taskTypeById[it.taskId] != TaskType.LONG_TERM.name &&
                    it.ruleType == RuleType.EVERY_X_DAYS.name &&
                    (it.intervalDays ?: 0) < 2
            }
        ) {
            return "Backup contains an invalid recurrence interval"
        }
        if (backup.recurrenceRules.any { it.ruleType == RuleType.WEEKDAYS.name && it.weekdays.isEmpty() }) {
            return "Backup contains a weekday rule without weekdays"
        }
        if (backup.recurrenceRules.any { it.endDate != null && LocalDate.parse(it.endDate).isBefore(LocalDate.parse(it.startDate)) }) {
            return "Backup contains a recurrence rule with an invalid date range"
        }
        if (
            backup.recurrenceRules.any { rule ->
                val taskType = taskTypeById[rule.taskId]
                taskType != null && !ruleTypeMatchesTaskType(taskType, rule.ruleType)
            }
        ) {
            return "Backup contains a recurrence rule that does not match its task type"
        }
        if (backup.sequenceItems.groupBy { it.sequenceId to it.position }.any { it.value.size > 1 }) {
            return "Backup contains duplicate sequence positions"
        }
        if (backup.sequenceExercises.groupBy { it.sequenceItemId to it.position }.any { it.value.size > 1 }) {
            return "Backup contains duplicate exercise positions"
        }
        if (backup.occurrenceExerciseChecks.groupBy { it.occurrenceId to it.sequenceExerciseId }.any { it.value.size > 1 }) {
            return "Backup contains duplicate exercise checks"
        }
        if (backup.routinePhases.groupBy { it.routinePlanId to it.position }.any { it.value.size > 1 }) {
            return "Backup contains duplicate routine phase positions"
        }
        if (backup.routinePhases.groupBy { it.taskId }.any { it.value.size > 1 }) {
            return "Backup contains a task in more than one routine phase"
        }
        if (backup.recurrenceRules.any { it.taskId !in taskIds }) return "Backup contains a recurrence rule without a task"
        if (backup.recurrenceRules.any { it.startsAfterTaskId != null && it.startsAfterTaskId !in taskIds }) {
            return "Backup contains a cycle rule without a parent task"
        }
        if (backup.recurrenceRules.any { it.startsAfterTaskId == it.taskId }) {
            return "Backup contains a cycle rule that starts after itself"
        }
        if (backup.scheduledOccurrences.any { it.taskId !in taskIds || it.recurrenceRuleId !in ruleIds }) {
            return "Backup contains an occurrence without a task or rule"
        }
        if (backup.scheduledOccurrences.any { ruleTaskIdById[it.recurrenceRuleId] != it.taskId }) {
            return "Backup contains an occurrence with a rule from another task"
        }
        if (backup.workoutSequences.any { it.taskId !in taskIds }) return "Backup contains a sequence without a task"
        if (
            backup.workoutSequences.any { sequence ->
                taskTypeById[sequence.taskId] != TaskType.SEQUENCE_ROUTINE.name &&
                    sequenceItemsBySequenceId[sequence.id].orEmpty().none { it.id in sequenceItemIdsReferencedByOccurrences }
            }
        ) {
            return "Backup contains an unused sequence for a non-sequence task"
        }
        if (backup.tasks.any { it.taskType == TaskType.SEQUENCE_ROUTINE.name && it.id !in sequenceTaskIds }) {
            return "Backup contains a sequence task without a sequence"
        }
        if (backup.sequenceItems.any { it.sequenceId !in sequenceIds }) return "Backup contains a sequence item without a sequence"
        if (backup.sequenceExercises.any { it.sequenceItemId !in sequenceItemIds }) {
            return "Backup contains an exercise without a workout day"
        }
        if (backup.workoutSequences.any { sequenceItemsBySequenceId[it.id].isNullOrEmpty() }) {
            return "Backup contains a sequence without items"
        }
        if (backup.scheduledOccurrences.any { it.sequenceItemId != null && it.sequenceItemId !in sequenceItemIds }) {
            return "Backup contains an occurrence with an unknown sequence item"
        }
        if (backup.scheduledOccurrences.any { taskTypeById[it.taskId] == TaskType.SEQUENCE_ROUTINE.name && it.sequenceItemId == null }) {
            return "Backup contains a sequence occurrence without an item"
        }
        if (backup.scheduledOccurrences.any { it.sequenceItemId != null && sequenceItemTaskIdById[it.sequenceItemId] != it.taskId }) {
            return "Backup contains an occurrence with a sequence item from another task"
        }
        if (
            backup.occurrenceExerciseChecks.any { check ->
                check.occurrenceId !in occurrenceIds || check.sequenceExerciseId !in sequenceExerciseIds
            }
        ) {
            return "Backup contains an exercise check without an occurrence or exercise"
        }
        if (
            backup.occurrenceExerciseChecks.any { check ->
                val occurrenceItemId = occurrenceById[check.occurrenceId]?.sequenceItemId
                val exerciseItemId = sequenceExerciseById[check.sequenceExerciseId]?.sequenceItemId
                occurrenceItemId == null || occurrenceItemId != exerciseItemId || sequenceItemById[exerciseItemId] == null
            }
        ) {
            return "Backup contains an exercise check for another workout day"
        }
        if (backup.routinePhases.any { it.routinePlanId !in routinePlanIds || it.taskId !in taskIds }) {
            return "Backup contains a routine phase without a plan or task"
        }
        if (backup.routinePlans.any { plan -> backup.routinePhases.none { it.routinePlanId == plan.id } }) {
            return "Backup contains a routine plan without phases"
        }
        if (backup.completionLogs.any { it.taskId !in taskIds }) return "Backup contains a log without a task"
        if (backup.completionLogs.any { it.occurrenceId != null && it.occurrenceId !in occurrenceIds }) {
            return "Backup contains a log without an occurrence"
        }
        if (backup.completionLogs.any { it.occurrenceId != null && occurrenceTaskIdById[it.occurrenceId] != it.taskId }) {
            return "Backup contains a log with an occurrence from another task"
        }
        val statusChangingLogActions = setOf(
            LogAction.COMPLETED.name,
            LogAction.SKIPPED.name,
            LogAction.MARKED_MISSED.name,
            LogAction.SHIFTED_FORWARD.name,
        )
        if (backup.completionLogs.any { it.action in statusChangingLogActions && it.occurrenceId == null }) {
            return "Backup contains a status log without an occurrence"
        }
        return null
    }

    private fun parseValidationError(backup: HabitBackupV1): String? {
        return runCatching {
            backup.tasks.forEach {
                TaskType.valueOf(it.taskType)
                TaskTimeOfDay.valueOf(it.timeOfDay)
                NoActionBehavior.valueOf(it.noActionBehavior)
                it.blockedDays.forEach(DayOfWeek::valueOf)
                LocalDateTime.parse(it.createdAt)
                LocalDateTime.parse(it.updatedAt)
            }
            backup.recurrenceRules.forEach {
                RuleType.valueOf(it.ruleType)
                it.weekdays.forEach(DayOfWeek::valueOf)
                SkipBlockedDaysBehavior.valueOf(it.skipBlockedDaysBehavior)
                CycleRestartBehavior.valueOf(it.autoRestartBehavior)
                CycleRestartTiming.valueOf(it.autoRestartTiming)
                it.autoRestartBlockedDays.forEach(DayOfWeek::valueOf)
                LocalDate.parse(it.startDate)
                it.endDate?.let(LocalDate::parse)
                it.lastGeneratedDate?.let(LocalDate::parse)
                it.autoRestartCurrentStartDate?.let(LocalDate::parse)
                it.autoRestartLastRestartedAt?.let(LocalDateTime::parse)
                LocalDateTime.parse(it.createdAt)
                LocalDateTime.parse(it.updatedAt)
            }
            backup.scheduledOccurrences.forEach {
                LocalDate.parse(it.scheduledDate)
                LocalDate.parse(it.operationalDate)
                OccurrenceStatus.valueOf(it.status)
                it.originalDate?.let(LocalDate::parse)
                LocalDateTime.parse(it.createdAt)
                LocalDateTime.parse(it.updatedAt)
            }
            backup.completionLogs.forEach {
                LogAction.valueOf(it.action)
                LocalDateTime.parse(it.timestamp)
                LocalDate.parse(it.operationalDate)
                LocalDateTime.parse(it.createdAt)
            }
            backup.workoutSequences.forEach {
                LocalDateTime.parse(it.createdAt)
                LocalDateTime.parse(it.updatedAt)
            }
            backup.sequenceExercises.forEach {
                ExerciseRequirement.valueOf(it.requirement)
            }
            backup.occurrenceExerciseChecks.forEach {
                ExerciseCheckStatus.valueOf(it.status)
                LocalDateTime.parse(it.updatedAt)
            }
            backup.routinePlans.forEach {
                LocalDateTime.parse(it.createdAt)
                LocalDateTime.parse(it.updatedAt)
            }
            backup.routinePhases.forEach {
                PhaseAdvanceMode.valueOf(it.advanceMode)
                RoutinePhaseStatus.valueOf(it.status)
                it.activatedDate?.let(LocalDate::parse)
                it.advancedAt?.let(LocalDateTime::parse)
                it.lastReviewedDate?.let(LocalDate::parse)
                LocalDateTime.parse(it.createdAt)
                LocalDateTime.parse(it.updatedAt)
            }
            backup.cycleGroups.forEach {
                CycleRestartBehavior.valueOf(it.restartBehavior)
                CycleRestartTiming.valueOf(it.restartTiming)
                it.blockedDays.forEach(DayOfWeek::valueOf)
                LocalDate.parse(it.currentStartDate)
                it.lastRestartedAt?.let(LocalDateTime::parse)
                LocalDateTime.parse(it.createdAt)
                LocalDateTime.parse(it.updatedAt)
            }
            backup.cycleTaskMemberships.forEach {
                LocalDateTime.parse(it.createdAt)
                LocalDateTime.parse(it.updatedAt)
            }
            backup.cycleLogs.forEach {
                LocalDateTime.parse(it.timestamp)
                LocalDate.parse(it.operationalDate)
                LocalDateTime.parse(it.createdAt)
            }
            LocalDateTime.parse(backup.exportedAt)
            LocalTime.parse(backup.appSettings.dayRolloverTime)
            LocalTime.parse(backup.appSettings.dailyReviewReminderTime)
            LocalTime.parse(backup.appSettings.lateDayReminderTime)
            LocalTime.parse(backup.appSettings.morningTaskReminderTime)
            LocalTime.parse(backup.appSettings.noonTaskReminderTime)
            LocalTime.parse(backup.appSettings.eveningTaskReminderTime)
            backup.appSettings.defaultBlockedDays
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach(DayOfWeek::valueOf)
            require(backup.appSettings.themePreference in setOf("system", "light", "dark"))
            require(backup.appSettings.autoBackupIntervalDays >= 1)
            backup.appSettings.backupLastExportedAt
                .takeIf { it.isNotBlank() }
                ?.let(LocalDateTime::parse)
            backup.appSettings.autoBackupLastRunAt
                .takeIf { it.isNotBlank() }
                ?.let(LocalDateTime::parse)
        }.fold(
            onSuccess = { null },
            onFailure = { "Backup contains invalid enum, date, or timestamp data" },
        )
    }

    private fun duplicateIds(ids: List<Long>): Boolean {
        return ids.size != ids.toSet().size
    }

    private fun ruleTypeMatchesTaskType(taskType: String, ruleType: String): Boolean {
        return when (TaskType.valueOf(taskType)) {
            TaskType.SIMPLE_HABIT -> ruleType == RuleType.DAILY.name
            TaskType.QUICK_ONE_TIME -> ruleType == RuleType.DAILY.name
            TaskType.INTERVAL -> ruleType == RuleType.EVERY_X_DAYS.name
            TaskType.WEEKDAY_BASED -> ruleType == RuleType.WEEKDAYS.name
            TaskType.SEQUENCE_ROUTINE -> ruleType == RuleType.SEQUENCE.name
            TaskType.LONG_TERM -> ruleType in LONG_TERM_RULE_TYPE_NAMES
        }
    }

    private data class OccurrenceScheduleKey(
        val taskId: Long,
        val recurrenceRuleId: Long,
        val operationalDate: String,
        val sequenceItemId: Long?,
    )

    private val INTERVAL_RULE_TYPE_NAMES = setOf(
        RuleType.EVERY_X_DAYS.name,
        RuleType.EVERY_X_WEEKS.name,
        RuleType.EVERY_X_MONTHS.name,
        RuleType.EVERY_X_YEARS.name,
    )
    private val LONG_TERM_RULE_TYPE_NAMES = INTERVAL_RULE_TYPE_NAMES
}
