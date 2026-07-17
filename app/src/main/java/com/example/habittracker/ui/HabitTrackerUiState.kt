package com.example.habittracker.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.example.habittracker.backup.BackupRepository
import com.example.habittracker.backup.PreparedManualBackup
import com.example.habittracker.data.CycleRestartBehavior
import com.example.habittracker.data.CycleRestartTiming
import com.example.habittracker.data.ExerciseCheckStatus
import com.example.habittracker.data.ExerciseRequirement
import com.example.habittracker.data.HabitRepository
import com.example.habittracker.data.GenerationRequest
import com.example.habittracker.data.LogAction
import com.example.habittracker.data.LongTermRecurrenceAnchor
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
import com.example.habittracker.data.local.HabitDatabase
import com.example.habittracker.data.local.OccurrenceExerciseCheckEntity
import com.example.habittracker.data.local.RecurrenceRuleEntity
import com.example.habittracker.data.local.RoutinePhaseEntity
import com.example.habittracker.data.local.RoutinePlanEntity
import com.example.habittracker.data.local.ScheduledOccurrenceEntity
import com.example.habittracker.data.local.SequenceExerciseEntity
import com.example.habittracker.data.local.SequenceItemEntity
import com.example.habittracker.data.local.TaskEntity
import com.example.habittracker.data.local.WorkoutSequenceEntity
import com.example.habittracker.data.scheduling.OccurrenceGenerator
import com.example.habittracker.data.settings.AppSettingsRepository
import com.example.habittracker.data.settings.AppSettingsSnapshot
import com.example.habittracker.reminders.ReminderScheduler
import com.example.habittracker.timers.ExerciseTimerController
import com.example.habittracker.timers.ExerciseTimerServiceController
import com.example.habittracker.workers.AutoBackupScheduler
import com.example.habittracker.workers.MaintenanceScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.min
import kotlin.math.roundToInt

private const val ROUTINE_PHASE_EXTENSION_DAYS = 7L

internal fun backupByteCountLabel(byteCount: Long): String {
    val safeBytes = byteCount.coerceAtLeast(0L)
    return if (safeBytes < 1024L) {
        "$safeBytes bytes"
    } else {
        "${(safeBytes + 512L) / 1024L} KB"
    }
}

internal fun backupFailureLabel(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        error is SecurityException -> "folder permission expired"
        message.contains("empty", ignoreCase = true) -> "destination stayed empty"
        message.contains("file size", ignoreCase = true) -> "destination reported the wrong size"
        message.contains("did not match", ignoreCase = true) -> "destination changed the backup data"
        message.contains("incomplete", ignoreCase = true) -> "destination did not finish uploading"
        message.contains("finalize", ignoreCase = true) -> "destination could not finalize the file"
        message.contains("Could not open", ignoreCase = true) -> "destination could not be opened"
        else -> "provider write failed"
    }
}

internal fun manualBackupFailureStatus(error: Throwable): String {
    return "Backup failed: ${backupFailureLabel(error)}"
}

enum class AppDestination(val label: String, val marker: String) {
    Today("Today", "T"),
    Calendar("Calendar", "C"),
    Tasks("Tasks", "E"),
    Stats("Stats", "S"),
    Settings("Settings", "B"),
}

enum class HabitTaskType(val label: String) {
    Simple("Simple habit"),
    OneTime("1-time task"),
    Interval("Interval"),
    Weekday("Weekday"),
    Sequence("Sequence"),
    LongTerm("Long-term"),
}

enum class LongTermRecurrenceUnit(val label: String) {
    Days("Days"),
    Weeks("Weeks"),
    Months("Months"),
    Years("Years"),
}

internal fun defaultSkipBlockedDaysBehavior(type: HabitTaskType): SkipBlockedDaysBehavior {
    return if (type == HabitTaskType.Sequence) {
        SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY
    } else if (type == HabitTaskType.LongTerm) {
        SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY
    } else {
        SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY
    }
}

enum class HabitStatus(val label: String) {
    Pending("Pending"),
    Completed("Completed"),
    Skipped("Skipped"),
    Missed("Missed"),
    Shifted("Pushed"),
}

data class HabitWorkoutExerciseUi(
    val id: Int,
    val position: Int,
    val name: String,
    val prescription: String,
    val instructions: String = "",
    val requirement: ExerciseRequirement = ExerciseRequirement.REQUIRED,
)

data class HabitWorkoutDayUi(
    val position: Int,
    val title: String,
    val notes: String = "",
    val exercises: List<HabitWorkoutExerciseUi> = emptyList(),
)

data class HabitRoutinePhaseUi(
    val id: Int,
    val routinePlanId: Int,
    val routinePlanName: String,
    val taskId: Int,
    val position: Int,
    val advanceMode: PhaseAdvanceMode,
    val minimumDays: Int,
    val progressionNote: String,
    val status: RoutinePhaseStatus,
    val activatedDate: LocalDate?,
    val lastReviewedDate: LocalDate?,
    val nextPhaseName: String? = null,
)

data class HabitExerciseTimerUi(
    val durationSeconds: Int,
    val remainingSeconds: Int = durationSeconds,
    val endsAtEpochMillis: Long? = null,
) {
    val isRunning: Boolean
        get() = endsAtEpochMillis != null

    val isComplete: Boolean
        get() = remainingSeconds <= 0 && !isRunning
}

private data class ExerciseTimerKey(
    val occurrenceId: Int,
    val exerciseId: Int,
)

enum class HabitLogAction(val label: String) {
    Completed("Completed"),
    Skipped("Skipped"),
    MarkedMissed("Marked missed"),
    ShiftedForward("Pushed forward"),
    Edited("Edited"),
    Backup("Backup"),
    Restore("Restore"),
}

data class HabitTaskUi(
    val id: Int,
    val name: String,
    val type: HabitTaskType,
    val notes: String,
    val recurrenceSummary: String,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val durationDays: Int? = null,
    val startsAfterTaskId: Int? = null,
    val intervalDays: Int? = null,
    val longTermRecurrenceUnit: LongTermRecurrenceUnit = LongTermRecurrenceUnit.Months,
    val longTermRecurrenceAnchor: LongTermRecurrenceAnchor = LongTermRecurrenceAnchor.COMPLETION_DATE,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val blockedDays: Set<DayOfWeek> = emptySet(),
    val skipBlockedDaysBehavior: SkipBlockedDaysBehavior = SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY,
    val sequenceItems: List<String> = emptyList(),
    val workoutDays: List<HabitWorkoutDayUi> = emptyList(),
    val timeOfDay: TaskTimeOfDay = TaskTimeOfDay.GENERAL,
    val pushable: Boolean = false,
    val noActionBehavior: NoActionBehavior = NoActionBehavior.MARK_MISSED,
    val reminderEnabled: Boolean = true,
    val calendarVisible: Boolean = true,
    val isActive: Boolean = true,
    val archived: Boolean = false,
    val cycleGroupId: Int? = null,
    val cycleGroupName: String = "",
    val cycleDurationDays: Int = 14,
    val cycleResetThresholdPercent: Int = 50,
    val cycleRestartBehavior: CycleRestartBehavior = CycleRestartBehavior.OFF,
    val cycleRestartTiming: CycleRestartTiming = CycleRestartTiming.TODAY,
    val cycleBlockedDays: Set<DayOfWeek> = emptySet(),
)

data class HabitOccurrenceUi(
    val id: Int,
    val taskId: Int,
    val scheduledDate: LocalDate,
    val operationalDate: LocalDate,
    val status: HabitStatus,
    val sequenceItemName: String? = null,
    val sequenceItemPosition: Int? = null,
    val isShifted: Boolean = false,
    val originalDate: LocalDate? = null,
    val note: String = "",
    val exerciseChecks: Map<Int, ExerciseCheckStatus> = emptyMap(),
)

data class HabitLogUi(
    val id: Int,
    val taskId: Int,
    val occurrenceId: Int?,
    val action: HabitLogAction,
    val timestamp: LocalDateTime,
    val operationalDate: LocalDate,
    val note: String,
)

data class HabitCycleUi(
    val id: Int,
    val name: String,
    val durationDays: Int,
    val resetThresholdPercent: Int,
    val restartBehavior: CycleRestartBehavior,
    val restartTiming: CycleRestartTiming,
    val blockedDays: Set<DayOfWeek>,
    val currentStartDate: LocalDate,
    val lastRestartedAt: LocalDateTime? = null,
)

data class HabitCycleLogUi(
    val id: Int,
    val cycleGroupId: Int,
    val timestamp: LocalDateTime,
    val operationalDate: LocalDate,
    val note: String,
)

data class HabitCycleProgressUi(
    val completed: Int,
    val remaining: Int,
    val disrupted: Int = 0,
    val expected: Int = completed + disrupted + remaining,
)

data class ReminderSettingsUi(
    val dayRolloverTime: LocalTime = LocalTime.of(3, 0),
    val dailyReviewTime: LocalTime = LocalTime.of(8, 0),
    val lateReminderTime: LocalTime = LocalTime.of(20, 0),
    val morningTaskReminderTime: LocalTime = LocalTime.of(8, 0),
    val noonTaskReminderTime: LocalTime = LocalTime.of(12, 0),
    val eveningTaskReminderTime: LocalTime = LocalTime.of(18, 0),
    val dailyReviewEnabled: Boolean = true,
    val lateReminderEnabled: Boolean = true,
    val taskTimeReminderEnabled: Boolean = true,
    val exactAlarmPermissionPromptShown: Boolean = false,
    val defaultBlockedDays: Set<DayOfWeek> = emptySet(),
    val themePreference: String = "system",
    val backupLastExportedAt: String = "",
    val backupLastVerifiedBytes: Long = 0L,
    val autoBackupEnabled: Boolean = false,
    val autoBackupIntervalDays: Int = 7,
    val autoBackupFolderUri: String = "",
    val autoBackupLastRunAt: String = "",
    val autoBackupLastFailureAt: String = "",
    val autoBackupLastFailureReason: String = "",
)

data class HabitTaskDraft(
    val id: Int? = null,
    val name: String = "",
    val type: HabitTaskType = HabitTaskType.Simple,
    val notes: String = "",
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,
    val durationDays: Int? = null,
    val startsAfterTaskId: Int? = null,
    val intervalDays: Int = 2,
    val longTermRecurrenceUnit: LongTermRecurrenceUnit = LongTermRecurrenceUnit.Months,
    val longTermRecurrenceAnchor: LongTermRecurrenceAnchor = LongTermRecurrenceAnchor.COMPLETION_DATE,
    val weekdays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
    val blockedDays: Set<DayOfWeek> = emptySet(),
    val skipBlockedDaysBehavior: SkipBlockedDaysBehavior = SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY,
    val sequenceText: String = "Push\nPull\nLegs\nCardio\nHIIT",
    val workoutDays: List<HabitWorkoutDayUi> = emptyList(),
    val sequenceSpacingDays: Int = 1,
    val timeOfDay: TaskTimeOfDay = TaskTimeOfDay.GENERAL,
    val pushable: Boolean = false,
    val noActionBehavior: NoActionBehavior = NoActionBehavior.MARK_MISSED,
    val reminderEnabled: Boolean = true,
    val calendarVisible: Boolean = true,
    val isActive: Boolean = true,
    val archived: Boolean = false,
    val cycleGroupId: Int? = null,
    val cycleGroupName: String = "",
    val cycleDurationDays: Int = 14,
    val cycleResetThresholdPercent: Int = 50,
    val cycleRestartBehavior: CycleRestartBehavior = CycleRestartBehavior.OFF,
    val cycleRestartTiming: CycleRestartTiming = CycleRestartTiming.TODAY,
    val cycleBlockedDays: Set<DayOfWeek> = emptySet(),
) {
    companion object {
        fun fromTask(task: HabitTaskUi) = HabitTaskDraft(
            id = task.id,
            name = task.name,
            type = task.type,
            notes = task.notes,
            startDate = task.startDate,
            endDate = task.endDate,
            durationDays = task.durationDays,
            startsAfterTaskId = task.startsAfterTaskId,
            intervalDays = task.intervalDays ?: if (task.type == HabitTaskType.LongTerm) 6 else 2,
            longTermRecurrenceUnit = task.longTermRecurrenceUnit,
            longTermRecurrenceAnchor = task.longTermRecurrenceAnchor,
            weekdays = if (task.weekdays.isEmpty()) {
                setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
            } else {
                task.weekdays
            },
            blockedDays = task.blockedDays,
            skipBlockedDaysBehavior = task.skipBlockedDaysBehavior,
            sequenceText = if (task.sequenceItems.isEmpty()) {
                "Push\nPull\nLegs\nCardio\nHIIT"
            } else {
                formatSequenceEditorText(task.sequenceItems)
            },
            sequenceSpacingDays = if (task.type == HabitTaskType.Sequence) (task.intervalDays ?: 1).coerceAtLeast(1) else 1,
            workoutDays = task.workoutDays,
            timeOfDay = task.timeOfDay,
            pushable = task.pushable,
            noActionBehavior = task.noActionBehavior,
            reminderEnabled = task.reminderEnabled,
            calendarVisible = task.calendarVisible,
            isActive = task.isActive,
            archived = task.archived,
            cycleGroupId = task.cycleGroupId,
            cycleGroupName = task.cycleGroupName,
            cycleDurationDays = task.cycleDurationDays,
            cycleResetThresholdPercent = task.cycleResetThresholdPercent,
            cycleRestartBehavior = task.cycleRestartBehavior,
            cycleRestartTiming = task.cycleRestartTiming,
            cycleBlockedDays = task.cycleBlockedDays,
        )
    }
}

data class HabitStatsUi(
    val currentStreak: Int,
    val longestStreak: Int,
    val completionPercentage: Int,
    val completed: Int,
    val skipped: Int,
    val missed: Int,
    val shifted: Int,
    val pastTotal: Int,
    val skipRate: Int,
    val missRate: Int,
)

class HabitTrackerUiStore(
    private val appContext: Context? = null,
    private val scope: CoroutineScope? = null,
    private val enqueueMaintenance: (Context, LocalTime) -> Unit = MaintenanceScheduler::enqueue,
    private val configureAutoBackup: (Context, AppSettingsSnapshot) -> Unit = AutoBackupScheduler::configure,
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now() },
    private val timerNowProvider: () -> Long = System::currentTimeMillis,
    private val exerciseTimerController: ExerciseTimerController? =
        appContext?.let(::ExerciseTimerServiceController),
) {
    private val database = appContext?.let { HabitDatabase.get(it) }
    private val dao = database?.habitDao()
    private val repository = database?.let { HabitRepository(it) }
    private val settingsRepository = appContext?.let { AppSettingsRepository(it) }
    private val reminderScheduler = appContext?.let { ReminderScheduler(it) }
    private val backupRepository = appContext?.let { BackupRepository(it) }
    private var preparedManualBackup: PreparedManualBackup? = null
    private var nextTaskId = 10
    private var nextOccurrenceId = 500
    private var nextLogId = 900
    private var nextCycleId = 100
    private var nextCycleLogId = 1200
    private val recentlyCompletedLongTermOccurrenceIds = mutableStateListOf<Int>()
    private val exerciseTimers = mutableStateMapOf<ExerciseTimerKey, HabitExerciseTimerUi>()

    val tasks = mutableStateListOf<HabitTaskUi>()
    val occurrences = mutableStateListOf<HabitOccurrenceUi>()
    val logs = mutableStateListOf<HabitLogUi>()
    val cycleGroups = mutableStateListOf<HabitCycleUi>()
    val cycleLogs = mutableStateListOf<HabitCycleLogUi>()
    val routinePhases = mutableStateListOf<HabitRoutinePhaseUi>()

    var currentDestination by mutableStateOf(AppDestination.Today)
    var settings by mutableStateOf(ReminderSettingsUi())
    var calendarMonth by mutableStateOf(YearMonth.from(operationalDate))
    var calendarTaskFilterId by mutableStateOf<Int?>(null)
    var selectedCalendarDate by mutableStateOf(operationalDate)
    var detailTaskId by mutableStateOf<Int?>(null)
    var draft by mutableStateOf(defaultDraft())
    var backupStatus by mutableStateOf("No backup file selected")
    var restoreStatus by mutableStateOf("No restore file selected")
    var reminderScheduleStatus by mutableStateOf("Reminder scheduling not checked")
    val startupJob: Job?

    val operationalDate: LocalDate
        get() {
            val now = nowProvider()
            return if (now.toLocalTime().isBefore(settings.dayRolloverTime)) {
                now.toLocalDate().minusDays(1)
            } else {
                now.toLocalDate()
            }
        }

    init {
        val today = operationalDate
        if (appContext == null) {
            tasks.addAll(seedTasks(today))
            occurrences.addAll(seedOccurrences(today))
            logs.addAll(seedLogs(today))
        }
        detailTaskId = tasks.firstOrNull()?.id
        startupJob = scope?.launch {
            reloadSettings()
            reloadFromDatabase()
        }
    }

    fun visibleTasks(includeArchived: Boolean = false): List<HabitTaskUi> {
        return tasks.filter { includeArchived || (!it.archived && it.isActive) }
    }

    fun taskById(taskId: Int): HabitTaskUi? {
        return tasks.firstOrNull { it.id == taskId }
    }

    fun cycleById(cycleGroupId: Int): HabitCycleUi? {
        return cycleGroups.firstOrNull { it.id == cycleGroupId }
    }

    fun cycleLogsForGroup(cycleGroupId: Int): List<HabitCycleLogUi> {
        return cycleLogs
            .filter { it.cycleGroupId == cycleGroupId }
            .sortedWith(compareByDescending<HabitCycleLogUi> { it.timestamp }.thenByDescending { it.id })
    }

    fun cycleProgressForTask(task: HabitTaskUi): HabitCycleProgressUi? {
        val cycleGroupId = task.cycleGroupId ?: return null
        val cycle = cycleById(cycleGroupId) ?: return null
        val cycleTasks = tasks
            .filter { it.cycleGroupId == cycleGroupId && it.isActive && !it.archived }
        if (cycleTasks.isEmpty()) return null
        val cycleStart = cycle.currentStartDate
        val cycleEnd = cycleStart.plusDays((cycle.durationDays.coerceAtLeast(1) - 1).toLong())
        val expectedSlots = cycleTasks.flatMap { cycleTask ->
            expectedCycleSlotsForTask(cycleTask, cycleStart, cycleEnd)
        }
        return progressForExpectedCycleSlots(expectedSlots, cycleStart, cycleEnd)
    }

    fun durationProgressForTask(task: HabitTaskUi): HabitCycleProgressUi? {
        val durationDays = task.durationDays?.coerceAtLeast(1) ?: return null
        if (durationDays <= 1) return null
        if (task.archived || !task.isActive) return null
        val cycle = task.cycleGroupId?.let { cycleById(it) }
        val cycleStart = cycle?.currentStartDate ?: task.startDate
        val cycleEnd = cycleStart.plusDays(((cycle?.durationDays ?: durationDays).coerceAtLeast(1) - 1).toLong())
        val expectedSlots = expectedCycleSlotsForTask(task, cycleStart, cycleEnd)
        return progressForExpectedCycleSlots(expectedSlots, cycleStart, cycleEnd)
    }

    fun progressForTaskCycleTiming(task: HabitTaskUi): HabitCycleProgressUi? {
        return durationProgressForTask(task)
    }

    private fun expectedCycleSlotsForTask(
        task: HabitTaskUi,
        cycleStart: LocalDate,
        cycleEnd: LocalDate,
    ): List<ExpectedCycleSlot> {
        if (cycleEnd.isBefore(cycleStart)) return emptyList()
        val slotStart = maxOf(task.startDate, cycleStart)
        if (slotStart.isAfter(cycleEnd)) return emptyList()
        val cycleDays = (ChronoUnit.DAYS.between(slotStart, cycleEnd) + 1).toInt().coerceAtLeast(1)
        val progressTask = task.copy(
            startDate = slotStart,
            endDate = cycleEnd,
            durationDays = cycleDays,
        )
        return generatedOccurrencesFor(progressTask, slotStart, cycleDays)
            .map { ExpectedCycleSlot(taskId = task.id, date = it.date) }
            .filter { !it.date.isBefore(cycleStart) && !it.date.isAfter(cycleEnd) }
            .distinct()
    }

    private fun progressForExpectedCycleSlots(
        slots: List<ExpectedCycleSlot>,
        cycleStart: LocalDate,
        cycleEnd: LocalDate,
    ): HabitCycleProgressUi? {
        val expectedSlots = slots.distinct()
        if (expectedSlots.isEmpty()) return null
        val occurrencesByTask = occurrences.groupBy { it.taskId }
        val disruptedDatesByTask = expectedSlots
            .map { it.taskId }
            .distinct()
            .associateWith { taskId -> disruptedDatesForWindow(taskId, cycleStart, cycleEnd) }
        var completed = 0
        var disrupted = 0
        var remaining = 0

        expectedSlots.forEach { slot ->
            val slotOccurrences = occurrencesByTask[slot.taskId].orEmpty()
                .filter { occurrence ->
                    occurrence.operationalDate == slot.date || occurrence.originalDate == slot.date
                }
            when {
                slotOccurrences.any { it.status == HabitStatus.Completed } -> completed += 1
                slot.date in disruptedDatesByTask.getValue(slot.taskId) -> disrupted += 1
                else -> remaining += 1
            }
        }

        return HabitCycleProgressUi(
            completed = completed,
            disrupted = disrupted,
            remaining = remaining,
            expected = expectedSlots.size,
        )
    }

    fun todayOccurrences(): List<HabitOccurrenceUi> {
        return checklistOccurrencesForDate(operationalDate)
    }

    fun longTermDueOccurrences(): List<HabitOccurrenceUi> {
        val eligibleLongTermOccurrences = occurrences
            .filter { occurrence ->
                val task = taskById(occurrence.taskId)
                task?.type == HabitTaskType.LongTerm &&
                    task.archived != true &&
                    task.isActive != false &&
                    !occurrence.operationalDate.isAfter(operationalDate)
            }
        val pendingDue = eligibleLongTermOccurrences
            .filter { it.status == HabitStatus.Pending }
            .groupBy { it.taskId }
            .mapNotNull { (_, taskOccurrences) ->
                taskOccurrences.minWithOrNull(
                    compareBy<HabitOccurrenceUi> { it.operationalDate }
                        .thenBy { it.id },
                )
            }
        val recentlyCompleted = eligibleLongTermOccurrences
            .filter {
                it.status == HabitStatus.Completed &&
                    it.id in recentlyCompletedLongTermOccurrenceIds
            }
        return (pendingDue + recentlyCompleted)
            .distinctBy { it.id }
            .sortedWith(
                compareBy<HabitOccurrenceUi> { if (it.status == HabitStatus.Pending) 0 else 1 }
                    .thenBy { it.operationalDate }
                    .thenBy { taskById(it.taskId)?.name },
            )
    }

    fun yesterdayCompletionCandidates(): List<HabitOccurrenceUi> {
        val completedDate = operationalDate.minusDays(1)
        val taskIdsOnToday = checklistOccurrencesForDate(operationalDate)
            .map { it.taskId }
            .toSet()
        return tasks
            .filter { it.pushable && it.isActive && !it.archived && it.id !in taskIdsOnToday }
            .mapNotNull { task ->
                val nextPending = occurrences
                    .filter {
                        it.taskId == task.id &&
                            it.status == HabitStatus.Pending &&
                            it.operationalDate.isAfter(completedDate)
                    }
                    .minByOrNull { it.operationalDate }
                    ?: return@mapNotNull null
                val candidateWindowEnd = completedDate.plusDays(task.scheduleSpacingDays().toLong())
                nextPending.takeIf {
                    it.operationalDate == operationalDate &&
                        !it.operationalDate.isAfter(candidateWindowEnd) &&
                        canCompleteOccurrenceYesterday(it.id)
                }
            }
            .sortedWith(
                compareBy<HabitOccurrenceUi> { taskById(it.taskId)?.timeOfDay?.sortOrder() ?: Int.MAX_VALUE }
                    .thenBy { taskById(it.taskId)?.name }
                    .thenBy { it.operationalDate },
            )
    }

    fun occurrencesForDate(date: LocalDate, taskFilterId: Int? = null): List<HabitOccurrenceUi> {
        return occurrences
            .filter { occurrence ->
                val task = taskById(occurrence.taskId)
                occurrence.operationalDate == date &&
                    (taskFilterId == null || occurrence.taskId == taskFilterId) &&
                    if (taskFilterId == null) {
                        task?.calendarVisible != false &&
                            task?.archived != true &&
                            task?.isActive != false
                    } else {
                        task != null
                    }
            }
            .sortedWith(
                compareBy<HabitOccurrenceUi> { it.status.sortOrder() }
                    .thenBy { taskById(it.taskId)?.timeOfDay?.sortOrder() ?: Int.MAX_VALUE }
                    .thenBy { taskById(it.taskId)?.name },
            )
    }

    private fun checklistOccurrencesForDate(date: LocalDate): List<HabitOccurrenceUi> {
        return occurrences
            .filter { occurrence ->
                val task = taskById(occurrence.taskId)
                occurrence.operationalDate == date &&
                    task?.type != HabitTaskType.LongTerm &&
                    task?.archived != true &&
                    task?.isActive != false
            }
            .sortedWith(
                compareBy<HabitOccurrenceUi> { it.status.sortOrder() }
                    .thenBy { taskById(it.taskId)?.timeOfDay?.sortOrder() ?: Int.MAX_VALUE }
                    .thenBy { taskById(it.taskId)?.name },
            )
    }

    fun occurrencesForTask(taskId: Int): List<HabitOccurrenceUi> {
        return occurrences
            .filter { it.taskId == taskId }
            .sortedByDescending { it.operationalDate }
    }

    fun historyOccurrencesForTask(taskId: Int): List<HabitOccurrenceUi> {
        return occurrencesForTask(taskId)
            .filter {
                it.operationalDate.isBefore(operationalDate) ||
                    (it.operationalDate == operationalDate && it.status != HabitStatus.Pending)
            }
    }

    fun sequenceStepNoteHistory(
        taskId: Int,
        sequenceItemName: String,
        beforeOccurrenceId: Int,
        limit: Int = 3,
    ): List<HabitOccurrenceUi> {
        val current = occurrences.firstOrNull { it.id == beforeOccurrenceId }
        return occurrences
            .filter {
                it.taskId == taskId &&
                    it.id != beforeOccurrenceId &&
                    (
                        if (current?.sequenceItemPosition != null) {
                            it.sequenceItemPosition == current.sequenceItemPosition
                        } else {
                            it.sequenceItemName == sequenceItemName
                        }
                    ) &&
                    it.note.isNotBlank() &&
                    !it.note.isActionGeneratedNote() &&
                    (
                        current == null ||
                            it.operationalDate.isBefore(current.operationalDate) ||
                            (it.operationalDate == current.operationalDate && it.id < current.id)
                    )
            }
            .sortedWith(compareByDescending<HabitOccurrenceUi> { it.operationalDate }.thenByDescending { it.id })
            .take(limit)
    }

    fun sequenceSwapCandidates(occurrenceId: Int): List<HabitOccurrenceUi> {
        val occurrence = occurrences.firstOrNull { it.id == occurrenceId } ?: return emptyList()
        val task = taskById(occurrence.taskId) ?: return emptyList()
        if (
            task.type != HabitTaskType.Sequence ||
            occurrence.status != HabitStatus.Pending ||
            occurrence.sequenceItemName == null
        ) {
            return emptyList()
        }
        return occurrences
            .filter {
                it.id != occurrenceId &&
                    it.taskId == occurrence.taskId &&
                    it.sequenceItemName != null &&
                    it.sequenceItemName != occurrence.sequenceItemName &&
                    !it.operationalDate.isBefore(task.startDate) &&
                    it.status == HabitStatus.Pending &&
                    !it.operationalDate.isBefore(occurrence.operationalDate)
            }
            .sortedWith(
                compareBy<HabitOccurrenceUi> {
                    if (it.operationalDate.isBefore(occurrence.operationalDate)) 1 else 0
                }
                    .thenBy { if (it.operationalDate.isBefore(occurrence.operationalDate)) -it.operationalDate.toEpochDay() else it.operationalDate.toEpochDay() }
                    .thenBy { it.id },
            )
    }

    fun recentLogsForTask(taskId: Int, limit: Int = Int.MAX_VALUE): List<HabitLogUi> {
        return logs
            .filter { it.taskId == taskId }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    fun workoutDayForOccurrence(occurrence: HabitOccurrenceUi): HabitWorkoutDayUi? {
        val task = taskById(occurrence.taskId) ?: return null
        val position = occurrence.sequenceItemPosition ?: return null
        return task.workoutDays.firstOrNull { it.position == position }
    }

    fun setExerciseCheckStatus(
        occurrenceId: Int,
        exerciseId: Int,
        status: ExerciseCheckStatus,
    ): Job? {
        val occurrenceIndex = occurrences.indexOfFirst { it.id == occurrenceId }
        if (occurrenceIndex == -1) return null
        val occurrence = occurrences[occurrenceIndex]
        val workoutDay = workoutDayForOccurrence(occurrence) ?: return null
        if (workoutDay.exercises.none { it.id == exerciseId }) return null
        val updatedChecks = occurrence.exerciseChecks.toMutableMap().apply {
            if (status == ExerciseCheckStatus.PENDING) remove(exerciseId) else put(exerciseId, status)
        }
        val requiredExercises = workoutDay.exercises.filter { it.requirement == ExerciseRequirement.REQUIRED }
        val allRequiredComplete = requiredExercises.isNotEmpty() && requiredExercises.all {
            updatedChecks[it.id] == ExerciseCheckStatus.COMPLETED
        }
        val updatedStatus = when {
            allRequiredComplete && occurrence.status == HabitStatus.Pending -> HabitStatus.Completed
            !allRequiredComplete && occurrence.status == HabitStatus.Completed -> HabitStatus.Pending
            else -> occurrence.status
        }
        occurrences[occurrenceIndex] = occurrence.copy(
            status = updatedStatus,
            exerciseChecks = updatedChecks,
        )
        if (updatedStatus != occurrence.status) {
            addLog(
                taskId = occurrence.taskId,
                occurrenceId = occurrence.id,
                action = if (updatedStatus == HabitStatus.Completed) HabitLogAction.Completed else HabitLogAction.Edited,
                note = if (updatedStatus == HabitStatus.Completed) {
                    "Completed all required exercises"
                } else {
                    "Reopened workout day after an exercise was unchecked"
                },
                logOperationalDate = occurrence.operationalDate,
            )
        }
        return scope?.launch {
            repository?.setExerciseCheckStatus(
                occurrenceId = occurrenceId.toLong(),
                sequenceExerciseId = exerciseId.toLong(),
                status = status,
                currentOperationalDate = operationalDate,
            )
            reloadFromDatabase()
        }
    }

    fun activeRoutinePhaseForTask(taskId: Int): HabitRoutinePhaseUi? {
        return routinePhases.firstOrNull {
            it.taskId == taskId && it.status == RoutinePhaseStatus.ACTIVE
        }
    }

    fun exerciseTimerFor(
        occurrenceId: Int,
        exerciseId: Int,
        durationSeconds: Int,
    ): HabitExerciseTimerUi {
        val duration = durationSeconds.coerceAtLeast(1)
        val existing = exerciseTimers[ExerciseTimerKey(occurrenceId, exerciseId)]
        return existing?.takeIf { it.durationSeconds == duration }
            ?: HabitExerciseTimerUi(durationSeconds = duration)
    }

    fun toggleExerciseTimer(
        occurrenceId: Int,
        exerciseId: Int,
        durationSeconds: Int,
        exerciseName: String = "Exercise timer",
    ) {
        val key = ExerciseTimerKey(occurrenceId, exerciseId)
        val now = timerNowProvider()
        val current = exerciseTimerFor(occurrenceId, exerciseId, durationSeconds)
        if (current.isRunning) {
            exerciseTimerController?.cancelTimer(occurrenceId, exerciseId)
            exerciseTimers[key] = current.copy(
                remainingSeconds = remainingExerciseTimerSeconds(current, now),
                endsAtEpochMillis = null,
            )
            return
        }
        val secondsToRun = current.remainingSeconds
            .takeIf { it > 0 }
            ?: current.durationSeconds
        val endsAtEpochMillis = now + secondsToRun * 1_000L
        exerciseTimers[key] = current.copy(
            remainingSeconds = secondsToRun,
            endsAtEpochMillis = endsAtEpochMillis,
        )
        exerciseTimerController?.startTimer(
            occurrenceId = occurrenceId,
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            endsAtEpochMillis = endsAtEpochMillis,
        )
    }

    fun hasRunningExerciseTimers(): Boolean {
        return exerciseTimers.values.any { it.isRunning }
    }

    fun tickExerciseTimers(): Int {
        val now = timerNowProvider()
        var completedCount = 0
        exerciseTimers.toMap().forEach { (key, timer) ->
            if (!timer.isRunning) return@forEach
            val remaining = remainingExerciseTimerSeconds(timer, now)
            if (remaining == 0) {
                exerciseTimers[key] = timer.copy(
                    remainingSeconds = 0,
                    endsAtEpochMillis = null,
                )
                completedCount += 1
            } else if (remaining != timer.remainingSeconds) {
                exerciseTimers[key] = timer.copy(remainingSeconds = remaining)
            }
        }
        return completedCount
    }

    private fun remainingExerciseTimerSeconds(timer: HabitExerciseTimerUi, now: Long): Int {
        val remainingMillis = ((timer.endsAtEpochMillis ?: now) - now).coerceAtLeast(0L)
        return ((remainingMillis + 999L) / 1_000L)
            .toInt()
            .coerceIn(0, timer.durationSeconds)
    }

    fun pendingRoutinePhaseReviews(): List<HabitRoutinePhaseUi> {
        return routinePhases
            .filter { phase ->
                val activatedDate = phase.activatedDate ?: return@filter false
                val task = taskById(phase.taskId) ?: return@filter false
                val nextReviewDate = phase.lastReviewedDate?.plusDays(ROUTINE_PHASE_EXTENSION_DAYS)
                phase.status == RoutinePhaseStatus.ACTIVE &&
                    phase.advanceMode == PhaseAdvanceMode.MANUAL &&
                    task.isActive &&
                    !task.archived &&
                    !operationalDate.isBefore(activatedDate.plusDays(phase.minimumDays.toLong())) &&
                    (nextReviewDate == null || !operationalDate.isBefore(nextReviewDate))
            }
            .sortedWith(compareBy<HabitRoutinePhaseUi> { it.routinePlanName }.thenBy { it.position })
    }

    fun extendRoutinePhaseOneWeek(phaseId: Int): Job? {
        val index = routinePhases.indexOfFirst { it.id == phaseId }
        if (index == -1) return null
        routinePhases[index] = routinePhases[index].copy(lastReviewedDate = operationalDate)
        return scope?.launch {
            repository?.extendRoutinePhaseOneWeek(phaseId.toLong(), operationalDate)
            reloadFromDatabase()
        }
    }

    fun advanceRoutinePhase(phaseId: Int): Job? {
        return scope?.launch {
            repository?.advanceRoutinePhase(phaseId.toLong(), operationalDate)
            reloadFromDatabase()
        }
    }

    fun completeOccurrence(occurrenceId: Int): Job? {
        updateOccurrenceStatus(occurrenceId, HabitStatus.Completed, HabitLogAction.Completed, "Marked complete")
        return scope?.launch {
            repository?.completeOccurrence(occurrenceId.toLong(), operationalDate, "Marked complete")
        }
    }

    fun canCompleteOccurrenceYesterday(occurrenceId: Int): Boolean {
        val occurrence = occurrences.firstOrNull { it.id == occurrenceId } ?: return false
        val task = taskById(occurrence.taskId) ?: return false
        val completedDate = operationalDate.minusDays(1)
        val nextPending = occurrences
            .filter {
                it.taskId == occurrence.taskId &&
                    it.status == HabitStatus.Pending &&
                    it.operationalDate.isAfter(completedDate)
            }
            .minWithOrNull(compareBy<HabitOccurrenceUi> { it.operationalDate }.thenBy { it.id })
        return task.pushable &&
            occurrence.status == HabitStatus.Pending &&
            occurrence.operationalDate == operationalDate &&
            occurrence.id == nextPending?.id &&
            occurrences
                .filter {
                    it.id != occurrence.id &&
                        it.taskId == occurrence.taskId &&
                        it.operationalDate == completedDate
                }
                .all {
                    it.isShifted &&
                        it.status in setOf(HabitStatus.Shifted, HabitStatus.Missed)
            }
    }

    fun completeOccurrenceYesterday(occurrenceId: Int): Job? {
        val index = occurrences.indexOfFirst { it.id == occurrenceId }
        if (index == -1) return null

        val occurrence = occurrences[index]
        val task = taskById(occurrence.taskId) ?: return null
        if (!canCompleteOccurrenceYesterday(occurrenceId)) return null

        val completedDate = operationalDate.minusDays(1)
        val completedDateIndex = occurrences.indexOfFirst {
            it.id != occurrence.id &&
                it.taskId == occurrence.taskId &&
                it.operationalDate == completedDate
        }
        val completedDateOccurrence = completedDateIndex.takeIf { it != -1 }?.let { occurrences[it] }
        val completesExistingShiftedDate = completedDateOccurrence != null
        if (
            completedDateOccurrence != null &&
            (
                !completedDateOccurrence.isShifted ||
                    completedDateOccurrence.status !in setOf(HabitStatus.Shifted, HabitStatus.Missed)
            )
        ) {
            return null
        }

        val futurePending = occurrences
            .mapIndexedNotNull { occurrenceIndex, candidate ->
                val shouldShift = candidate.taskId == occurrence.taskId &&
                    candidate.status == HabitStatus.Pending &&
                    candidate.operationalDate.isAfter(occurrence.operationalDate)
                if (shouldShift) occurrenceIndex else null
            }
            .sortedBy { occurrences[it].operationalDate }
        val pendingToRealign = if (completesExistingShiftedDate) {
            listOf(index) + futurePending
        } else {
            futurePending
        }
        val pendingToRealignIds = pendingToRealign.map { occurrences[it].id }.toSet()
        val occupiedDates = occurrences
            .filter { it.taskId == occurrence.taskId }
            .filter {
                it.id !in pendingToRealignIds &&
                    it.id != completedDateOccurrence?.id &&
                    it.id != occurrence.id
            }
            .map { it.operationalDate }
            .toMutableSet()
        occupiedDates.add(completedDate)

        val spacingDays = task.scheduleSpacingDays()
        var cursor = completedDate.plusDays(spacingDays.toLong())
        val shiftedPendingDates = mutableListOf<Pair<Int, LocalDate>>()
        for (pendingIndex in pendingToRealign) {
            val shiftedDate = nextAvailableValidDate(cursor, task.blockedDays, occupiedDates) ?: return null
            occupiedDates.add(shiftedDate)
            shiftedPendingDates.add(occurrences[pendingIndex].id to shiftedDate)
            cursor = shiftedDate.plusDays(spacingDays.toLong())
        }

        val completedOccurrenceId = if (completedDateIndex != -1 && completedDateOccurrence != null) {
            occurrences[completedDateIndex] = completedDateOccurrence.copy(
                status = HabitStatus.Completed,
                note = completedDateOccurrence.note.ifBlank { "Completed yesterday" },
            )
            completedDateOccurrence.id
        } else {
            occurrences[index] = occurrence.copy(
                scheduledDate = completedDate,
                operationalDate = completedDate,
                status = HabitStatus.Completed,
                isShifted = true,
                originalDate = occurrence.originalDate ?: occurrence.operationalDate,
                note = occurrence.note.ifBlank { "Completed yesterday" },
            )
            occurrence.id
        }
        shiftedPendingDates.forEach { (pendingId, shiftedDate) ->
            val pendingIndex = occurrences.indexOfFirst { it.id == pendingId }
            if (pendingIndex == -1) return@forEach
            val pending = occurrences[pendingIndex]
            occurrences[pendingIndex] = pending.copy(
                scheduledDate = shiftedDate,
                operationalDate = shiftedDate,
                isShifted = true,
                originalDate = pending.originalDate ?: pending.operationalDate,
            )
        }
        repairPendingCadenceForTask(task.id)
        moveTaskStartDateBackIfNeeded(task.id, completedDate)
        addLog(
            taskId = occurrence.taskId,
            occurrenceId = completedOccurrenceId,
            action = HabitLogAction.Completed,
            note = "Completed yesterday; schedule shifted from ${occurrence.operationalDate.monthDayLabel()}",
            logOperationalDate = completedDate,
        )
        shiftDurationWindowForTask(
            taskId = task.id,
            dayDelta = ChronoUnit.DAYS.between(occurrence.operationalDate, completedDate),
        )
        return scope?.launch {
            repository?.completeOccurrenceYesterday(occurrenceId.toLong(), operationalDate)
            reloadFromDatabase()
        }
    }

    fun skipOccurrence(occurrenceId: Int): Job? {
        updateOccurrenceStatus(occurrenceId, HabitStatus.Skipped, HabitLogAction.Skipped, "Skipped intentionally")
        return scope?.launch {
            repository?.skipOccurrence(occurrenceId.toLong(), operationalDate, "Skipped intentionally")
        }
    }

    fun shiftOccurrenceForward(occurrenceId: Int): Job? {
        val index = occurrences.indexOfFirst { it.id == occurrenceId }
        if (index == -1) return null

        val occurrence = occurrences[index]
        val task = taskById(occurrence.taskId) ?: return null
        if (task.type != HabitTaskType.Sequence || occurrence.status !in setOf(HabitStatus.Pending, HabitStatus.Missed)) return null
        val futureIndexes = occurrences
            .mapIndexedNotNull { occurrenceIndex, candidate ->
                val shouldShift = candidate.taskId == occurrence.taskId &&
                    candidate.status == HabitStatus.Pending &&
                    candidate.operationalDate.isAfter(occurrence.operationalDate)
                if (shouldShift) occurrenceIndex else null
            }
            .sortedBy { occurrences[it].operationalDate }
        val futureIds = futureIndexes.map { occurrences[it].id }.toSet()
        val occupiedDates = occurrences
            .filter { it.taskId == occurrence.taskId }
            .filter { it.id != occurrence.id && it.id !in futureIds }
            .map { it.operationalDate }
            .toMutableSet()
        val nextDate = nextAvailableValidDate(occurrence.operationalDate.plusDays(1), task.blockedDays, occupiedDates) ?: return null
        occupiedDates.add(nextDate)

        occurrences[index] = occurrence.copy(
            status = if (occurrence.status == HabitStatus.Missed) HabitStatus.Missed else HabitStatus.Shifted,
            isShifted = true,
            originalDate = occurrence.originalDate ?: occurrence.operationalDate,
            note = occurrence.note.ifBlank { "Pushed forward" },
        )
        occurrences.add(
            occurrence.copy(
                id = nextOccurrenceId++,
                scheduledDate = nextDate,
                operationalDate = nextDate,
                status = HabitStatus.Pending,
                isShifted = true,
                originalDate = occurrence.operationalDate,
                note = "",
            ),
        )

        val spacingDays = task.scheduleSpacingDays()
        var cursor = nextDate.plusDays(spacingDays.toLong())
        for (futureIndex in futureIndexes) {
            val future = occurrences[futureIndex]
            val shiftedDate = nextAvailableValidDate(cursor, task.blockedDays, occupiedDates) ?: return null
            occupiedDates.add(shiftedDate)
            occurrences[futureIndex] = future.copy(
                scheduledDate = shiftedDate,
                operationalDate = shiftedDate,
                isShifted = true,
                originalDate = future.originalDate ?: future.operationalDate,
            )
            cursor = shiftedDate.plusDays(spacingDays.toLong())
        }
        repairPendingCadenceForTask(task.id)
        addLog(
            taskId = occurrence.taskId,
            occurrenceId = occurrence.id,
            action = HabitLogAction.ShiftedForward,
            note = "Pushed to ${nextDate.monthDayLabel()}",
            logOperationalDate = occurrence.operationalDate,
        )
        shiftDurationWindowForTask(
            taskId = task.id,
            dayDelta = ChronoUnit.DAYS.between(occurrence.operationalDate, nextDate),
        )
        return scope?.launch {
            repository?.shiftSequenceForward(occurrenceId.toLong(), operationalDate)
            reloadFromDatabase()
        }
    }

    fun pushOccurrenceForward(occurrenceId: Int): Job? {
        val index = occurrences.indexOfFirst { it.id == occurrenceId }
        if (index == -1) return null

        val occurrence = occurrences[index]
        val task = taskById(occurrence.taskId) ?: return null
        if (!task.pushable || occurrence.status !in setOf(HabitStatus.Pending, HabitStatus.Missed)) {
            return null
        }
        val futureIndexes = occurrences
            .mapIndexedNotNull { occurrenceIndex, candidate ->
                val shouldShift = candidate.taskId == occurrence.taskId &&
                    candidate.status == HabitStatus.Pending &&
                    candidate.operationalDate.isAfter(occurrence.operationalDate)
                if (shouldShift) occurrenceIndex else null
            }
            .sortedBy { occurrences[it].operationalDate }
        val futureIds = futureIndexes.map { occurrences[it].id }.toSet()
        val occupiedDates = occurrences
            .filter { it.taskId == occurrence.taskId }
            .filter { it.id != occurrence.id && it.id !in futureIds }
            .map { it.operationalDate }
            .toMutableSet()
        val nextDate = nextAvailableValidDate(occurrence.operationalDate.plusDays(1), task.blockedDays, occupiedDates) ?: return null
        occupiedDates.add(nextDate)

        occurrences[index] = occurrence.copy(
            status = if (occurrence.status == HabitStatus.Missed) HabitStatus.Missed else HabitStatus.Shifted,
            isShifted = true,
            originalDate = occurrence.originalDate ?: occurrence.operationalDate,
            note = occurrence.note.ifBlank { "Pushed forward" },
        )
        occurrences.add(
            occurrence.copy(
                id = nextOccurrenceId++,
                scheduledDate = nextDate,
                operationalDate = nextDate,
                status = HabitStatus.Pending,
                isShifted = true,
                originalDate = occurrence.operationalDate,
                note = "",
            ),
        )

        val spacingDays = task.scheduleSpacingDays()
        var cursor = nextDate.plusDays(spacingDays.toLong())
        for (futureIndex in futureIndexes) {
            val future = occurrences[futureIndex]
            val shiftedDate = nextAvailableValidDate(cursor, task.blockedDays, occupiedDates) ?: return null
            occupiedDates.add(shiftedDate)
            occurrences[futureIndex] = future.copy(
                scheduledDate = shiftedDate,
                operationalDate = shiftedDate,
                isShifted = true,
                originalDate = future.originalDate ?: future.operationalDate,
            )
            cursor = shiftedDate.plusDays(spacingDays.toLong())
        }
        repairPendingCadenceForTask(task.id)
        addLog(
            taskId = occurrence.taskId,
            occurrenceId = occurrence.id,
            action = HabitLogAction.ShiftedForward,
            note = "Pushed to ${nextDate.monthDayLabel()}",
            logOperationalDate = occurrence.operationalDate,
        )
        shiftDurationWindowForTask(
            taskId = task.id,
            dayDelta = ChronoUnit.DAYS.between(occurrence.operationalDate, nextDate),
        )
        return scope?.launch {
            repository?.pushOccurrenceForward(occurrenceId.toLong(), operationalDate)
            reloadFromDatabase()
        }
    }

    fun undoOccurrenceDecision(occurrenceId: Int): Job? {
        val index = occurrences.indexOfFirst { it.id == occurrenceId }
        if (index == -1) return null
        val occurrence = occurrences[index]
        val task = taskById(occurrence.taskId) ?: return null
        if (
            task.type == HabitTaskType.LongTerm &&
            occurrence.status == HabitStatus.Completed &&
            occurrence.originalDate != null
        ) {
            val restoredStartDate = occurrence.originalDate
            val resetIndex = occurrences.indexOfFirst { it.id == occurrence.id }
            if (resetIndex != -1) {
                occurrences[resetIndex] = occurrence.copy(
                    status = HabitStatus.Pending,
                    originalDate = null,
                    note = occurrence.note.takeUnless { it in ACTION_GENERATED_NOTES }.orEmpty(),
                )
            }
            restoreLongTermFutureAfterCompletionUndo(task, occurrence, restoredStartDate)
            recentlyCompletedLongTermOccurrenceIds.remove(occurrence.id)
            addLog(
                occurrence.taskId,
                occurrence.id,
                HabitLogAction.Edited,
                "Undid long-term completion",
                occurrence.operationalDate,
            )
        } else if (
            occurrence.status == HabitStatus.Completed &&
            occurrence.isShifted &&
            occurrence.originalDate != null &&
            occurrence.originalDate.isAfter(occurrence.operationalDate)
        ) {
            val restoredDate = occurrence.originalDate
            val shiftedDays = ChronoUnit.DAYS.between(occurrence.operationalDate, restoredDate)
            occurrences.indices
                .filter { candidateIndex ->
                    val candidate = occurrences[candidateIndex]
                    candidate.taskId == occurrence.taskId &&
                        candidate.status == HabitStatus.Pending &&
                        candidate.originalDate != null &&
                        candidate.operationalDate.isAfter(occurrence.operationalDate)
                }
                .sortedByDescending { occurrences[it].operationalDate }
                .forEach { candidateIndex ->
                    val candidate = occurrences[candidateIndex]
                    val originalDate = candidate.originalDate ?: return@forEach
                    occurrences[candidateIndex] = candidate.copy(
                        scheduledDate = originalDate,
                        operationalDate = originalDate,
                        isShifted = false,
                        originalDate = null,
                    )
                }
            occurrences[index] = occurrence.copy(
                scheduledDate = restoredDate,
                operationalDate = restoredDate,
                status = HabitStatus.Pending,
                isShifted = false,
                originalDate = null,
                note = occurrence.note.takeUnless { it in ACTION_GENERATED_NOTES }.orEmpty(),
            )
            restoreTaskStartDateAfterCompletedYesterdayUndo(task.id, occurrence.operationalDate, restoredDate)
            if (shiftedDays != 0L) {
                shiftDurationWindowForTask(task.id, shiftedDays)
            }
            addLog(occurrence.taskId, occurrence.id, HabitLogAction.Edited, "Undid completed-yesterday shift", occurrence.operationalDate)
        } else if (occurrence.status == HabitStatus.Shifted && occurrence.isShifted) {
            val replacementIndex = occurrences.indexOfFirst {
                it.id != occurrence.id &&
                    it.taskId == occurrence.taskId &&
                    it.status == HabitStatus.Pending &&
                    it.originalDate == occurrence.operationalDate
            }
            val shiftedDays = if (replacementIndex != -1) {
                ChronoUnit.DAYS.between(occurrence.operationalDate, occurrences[replacementIndex].operationalDate)
            } else {
                0L
            }
            if (replacementIndex != -1) {
                occurrences.removeAt(replacementIndex)
            }
            val resetIndex = occurrences.indexOfFirst { it.id == occurrence.id }
            if (resetIndex != -1) {
                occurrences[resetIndex] = occurrence.copy(
                    status = HabitStatus.Pending,
                    isShifted = false,
                    originalDate = null,
                    note = occurrence.note.takeUnless { it in ACTION_GENERATED_NOTES }.orEmpty(),
                )
            }
            for (candidateIndex in occurrences.indices) {
                val candidate = occurrences[candidateIndex]
                occurrences[candidateIndex] = if (
                    candidate.taskId == occurrence.taskId &&
                    candidate.status == HabitStatus.Pending &&
                    candidate.originalDate != null &&
                    candidate.operationalDate.isAfter(occurrence.operationalDate)
                ) {
                    candidate.copy(
                        scheduledDate = candidate.originalDate,
                        operationalDate = candidate.originalDate,
                        isShifted = false,
                        originalDate = null,
                    )
                } else {
                    candidate
                }
            }
            if (shiftedDays != 0L) {
                shiftDurationWindowForTask(task.id, -shiftedDays)
            }
            repairPendingCadenceForTask(task.id)
            addLog(occurrence.taskId, occurrence.id, HabitLogAction.Edited, "Undid push", occurrence.operationalDate)
        } else if (occurrence.status in setOf(HabitStatus.Completed, HabitStatus.Skipped, HabitStatus.Missed)) {
            occurrences[index] = occurrence.copy(
                status = HabitStatus.Pending,
                note = occurrence.note.takeUnless { it in ACTION_GENERATED_NOTES }.orEmpty(),
            )
            recentlyCompletedLongTermOccurrenceIds.remove(occurrence.id)
            addLog(occurrence.taskId, occurrence.id, HabitLogAction.Edited, "Reset checklist decision", occurrence.operationalDate)
        } else {
            return null
        }
        return scope?.launch {
            repository?.undoOccurrenceDecision(occurrenceId.toLong(), operationalDate)
            reloadFromDatabase()
        }
    }

    fun swapSequenceOccurrenceItems(occurrenceId: Int, targetOccurrenceId: Int): Job? {
        if (occurrenceId == targetOccurrenceId) return null
        val occurrenceIndex = occurrences.indexOfFirst { it.id == occurrenceId }
        val targetIndex = occurrences.indexOfFirst { it.id == targetOccurrenceId }
        if (occurrenceIndex == -1 || targetIndex == -1) return null
        val occurrence = occurrences[occurrenceIndex]
        val target = occurrences[targetIndex]
        val task = taskById(occurrence.taskId) ?: return null
        if (
            task.type != HabitTaskType.Sequence ||
            occurrence.taskId != target.taskId ||
            occurrence.status != HabitStatus.Pending ||
            occurrence.sequenceItemName == null ||
            target.sequenceItemName == null ||
            occurrence.sequenceItemName == target.sequenceItemName ||
            target.operationalDate.isBefore(task.startDate) ||
            target.status != HabitStatus.Pending ||
            target.operationalDate.isBefore(occurrence.operationalDate)
        ) {
            return null
        }

        occurrences[occurrenceIndex] = occurrence.copy(
            sequenceItemName = target.sequenceItemName,
            sequenceItemPosition = target.sequenceItemPosition,
            note = target.note,
            exerciseChecks = target.exerciseChecks,
        )
        occurrences[targetIndex] = target.copy(
            sequenceItemName = occurrence.sequenceItemName,
            sequenceItemPosition = occurrence.sequenceItemPosition,
            note = occurrence.note,
            exerciseChecks = occurrence.exerciseChecks,
        )
        addLog(
            taskId = occurrence.taskId,
            occurrenceId = occurrence.id,
            action = HabitLogAction.Edited,
            note = "Swapped sequence item with ${target.operationalDate.monthDayLabel()}",
            logOperationalDate = operationalDate,
        )
        return scope?.launch {
            repository?.swapSequenceOccurrenceItems(
                occurrenceId = occurrenceId.toLong(),
                targetOccurrenceId = targetOccurrenceId.toLong(),
                currentOperationalDate = operationalDate,
            )
            reloadFromDatabase()
        }
    }

    fun setSequenceOccurrencePoint(occurrenceId: Int, targetSequenceIndex: Int): Job? {
        val occurrenceIndex = occurrences.indexOfFirst { it.id == occurrenceId }
        if (occurrenceIndex == -1) return null
        val occurrence = occurrences[occurrenceIndex]
        val task = taskById(occurrence.taskId) ?: return null
        if (
            task.type != HabitTaskType.Sequence ||
            occurrence.status != HabitStatus.Pending ||
            occurrence.sequenceItemName == null ||
            targetSequenceIndex !in task.sequenceItems.indices
        ) {
            return null
        }

        occurrences[occurrenceIndex] = occurrence.copy(
            sequenceItemName = task.sequenceItems[targetSequenceIndex],
            sequenceItemPosition = targetSequenceIndex,
            exerciseChecks = emptyMap(),
        )
        occurrences
            .mapIndexedNotNull { index, candidate ->
                val shouldUpdate = candidate.taskId == occurrence.taskId &&
                    candidate.status == HabitStatus.Pending &&
                    candidate.operationalDate.isAfter(occurrence.operationalDate)
                if (shouldUpdate) index to candidate else null
            }
            .sortedWith(compareBy<Pair<Int, HabitOccurrenceUi>> { it.second.operationalDate }.thenBy { it.second.id })
            .forEachIndexed { offset, (index, candidate) ->
                val sequenceIndex = Math.floorMod(targetSequenceIndex + offset + 1, task.sequenceItems.size)
                occurrences[index] = candidate.copy(
                    sequenceItemName = task.sequenceItems[sequenceIndex],
                    sequenceItemPosition = sequenceIndex,
                    exerciseChecks = emptyMap(),
                )
            }
        addLog(
            taskId = occurrence.taskId,
            occurrenceId = occurrence.id,
            action = HabitLogAction.Edited,
            note = "Set sequence point to ${task.sequenceItems[targetSequenceIndex]}",
            logOperationalDate = operationalDate,
        )
        return scope?.launch {
            repository?.setSequenceOccurrencePoint(
                occurrenceId = occurrenceId.toLong(),
                targetSequenceIndex = targetSequenceIndex,
                currentOperationalDate = operationalDate,
            )
            reloadFromDatabase()
        }
    }

    fun updateOccurrenceNote(occurrenceId: Int, note: String) {
        val index = occurrences.indexOfFirst { it.id == occurrenceId }
        if (index == -1) return
        val occurrence = occurrences[index]
        occurrences[index] = occurrence.copy(note = note.trim())
        addLog(
            taskId = occurrence.taskId,
            occurrenceId = occurrence.id,
            action = HabitLogAction.Edited,
            note = "Updated occurrence note",
            logOperationalDate = occurrence.operationalDate,
        )
        scope?.launch {
            repository?.updateOccurrenceNote(occurrenceId.toLong(), note)
        }
    }

    fun selectTaskForDetail(taskId: Int) {
        detailTaskId = taskId
        currentDestination = AppDestination.Stats
    }

    fun openTaskCalendar(taskId: Int) {
        calendarTaskFilterId = taskId
        selectCalendarDate(operationalDate)
        currentDestination = AppDestination.Calendar
    }

    fun moveCalendarMonth(monthDelta: Long) {
        showCalendarMonth(calendarMonth.plusMonths(monthDelta))
    }

    fun showCalendarToday() {
        selectCalendarDate(operationalDate)
    }

    fun selectCalendarDate(date: LocalDate) {
        selectedCalendarDate = date
        calendarMonth = YearMonth.from(date)
    }

    private fun showCalendarMonth(month: YearMonth) {
        val selectedDay = min(selectedCalendarDate.dayOfMonth, month.lengthOfMonth())
        calendarMonth = month
        selectedCalendarDate = month.atDay(selectedDay)
    }

    fun editTask(task: HabitTaskUi) {
        draft = HabitTaskDraft.fromTask(task)
        currentDestination = AppDestination.Tasks
    }

    fun clearDraft() {
        draft = defaultDraft()
    }

    fun saveDraft(): Job? {
        return saveDraft(draft, clearCurrentDraft = true)
    }

    fun quickAddOneTimeTask(name: String): Job? {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return null
        val today = operationalDate
        val quickDraft = defaultDraft().copy(
            name = trimmedName,
            type = HabitTaskType.OneTime,
            startDate = today,
            endDate = today,
            durationDays = 1,
            startsAfterTaskId = null,
            blockedDays = emptySet(),
            skipBlockedDaysBehavior = defaultSkipBlockedDaysBehavior(HabitTaskType.Simple),
            pushable = true,
            noActionBehavior = NoActionBehavior.AUTO_PUSH,
        )
        return saveDraft(quickDraft, clearCurrentDraft = false)
    }

    internal fun importPhases(
        phases: List<PhaseImportPhase>,
        firstStartDate: LocalDate,
    ): Job? {
        val timeline = buildPhaseImportTimeline(phases, firstStartDate)
        if (timeline.isEmpty()) return null

        val importedTasks = mutableListOf<HabitTaskUi>()
        val structuredPlan = phases.all { it.structured }
        var previousTaskId: Int? = null
        timeline.forEachIndexed { index, row ->
            val expectedTaskId = nextTaskId
            val phaseDraft = row.phase.toTaskDraft(
                startDate = row.startDate,
                startsAfterTaskId = previousTaskId,
            ).copy(
                isActive = !structuredPlan || index == 0,
            )
            saveDraft(
                sourceDraft = phaseDraft,
                clearCurrentDraft = false,
                persist = false,
            )
            val importedTask = taskById(expectedTaskId) ?: return null
            importedTasks += importedTask
            previousTaskId = importedTask.id
        }
        if (structuredPlan) {
            val temporaryPlanId = -(routinePhases.maxOfOrNull { kotlin.math.abs(it.routinePlanId) } ?: 0) - 1
            routinePhases.addAll(
                importedTasks.mapIndexed { index, task ->
                    val phase = phases[index]
                    HabitRoutinePhaseUi(
                        id = -(routinePhases.size + index + 1),
                        routinePlanId = temporaryPlanId,
                        routinePlanName = "${phases.first().name} routine",
                        taskId = task.id,
                        position = index,
                        advanceMode = phase.advanceMode,
                        minimumDays = phase.durationDays,
                        progressionNote = phase.progressionNote,
                        status = if (index == 0) RoutinePhaseStatus.ACTIVE else RoutinePhaseStatus.UPCOMING,
                        activatedDate = firstStartDate.takeIf { index == 0 },
                        lastReviewedDate = null,
                        nextPhaseName = importedTasks.getOrNull(index + 1)?.name,
                    )
                },
            )
        }
        clearDraft()
        return scope?.launch {
            importedTasks.forEach { task ->
                persistTask(
                    task = task,
                    isNew = true,
                    generatePendingOccurrences = !structuredPlan || task.isActive,
                )
            }
            if (structuredPlan) {
                val now = LocalDateTime.now()
                repository?.createRoutinePlan(
                    plan = RoutinePlanEntity(
                        name = "${phases.first().name} routine",
                        createdAt = now,
                        updatedAt = now,
                    ),
                    phases = importedTasks.mapIndexed { index, task ->
                        val phase = phases[index]
                        RoutinePhaseEntity(
                            routinePlanId = 0,
                            taskId = task.id.toLong(),
                            position = index,
                            advanceMode = phase.advanceMode,
                            minimumDays = phase.durationDays,
                            progressionNote = phase.progressionNote,
                            status = if (index == 0) RoutinePhaseStatus.ACTIVE else RoutinePhaseStatus.UPCOMING,
                            activatedDate = firstStartDate.takeIf { index == 0 },
                            createdAt = now,
                            updatedAt = now,
                        )
                    },
                )
            }
            reloadFromDatabase()
        }
    }

    private fun saveDraft(
        sourceDraft: HabitTaskDraft,
        clearCurrentDraft: Boolean,
        persist: Boolean = true,
    ): Job? {
        val trimmedName = sourceDraft.name.trim()
        if (trimmedName.isBlank()) return null

        val sequenceItems = parseSequenceEditorText(sourceDraft.sequenceText)
        val type = sourceDraft.type
        val validStartsAfterTaskId = if (type == HabitTaskType.LongTerm) {
            null
        } else {
            sourceDraft.startsAfterTaskId
                ?.takeIf { it != sourceDraft.id }
                ?.takeIf { parentId ->
                    taskById(parentId)?.let { it.durationDays != null && it.endDate != null } == true
                }
        }
        val normalizedStartDate = validStartsAfterTaskId
            ?.let { taskById(it)?.endDate?.plusDays(1) }
            ?: sourceDraft.startDate
        val normalizedDurationDays = if (type == HabitTaskType.LongTerm) {
            null
        } else if (type == HabitTaskType.OneTime) {
            1
        } else {
            sourceDraft.durationDays?.coerceAtLeast(1)
        }
        val normalizedEndDate = normalizedDurationDays
            ?.let { normalizedStartDate.plusDays((it - 1).toLong()) }
            ?: sourceDraft.endDate?.let { maxOf(it, normalizedStartDate) }
        val scheduledWeekdays = if (type == HabitTaskType.Weekday) {
            sourceDraft.weekdays.ifEmpty { setOf(normalizedStartDate.dayOfWeek) }
        } else {
            emptySet()
        }
        val cycleForTask = normalizedCycleForDraft(sourceDraft, trimmedName, normalizedStartDate, type)
        val resolvedTaskId = sourceDraft.id ?: nextTaskId++
        val workoutDays = if (type == HabitTaskType.Sequence) {
            sourceDraft.workoutDays
                .filter { it.position in sequenceItems.indices }
                .map { day ->
                    day.copy(
                        title = sequenceItems[day.position],
                        exercises = day.exercises.mapIndexed { exerciseIndex, exercise ->
                            exercise.copy(
                                id = exercise.id.takeIf { it != 0 }
                                    ?: temporaryExerciseId(resolvedTaskId, day.position, exerciseIndex),
                                position = exerciseIndex,
                            )
                        },
                    )
                }
        } else {
            emptyList()
        }
        var task = HabitTaskUi(
            id = resolvedTaskId,
            name = trimmedName,
            type = type,
            notes = sourceDraft.notes.trim(),
            recurrenceSummary = recurrenceSummary(
                sourceDraft.copy(
                    startDate = normalizedStartDate,
                    endDate = normalizedEndDate,
                    durationDays = normalizedDurationDays,
                    startsAfterTaskId = validStartsAfterTaskId,
                    longTermRecurrenceUnit = sourceDraft.longTermRecurrenceUnit,
                    longTermRecurrenceAnchor = sourceDraft.longTermRecurrenceAnchor,
                    weekdays = scheduledWeekdays,
                ),
                sequenceItems,
            ),
            startDate = normalizedStartDate,
            endDate = normalizedEndDate,
            durationDays = normalizedDurationDays,
            startsAfterTaskId = validStartsAfterTaskId,
            intervalDays = when (type) {
                HabitTaskType.Interval -> sourceDraft.intervalDays.coerceAtLeast(2)
                HabitTaskType.Sequence -> sourceDraft.sequenceSpacingDays.coerceAtLeast(1)
                HabitTaskType.LongTerm -> sourceDraft.intervalDays.coerceAtLeast(1)
                else -> null
            },
            longTermRecurrenceUnit = sourceDraft.longTermRecurrenceUnit,
            longTermRecurrenceAnchor = sourceDraft.longTermRecurrenceAnchor,
            weekdays = scheduledWeekdays,
            blockedDays = sourceDraft.blockedDays,
            skipBlockedDaysBehavior = sourceDraft.skipBlockedDaysBehavior,
            sequenceItems = if (type == HabitTaskType.Sequence) sequenceItems.ifEmpty { listOf("Workout") } else emptyList(),
            workoutDays = workoutDays,
            timeOfDay = sourceDraft.timeOfDay,
            pushable = type == HabitTaskType.OneTime ||
                (
                    type != HabitTaskType.LongTerm &&
                        (sourceDraft.pushable || sourceDraft.noActionBehavior == NoActionBehavior.AUTO_PUSH)
                ),
            noActionBehavior = when (type) {
                HabitTaskType.LongTerm -> NoActionBehavior.MARK_MISSED
                HabitTaskType.OneTime -> NoActionBehavior.AUTO_PUSH
                else -> sourceDraft.noActionBehavior
            },
            reminderEnabled = sourceDraft.reminderEnabled,
            calendarVisible = sourceDraft.calendarVisible,
            isActive = sourceDraft.isActive && !sourceDraft.archived,
            archived = sourceDraft.archived,
            cycleGroupId = cycleForTask?.id,
            cycleGroupName = cycleForTask?.name.orEmpty(),
            cycleDurationDays = cycleForTask?.durationDays ?: 14,
            cycleResetThresholdPercent = cycleForTask?.resetThresholdPercent ?: 50,
            cycleRestartBehavior = cycleForTask?.restartBehavior ?: CycleRestartBehavior.OFF,
            cycleRestartTiming = cycleForTask?.restartTiming ?: CycleRestartTiming.TODAY,
            cycleBlockedDays = cycleForTask?.blockedDays.orEmpty(),
        )
        val restartDate = cycleForTask
            ?.takeIf { it.restartBehavior == CycleRestartBehavior.AUTO_RESTART }
            ?.takeIf { shouldRestartCycleTimingOnSave(task, it) }
            ?.let { cycle ->
                val date = restartDateForCycleTiming(cycle)
                val restartedCycle = cycle.copy(
                    currentStartDate = date,
                    lastRestartedAt = nowProvider(),
                )
                upsertUiCycle(restartedCycle)
                task = task.copy(
                    startDate = date,
                    endDate = date.plusDays((cycle.durationDays.coerceAtLeast(1) - 1).toLong()),
                    cycleDurationDays = cycle.durationDays.coerceAtLeast(1),
                ).withUpdatedRecurrenceSummary()
                date
            }

        val existingIndex = tasks.indexOfFirst { it.id == task.id }
        if (existingIndex == -1) {
            tasks.add(task)
            if (restartDate != null) {
                restartCycleTimingOccurrences(task, restartDate)
            } else {
                addStarterOccurrence(task)
            }
            addLog(task.id, null, HabitLogAction.Edited, "Created task")
        } else {
            val previousTask = tasks[existingIndex]
            tasks[existingIndex] = task
            if (restartDate != null) {
                restartCycleTimingOccurrences(task, restartDate)
                addLog(task.id, null, HabitLogAction.Edited, "Auto restarted cycle timing")
            } else if (task.hasSameScheduleDefinitionAs(previousTask)) {
                addLog(task.id, null, HabitLogAction.Edited, "Updated task metadata")
            } else {
                regenerateFuturePendingOccurrences(task, previousTask)
                addLog(task.id, null, HabitLogAction.Edited, "Updated task and refreshed future pending occurrences")
            }
        }
        val persistenceJob = if (persist) {
            scope?.launch {
                persistTask(task, existingIndex == -1)
                reloadFromDatabase()
            }
        } else {
            null
        }
        detailTaskId = task.id
        if (clearCurrentDraft) {
            clearDraft()
        }
        return persistenceJob
    }

    private fun normalizedCycleForDraft(
        sourceDraft: HabitTaskDraft,
        taskName: String,
        taskStartDate: LocalDate,
        type: HabitTaskType,
    ): HabitCycleUi? {
        if (type == HabitTaskType.LongTerm) return null
        val durationDays = sourceDraft.durationDays?.coerceAtLeast(1) ?: return null
        if (sourceDraft.cycleRestartBehavior == CycleRestartBehavior.OFF) return null
        val selectedCycle = sourceDraft.cycleGroupId?.let { cycleById(it) }
        val cycleName = "$taskName auto restart"
        val cycle = HabitCycleUi(
            id = selectedCycle?.id ?: nextCycleId++,
            name = cycleName,
            durationDays = durationDays,
            resetThresholdPercent = sourceDraft.cycleResetThresholdPercent.coerceIn(1, 100),
            restartBehavior = sourceDraft.cycleRestartBehavior,
            restartTiming = sourceDraft.cycleRestartTiming,
            blockedDays = sourceDraft.cycleBlockedDays,
            currentStartDate = selectedCycle?.currentStartDate ?: taskStartDate,
            lastRestartedAt = selectedCycle?.lastRestartedAt,
        )
        upsertUiCycle(cycle)
        return cycle
    }

    private fun upsertUiCycle(cycle: HabitCycleUi) {
        val existingIndex = cycleGroups.indexOfFirst { it.id == cycle.id }
        if (existingIndex == -1) {
            cycleGroups.add(cycle)
        } else {
            cycleGroups[existingIndex] = cycle
        }
    }

    private fun shouldRestartCycleTimingOnSave(task: HabitTaskUi, cycle: HabitCycleUi): Boolean {
        val disruptedDateCount = disruptedDatesForCycleTiming(task.id, cycle).size
        if (disruptedDateCount >= cycleThresholdDays(cycle.durationDays, cycle.resetThresholdPercent)) {
            return true
        }
        val cycleEnd = cycle.currentStartDate.plusDays((cycle.durationDays.coerceAtLeast(1) - 1).toLong())
        val hasUpcomingPending = occurrences.any {
            it.taskId == task.id &&
                it.status == HabitStatus.Pending &&
                !it.operationalDate.isBefore(operationalDate)
        }
        return !operationalDate.isBefore(cycleEnd) && !hasUpcomingPending
    }

    private fun disruptedDatesForCycleTiming(taskId: Int, cycle: HabitCycleUi): Set<LocalDate> {
        val cycleStart = cycle.currentStartDate
        val cycleEnd = cycleStart.plusDays((cycle.durationDays.coerceAtLeast(1) - 1).toLong())
        return disruptedDatesForWindow(taskId, cycleStart, cycleEnd)
    }

    private fun disruptedDatesForWindow(taskId: Int, cycleStart: LocalDate, cycleEnd: LocalDate): Set<LocalDate> {
        return occurrences
            .filter { occurrence ->
                occurrence.taskId == taskId &&
                    occurrence.status in setOf(HabitStatus.Skipped, HabitStatus.Missed, HabitStatus.Shifted) &&
                    !occurrence.operationalDate.isBefore(cycleStart) &&
                    !occurrence.operationalDate.isAfter(cycleEnd)
            }
            .map { it.operationalDate }
            .toSet()
    }

    private fun restartDateForCycleTiming(cycle: HabitCycleUi): LocalDate {
        val baseDate = when (cycle.restartTiming) {
            CycleRestartTiming.TODAY -> operationalDate
            CycleRestartTiming.TOMORROW -> operationalDate.plusDays(1)
            CycleRestartTiming.NEXT_VALID_DAY -> operationalDate
        }
        return nextValidDate(baseDate, cycle.blockedDays) ?: baseDate
    }

    private fun restartCycleTimingOccurrences(task: HabitTaskUi, restartDate: LocalDate) {
        occurrences.removeAll {
            it.taskId == task.id &&
                it.status == HabitStatus.Pending &&
                !it.operationalDate.isBefore(restartDate)
        }
        val occupiedDates = occurrences
            .filter { it.taskId == task.id }
            .map { it.operationalDate }
            .toMutableSet()
        generatedOccurrencesFor(task, restartDate, 370)
            .filter { it.date !in occupiedDates }
            .forEach { generated ->
                occupiedDates.add(generated.date)
                occurrences.add(
                    HabitOccurrenceUi(
                        id = nextOccurrenceId++,
                        taskId = task.id,
                        scheduledDate = generated.date,
                        operationalDate = generated.date,
                        status = HabitStatus.Pending,
                        sequenceItemName = generated.sequenceItemName,
                        sequenceItemPosition = generated.sequenceItemPosition,
                    ),
                )
            }
        realignCycleTasksStartingAfter(task.id)
    }

    fun archiveTask(taskId: Int, archived: Boolean): Job? {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index == -1) return null
        val task = tasks[index]
        tasks[index] = task.copy(archived = archived, isActive = !archived)
        addLog(taskId, null, HabitLogAction.Edited, if (archived) "Archived task" else "Restored task")
        return scope?.launch {
            repository?.setTaskArchived(taskId.toLong(), archived, operationalDate)
        }
    }

    fun archiveRoutinePlan(planId: Int, archived: Boolean): Job? {
        val phases = routinePhases.filter { it.routinePlanId == planId }
        if (phases.isEmpty()) return null
        val phaseByTaskId = phases.associateBy { it.taskId }
        tasks.indices.forEach { index ->
            val task = tasks[index]
            val phase = phaseByTaskId[task.id] ?: return@forEach
            tasks[index] = task.copy(
                archived = archived,
                isActive = !archived && phase.status == RoutinePhaseStatus.ACTIVE,
            )
            addLog(
                task.id,
                null,
                HabitLogAction.Edited,
                if (archived) "Archived phased routine" else "Restored phased routine",
            )
        }
        return scope?.launch {
            repository?.setRoutinePlanArchived(planId.toLong(), archived, operationalDate)
            reloadFromDatabase()
        }
    }

    fun deleteTaskPermanently(taskId: Int): Job? {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index == -1) return null
        tasks.removeAt(index)
        occurrences.removeAll { it.taskId == taskId }
        logs.removeAll { it.taskId == taskId }
        if (detailTaskId == taskId) {
            detailTaskId = tasks.firstOrNull()?.id
        }
        if (calendarTaskFilterId == taskId) {
            calendarTaskFilterId = null
        }
        if (draft.id == taskId) {
            clearDraft()
        }
        return scope?.launch {
            repository?.deleteTaskPermanently(taskId.toLong())
            reloadFromDatabase()
        }
    }

    fun deleteRoutinePlanPermanently(planId: Int): Job? {
        val taskIds = routinePhases
            .filter { it.routinePlanId == planId }
            .map { it.taskId }
            .toSet()
        if (taskIds.isEmpty()) return null
        tasks.removeAll { it.id in taskIds }
        occurrences.removeAll { it.taskId in taskIds }
        logs.removeAll { it.taskId in taskIds }
        routinePhases.removeAll { it.routinePlanId == planId }
        if (detailTaskId?.let { it in taskIds } == true) {
            detailTaskId = tasks.firstOrNull()?.id
        }
        if (calendarTaskFilterId?.let { it in taskIds } == true) {
            calendarTaskFilterId = null
        }
        if (draft.id?.let { it in taskIds } == true) {
            clearDraft()
        }
        return scope?.launch {
            repository?.deleteRoutinePlanPermanently(planId.toLong())
            reloadFromDatabase()
        }
    }

    fun statsFor(taskId: Int): HabitStatsUi {
        val pastOccurrences = occurrences
            .filter {
                it.taskId == taskId &&
                    (
                        it.operationalDate.isBefore(operationalDate) ||
                            (it.operationalDate == operationalDate && it.status != HabitStatus.Pending)
                    )
            }
            .sortedBy { it.operationalDate }
        val total = pastOccurrences.size.coerceAtLeast(1)
        val completed = pastOccurrences.count { it.status == HabitStatus.Completed }
        val skipped = pastOccurrences.count { it.status == HabitStatus.Skipped }
        val missed = pastOccurrences.count { it.status == HabitStatus.Missed }
        val shifted = pastOccurrences.count {
            it.status == HabitStatus.Shifted || (it.status == HabitStatus.Missed && it.isShifted)
        }
        val completion = ((completed.toFloat() / total.toFloat()) * 100).roundToInt()
        return HabitStatsUi(
            currentStreak = currentStreak(pastOccurrences),
            longestStreak = longestStreak(pastOccurrences),
            completionPercentage = completion,
            completed = completed,
            skipped = skipped,
            missed = missed,
            shifted = shifted,
            pastTotal = pastOccurrences.size,
            skipRate = ((skipped.toFloat() / total.toFloat()) * 100).roundToInt(),
            missRate = ((missed.toFloat() / total.toFloat()) * 100).roundToInt(),
        )
    }

    fun updateSettings(transform: (ReminderSettingsUi) -> ReminderSettingsUi): Job? {
        val previous = settings
        val updated = transform(settings)
        settings = updated
        if (draft.id == null && draft.name.isBlank() && draft.blockedDays == previous.defaultBlockedDays) {
            draft = draft.copy(blockedDays = updated.defaultBlockedDays)
        }
        return scope?.launch {
            settingsRepository?.updateDayRolloverTime(updated.dayRolloverTime)
            settingsRepository?.updateReminderTimes(updated.dailyReviewTime, updated.lateReminderTime)
            settingsRepository?.updateTaskReminderTimes(
                updated.morningTaskReminderTime,
                updated.noonTaskReminderTime,
                updated.eveningTaskReminderTime,
            )
            settingsRepository?.updateReminderEnabled(
                updated.dailyReviewEnabled,
                updated.lateReminderEnabled,
                updated.taskTimeReminderEnabled,
            )
            settingsRepository?.updateDefaultBlockedDays(updated.defaultBlockedDays.toSettingsString())
            settingsRepository?.updateThemePreference(updated.themePreference)
            settingsRepository?.updateAutoBackup(
                enabled = updated.autoBackupEnabled,
                intervalDays = updated.autoBackupIntervalDays,
                folderUri = updated.autoBackupFolderUri,
            )
            val scheduled = reminderScheduler?.schedule(updated.toReminderSnapshot())
            withContext(Dispatchers.Main) {
                updateReminderScheduleStatus(scheduled)
            }
            appContext?.let { enqueueMaintenance(it, updated.dayRolloverTime) }
            appContext?.let { configureAutoBackup(it, updated.toReminderSnapshot()) }
        }
    }

    fun rescheduleReminders(): Job? {
        return scope?.launch {
            val scheduled = reminderScheduler?.schedule(settings.toReminderSnapshot())
            withContext(Dispatchers.Main) {
                updateReminderScheduleStatus(scheduled)
            }
        }
    }

    fun markExactAlarmPromptShown(): Job? {
        settings = settings.copy(exactAlarmPermissionPromptShown = true)
        return scope?.launch {
            settingsRepository?.setExactAlarmPermissionPromptShown(true)
        }
    }

    suspend fun reloadSettings() {
        val snapshot = settingsRepository?.settings?.first() ?: return
        withContext(Dispatchers.Main) {
            settings = snapshot.toUiSettings()
            if (draft.id == null && draft.name.isBlank() && draft.blockedDays.isEmpty()) {
                draft = defaultDraft()
            }
            if (selectedCalendarDate == LocalDate.now()) {
                selectedCalendarDate = operationalDate
                calendarMonth = YearMonth.from(operationalDate)
            }
        }
        val scheduled = reminderScheduler?.schedule(snapshot)
        withContext(Dispatchers.Main) {
            updateReminderScheduleStatus(scheduled)
        }
        appContext?.let { enqueueMaintenance(it, snapshot.dayRolloverTime) }
        appContext?.let { configureAutoBackup(it, snapshot) }
    }

    suspend fun reloadAfterRestore() {
        reloadSettings()
        reloadFromDatabase()
    }

    suspend fun reloadFromDatabase() {
        val activeDao = dao ?: return
        withContext(Dispatchers.IO) {
            repository?.advanceDueRoutinePhases(operationalDate)
            repository?.markOverduePendingMissed(operationalDate)
            repository?.extendGeneratedOccurrences(operationalDate)
            repository?.repairPendingCadences(operationalDate)
            repository?.restartEndedCycles(operationalDate)
            val dbTasks = activeDao.tasks(includeArchived = true)
            val dbRules = activeDao.allRules().associateBy { it.taskId }
            val dbSequences = activeDao.allSequences()
                .groupBy { it.taskId }
                .mapValues { (_, sequences) -> sequences.maxBy { it.id } }
            val dbSequenceItems = activeDao.allSequenceItems()
            val dbSequenceExercises = activeDao.allSequenceExercises()
            val dbExerciseChecks = activeDao.allOccurrenceExerciseChecks()
            val dbRoutinePlans = activeDao.allRoutinePlans()
            val dbRoutinePhases = activeDao.allRoutinePhases()
            val dbCycleGroups = activeDao.allCycleGroups()
            val dbCycleMemberships = activeDao.allCycleMemberships()
            val cycleById = dbCycleGroups.associateBy { it.id }
            val cycleMembershipByTaskId = dbCycleMemberships.associateBy { it.taskId }
            val itemsBySequenceId = dbSequenceItems.groupBy { it.sequenceId }
            val exercisesBySequenceItemId = dbSequenceExercises.groupBy { it.sequenceItemId }
            val exerciseChecksByOccurrenceId = dbExerciseChecks.groupBy { it.occurrenceId }
            val itemNameById = dbSequenceItems.associate { it.id to it.name }
            val itemPositionById = dbSequenceItems.associate { it.id to it.position }
            val dbOccurrences = activeDao.allOccurrences()
            val dbLogs = activeDao.allLogs()
            val dbCycleLogs = activeDao.allCycleLogs()
            val nextTaskIdFromDatabase = ((dbTasks.maxOfOrNull { it.id.toInt() } ?: 0) + 1).coerceAtLeast(nextTaskId)
            val nextOccurrenceIdFromDatabase = ((dbOccurrences.maxOfOrNull { it.id.toInt() } ?: 0) + 1).coerceAtLeast(nextOccurrenceId)
            val nextLogIdFromDatabase = ((dbLogs.maxOfOrNull { it.id.toInt() } ?: 0) + 1).coerceAtLeast(nextLogId)
            val nextCycleIdFromDatabase = ((dbCycleGroups.maxOfOrNull { it.id.toInt() } ?: 0) + 1).coerceAtLeast(nextCycleId)
            val nextCycleLogIdFromDatabase = ((dbCycleLogs.maxOfOrNull { it.id.toInt() } ?: 0) + 1).coerceAtLeast(nextCycleLogId)

            withContext(Dispatchers.Main) {
                cycleGroups.clear()
                cycleGroups.addAll(dbCycleGroups.map { it.toUiCycle() })
                tasks.clear()
                tasks.addAll(
                    dbTasks.map { task ->
                        val rule = dbRules[task.id]
                        val cycle = cycleMembershipByTaskId[task.id]
                            ?.let { membership -> cycleById[membership.cycleGroupId] }
                        val sequenceItems = if (task.taskType == TaskType.SEQUENCE_ROUTINE) dbSequences[task.id]
                            ?.let { sequence -> itemsBySequenceId[sequence.id].orEmpty().sortedBy { it.position }.map { it.name } }
                            .orEmpty() else emptyList()
                        val currentSequenceItems = if (task.taskType == TaskType.SEQUENCE_ROUTINE) {
                            dbSequences[task.id]
                                ?.let { sequence -> itemsBySequenceId[sequence.id].orEmpty().sortedBy { it.position } }
                                .orEmpty()
                        } else {
                            emptyList()
                        }
                        val hasStructuredDays = currentSequenceItems.any { item ->
                            item.notes.isNotBlank() || exercisesBySequenceItemId[item.id].orEmpty().isNotEmpty()
                        }
                        val workoutDays = if (hasStructuredDays) {
                            currentSequenceItems.map { item ->
                                HabitWorkoutDayUi(
                                    position = item.position,
                                    title = item.name,
                                    notes = item.notes,
                                    exercises = exercisesBySequenceItemId[item.id]
                                        .orEmpty()
                                        .sortedBy { it.position }
                                        .map { it.toUiWorkoutExercise() },
                                )
                            }
                        } else {
                            emptyList()
                        }
                        task.toUiTask(rule, sequenceItems, workoutDays, cycle)
                    },
                )
                occurrences.clear()
                occurrences.addAll(
                    dbOccurrences.map { occurrence ->
                        occurrence.toUiOccurrence(
                            itemNameById = itemNameById,
                            itemPositionById = itemPositionById,
                            exerciseChecks = exerciseChecksByOccurrenceId[occurrence.id]
                                .orEmpty()
                                .associate { it.sequenceExerciseId.toInt() to it.status },
                        )
                    },
                )
                logs.clear()
                logs.addAll(dbLogs.map { it.toUiLog() })
                cycleLogs.clear()
                cycleLogs.addAll(dbCycleLogs.map { it.toUiCycleLog() })
                val planNameById = dbRoutinePlans.associate { it.id to it.name }
                val taskNameById = dbTasks.associate { it.id to it.name }
                val phasesByPlanId = dbRoutinePhases.groupBy { it.routinePlanId }
                routinePhases.clear()
                routinePhases.addAll(
                    dbRoutinePhases.map { phase ->
                        val nextPhase = phasesByPlanId[phase.routinePlanId]
                            .orEmpty()
                            .filter { it.position > phase.position }
                            .minByOrNull { it.position }
                        phase.toUiRoutinePhase(
                            routinePlanName = planNameById[phase.routinePlanId].orEmpty(),
                            nextPhaseName = nextPhase?.let { taskNameById[it.taskId] },
                        )
                    },
                )
                detailTaskId = detailTaskId ?: tasks.firstOrNull()?.id
                nextTaskId = nextTaskIdFromDatabase
                nextOccurrenceId = nextOccurrenceIdFromDatabase
                nextLogId = nextLogIdFromDatabase
                nextCycleId = nextCycleIdFromDatabase
                nextCycleLogId = nextCycleLogIdFromDatabase
            }
        }
    }

    fun setBackupTarget(uriDescription: String?) {
        backupStatus = if (uriDescription == null) {
            "Backup cancelled"
        } else {
            "Backup target selected"
        }
        addLog(tasks.firstOrNull()?.id ?: 0, null, HabitLogAction.Backup, backupStatus)
    }

    fun prepareManualBackup(launchDocument: (String) -> Unit): Job? {
        val activeRepository = backupRepository ?: run {
            backupStatus = "Backup unavailable"
            return null
        }
        backupStatus = "Preparing and validating backup..."
        return scope?.launch {
            val result = activeRepository.prepareManualBackup()
            result.fold(
                onSuccess = { prepared ->
                    preparedManualBackup = prepared
                    backupStatus = "Backup prepared: ${backupByteCountLabel(prepared.byteCount.toLong())}"
                    launchDocument(prepared.pendingDisplayName)
                },
                onFailure = { error ->
                    preparedManualBackup = null
                    backupStatus = manualBackupFailureStatus(error)
                },
            )
        }
    }

    fun completePreparedManualBackup(uri: Uri): Job? {
        val activeRepository = backupRepository ?: run {
            backupStatus = "Backup unavailable"
            return null
        }
        val prepared = preparedManualBackup ?: run {
            backupStatus = "Backup failed: prepared data was unavailable"
            scope?.launch { activeRepository.discardPendingDocument(uri) }
            return null
        }
        preparedManualBackup = null
        backupStatus = "Uploading and verifying backup..."
        return scope?.launch {
            val result = activeRepository.exportPreparedManualBackup(uri, prepared)
            if (result.isSuccess) reloadSettings()
            backupStatus = result.fold(
                onSuccess = { receipt ->
                    "Backup verified: ${backupByteCountLabel(receipt.byteCount.toLong())}"
                },
                onFailure = ::manualBackupFailureStatus,
            )
        }
    }

    fun cancelPreparedManualBackup(): Job? {
        val prepared = preparedManualBackup
        preparedManualBackup = null
        backupStatus = "Backup cancelled"
        return scope?.launch {
            backupRepository?.discardPreparedManualBackup(prepared)
        }
    }

    fun runAutoBackupNow(): Job? {
        val activeRepository = backupRepository ?: run {
            backupStatus = "Auto backup unavailable"
            return null
        }
        val folderUri = settings.autoBackupFolderUri.takeIf { it.isNotBlank() } ?: run {
            backupStatus = "Choose an auto backup folder first"
            return null
        }
        backupStatus = "Uploading and verifying auto backup..."
        return scope?.launch {
            val result = activeRepository.exportToAutoBackupFolder(Uri.parse(folderUri))
            reloadSettings()
            backupStatus = result.fold(
                onSuccess = { receipt ->
                    "Auto backup verified: ${backupByteCountLabel(receipt.byteCount.toLong())}"
                },
                onFailure = { error -> "Auto backup failed: ${backupFailureLabel(error)}" },
            )
        }
    }

    fun setRestoreSource(uriDescription: String?) {
        restoreStatus = if (uriDescription == null) {
            "Restore cancelled"
        } else {
            "Restore source selected"
        }
        addLog(tasks.firstOrNull()?.id ?: 0, null, HabitLogAction.Restore, restoreStatus)
    }

    private suspend fun seedDatabaseFromUi() {
        val activeDatabase = database ?: return
        activeDatabase.withTransaction {
            val now = LocalDateTime.now()
            val dbTasks = tasks.map { it.toEntity(now) }
            val dbRules = tasks.map { it.toRuleEntity(now) }
            val dbCycleGroups = cycleGroups.map { it.toEntity(now) }
            val dbCycleById = dbCycleGroups.associateBy { it.id.toInt() }
            val dbCycleMemberships = tasks.mapNotNull { task ->
                task.toCycleMembershipEntity(now, task.cycleGroupId?.let { dbCycleById[it] })
            }
            val dbCycleLogs = cycleLogs.map { it.toEntity() }
            val dbSequences = tasks
                .filter { it.sequenceItems.isNotEmpty() }
                .map { WorkoutSequenceEntity(id = it.id.toLong(), taskId = it.id.toLong(), name = it.name, createdAt = now, updatedAt = now) }
            val dbSequenceItems = tasks.flatMap { task ->
                task.sequenceItems.mapIndexed { index, item ->
                    SequenceItemEntity(
                        id = sequenceItemEntityId(task.id, index),
                        sequenceId = task.id.toLong(),
                        name = item,
                        position = index,
                        notes = task.workoutDays.firstOrNull { it.position == index }?.notes.orEmpty(),
                    )
                }
            }
            val dbSequenceExercises = tasks.flatMap { task ->
                task.workoutDays.flatMap { day ->
                    day.exercises.mapIndexed { exerciseIndex, exercise ->
                        SequenceExerciseEntity(
                            id = sequenceExerciseEntityId(task.id, day.position, exerciseIndex),
                            sequenceItemId = sequenceItemEntityId(task.id, day.position),
                            position = exerciseIndex,
                            name = exercise.name,
                            prescription = exercise.prescription,
                            instructions = exercise.instructions,
                            requirement = exercise.requirement,
                        )
                    }
                }
            }
            val exerciseEntityIdByUiId = tasks.flatMap { task ->
                task.workoutDays.flatMap { day ->
                    day.exercises.mapIndexed { exerciseIndex, exercise ->
                        exercise.id to sequenceExerciseEntityId(task.id, day.position, exerciseIndex)
                    }
                }
            }.toMap()
            val sequenceItemIdByTaskAndName = dbSequenceItems.associateBy { it.sequenceId.toInt() to it.name }
            val sequenceItemIdByTaskAndPosition = dbSequenceItems.associateBy { it.sequenceId.toInt() to it.position }
            dao?.restoreTasks(dbTasks)
            dao?.restoreRules(dbRules)
            dao?.restoreCycleGroups(dbCycleGroups)
            dao?.restoreCycleMemberships(dbCycleMemberships)
            dao?.restoreSequences(dbSequences)
            dao?.restoreSequenceItems(dbSequenceItems)
            dao?.restoreSequenceExercises(dbSequenceExercises)
            dao?.restoreOccurrences(
                occurrences.map {
                    it.toEntity(
                        recurrenceRuleId = it.taskId.toLong(),
                        sequenceItemId = it.sequenceItemPosition
                            ?.let { position -> sequenceItemIdByTaskAndPosition[it.taskId to position]?.id }
                            ?: it.sequenceItemName?.let { name -> sequenceItemIdByTaskAndName[it.taskId to name]?.id },
                    )
                },
            )
            dao?.restoreOccurrenceExerciseChecks(
                occurrences.flatMap { occurrence ->
                    occurrence.exerciseChecks.mapNotNull { (exerciseUiId, status) ->
                        exerciseEntityIdByUiId[exerciseUiId]?.let { exerciseEntityId ->
                            OccurrenceExerciseCheckEntity(
                                occurrenceId = occurrence.id.toLong(),
                                sequenceExerciseId = exerciseEntityId,
                                status = status,
                                updatedAt = now,
                            )
                        }
                    }
                },
            )
            dao?.restoreLogs(logs.map { it.toEntity() })
            dao?.restoreCycleLogs(dbCycleLogs)
        }
    }

    private suspend fun persistTask(
        task: HabitTaskUi,
        isNew: Boolean,
        generatePendingOccurrences: Boolean = true,
    ) {
        val activeRepository = repository ?: return
        val now = LocalDateTime.now()
        val taskEntity = task.toEntity(now)
        val ruleEntity = task.toRuleEntity(now)
        val cycleGroupEntity = task.cycleGroupId?.let { cycleById(it)?.toEntity(now) }
        val cycleMembershipEntity = task.toCycleMembershipEntity(now, cycleGroupEntity)
        val sequenceItems = task.sequenceItems.mapIndexed { index, item ->
            SequenceItemEntity(
                sequenceId = 0,
                name = item,
                position = index,
                notes = task.workoutDays.firstOrNull { it.position == index }?.notes.orEmpty(),
            )
        }
        val sequenceExercisesByPosition = task.workoutDays.associate { day ->
            day.position to day.exercises.mapIndexed { exerciseIndex, exercise ->
                SequenceExerciseEntity(
                    sequenceItemId = 0,
                    position = exerciseIndex,
                    name = exercise.name,
                    prescription = exercise.prescription,
                    instructions = exercise.instructions,
                    requirement = exercise.requirement,
                )
            }
        }
        if (isNew) {
            activeRepository.createTaskWithRule(
                task = taskEntity,
                rule = ruleEntity.copy(taskId = task.id.toLong()),
                sequenceItems = sequenceItems,
                sequenceExercisesByPosition = sequenceExercisesByPosition,
                cycleGroup = cycleGroupEntity,
                cycleMembership = cycleMembershipEntity,
                generateThrough = generationThroughForTask(task, operationalDate),
                generatePendingOccurrences = generatePendingOccurrences,
            )
        } else {
            activeRepository.editRuleAndRegenerate(
                task = taskEntity,
                rule = ruleEntity,
                sequenceItems = sequenceItems,
                sequenceExercisesByPosition = sequenceExercisesByPosition,
                cycleGroup = cycleGroupEntity,
                cycleMembership = cycleMembershipEntity,
                currentOperationalDate = operationalDate,
                generateThrough = generationThroughForTask(task, operationalDate),
            )
        }
    }

    private fun updateOccurrenceStatus(
        occurrenceId: Int,
        status: HabitStatus,
        action: HabitLogAction,
        note: String,
    ) {
        val index = occurrences.indexOfFirst { it.id == occurrenceId }
        if (index == -1) return
        val occurrence = occurrences[index]
        if (occurrence.status != HabitStatus.Pending) return
        val task = taskById(occurrence.taskId) ?: return
        if (task.type == HabitTaskType.LongTerm && status != HabitStatus.Completed) return
        val completionAnchoredLongTerm = task.type == HabitTaskType.LongTerm &&
            status == HabitStatus.Completed &&
            task.longTermRecurrenceAnchor == LongTermRecurrenceAnchor.COMPLETION_DATE
        if (task.type == HabitTaskType.LongTerm && status == HabitStatus.Completed) {
            if (occurrence.id !in recentlyCompletedLongTermOccurrenceIds) {
                recentlyCompletedLongTermOccurrenceIds.add(occurrence.id)
            }
        }
        occurrences[index] = occurrence.copy(
            status = status,
            note = occurrence.note.ifBlank { note },
            originalDate = if (completionAnchoredLongTerm) occurrence.originalDate ?: task.startDate else occurrence.originalDate,
        )
        if (completionAnchoredLongTerm) {
            realignLongTermFutureAfterCompletion(
                task = task,
                completedOccurrenceDate = occurrence.operationalDate,
                completionDate = operationalDate,
            )
        }
        addLog(
            taskId = occurrence.taskId,
            occurrenceId = occurrence.id,
            action = action,
            note = note,
            logOperationalDate = occurrence.operationalDate,
        )
    }

    private fun addStarterOccurrence(task: HabitTaskUi) {
        val firstOccurrence = generatedOccurrencesFor(task, maxOf(task.startDate, operationalDate), 1).firstOrNull() ?: return
        occurrences.add(
            HabitOccurrenceUi(
                id = nextOccurrenceId++,
                taskId = task.id,
                scheduledDate = firstOccurrence.date,
                operationalDate = firstOccurrence.date,
                status = HabitStatus.Pending,
                sequenceItemName = firstOccurrence.sequenceItemName,
                sequenceItemPosition = firstOccurrence.sequenceItemPosition,
            ),
        )
    }

    private fun regenerateFuturePendingOccurrences(task: HabitTaskUi, previousTask: HabitTaskUi) {
        val futurePendingIndexes = occurrences
            .mapIndexedNotNull { index, occurrence ->
                val shouldReplace = occurrence.taskId == task.id &&
                    occurrence.status == HabitStatus.Pending &&
                    occurrence.operationalDate.isAfter(operationalDate)
                if (shouldReplace) index else null
            }
            .asReversed()
        futurePendingIndexes.forEach { occurrences.removeAt(it) }

        val sequenceStartIndex = if (task.type == HabitTaskType.Sequence && task.sequenceItems.isNotEmpty()) {
            val previousOccurrence = occurrences
                .filter { it.taskId == task.id && it.operationalDate < operationalDate.plusDays(1) }
                .maxWithOrNull(compareBy<HabitOccurrenceUi> { it.operationalDate }.thenBy { it.id })
            val previousIndex = previousOccurrence?.sequenceItemPosition
                ?.takeIf { it in previousTask.sequenceItems.indices }
                ?: previousTask.sequenceItems.indexOf(previousOccurrence?.sequenceItemName)
            if (previousIndex == -1) 0 else (previousIndex + 1) % task.sequenceItems.size
        } else {
            0
        }
        val generatedOccurrences = generatedOccurrencesFor(task, operationalDate.plusDays(1), 60, sequenceStartIndex)
        generatedOccurrences.forEach { generated ->
            occurrences.add(
                HabitOccurrenceUi(
                    id = nextOccurrenceId++,
                    taskId = task.id,
                    scheduledDate = generated.date,
                    operationalDate = generated.date,
                    status = HabitStatus.Pending,
                    sequenceItemName = generated.sequenceItemName,
                    sequenceItemPosition = generated.sequenceItemPosition,
                ),
            )
        }
    }

    private fun realignLongTermFutureAfterCompletion(
        task: HabitTaskUi,
        completedOccurrenceDate: LocalDate,
        completionDate: LocalDate,
    ) {
        occurrences
            .mapIndexedNotNull { index, occurrence ->
                val shouldReplace = occurrence.taskId == task.id &&
                    occurrence.status == HabitStatus.Pending &&
                    occurrence.operationalDate.isAfter(completedOccurrenceDate)
                if (shouldReplace) index else null
            }
            .asReversed()
            .forEach { occurrences.removeAt(it) }

        val anchoredTask = task.copy(startDate = completionDate).withUpdatedRecurrenceSummary()
        val taskIndex = tasks.indexOfFirst { it.id == task.id }
        if (taskIndex != -1) {
            tasks[taskIndex] = anchoredTask
        }
        addGeneratedPendingOccurrences(
            task = anchoredTask,
            fromDate = completionDate.plusDays(1),
            count = 60,
        )
    }

    private fun restoreLongTermFutureAfterCompletionUndo(
        task: HabitTaskUi,
        occurrence: HabitOccurrenceUi,
        restoredStartDate: LocalDate,
    ) {
        occurrences.removeAll {
            it.taskId == task.id &&
                it.status == HabitStatus.Pending &&
                it.operationalDate.isAfter(occurrence.operationalDate)
        }
        val restoredTask = task.copy(startDate = restoredStartDate).withUpdatedRecurrenceSummary()
        val taskIndex = tasks.indexOfFirst { it.id == task.id }
        if (taskIndex != -1) {
            tasks[taskIndex] = restoredTask
        }
        addGeneratedPendingOccurrences(
            task = restoredTask,
            fromDate = occurrence.operationalDate.plusDays(1),
            count = 60,
        )
    }

    private fun addGeneratedPendingOccurrences(
        task: HabitTaskUi,
        fromDate: LocalDate,
        count: Int,
    ) {
        val occupiedDates = occurrences
            .filter { it.taskId == task.id }
            .map { it.operationalDate }
            .toMutableSet()
        generatedOccurrencesFor(task, fromDate, count)
            .filter { generated -> generated.date !in occupiedDates }
            .forEach { generated ->
                occupiedDates.add(generated.date)
                occurrences.add(
                    HabitOccurrenceUi(
                        id = nextOccurrenceId++,
                        taskId = task.id,
                        scheduledDate = generated.date,
                        operationalDate = generated.date,
                        status = HabitStatus.Pending,
                        sequenceItemName = generated.sequenceItemName,
                        sequenceItemPosition = generated.sequenceItemPosition,
                    ),
                )
            }
    }

    private fun shiftDurationWindowForTask(taskId: Int, dayDelta: Long) {
        if (dayDelta == 0L) return
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index == -1) return
        val task = tasks[index]
        if (task.durationDays == null || task.endDate == null) return
        val shiftedTask = task.copy(
            endDate = task.endDate.plusDays(dayDelta),
        ).withUpdatedRecurrenceSummary()
        tasks[index] = shiftedTask
        realignCycleTasksStartingAfter(taskId)
    }

    private fun moveTaskStartDateBackIfNeeded(taskId: Int, completedDate: LocalDate) {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index == -1) return
        val task = tasks[index]
        if (!completedDate.isBefore(task.startDate)) return
        tasks[index] = task.copy(
            startDate = completedDate,
        ).withUpdatedRecurrenceSummary()
    }

    private fun restoreTaskStartDateAfterCompletedYesterdayUndo(
        taskId: Int,
        completedDate: LocalDate,
        restoredDate: LocalDate,
    ) {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index == -1) return
        val task = tasks[index]
        if (task.startDate != completedDate || !restoredDate.isAfter(completedDate)) return
        tasks[index] = task.copy(
            startDate = restoredDate,
        ).withUpdatedRecurrenceSummary()
    }

    private fun realignCycleTasksStartingAfter(parentTaskId: Int) {
        val parent = taskById(parentTaskId) ?: return
        val parentEndDate = parent.endDate ?: return
        tasks
            .filter { it.startsAfterTaskId == parentTaskId }
            .forEach { task ->
                val index = tasks.indexOfFirst { it.id == task.id }
                if (index == -1) return@forEach
                val newStartDate = parentEndDate.plusDays(1)
                val newEndDate = task.durationDays?.let { newStartDate.plusDays((it - 1).toLong()) } ?: task.endDate
                val shiftedTask = task.copy(
                    startDate = newStartDate,
                    endDate = newEndDate,
                ).withUpdatedRecurrenceSummary()
                tasks[index] = shiftedTask
                regenerateFuturePendingOccurrences(shiftedTask, task)
                realignCycleTasksStartingAfter(task.id)
            }
    }

    private fun repairPendingCadenceForTask(taskId: Int) {
        val task = taskById(taskId) ?: return
        val spacingDays = task.scheduleSpacingDays()
        if (spacingDays <= 1) return
        val pending = occurrences
            .filter {
                it.taskId == taskId &&
                    it.status == HabitStatus.Pending &&
                    !it.operationalDate.isBefore(operationalDate)
            }
            .sortedWith(compareBy<HabitOccurrenceUi> { it.operationalDate }.thenBy { it.id })
        if (pending.size <= 1) return

        val pendingDates = pending.map { it.operationalDate }.toSet()
        val staleReplacementIds = pending
            .filter { item -> item.originalDate?.let { it in pendingDates } == true }
            .map { it.id }
            .toMutableSet()
        val candidates = pending.filter { it.id !in staleReplacementIds }
        if (candidates.size <= 1) {
            occurrences.removeAll { it.id in staleReplacementIds }
            return
        }

        val occupiedDates = occurrences
            .filter { it.taskId == taskId && it.status != HabitStatus.Pending }
            .map { it.operationalDate }
            .toMutableSet()
        val candidatesByDate = candidates.associateBy { it.operationalDate }
        val consumedIds = mutableSetOf<Int>()
        val deleteIds = staleReplacementIds.toMutableSet()
        val updates = mutableMapOf<Int, LocalDate>()
        var expectedDate = candidates.first().operationalDate

        while (consumedIds.size < candidates.size) {
            val exact = candidatesByDate[expectedDate]?.takeIf { it.id !in consumedIds }
            if (exact != null) {
                candidates
                    .filter { it.id !in consumedIds && it.operationalDate.isBefore(expectedDate) }
                    .forEach {
                        consumedIds.add(it.id)
                        deleteIds.add(it.id)
                    }
            }
            val item = exact ?: candidates.firstOrNull { it.id !in consumedIds } ?: break
            consumedIds.add(item.id)
            if (item.operationalDate != expectedDate) {
                updates[item.id] = expectedDate
            }
            occupiedDates.add(expectedDate)
            expectedDate = nextAvailableValidDate(
                startDate = expectedDate.plusDays(spacingDays.toLong()),
                blockedDays = task.blockedDays,
                occupiedDates = occupiedDates,
            ) ?: break
        }
        candidates
            .filter { it.id !in consumedIds }
            .forEach { deleteIds.add(it.id) }

        occurrences.removeAll { it.id in deleteIds }
        updates.forEach { (occurrenceId, repairedDate) ->
            val index = occurrences.indexOfFirst { it.id == occurrenceId }
            if (index == -1) return@forEach
            val occurrence = occurrences[index]
            occurrences[index] = occurrence.copy(
                scheduledDate = repairedDate,
                operationalDate = repairedDate,
                isShifted = true,
                originalDate = occurrence.originalDate ?: occurrence.operationalDate,
            )
        }
    }

    private fun addLog(
        taskId: Int,
        occurrenceId: Int?,
        action: HabitLogAction,
        note: String,
        logOperationalDate: LocalDate = operationalDate,
    ) {
        logs.add(
            HabitLogUi(
                id = nextLogId++,
                taskId = taskId,
                occurrenceId = occurrenceId,
                action = action,
                timestamp = LocalDateTime.now(),
                operationalDate = logOperationalDate,
                note = note,
            ),
        )
    }

    private fun updateReminderScheduleStatus(scheduled: Boolean?) {
        reminderScheduleStatus = when (scheduled) {
            true -> if (!settings.dailyReviewEnabled && !settings.lateReminderEnabled && !settings.taskTimeReminderEnabled) {
                "Reminders disabled"
            } else if (reminderScheduler?.canScheduleExactAlarms() == false) {
                "Reminders scheduled; exact alarm permission improves timing"
            } else {
                "Exact reminders scheduled"
            }
            false -> "Exact alarm permission needed"
            null -> "Reminder scheduling unavailable"
        }
    }

    private fun defaultDraft(): HabitTaskDraft {
        return HabitTaskDraft(
            startDate = operationalDate,
            blockedDays = settings.defaultBlockedDays,
        )
    }
}

class HabitTrackerViewModel(application: Application) : AndroidViewModel(application) {
    val store = HabitTrackerUiStore(application.applicationContext, viewModelScope)
}

@Composable
fun rememberHabitTrackerUiStore(): HabitTrackerUiStore {
    return viewModel<HabitTrackerViewModel>().store
}

private fun HabitTaskUi.toEntity(now: LocalDateTime): TaskEntity {
    return TaskEntity(
        id = id.toLong(),
        name = name,
        taskType = type.toDomainTaskType(),
        notes = notes,
        isActive = isActive,
        archived = archived,
        createdAt = now,
        updatedAt = now,
        defaultReminderEnabled = reminderEnabled,
        calendarVisible = calendarVisible,
        blockedDays = blockedDays,
        timeOfDay = timeOfDay,
        pushable = pushable,
        noActionBehavior = noActionBehavior,
    )
}

private fun HabitTaskUi.toRuleEntity(now: LocalDateTime): RecurrenceRuleEntity {
    return RecurrenceRuleEntity(
        id = id.toLong(),
        taskId = id.toLong(),
        ruleType = toRuleType(),
        intervalDays = when (type) {
            HabitTaskType.Interval,
            HabitTaskType.Sequence,
            HabitTaskType.LongTerm,
            -> intervalDays
            HabitTaskType.Simple,
            HabitTaskType.OneTime,
            HabitTaskType.Weekday,
            -> null
        },
        weekdays = weekdays,
        cycleDefinition = when (type) {
            HabitTaskType.Sequence -> sequenceItems.joinToString(",")
            HabitTaskType.LongTerm -> longTermRecurrenceAnchor.name
            HabitTaskType.Simple,
            HabitTaskType.OneTime,
            HabitTaskType.Interval,
            HabitTaskType.Weekday,
            -> ""
        },
        startDate = startDate,
        endDate = endDate,
        durationDays = durationDays,
        startsAfterTaskId = startsAfterTaskId?.toLong(),
        skipBlockedDaysBehavior = skipBlockedDaysBehavior,
        lastGeneratedDate = LocalDate.now().plusDays(if (type == HabitTaskType.LongTerm) 3655 else 60),
        createdAt = now,
        updatedAt = now,
    )
}

private fun HabitTaskUi.hasSameScheduleDefinitionAs(other: HabitTaskUi): Boolean {
    return type == other.type &&
        startDate == other.startDate &&
        endDate == other.endDate &&
        durationDays == other.durationDays &&
        startsAfterTaskId == other.startsAfterTaskId &&
        intervalDays == other.intervalDays &&
        longTermRecurrenceUnit == other.longTermRecurrenceUnit &&
        longTermRecurrenceAnchor == other.longTermRecurrenceAnchor &&
        weekdays == other.weekdays &&
        blockedDays == other.blockedDays &&
        skipBlockedDaysBehavior == other.skipBlockedDaysBehavior &&
        sequenceItems == other.sequenceItems &&
        workoutDays == other.workoutDays &&
        cycleGroupId == other.cycleGroupId &&
        cycleGroupName == other.cycleGroupName &&
        cycleDurationDays == other.cycleDurationDays &&
        cycleResetThresholdPercent == other.cycleResetThresholdPercent &&
        cycleRestartBehavior == other.cycleRestartBehavior &&
        cycleRestartTiming == other.cycleRestartTiming &&
        cycleBlockedDays == other.cycleBlockedDays
}

private fun HabitTaskUi.withUpdatedRecurrenceSummary(): HabitTaskUi {
    return copy(recurrenceSummary = recurrenceSummaryForTask(this))
}

private fun HabitOccurrenceUi.toEntity(
    recurrenceRuleId: Long,
    sequenceItemId: Long?,
): ScheduledOccurrenceEntity {
    val now = LocalDateTime.now()
    return ScheduledOccurrenceEntity(
        id = id.toLong(),
        taskId = taskId.toLong(),
        recurrenceRuleId = recurrenceRuleId,
        scheduledDate = scheduledDate,
        operationalDate = operationalDate,
        status = status.toDomainStatus(),
        sequenceItemId = sequenceItemId,
        isShifted = isShifted,
        originalDate = originalDate,
        note = note,
        createdAt = now,
        updatedAt = now,
    )
}

private fun HabitLogUi.toEntity(): CompletionLogEntity {
    return CompletionLogEntity(
        id = id.toLong(),
        occurrenceId = occurrenceId?.toLong(),
        taskId = taskId.toLong(),
        action = action.toDomainAction(),
        timestamp = timestamp,
        operationalDate = operationalDate,
        note = note,
        createdAt = timestamp,
    )
}

private fun HabitCycleUi.toEntity(now: LocalDateTime): CycleGroupEntity {
    return CycleGroupEntity(
        id = id.toLong(),
        name = name,
        durationDays = durationDays.coerceAtLeast(1),
        resetThresholdPercent = resetThresholdPercent.coerceIn(1, 100),
        restartBehavior = restartBehavior,
        restartTiming = restartTiming,
        blockedDays = blockedDays,
        currentStartDate = currentStartDate,
        lastRestartedAt = lastRestartedAt,
        createdAt = now,
        updatedAt = now,
    )
}

private fun HabitTaskUi.toCycleMembershipEntity(
    now: LocalDateTime,
    cycle: CycleGroupEntity?,
): CycleTaskMembershipEntity? {
    val cycleGroupId = cycleGroupId ?: return null
    val cycleStart = cycle?.currentStartDate ?: startDate
    return CycleTaskMembershipEntity(
        cycleGroupId = cycleGroupId.toLong(),
        taskId = id.toLong(),
        startOffsetDays = ChronoUnit.DAYS.between(cycleStart, startDate).toInt().coerceAtLeast(0),
        createdAt = now,
        updatedAt = now,
    )
}

private fun HabitCycleLogUi.toEntity(): CycleLogEntity {
    return CycleLogEntity(
        id = id.toLong(),
        cycleGroupId = cycleGroupId.toLong(),
        timestamp = timestamp,
        operationalDate = operationalDate,
        note = note,
        createdAt = timestamp,
    )
}

private fun TaskEntity.toUiTask(
    rule: RecurrenceRuleEntity?,
    sequenceItems: List<String>,
    workoutDays: List<HabitWorkoutDayUi> = emptyList(),
    cycle: CycleGroupEntity? = null,
): HabitTaskUi {
    val uiTaskType = if (
        taskType == TaskType.SIMPLE_HABIT &&
        pushable &&
        noActionBehavior == NoActionBehavior.AUTO_PUSH &&
        rule?.ruleType == RuleType.DAILY &&
        rule.durationDays == 1 &&
        rule.endDate != null
    ) {
        HabitTaskType.OneTime
    } else {
        taskType.toUiTaskType()
    }
    return HabitTaskUi(
        id = id.toInt(),
        name = name,
        type = uiTaskType,
        notes = notes,
        recurrenceSummary = if (uiTaskType == HabitTaskType.OneTime) {
            "Once"
        } else {
            rule?.summary(sequenceItems) ?: uiTaskType.label
        },
        startDate = rule?.startDate ?: createdAt.toLocalDate(),
        endDate = rule?.endDate,
        durationDays = rule?.durationDays,
        startsAfterTaskId = rule?.startsAfterTaskId?.toInt(),
        intervalDays = rule?.intervalDays,
        longTermRecurrenceUnit = if (taskType == TaskType.LONG_TERM) {
            rule?.ruleType?.toLongTermRecurrenceUnit() ?: LongTermRecurrenceUnit.Months
        } else {
            LongTermRecurrenceUnit.Months
        },
        longTermRecurrenceAnchor = if (taskType == TaskType.LONG_TERM) {
            rule?.cycleDefinition.toLongTermRecurrenceAnchor()
        } else {
            LongTermRecurrenceAnchor.COMPLETION_DATE
        },
        weekdays = rule?.weekdays.orEmpty(),
        blockedDays = blockedDays,
        skipBlockedDaysBehavior = rule?.skipBlockedDaysBehavior ?: defaultSkipBlockedDaysBehavior(taskType.toUiTaskType()),
        sequenceItems = sequenceItems,
        workoutDays = workoutDays,
        timeOfDay = timeOfDay,
        pushable = pushable,
        noActionBehavior = noActionBehavior,
        reminderEnabled = defaultReminderEnabled,
        calendarVisible = calendarVisible,
        isActive = isActive,
        archived = archived,
        cycleGroupId = cycle?.id?.toInt(),
        cycleGroupName = cycle?.name.orEmpty(),
        cycleDurationDays = cycle?.durationDays ?: 14,
        cycleResetThresholdPercent = cycle?.resetThresholdPercent ?: 50,
        cycleRestartBehavior = cycle?.restartBehavior ?: CycleRestartBehavior.OFF,
        cycleRestartTiming = cycle?.restartTiming ?: CycleRestartTiming.TODAY,
        cycleBlockedDays = cycle?.blockedDays.orEmpty(),
    )
}

private fun CycleGroupEntity.toUiCycle(): HabitCycleUi {
    return HabitCycleUi(
        id = id.toInt(),
        name = name,
        durationDays = durationDays,
        resetThresholdPercent = resetThresholdPercent,
        restartBehavior = restartBehavior,
        restartTiming = restartTiming,
        blockedDays = blockedDays,
        currentStartDate = currentStartDate,
        lastRestartedAt = lastRestartedAt,
    )
}

private fun ScheduledOccurrenceEntity.toUiOccurrence(
    itemNameById: Map<Long, String>,
    itemPositionById: Map<Long, Int>,
    exerciseChecks: Map<Int, ExerciseCheckStatus> = emptyMap(),
): HabitOccurrenceUi {
    return HabitOccurrenceUi(
        id = id.toInt(),
        taskId = taskId.toInt(),
        scheduledDate = scheduledDate,
        operationalDate = operationalDate,
        status = status.toUiStatus(),
        sequenceItemName = sequenceItemId?.let { itemNameById[it] },
        sequenceItemPosition = sequenceItemId?.let { itemPositionById[it] },
        isShifted = isShifted,
        originalDate = originalDate,
        note = note,
        exerciseChecks = exerciseChecks,
    )
}

private fun SequenceExerciseEntity.toUiWorkoutExercise(): HabitWorkoutExerciseUi {
    return HabitWorkoutExerciseUi(
        id = id.toInt(),
        position = position,
        name = name,
        prescription = prescription,
        instructions = instructions,
        requirement = requirement,
    )
}

private fun RoutinePhaseEntity.toUiRoutinePhase(
    routinePlanName: String,
    nextPhaseName: String?,
): HabitRoutinePhaseUi {
    return HabitRoutinePhaseUi(
        id = id.toInt(),
        routinePlanId = routinePlanId.toInt(),
        routinePlanName = routinePlanName,
        taskId = taskId.toInt(),
        position = position,
        advanceMode = advanceMode,
        minimumDays = minimumDays,
        progressionNote = progressionNote,
        status = status,
        activatedDate = activatedDate,
        lastReviewedDate = lastReviewedDate,
        nextPhaseName = nextPhaseName,
    )
}

private fun CompletionLogEntity.toUiLog(): HabitLogUi {
    return HabitLogUi(
        id = id.toInt(),
        taskId = taskId.toInt(),
        occurrenceId = occurrenceId?.toInt(),
        action = action.toUiAction(),
        timestamp = timestamp,
        operationalDate = operationalDate,
        note = note,
    )
}

private fun CycleLogEntity.toUiCycleLog(): HabitCycleLogUi {
    return HabitCycleLogUi(
        id = id.toInt(),
        cycleGroupId = cycleGroupId.toInt(),
        timestamp = timestamp,
        operationalDate = operationalDate,
        note = note,
    )
}

private fun RecurrenceRuleEntity.summary(sequenceItems: List<String>): String {
    val base = when (ruleType) {
        RuleType.DAILY -> "Daily"
        RuleType.EVERY_X_DAYS -> "Every ${(intervalDays ?: 1).coerceAtLeast(1).dayCountLabel()}"
        RuleType.EVERY_X_WEEKS -> "Every ${(intervalDays ?: 1).coerceAtLeast(1).weekCountLabel()}"
        RuleType.EVERY_X_MONTHS -> "Every ${(intervalDays ?: 1).coerceAtLeast(1).monthCountLabel()}"
        RuleType.EVERY_X_YEARS -> "Every ${(intervalDays ?: 1).coerceAtLeast(1).yearCountLabel()}"
        RuleType.WEEKDAYS -> weekdays
            .sortedBy { it.value }
            .joinToString(" / ") { it.shortLabel() }
            .ifBlank { "No weekdays selected" }
        RuleType.SEQUENCE -> buildString {
            append(sequenceItems.ifEmpty { listOf("Workout") }.joinToString(" / "))
            val spacingDays = (intervalDays ?: 1).coerceAtLeast(1)
            if (spacingDays > 1) {
                append(" every ")
                append(spacingDays)
                append(" days")
            }
        }
    }
    return appendDurationSummary(base, durationDays)
}

private fun HabitTaskType.toDomainTaskType(): TaskType {
    return when (this) {
        HabitTaskType.Simple -> TaskType.SIMPLE_HABIT
        HabitTaskType.OneTime -> TaskType.QUICK_ONE_TIME
        HabitTaskType.Interval -> TaskType.INTERVAL
        HabitTaskType.Weekday -> TaskType.WEEKDAY_BASED
        HabitTaskType.Sequence -> TaskType.SEQUENCE_ROUTINE
        HabitTaskType.LongTerm -> TaskType.LONG_TERM
    }
}

private fun HabitTaskUi.toRuleType(): RuleType {
    return if (type == HabitTaskType.LongTerm) {
        longTermRecurrenceUnit.toRuleType()
    } else {
        type.toRuleType()
    }
}

private fun HabitTaskType.toRuleType(): RuleType {
    return when (this) {
        HabitTaskType.Simple -> RuleType.DAILY
        HabitTaskType.OneTime -> RuleType.DAILY
        HabitTaskType.Interval -> RuleType.EVERY_X_DAYS
        HabitTaskType.Weekday -> RuleType.WEEKDAYS
        HabitTaskType.Sequence -> RuleType.SEQUENCE
        HabitTaskType.LongTerm -> RuleType.EVERY_X_MONTHS
    }
}

private fun LongTermRecurrenceUnit.toRuleType(): RuleType {
    return when (this) {
        LongTermRecurrenceUnit.Days -> RuleType.EVERY_X_DAYS
        LongTermRecurrenceUnit.Weeks -> RuleType.EVERY_X_WEEKS
        LongTermRecurrenceUnit.Months -> RuleType.EVERY_X_MONTHS
        LongTermRecurrenceUnit.Years -> RuleType.EVERY_X_YEARS
    }
}

private fun RuleType.toLongTermRecurrenceUnit(): LongTermRecurrenceUnit {
    return when (this) {
        RuleType.EVERY_X_DAYS -> LongTermRecurrenceUnit.Days
        RuleType.EVERY_X_WEEKS -> LongTermRecurrenceUnit.Weeks
        RuleType.EVERY_X_MONTHS -> LongTermRecurrenceUnit.Months
        RuleType.EVERY_X_YEARS -> LongTermRecurrenceUnit.Years
        RuleType.DAILY,
        RuleType.WEEKDAYS,
        RuleType.SEQUENCE,
        -> LongTermRecurrenceUnit.Months
    }
}

private fun String?.toLongTermRecurrenceAnchor(): LongTermRecurrenceAnchor {
    val value = this?.takeIf { it.isNotBlank() } ?: return LongTermRecurrenceAnchor.COMPLETION_DATE
    return runCatching { LongTermRecurrenceAnchor.valueOf(value) }
        .getOrDefault(LongTermRecurrenceAnchor.COMPLETION_DATE)
}

private fun TaskType.toUiTaskType(): HabitTaskType {
    return when (this) {
        TaskType.SIMPLE_HABIT -> HabitTaskType.Simple
        TaskType.QUICK_ONE_TIME -> HabitTaskType.OneTime
        TaskType.INTERVAL -> HabitTaskType.Interval
        TaskType.WEEKDAY_BASED -> HabitTaskType.Weekday
        TaskType.SEQUENCE_ROUTINE -> HabitTaskType.Sequence
        TaskType.LONG_TERM -> HabitTaskType.LongTerm
    }
}

private fun HabitStatus.toDomainStatus(): OccurrenceStatus {
    return when (this) {
        HabitStatus.Pending -> OccurrenceStatus.PENDING
        HabitStatus.Completed -> OccurrenceStatus.COMPLETED
        HabitStatus.Skipped -> OccurrenceStatus.SKIPPED
        HabitStatus.Missed -> OccurrenceStatus.MISSED
        HabitStatus.Shifted -> OccurrenceStatus.SHIFTED
    }
}

private fun OccurrenceStatus.toUiStatus(): HabitStatus {
    return when (this) {
        OccurrenceStatus.PENDING -> HabitStatus.Pending
        OccurrenceStatus.COMPLETED -> HabitStatus.Completed
        OccurrenceStatus.SKIPPED -> HabitStatus.Skipped
        OccurrenceStatus.MISSED -> HabitStatus.Missed
        OccurrenceStatus.SHIFTED -> HabitStatus.Shifted
    }
}

private fun HabitLogAction.toDomainAction(): LogAction {
    return when (this) {
        HabitLogAction.Completed -> LogAction.COMPLETED
        HabitLogAction.Skipped -> LogAction.SKIPPED
        HabitLogAction.MarkedMissed -> LogAction.MARKED_MISSED
        HabitLogAction.ShiftedForward -> LogAction.SHIFTED_FORWARD
        HabitLogAction.Edited -> LogAction.EDITED
        HabitLogAction.Backup -> LogAction.EDITED
        HabitLogAction.Restore -> LogAction.RESTORED_FROM_BACKUP
    }
}

private fun LogAction.toUiAction(): HabitLogAction {
    return when (this) {
        LogAction.COMPLETED -> HabitLogAction.Completed
        LogAction.SKIPPED -> HabitLogAction.Skipped
        LogAction.MARKED_MISSED -> HabitLogAction.MarkedMissed
        LogAction.SHIFTED_FORWARD -> HabitLogAction.ShiftedForward
        LogAction.RESTORED_FROM_BACKUP -> HabitLogAction.Restore
        LogAction.EDITED -> HabitLogAction.Edited
    }
}

private fun sequenceItemEntityId(taskId: Int, index: Int): Long {
    return (taskId.toLong() * 1000L) + index + 1L
}

private fun sequenceExerciseEntityId(taskId: Int, dayPosition: Int, exercisePosition: Int): Long {
    return (taskId.toLong() * 1_000_000L) + (dayPosition * 1_000L) + exercisePosition + 1L
}

private fun temporaryExerciseId(taskId: Int, dayPosition: Int, exercisePosition: Int): Int {
    val value = (taskId.toLong() * 100_000L) + (dayPosition * 1_000L) + exercisePosition + 1L
    return -value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private fun ReminderSettingsUi.toReminderSnapshot(): AppSettingsSnapshot {
    return AppSettingsSnapshot(
        dayRolloverTime = dayRolloverTime,
        dailyReviewReminderTime = dailyReviewTime,
        lateDayReminderTime = lateReminderTime,
        morningTaskReminderTime = morningTaskReminderTime,
        noonTaskReminderTime = noonTaskReminderTime,
        eveningTaskReminderTime = eveningTaskReminderTime,
        dailyReviewEnabled = dailyReviewEnabled,
        lateDayReminderEnabled = lateReminderEnabled,
        taskTimeReminderEnabled = taskTimeReminderEnabled,
        exactAlarmPermissionPromptShown = exactAlarmPermissionPromptShown,
        defaultBlockedDays = defaultBlockedDays.toSettingsString(),
        themePreference = themePreference,
        backupLastExportedAt = backupLastExportedAt,
        backupLastVerifiedBytes = backupLastVerifiedBytes,
        autoBackupEnabled = autoBackupEnabled,
        autoBackupIntervalDays = autoBackupIntervalDays,
        autoBackupFolderUri = autoBackupFolderUri,
        autoBackupLastRunAt = autoBackupLastRunAt,
        autoBackupLastFailureAt = autoBackupLastFailureAt,
        autoBackupLastFailureReason = autoBackupLastFailureReason,
    )
}

private fun AppSettingsSnapshot.toUiSettings(): ReminderSettingsUi {
    return ReminderSettingsUi(
        dayRolloverTime = dayRolloverTime,
        dailyReviewTime = dailyReviewReminderTime,
        lateReminderTime = lateDayReminderTime,
        morningTaskReminderTime = morningTaskReminderTime,
        noonTaskReminderTime = noonTaskReminderTime,
        eveningTaskReminderTime = eveningTaskReminderTime,
        dailyReviewEnabled = dailyReviewEnabled,
        lateReminderEnabled = lateDayReminderEnabled,
        taskTimeReminderEnabled = taskTimeReminderEnabled,
        exactAlarmPermissionPromptShown = exactAlarmPermissionPromptShown,
        defaultBlockedDays = defaultBlockedDays.toDayOfWeekSet(),
        themePreference = themePreference,
        backupLastExportedAt = backupLastExportedAt,
        backupLastVerifiedBytes = backupLastVerifiedBytes,
        autoBackupEnabled = autoBackupEnabled,
        autoBackupIntervalDays = autoBackupIntervalDays,
        autoBackupFolderUri = autoBackupFolderUri,
        autoBackupLastRunAt = autoBackupLastRunAt,
        autoBackupLastFailureAt = autoBackupLastFailureAt,
        autoBackupLastFailureReason = autoBackupLastFailureReason,
    )
}

private fun Set<DayOfWeek>.toSettingsString(): String {
    return sortedBy { it.value }.joinToString(",") { it.name }
}

private fun String.toDayOfWeekSet(): Set<DayOfWeek> {
    return split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }
        .toSet()
}

private fun seedTasks(today: LocalDate): List<HabitTaskUi> {
    return listOf(
        HabitTaskUi(
            id = 1,
            name = "Workout sequence",
            type = HabitTaskType.Sequence,
            notes = "Preserve order and push forward manually when needed.",
            recurrenceSummary = "Push / Pull / Legs / Cardio / HIIT",
            startDate = today.minusDays(18),
            intervalDays = 1,
            blockedDays = setOf(DayOfWeek.SUNDAY),
            sequenceItems = listOf("Push", "Pull", "Legs", "Cardio", "HIIT"),
            timeOfDay = TaskTimeOfDay.EVENING,
            pushable = true,
        ),
        HabitTaskUi(
            id = 2,
            name = "Supplements",
            type = HabitTaskType.Simple,
            notes = "Morning baseline checklist.",
            recurrenceSummary = "Daily",
            startDate = today.minusDays(30),
            timeOfDay = TaskTimeOfDay.MORNING,
        ),
        HabitTaskUi(
            id = 3,
            name = "Zone 2 cardio",
            type = HabitTaskType.Interval,
            notes = "Easy pace, keep it recoverable.",
            recurrenceSummary = "Every 2 days",
            startDate = today.minusDays(14),
            intervalDays = 2,
            blockedDays = setOf(DayOfWeek.SUNDAY),
            timeOfDay = TaskTimeOfDay.NOON,
            pushable = true,
        ),
        HabitTaskUi(
            id = 4,
            name = "Red light therapy",
            type = HabitTaskType.Weekday,
            notes = "Short evening session.",
            recurrenceSummary = "Mon / Wed / Fri",
            startDate = today.minusDays(21),
            weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            timeOfDay = TaskTimeOfDay.EVENING,
            reminderEnabled = false,
        ),
    )
}

private fun seedOccurrences(today: LocalDate): List<HabitOccurrenceUi> {
    val occurrences = mutableListOf<HabitOccurrenceUi>()
    var id = 1
    fun add(
        taskId: Int,
        offset: Int,
        status: HabitStatus,
        sequenceItemName: String? = null,
        sequenceItemPosition: Int? = null,
        note: String = "",
        isShifted: Boolean = false,
    ) {
        val date = today.plusDays(offset.toLong())
        occurrences.add(
            HabitOccurrenceUi(
                id = id++,
                taskId = taskId,
                scheduledDate = date,
                operationalDate = date,
                status = status,
                sequenceItemName = sequenceItemName,
                sequenceItemPosition = sequenceItemPosition,
                note = note,
                isShifted = isShifted,
                originalDate = if (isShifted) date.minusDays(1) else null,
            ),
        )
    }

    val sequence = listOf("Push", "Pull", "Legs", "Cardio", "HIIT")
    for (offset in -14..60) {
        val date = today.plusDays(offset.toLong())
        if (date.dayOfWeek != DayOfWeek.SUNDAY) {
            val sequenceIndex = Math.floorMod(offset + 1, sequence.size)
            add(
                taskId = 1,
                offset = offset,
                status = seededStatus(offset, skippedEvery = 9, missedEvery = 13),
                sequenceItemName = sequence[sequenceIndex],
                sequenceItemPosition = sequenceIndex,
                note = if (offset == -3) "Felt heavy, reduced volume" else "",
            )
        }
    }

    for (offset in -10..60) {
        add(
            taskId = 2,
            offset = offset,
            status = seededStatus(offset, skippedEvery = 12, missedEvery = 15),
            note = if (offset == -1) "Taken after breakfast" else "",
        )
    }

    for (offset in -12..60 step 2) {
        val date = today.plusDays(offset.toLong())
        if (date.dayOfWeek != DayOfWeek.SUNDAY) {
            add(
                taskId = 3,
                offset = offset,
                status = seededStatus(offset, skippedEvery = 10, missedEvery = 16),
                note = if (offset == -4) "Outdoor session" else "",
            )
        }
    }

    for (offset in -14..60) {
        val date = today.plusDays(offset.toLong())
        if (date.dayOfWeek in setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)) {
            add(
                taskId = 4,
                offset = offset,
                status = seededStatus(offset, skippedEvery = 8, missedEvery = 11),
            )
        }
    }

    return occurrences
}

private fun seedLogs(today: LocalDate): List<HabitLogUi> {
    return listOf(
        HabitLogUi(1, 1, 1, HabitLogAction.Completed, today.minusDays(1).atTime(19, 30), today.minusDays(1), "Completed Legs"),
        HabitLogUi(2, 2, 2, HabitLogAction.Skipped, today.minusDays(2).atTime(21, 10), today.minusDays(2), "Intentional recovery"),
        HabitLogUi(3, 3, null, HabitLogAction.Edited, today.minusDays(3).atTime(18, 45), today.minusDays(3), "Updated recurrence"),
        HabitLogUi(4, 4, 4, HabitLogAction.MarkedMissed, today.minusDays(4).atTime(3, 5), today.minusDays(4), "Marked missed after day reset"),
    )
}

private fun seededStatus(offset: Int, skippedEvery: Int, missedEvery: Int): HabitStatus {
    return when {
        offset > 0 -> HabitStatus.Pending
        offset == 0 -> HabitStatus.Pending
        offset % missedEvery == 0 -> HabitStatus.Missed
        offset % skippedEvery == 0 -> HabitStatus.Skipped
        else -> HabitStatus.Completed
    }
}

private fun recurrenceSummary(draft: HabitTaskDraft, sequenceItems: List<String>): String {
    val base = when (draft.type) {
        HabitTaskType.Simple -> "Daily"
        HabitTaskType.OneTime -> "Once"
        HabitTaskType.Interval -> "Every ${draft.intervalDays.coerceAtLeast(2)} days"
        HabitTaskType.LongTerm -> longTermRecurrenceSummary(
            interval = draft.intervalDays,
            unit = draft.longTermRecurrenceUnit,
        )
        HabitTaskType.Weekday -> draft.weekdays
            .sortedBy { it.value }
            .joinToString(" / ") { it.shortLabel() }
            .ifBlank { "No weekdays selected" }
        HabitTaskType.Sequence -> buildString {
            append(sequenceItems.ifEmpty { listOf("Workout") }.joinToString(" / "))
            val spacingDays = draft.sequenceSpacingDays.coerceAtLeast(1)
            if (spacingDays > 1) {
                append(" every ")
                append(spacingDays)
                append(" days")
            }
        }
    }
    return if (draft.type == HabitTaskType.OneTime) base else appendDurationSummary(base, draft.durationDays)
}

private fun recurrenceSummaryForTask(task: HabitTaskUi): String {
    val base = when (task.type) {
        HabitTaskType.Simple -> "Daily"
        HabitTaskType.OneTime -> "Once"
        HabitTaskType.Interval -> "Every ${(task.intervalDays ?: 2).coerceAtLeast(2)} days"
        HabitTaskType.LongTerm -> longTermRecurrenceSummary(
            interval = task.intervalDays ?: 1,
            unit = task.longTermRecurrenceUnit,
        )
        HabitTaskType.Weekday -> task.weekdays
            .sortedBy { it.value }
            .joinToString(" / ") { it.shortLabel() }
            .ifBlank { "No weekdays selected" }
        HabitTaskType.Sequence -> buildString {
            append(task.sequenceItems.ifEmpty { listOf("Workout") }.joinToString(" / "))
            val spacingDays = (task.intervalDays ?: 1).coerceAtLeast(1)
            if (spacingDays > 1) {
                append(" every ")
                append(spacingDays)
                append(" days")
            }
        }
    }
    return if (task.type == HabitTaskType.OneTime) base else appendDurationSummary(base, task.durationDays)
}

private fun appendDurationSummary(base: String, durationDays: Int?): String {
    val duration = durationDays?.coerceAtLeast(1) ?: return base
    return "$base for ${duration.dayCountLabel()}"
}

private fun cycleThresholdDays(durationDays: Int, thresholdPercent: Int): Int {
    val duration = durationDays.coerceAtLeast(1)
    val threshold = thresholdPercent.coerceIn(1, 100)
    return ((duration * threshold) + 99) / 100
}

private data class GeneratedUiOccurrence(
    val date: LocalDate,
    val sequenceItemName: String?,
    val sequenceItemPosition: Int?,
)

private data class ExpectedCycleSlot(
    val taskId: Int,
    val date: LocalDate,
)

private fun generatedOccurrencesFor(
    task: HabitTaskUi,
    startDate: LocalDate,
    count: Int,
    sequenceStartIndex: Int = 0,
): List<GeneratedUiOccurrence> {
    val sequenceItemIds = task.sequenceItems.indices.map { it.toLong() }
    val request = GenerationRequest(
        taskId = task.id.toLong(),
        recurrenceRuleId = task.id.toLong(),
        taskType = task.type.toDomainTaskType(),
        ruleType = task.toRuleType(),
        intervalDays = task.intervalDays,
        weekdays = task.weekdays,
        blockedDays = task.blockedDays,
        startDate = task.startDate,
        endDate = task.endDate,
        skipBlockedDaysBehavior = task.skipBlockedDaysBehavior,
        sequenceItems = sequenceItemIds,
        sequenceStartIndex = sequenceStartIndex,
    )
    return OccurrenceGenerator()
        .generate(
            request = request,
            fromDate = startDate,
            throughDate = minOf(
                task.endDate ?: startDate.plusDays(generationPreviewDays(task)),
                startDate.plusDays(generationPreviewDays(task)),
            ),
        )
        .take(count)
        .map { occurrence ->
            GeneratedUiOccurrence(
                date = occurrence.operationalDate,
                sequenceItemName = occurrence.sequenceItemId?.toInt()?.let { task.sequenceItems.getOrNull(it) },
                sequenceItemPosition = occurrence.sequenceItemId?.toInt(),
            )
        }
}

private fun generationThroughForTask(task: HabitTaskUi, currentOperationalDate: LocalDate): LocalDate {
    return if (task.type == HabitTaskType.LongTerm) {
        currentOperationalDate.plusDays(generationPreviewDays(task))
    } else {
        currentOperationalDate.plusDays(60)
    }
}

private fun nextValidDate(startDate: LocalDate, blockedDays: Set<DayOfWeek>): LocalDate? {
    var cursor = startDate
    repeat(7) {
        if (cursor.dayOfWeek !in blockedDays) return cursor
        cursor = cursor.plusDays(1)
    }
    return null
}

private fun nextAvailableValidDate(
    startDate: LocalDate,
    blockedDays: Set<DayOfWeek>,
    occupiedDates: Set<LocalDate>,
): LocalDate? {
    var cursor = startDate
    repeat(366) {
        val validDate = nextValidDate(cursor, blockedDays) ?: return null
        if (validDate !in occupiedDates) return validDate
        cursor = validDate.plusDays(1)
    }
    return null
}

private fun currentStreak(occurrences: List<HabitOccurrenceUi>): Int {
    var streak = 0
    for (occurrence in occurrences.sortedByDescending { it.operationalDate }) {
        if (occurrence.status == HabitStatus.Completed) {
            streak += 1
        } else if (occurrence.status != HabitStatus.Pending) {
            break
        }
    }
    return streak
}

private fun longestStreak(occurrences: List<HabitOccurrenceUi>): Int {
    var current = 0
    var longest = 0
    for (occurrence in occurrences.sortedBy { it.operationalDate }) {
        if (occurrence.status == HabitStatus.Completed) {
            current += 1
            longest = maxOf(longest, current)
        } else if (occurrence.status != HabitStatus.Pending) {
            current = 0
        }
    }
    return longest
}

private fun HabitStatus.sortOrder(): Int {
    return when (this) {
        HabitStatus.Pending -> 0
        HabitStatus.Shifted -> 1
        HabitStatus.Missed -> 2
        HabitStatus.Skipped -> 3
        HabitStatus.Completed -> 4
    }
}

private fun TaskTimeOfDay.sortOrder(): Int {
    return when (this) {
        TaskTimeOfDay.MORNING -> 0
        TaskTimeOfDay.NOON -> 1
        TaskTimeOfDay.EVENING -> 2
        TaskTimeOfDay.GENERAL -> 3
    }
}

private fun HabitTaskUi.scheduleSpacingDays(): Int {
    return when (type) {
        HabitTaskType.Sequence -> (intervalDays ?: 1).coerceAtLeast(1)
        HabitTaskType.Interval -> (intervalDays ?: 2).coerceAtLeast(2)
        HabitTaskType.LongTerm,
        HabitTaskType.Simple,
        HabitTaskType.OneTime,
        HabitTaskType.Weekday,
        -> 1
    }
}

private fun generationPreviewDays(task: HabitTaskUi): Long {
    return if (task.type == HabitTaskType.LongTerm) 3655L else 370L
}

private fun longTermRecurrenceSummary(interval: Int, unit: LongTermRecurrenceUnit): String {
    val count = interval.coerceAtLeast(1)
    val unitLabel = when (unit) {
        LongTermRecurrenceUnit.Days -> count.dayCountLabel()
        LongTermRecurrenceUnit.Weeks -> count.weekCountLabel()
        LongTermRecurrenceUnit.Months -> count.monthCountLabel()
        LongTermRecurrenceUnit.Years -> count.yearCountLabel()
    }
    return "Every $unitLabel"
}

fun DayOfWeek.shortLabel(): String {
    return name.take(3).lowercase().replaceFirstChar { it.uppercase() }
}

fun LocalDate.monthDayLabel(): String {
    return format(DateTimeFormatter.ofPattern("MMM d"))
}

fun LocalDate.fullDateLabel(): String {
    return format(DateTimeFormatter.ofPattern("EEE, MMM d"))
}

fun LocalTime.clockLabel(): String {
    return format(DateTimeFormatter.ofPattern("h:mm a"))
}

fun Int.dayCountLabel(): String {
    return if (this == 1) "1 day" else "$this days"
}

fun Int.weekCountLabel(): String {
    return if (this == 1) "1 week" else "$this weeks"
}

fun Int.monthCountLabel(): String {
    return if (this == 1) "1 month" else "$this months"
}

fun Int.yearCountLabel(): String {
    return if (this == 1) "1 year" else "$this years"
}

private val ACTION_GENERATED_NOTES = setOf(
    "Marked complete",
    "Completed yesterday",
    "Skipped intentionally",
    "Pushed forward",
    "Marked missed after day reset",
    "Auto-skipped after day reset",
)

internal fun String.isActionGeneratedNote(): Boolean {
    return this in ACTION_GENERATED_NOTES
}

internal fun HabitOccurrenceUi.userNote(): String {
    return note.takeUnless { it.isActionGeneratedNote() }.orEmpty()
}
