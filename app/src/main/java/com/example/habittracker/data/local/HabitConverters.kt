package com.example.habittracker.data.local

import androidx.room.TypeConverter
import com.example.habittracker.data.CycleRestartBehavior
import com.example.habittracker.data.CycleRestartTiming
import com.example.habittracker.data.ExerciseCheckStatus
import com.example.habittracker.data.ExerciseRequirement
import com.example.habittracker.data.LogAction
import com.example.habittracker.data.NoActionBehavior
import com.example.habittracker.data.OccurrenceStatus
import com.example.habittracker.data.PhaseAdvanceMode
import com.example.habittracker.data.RuleType
import com.example.habittracker.data.RoutinePhaseStatus
import com.example.habittracker.data.SkipBlockedDaysBehavior
import com.example.habittracker.data.TaskTimeOfDay
import com.example.habittracker.data.TaskType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

class HabitConverters {
    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun localDateTimeToString(value: LocalDateTime?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDateTime(value: String?): LocalDateTime? = value?.let(LocalDateTime::parse)

    @TypeConverter
    fun taskTypeToString(value: TaskType): String = value.name

    @TypeConverter
    fun stringToTaskType(value: String): TaskType = TaskType.valueOf(value)

    @TypeConverter
    fun taskTimeOfDayToString(value: TaskTimeOfDay): String = value.name

    @TypeConverter
    fun stringToTaskTimeOfDay(value: String): TaskTimeOfDay = TaskTimeOfDay.valueOf(value)

    @TypeConverter
    fun ruleTypeToString(value: RuleType): String = value.name

    @TypeConverter
    fun stringToRuleType(value: String): RuleType = RuleType.valueOf(value)

    @TypeConverter
    fun statusToString(value: OccurrenceStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): OccurrenceStatus = OccurrenceStatus.valueOf(value)

    @TypeConverter
    fun actionToString(value: LogAction): String = value.name

    @TypeConverter
    fun stringToAction(value: String): LogAction = LogAction.valueOf(value)

    @TypeConverter
    fun behaviorToString(value: SkipBlockedDaysBehavior): String = value.name

    @TypeConverter
    fun stringToBehavior(value: String): SkipBlockedDaysBehavior = SkipBlockedDaysBehavior.valueOf(value)

    @TypeConverter
    fun noActionBehaviorToString(value: NoActionBehavior): String = value.name

    @TypeConverter
    fun stringToNoActionBehavior(value: String): NoActionBehavior = NoActionBehavior.valueOf(value)

    @TypeConverter
    fun cycleRestartBehaviorToString(value: CycleRestartBehavior): String = value.name

    @TypeConverter
    fun stringToCycleRestartBehavior(value: String): CycleRestartBehavior = CycleRestartBehavior.valueOf(value)

    @TypeConverter
    fun cycleRestartTimingToString(value: CycleRestartTiming): String = value.name

    @TypeConverter
    fun stringToCycleRestartTiming(value: String): CycleRestartTiming = CycleRestartTiming.valueOf(value)

    @TypeConverter
    fun exerciseRequirementToString(value: ExerciseRequirement): String = value.name

    @TypeConverter
    fun stringToExerciseRequirement(value: String): ExerciseRequirement = ExerciseRequirement.valueOf(value)

    @TypeConverter
    fun exerciseCheckStatusToString(value: ExerciseCheckStatus): String = value.name

    @TypeConverter
    fun stringToExerciseCheckStatus(value: String): ExerciseCheckStatus = ExerciseCheckStatus.valueOf(value)

    @TypeConverter
    fun phaseAdvanceModeToString(value: PhaseAdvanceMode): String = value.name

    @TypeConverter
    fun stringToPhaseAdvanceMode(value: String): PhaseAdvanceMode = PhaseAdvanceMode.valueOf(value)

    @TypeConverter
    fun routinePhaseStatusToString(value: RoutinePhaseStatus): String = value.name

    @TypeConverter
    fun stringToRoutinePhaseStatus(value: String): RoutinePhaseStatus = RoutinePhaseStatus.valueOf(value)

    @TypeConverter
    fun daySetToString(value: Set<DayOfWeek>): String {
        return value.sortedBy { it.value }.joinToString(",") { it.name }
    }

    @TypeConverter
    fun stringToDaySet(value: String?): Set<DayOfWeek> {
        if (value.isNullOrBlank()) return emptySet()
        return value.split(",")
            .filter { it.isNotBlank() }
            .map { DayOfWeek.valueOf(it) }
            .toSet()
    }

    @TypeConverter
    fun longListToString(value: List<Long>): String = value.joinToString(",")

    @TypeConverter
    fun stringToLongList(value: String?): List<Long> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(",")
            .filter { it.isNotBlank() }
            .map { it.toLong() }
    }
}
