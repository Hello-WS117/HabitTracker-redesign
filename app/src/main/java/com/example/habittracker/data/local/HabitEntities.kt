package com.example.habittracker.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
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

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val taskType: TaskType,
    val notes: String = "",
    val isActive: Boolean = true,
    val archived: Boolean = false,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val defaultReminderEnabled: Boolean = true,
    val calendarVisible: Boolean = true,
    val blockedDays: Set<DayOfWeek> = emptySet(),
    val timeOfDay: TaskTimeOfDay = TaskTimeOfDay.GENERAL,
    val pushable: Boolean = false,
    val noActionBehavior: NoActionBehavior = NoActionBehavior.MARK_MISSED,
)

@Entity(
    tableName = "recurrence_rules",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class RecurrenceRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val ruleType: RuleType,
    val intervalDays: Int? = null,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val cycleDefinition: String = "",
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val durationDays: Int? = null,
    val startsAfterTaskId: Long? = null,
    val skipBlockedDaysBehavior: SkipBlockedDaysBehavior = SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY,
    val lastGeneratedDate: LocalDate? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

@Entity(
    tableName = "scheduled_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RecurrenceRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["recurrenceRuleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SequenceItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequenceItemId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("taskId"),
        Index("recurrenceRuleId"),
        Index("sequenceItemId"),
        Index("operationalDate"),
        Index(value = ["recurrenceRuleId", "operationalDate"], unique = true),
        Index(value = ["recurrenceRuleId", "operationalDate", "sequenceItemId"], unique = true),
    ],
)
data class ScheduledOccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val recurrenceRuleId: Long,
    val scheduledDate: LocalDate,
    val operationalDate: LocalDate,
    val status: OccurrenceStatus = OccurrenceStatus.PENDING,
    val sequenceItemId: Long? = null,
    val isShifted: Boolean = false,
    val originalDate: LocalDate? = null,
    val note: String = "",
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

@Entity(
    tableName = "completion_logs",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ScheduledOccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrenceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId"), Index("occurrenceId"), Index("operationalDate")],
)
data class CompletionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurrenceId: Long?,
    val taskId: Long,
    val action: LogAction,
    val timestamp: LocalDateTime,
    val operationalDate: LocalDate,
    val note: String = "",
    val createdAt: LocalDateTime,
)

@Entity(
    tableName = "workout_sequences",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class WorkoutSequenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val name: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

@Entity(
    tableName = "sequence_items",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSequenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequenceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sequenceId"), Index(value = ["sequenceId", "position"], unique = true)],
)
data class SequenceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sequenceId: Long,
    val name: String,
    val position: Int,
    val notes: String = "",
)

@Entity(
    tableName = "sequence_exercises",
    foreignKeys = [
        ForeignKey(
            entity = SequenceItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequenceItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sequenceItemId"),
        Index(value = ["sequenceItemId", "position"], unique = true),
    ],
)
data class SequenceExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sequenceItemId: Long,
    val position: Int,
    val name: String,
    val prescription: String = "",
    val instructions: String = "",
    val requirement: ExerciseRequirement = ExerciseRequirement.REQUIRED,
)

@Entity(
    tableName = "occurrence_exercise_checks",
    foreignKeys = [
        ForeignKey(
            entity = ScheduledOccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrenceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SequenceExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequenceExerciseId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("occurrenceId"),
        Index("sequenceExerciseId"),
        Index(value = ["occurrenceId", "sequenceExerciseId"], unique = true),
    ],
)
data class OccurrenceExerciseCheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurrenceId: Long,
    val sequenceExerciseId: Long,
    val status: ExerciseCheckStatus = ExerciseCheckStatus.PENDING,
    val updatedAt: LocalDateTime,
)

@Entity(tableName = "routine_plans")
data class RoutinePlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

@Entity(
    tableName = "routine_phases",
    foreignKeys = [
        ForeignKey(
            entity = RoutinePlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["routinePlanId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("routinePlanId"),
        Index(value = ["taskId"], unique = true),
        Index(value = ["routinePlanId", "position"], unique = true),
    ],
)
data class RoutinePhaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routinePlanId: Long,
    val taskId: Long,
    val position: Int,
    val advanceMode: PhaseAdvanceMode,
    val minimumDays: Int,
    val progressionNote: String = "",
    val status: RoutinePhaseStatus,
    val activatedDate: LocalDate? = null,
    val advancedAt: LocalDateTime? = null,
    val lastReviewedDate: LocalDate? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

@Entity(tableName = "cycle_groups")
data class CycleGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val durationDays: Int,
    val resetThresholdPercent: Int,
    val restartBehavior: CycleRestartBehavior = CycleRestartBehavior.OFF,
    val restartTiming: CycleRestartTiming = CycleRestartTiming.TODAY,
    val blockedDays: Set<DayOfWeek> = emptySet(),
    val currentStartDate: LocalDate,
    val lastRestartedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

@Entity(
    tableName = "cycle_task_memberships",
    foreignKeys = [
        ForeignKey(
            entity = CycleGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["cycleGroupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("cycleGroupId"),
        Index(value = ["taskId"], unique = true),
    ],
)
data class CycleTaskMembershipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cycleGroupId: Long,
    val taskId: Long,
    val startOffsetDays: Int = 0,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

@Entity(
    tableName = "cycle_logs",
    foreignKeys = [
        ForeignKey(
            entity = CycleGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["cycleGroupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("cycleGroupId"), Index("operationalDate")],
)
data class CycleLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cycleGroupId: Long,
    val timestamp: LocalDateTime,
    val operationalDate: LocalDate,
    val note: String,
    val createdAt: LocalDateTime,
)
