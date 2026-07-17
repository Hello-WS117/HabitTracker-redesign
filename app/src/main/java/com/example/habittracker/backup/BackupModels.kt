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
import com.example.habittracker.data.local.CompletionLogEntity
import com.example.habittracker.data.local.CycleGroupEntity
import com.example.habittracker.data.local.CycleLogEntity
import com.example.habittracker.data.local.CycleTaskMembershipEntity
import com.example.habittracker.data.local.OccurrenceExerciseCheckEntity
import com.example.habittracker.data.local.RecurrenceRuleEntity
import com.example.habittracker.data.local.RoutinePhaseEntity
import com.example.habittracker.data.local.RoutinePlanEntity
import com.example.habittracker.data.local.ScheduledOccurrenceEntity
import com.example.habittracker.data.local.SequenceExerciseEntity
import com.example.habittracker.data.local.SequenceItemEntity
import com.example.habittracker.data.local.TaskEntity
import com.example.habittracker.data.local.WorkoutSequenceEntity
import com.example.habittracker.data.settings.AppSettingsSnapshot
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

const val BACKUP_FILE_NAME = "personal_scheduler_backup_v1.json"
const val BACKUP_SCHEMA_VERSION = 1
private val BACKUP_FILENAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

fun manualBackupFileName(timestamp: LocalDateTime = LocalDateTime.now()): String {
    return "personal_scheduler_backup_v1_${timestamp.format(BACKUP_FILENAME_FORMATTER)}.json"
}

fun manualBackupPendingFileName(timestamp: LocalDateTime = LocalDateTime.now()): String {
    return "${manualBackupFileName(timestamp)}.pending"
}

@Serializable
data class HabitBackupV1(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAt: String,
    val tasks: List<TaskBackup>,
    val recurrenceRules: List<RecurrenceRuleBackup>,
    val scheduledOccurrences: List<ScheduledOccurrenceBackup>,
    val completionLogs: List<CompletionLogBackup>,
    val workoutSequences: List<WorkoutSequenceBackup>,
    val sequenceItems: List<SequenceItemBackup>,
    val sequenceExercises: List<SequenceExerciseBackup> = emptyList(),
    val occurrenceExerciseChecks: List<OccurrenceExerciseCheckBackup> = emptyList(),
    val routinePlans: List<RoutinePlanBackup> = emptyList(),
    val routinePhases: List<RoutinePhaseBackup> = emptyList(),
    val cycleGroups: List<CycleGroupBackup> = emptyList(),
    val cycleTaskMemberships: List<CycleTaskMembershipBackup> = emptyList(),
    val cycleLogs: List<CycleLogBackup> = emptyList(),
    val appSettings: SettingsBackup,
)

@Serializable
data class TaskBackup(
    val id: Long,
    val name: String,
    val taskType: String,
    val notes: String,
    val isActive: Boolean,
    val archived: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val defaultReminderEnabled: Boolean,
    val calendarVisible: Boolean,
    val blockedDays: List<String>,
    val timeOfDay: String = TaskTimeOfDay.GENERAL.name,
    val pushable: Boolean = false,
    val noActionBehavior: String = NoActionBehavior.MARK_MISSED.name,
)

@Serializable
data class RecurrenceRuleBackup(
    val id: Long,
    val taskId: Long,
    val ruleType: String,
    val intervalDays: Int?,
    val weekdays: List<String>,
    val cycleDefinition: String,
    val startDate: String,
    val endDate: String?,
    val durationDays: Int? = null,
    val startsAfterTaskId: Long? = null,
    val skipBlockedDaysBehavior: String,
    val lastGeneratedDate: String?,
    val autoRestartBehavior: String = CycleRestartBehavior.OFF.name,
    val autoRestartTiming: String = CycleRestartTiming.TODAY.name,
    val autoRestartResetThresholdPercent: Int = 50,
    val autoRestartBlockedDays: List<String> = emptyList(),
    val autoRestartCurrentStartDate: String? = null,
    val autoRestartLastRestartedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ScheduledOccurrenceBackup(
    val id: Long,
    val taskId: Long,
    val recurrenceRuleId: Long,
    val scheduledDate: String,
    val operationalDate: String,
    val status: String,
    val sequenceItemId: Long?,
    val isShifted: Boolean,
    val originalDate: String?,
    val note: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CompletionLogBackup(
    val id: Long,
    val occurrenceId: Long?,
    val taskId: Long,
    val action: String,
    val timestamp: String,
    val operationalDate: String,
    val note: String,
    val createdAt: String,
)

@Serializable
data class WorkoutSequenceBackup(
    val id: Long,
    val taskId: Long,
    val name: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class SequenceItemBackup(
    val id: Long,
    val sequenceId: Long,
    val name: String,
    val position: Int,
    val notes: String,
)

@Serializable
data class SequenceExerciseBackup(
    val id: Long,
    val sequenceItemId: Long,
    val position: Int,
    val name: String,
    val prescription: String,
    val instructions: String,
    val requirement: String,
)

@Serializable
data class OccurrenceExerciseCheckBackup(
    val id: Long,
    val occurrenceId: Long,
    val sequenceExerciseId: Long,
    val status: String,
    val updatedAt: String,
)

@Serializable
data class RoutinePlanBackup(
    val id: Long,
    val name: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class RoutinePhaseBackup(
    val id: Long,
    val routinePlanId: Long,
    val taskId: Long,
    val position: Int,
    val advanceMode: String,
    val minimumDays: Int,
    val progressionNote: String,
    val status: String,
    val activatedDate: String?,
    val advancedAt: String?,
    val lastReviewedDate: String?,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CycleGroupBackup(
    val id: Long,
    val name: String,
    val durationDays: Int,
    val resetThresholdPercent: Int,
    val restartBehavior: String,
    val restartTiming: String,
    val blockedDays: List<String>,
    val currentStartDate: String,
    val lastRestartedAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CycleTaskMembershipBackup(
    val id: Long,
    val cycleGroupId: Long,
    val taskId: Long,
    val startOffsetDays: Int,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CycleLogBackup(
    val id: Long,
    val cycleGroupId: Long,
    val timestamp: String,
    val operationalDate: String,
    val note: String,
    val createdAt: String,
)

@Serializable
data class SettingsBackup(
    val dayRolloverTime: String,
    val dailyReviewReminderTime: String,
    val lateDayReminderTime: String,
    val morningTaskReminderTime: String = "08:00",
    val noonTaskReminderTime: String = "12:00",
    val eveningTaskReminderTime: String = "18:00",
    val dailyReviewEnabled: Boolean,
    val lateDayReminderEnabled: Boolean,
    val taskTimeReminderEnabled: Boolean = true,
    val exactAlarmPermissionPromptShown: Boolean,
    val defaultBlockedDays: String,
    val themePreference: String,
    val backupLastExportedAt: String,
    val autoBackupEnabled: Boolean = false,
    val autoBackupIntervalDays: Int = 7,
    val autoBackupFolderUri: String = "",
    val autoBackupLastRunAt: String = "",
)

fun TaskEntity.toBackup() = TaskBackup(
    id = id,
    name = name,
    taskType = taskType.name,
    notes = notes,
    isActive = isActive,
    archived = archived,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    defaultReminderEnabled = defaultReminderEnabled,
    calendarVisible = calendarVisible,
    blockedDays = blockedDays.map { it.name }.sorted(),
    timeOfDay = timeOfDay.name,
    pushable = pushable,
    noActionBehavior = noActionBehavior.name,
)

fun TaskBackup.toEntity() = TaskEntity(
    id = id,
    name = name,
    taskType = TaskType.valueOf(taskType),
    notes = notes,
    isActive = isActive,
    archived = archived,
    createdAt = LocalDateTime.parse(createdAt),
    updatedAt = LocalDateTime.parse(updatedAt),
    defaultReminderEnabled = defaultReminderEnabled,
    calendarVisible = calendarVisible,
    blockedDays = blockedDays.map { DayOfWeek.valueOf(it) }.toSet(),
    timeOfDay = TaskTimeOfDay.valueOf(timeOfDay),
    pushable = pushable,
    noActionBehavior = NoActionBehavior.valueOf(noActionBehavior),
)

fun RecurrenceRuleEntity.toBackup(cycleGroup: CycleGroupEntity? = null) = RecurrenceRuleBackup(
    id = id,
    taskId = taskId,
    ruleType = ruleType.name,
    intervalDays = intervalDays,
    weekdays = weekdays.map { it.name }.sorted(),
    cycleDefinition = cycleDefinition,
    startDate = startDate.toString(),
    endDate = endDate?.toString(),
    durationDays = durationDays,
    startsAfterTaskId = startsAfterTaskId,
    skipBlockedDaysBehavior = skipBlockedDaysBehavior.name,
    lastGeneratedDate = lastGeneratedDate?.toString(),
    autoRestartBehavior = cycleGroup?.restartBehavior?.name ?: CycleRestartBehavior.OFF.name,
    autoRestartTiming = cycleGroup?.restartTiming?.name ?: CycleRestartTiming.TODAY.name,
    autoRestartResetThresholdPercent = cycleGroup?.resetThresholdPercent ?: 50,
    autoRestartBlockedDays = cycleGroup?.blockedDays?.map { it.name }?.sorted().orEmpty(),
    autoRestartCurrentStartDate = cycleGroup?.currentStartDate?.toString(),
    autoRestartLastRestartedAt = cycleGroup?.lastRestartedAt?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

fun RecurrenceRuleBackup.toEntity() = RecurrenceRuleEntity(
    id = id,
    taskId = taskId,
    ruleType = RuleType.valueOf(ruleType),
    intervalDays = intervalDays,
    weekdays = weekdays.map { DayOfWeek.valueOf(it) }.toSet(),
    cycleDefinition = cycleDefinition,
    startDate = LocalDate.parse(startDate),
    endDate = endDate?.let(LocalDate::parse),
    durationDays = durationDays,
    startsAfterTaskId = startsAfterTaskId,
    skipBlockedDaysBehavior = SkipBlockedDaysBehavior.valueOf(skipBlockedDaysBehavior),
    lastGeneratedDate = lastGeneratedDate?.let(LocalDate::parse),
    createdAt = LocalDateTime.parse(createdAt),
    updatedAt = LocalDateTime.parse(updatedAt),
)

fun RecurrenceRuleBackup.toAutoRestartCycleGroup(taskName: String): CycleGroupEntity? {
    val behavior = CycleRestartBehavior.valueOf(autoRestartBehavior)
    if (behavior == CycleRestartBehavior.OFF) return null
    val duration = durationDays?.coerceAtLeast(1) ?: return null
    return CycleGroupEntity(
        id = id,
        name = "$taskName auto restart",
        durationDays = duration,
        resetThresholdPercent = autoRestartResetThresholdPercent.coerceIn(1, 100),
        restartBehavior = behavior,
        restartTiming = CycleRestartTiming.valueOf(autoRestartTiming),
        blockedDays = autoRestartBlockedDays.map { DayOfWeek.valueOf(it) }.toSet(),
        currentStartDate = autoRestartCurrentStartDate?.let(LocalDate::parse) ?: LocalDate.parse(startDate),
        lastRestartedAt = autoRestartLastRestartedAt?.let(LocalDateTime::parse),
        createdAt = LocalDateTime.parse(createdAt),
        updatedAt = LocalDateTime.parse(updatedAt),
    )
}

fun RecurrenceRuleBackup.toAutoRestartCycleMembership(groupId: Long): CycleTaskMembershipEntity {
    return CycleTaskMembershipEntity(
        id = id,
        cycleGroupId = groupId,
        taskId = taskId,
        startOffsetDays = 0,
        createdAt = LocalDateTime.parse(createdAt),
        updatedAt = LocalDateTime.parse(updatedAt),
    )
}

fun ScheduledOccurrenceEntity.toBackup() = ScheduledOccurrenceBackup(
    id = id,
    taskId = taskId,
    recurrenceRuleId = recurrenceRuleId,
    scheduledDate = scheduledDate.toString(),
    operationalDate = operationalDate.toString(),
    status = status.name,
    sequenceItemId = sequenceItemId,
    isShifted = isShifted,
    originalDate = originalDate?.toString(),
    note = note,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

fun ScheduledOccurrenceBackup.toEntity() = ScheduledOccurrenceEntity(
    id = id,
    taskId = taskId,
    recurrenceRuleId = recurrenceRuleId,
    scheduledDate = LocalDate.parse(scheduledDate),
    operationalDate = LocalDate.parse(operationalDate),
    status = OccurrenceStatus.valueOf(status),
    sequenceItemId = sequenceItemId,
    isShifted = isShifted,
    originalDate = originalDate?.let(LocalDate::parse),
    note = note,
    createdAt = LocalDateTime.parse(createdAt),
    updatedAt = LocalDateTime.parse(updatedAt),
)

fun CompletionLogEntity.toBackup() = CompletionLogBackup(
    id = id,
    occurrenceId = occurrenceId,
    taskId = taskId,
    action = action.name,
    timestamp = timestamp.toString(),
    operationalDate = operationalDate.toString(),
    note = note,
    createdAt = createdAt.toString(),
)

fun CompletionLogBackup.toEntity() = CompletionLogEntity(
    id = id,
    occurrenceId = occurrenceId,
    taskId = taskId,
    action = LogAction.valueOf(action),
    timestamp = LocalDateTime.parse(timestamp),
    operationalDate = LocalDate.parse(operationalDate),
    note = note,
    createdAt = LocalDateTime.parse(createdAt),
)

fun WorkoutSequenceEntity.toBackup() = WorkoutSequenceBackup(
    id = id,
    taskId = taskId,
    name = name,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

fun WorkoutSequenceBackup.toEntity() = WorkoutSequenceEntity(
    id = id,
    taskId = taskId,
    name = name,
    createdAt = LocalDateTime.parse(createdAt),
    updatedAt = LocalDateTime.parse(updatedAt),
)

fun SequenceItemEntity.toBackup() = SequenceItemBackup(
    id = id,
    sequenceId = sequenceId,
    name = name,
    position = position,
    notes = notes,
)

fun SequenceItemBackup.toEntity() = SequenceItemEntity(
    id = id,
    sequenceId = sequenceId,
    name = name,
    position = position,
    notes = notes,
)

fun SequenceExerciseEntity.toBackup() = SequenceExerciseBackup(
    id = id,
    sequenceItemId = sequenceItemId,
    position = position,
    name = name,
    prescription = prescription,
    instructions = instructions,
    requirement = requirement.name,
)

fun SequenceExerciseBackup.toEntity() = SequenceExerciseEntity(
    id = id,
    sequenceItemId = sequenceItemId,
    position = position,
    name = name,
    prescription = prescription,
    instructions = instructions,
    requirement = ExerciseRequirement.valueOf(requirement),
)

fun OccurrenceExerciseCheckEntity.toBackup() = OccurrenceExerciseCheckBackup(
    id = id,
    occurrenceId = occurrenceId,
    sequenceExerciseId = sequenceExerciseId,
    status = status.name,
    updatedAt = updatedAt.toString(),
)

fun OccurrenceExerciseCheckBackup.toEntity() = OccurrenceExerciseCheckEntity(
    id = id,
    occurrenceId = occurrenceId,
    sequenceExerciseId = sequenceExerciseId,
    status = ExerciseCheckStatus.valueOf(status),
    updatedAt = LocalDateTime.parse(updatedAt),
)

fun RoutinePlanEntity.toBackup() = RoutinePlanBackup(
    id = id,
    name = name,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

fun RoutinePlanBackup.toEntity() = RoutinePlanEntity(
    id = id,
    name = name,
    createdAt = LocalDateTime.parse(createdAt),
    updatedAt = LocalDateTime.parse(updatedAt),
)

fun RoutinePhaseEntity.toBackup() = RoutinePhaseBackup(
    id = id,
    routinePlanId = routinePlanId,
    taskId = taskId,
    position = position,
    advanceMode = advanceMode.name,
    minimumDays = minimumDays,
    progressionNote = progressionNote,
    status = status.name,
    activatedDate = activatedDate?.toString(),
    advancedAt = advancedAt?.toString(),
    lastReviewedDate = lastReviewedDate?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

fun RoutinePhaseBackup.toEntity() = RoutinePhaseEntity(
    id = id,
    routinePlanId = routinePlanId,
    taskId = taskId,
    position = position,
    advanceMode = PhaseAdvanceMode.valueOf(advanceMode),
    minimumDays = minimumDays,
    progressionNote = progressionNote,
    status = RoutinePhaseStatus.valueOf(status),
    activatedDate = activatedDate?.let(LocalDate::parse),
    advancedAt = advancedAt?.let(LocalDateTime::parse),
    lastReviewedDate = lastReviewedDate?.let(LocalDate::parse),
    createdAt = LocalDateTime.parse(createdAt),
    updatedAt = LocalDateTime.parse(updatedAt),
)

fun CycleGroupEntity.toBackup() = CycleGroupBackup(
    id = id,
    name = name,
    durationDays = durationDays,
    resetThresholdPercent = resetThresholdPercent,
    restartBehavior = restartBehavior.name,
    restartTiming = restartTiming.name,
    blockedDays = blockedDays.map { it.name }.sorted(),
    currentStartDate = currentStartDate.toString(),
    lastRestartedAt = lastRestartedAt?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

fun CycleGroupBackup.toEntity() = CycleGroupEntity(
    id = id,
    name = name,
    durationDays = durationDays,
    resetThresholdPercent = resetThresholdPercent,
    restartBehavior = CycleRestartBehavior.valueOf(restartBehavior),
    restartTiming = CycleRestartTiming.valueOf(restartTiming),
    blockedDays = blockedDays.map { DayOfWeek.valueOf(it) }.toSet(),
    currentStartDate = LocalDate.parse(currentStartDate),
    lastRestartedAt = lastRestartedAt?.let(LocalDateTime::parse),
    createdAt = LocalDateTime.parse(createdAt),
    updatedAt = LocalDateTime.parse(updatedAt),
)

fun CycleTaskMembershipEntity.toBackup() = CycleTaskMembershipBackup(
    id = id,
    cycleGroupId = cycleGroupId,
    taskId = taskId,
    startOffsetDays = startOffsetDays,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

fun CycleTaskMembershipBackup.toEntity() = CycleTaskMembershipEntity(
    id = id,
    cycleGroupId = cycleGroupId,
    taskId = taskId,
    startOffsetDays = startOffsetDays,
    createdAt = LocalDateTime.parse(createdAt),
    updatedAt = LocalDateTime.parse(updatedAt),
)

fun CycleLogEntity.toBackup() = CycleLogBackup(
    id = id,
    cycleGroupId = cycleGroupId,
    timestamp = timestamp.toString(),
    operationalDate = operationalDate.toString(),
    note = note,
    createdAt = createdAt.toString(),
)

fun CycleLogBackup.toEntity() = CycleLogEntity(
    id = id,
    cycleGroupId = cycleGroupId,
    timestamp = LocalDateTime.parse(timestamp),
    operationalDate = LocalDate.parse(operationalDate),
    note = note,
    createdAt = LocalDateTime.parse(createdAt),
)

fun AppSettingsSnapshot.toBackup() = SettingsBackup(
    dayRolloverTime = dayRolloverTime.toString(),
    dailyReviewReminderTime = dailyReviewReminderTime.toString(),
    lateDayReminderTime = lateDayReminderTime.toString(),
    morningTaskReminderTime = morningTaskReminderTime.toString(),
    noonTaskReminderTime = noonTaskReminderTime.toString(),
    eveningTaskReminderTime = eveningTaskReminderTime.toString(),
    dailyReviewEnabled = dailyReviewEnabled,
    lateDayReminderEnabled = lateDayReminderEnabled,
    taskTimeReminderEnabled = taskTimeReminderEnabled,
    exactAlarmPermissionPromptShown = exactAlarmPermissionPromptShown,
    defaultBlockedDays = defaultBlockedDays,
    themePreference = themePreference,
    backupLastExportedAt = backupLastExportedAt,
    autoBackupEnabled = autoBackupEnabled,
    autoBackupIntervalDays = autoBackupIntervalDays,
    autoBackupFolderUri = autoBackupFolderUri,
    autoBackupLastRunAt = autoBackupLastRunAt,
)

fun SettingsBackup.toSnapshot() = AppSettingsSnapshot(
    dayRolloverTime = LocalTime.parse(dayRolloverTime),
    dailyReviewReminderTime = LocalTime.parse(dailyReviewReminderTime),
    lateDayReminderTime = LocalTime.parse(lateDayReminderTime),
    morningTaskReminderTime = LocalTime.parse(morningTaskReminderTime),
    noonTaskReminderTime = LocalTime.parse(noonTaskReminderTime),
    eveningTaskReminderTime = LocalTime.parse(eveningTaskReminderTime),
    dailyReviewEnabled = dailyReviewEnabled,
    lateDayReminderEnabled = lateDayReminderEnabled,
    taskTimeReminderEnabled = taskTimeReminderEnabled,
    exactAlarmPermissionPromptShown = exactAlarmPermissionPromptShown,
    defaultBlockedDays = defaultBlockedDays,
    themePreference = themePreference,
    backupLastExportedAt = backupLastExportedAt,
    autoBackupEnabled = autoBackupEnabled,
    autoBackupIntervalDays = autoBackupIntervalDays.coerceAtLeast(1),
    autoBackupFolderUri = autoBackupFolderUri,
    autoBackupLastRunAt = autoBackupLastRunAt,
)
