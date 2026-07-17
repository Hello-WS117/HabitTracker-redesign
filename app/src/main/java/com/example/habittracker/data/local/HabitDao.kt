package com.example.habittracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.habittracker.data.OccurrenceStatus
import com.example.habittracker.data.TaskTimeOfDay
import java.time.LocalDate

@Dao
interface HabitDao {
    @Query("SELECT * FROM tasks WHERE (:includeArchived OR archived = 0) ORDER BY archived ASC, name ASC")
    suspend fun tasks(includeArchived: Boolean = false): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    suspend fun taskById(taskId: Long): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: RecurrenceRuleEntity): Long

    @Update
    suspend fun updateRule(rule: RecurrenceRuleEntity)

    @Query("SELECT * FROM recurrence_rules WHERE id = :ruleId")
    suspend fun ruleById(ruleId: Long): RecurrenceRuleEntity?

    @Query("SELECT * FROM recurrence_rules WHERE taskId = :taskId ORDER BY createdAt DESC LIMIT 1")
    suspend fun ruleForTask(taskId: Long): RecurrenceRuleEntity?

    @Query("SELECT * FROM recurrence_rules WHERE startsAfterTaskId = :taskId")
    suspend fun rulesStartingAfterTask(taskId: Long): List<RecurrenceRuleEntity>

    @Query("SELECT * FROM recurrence_rules")
    suspend fun allRules(): List<RecurrenceRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOccurrences(occurrences: List<ScheduledOccurrenceEntity>): List<Long>

    @Update
    suspend fun updateOccurrence(occurrence: ScheduledOccurrenceEntity)

    @Update
    suspend fun updateOccurrences(occurrences: List<ScheduledOccurrenceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCycleGroup(group: CycleGroupEntity): Long

    @Update
    suspend fun updateCycleGroup(group: CycleGroupEntity)

    @Query("SELECT * FROM cycle_groups WHERE id = :cycleGroupId")
    suspend fun cycleGroupById(cycleGroupId: Long): CycleGroupEntity?

    @Query("SELECT * FROM cycle_groups ORDER BY name ASC, id ASC")
    suspend fun allCycleGroups(): List<CycleGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCycleMembership(membership: CycleTaskMembershipEntity): Long

    @Update
    suspend fun updateCycleMembership(membership: CycleTaskMembershipEntity)

    @Query("SELECT * FROM cycle_task_memberships WHERE taskId = :taskId LIMIT 1")
    suspend fun cycleMembershipForTask(taskId: Long): CycleTaskMembershipEntity?

    @Query("SELECT * FROM cycle_task_memberships WHERE cycleGroupId = :cycleGroupId ORDER BY startOffsetDays ASC, taskId ASC")
    suspend fun cycleMembershipsForGroup(cycleGroupId: Long): List<CycleTaskMembershipEntity>

    @Query("SELECT * FROM cycle_task_memberships")
    suspend fun allCycleMemberships(): List<CycleTaskMembershipEntity>

    @Query("DELETE FROM cycle_task_memberships WHERE taskId = :taskId")
    suspend fun deleteCycleMembershipForTask(taskId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCycleLog(log: CycleLogEntity): Long

    @Query("SELECT * FROM cycle_logs WHERE cycleGroupId = :cycleGroupId ORDER BY timestamp DESC, id DESC")
    suspend fun cycleLogsForGroup(cycleGroupId: Long): List<CycleLogEntity>

    @Query("SELECT * FROM cycle_logs")
    suspend fun allCycleLogs(): List<CycleLogEntity>

    @Query("SELECT * FROM scheduled_occurrences WHERE id = :occurrenceId")
    suspend fun occurrenceById(occurrenceId: Long): ScheduledOccurrenceEntity?

    @Query(
        """
        SELECT * FROM scheduled_occurrences
        WHERE operationalDate = :operationalDate
        ORDER BY scheduledDate ASC, id ASC
        """,
    )
    suspend fun occurrencesForOperationalDate(operationalDate: LocalDate): List<ScheduledOccurrenceEntity>

    @Query(
        """
        SELECT COUNT(DISTINCT scheduled_occurrences.taskId) FROM scheduled_occurrences
        INNER JOIN tasks ON tasks.id = scheduled_occurrences.taskId
        WHERE (
              scheduled_occurrences.operationalDate = :operationalDate
              OR (
                  tasks.taskType = 'LONG_TERM'
                  AND scheduled_occurrences.operationalDate <= :operationalDate
              )
          )
          AND scheduled_occurrences.status = 'PENDING'
          AND tasks.isActive = 1
          AND tasks.archived = 0
          AND tasks.defaultReminderEnabled = 1
        """,
    )
    suspend fun pendingReminderOccurrenceCountForDate(operationalDate: LocalDate): Int

    @Query(
        """
        SELECT COUNT(DISTINCT scheduled_occurrences.taskId) FROM scheduled_occurrences
        INNER JOIN tasks ON tasks.id = scheduled_occurrences.taskId
        WHERE (
              scheduled_occurrences.operationalDate = :operationalDate
              OR (
                  tasks.taskType = 'LONG_TERM'
                  AND scheduled_occurrences.operationalDate <= :operationalDate
              )
          )
          AND scheduled_occurrences.status = 'PENDING'
          AND tasks.isActive = 1
          AND tasks.archived = 0
          AND tasks.defaultReminderEnabled = 1
          AND tasks.timeOfDay = :timeOfDay
        """,
    )
    suspend fun pendingReminderOccurrenceCountForDateAndTimeOfDay(
        operationalDate: LocalDate,
        timeOfDay: TaskTimeOfDay,
    ): Int

    @Query(
        """
        SELECT * FROM scheduled_occurrences
        WHERE taskId = :taskId
        ORDER BY operationalDate DESC, id DESC
        """,
    )
    suspend fun occurrencesForTask(taskId: Long): List<ScheduledOccurrenceEntity>

    @Query(
        """
        SELECT * FROM scheduled_occurrences
        WHERE taskId = :taskId
          AND recurrenceRuleId = :ruleId
          AND operationalDate >= :fromDate
          AND status = :status
        ORDER BY operationalDate ASC, id ASC
        """,
    )
    suspend fun futureOccurrencesForRule(
        taskId: Long,
        ruleId: Long,
        fromDate: LocalDate,
        status: OccurrenceStatus = OccurrenceStatus.PENDING,
    ): List<ScheduledOccurrenceEntity>

    @Query(
        """
        SELECT scheduled_occurrences.* FROM scheduled_occurrences
        INNER JOIN tasks ON tasks.id = scheduled_occurrences.taskId
        WHERE scheduled_occurrences.operationalDate < :currentOperationalDate
          AND scheduled_occurrences.status = 'PENDING'
          AND tasks.isActive = 1
          AND tasks.archived = 0
        ORDER BY scheduled_occurrences.operationalDate ASC, scheduled_occurrences.id ASC
        """,
    )
    suspend fun pendingOccurrencesBeforeDate(
        currentOperationalDate: LocalDate,
    ): List<ScheduledOccurrenceEntity>

    @Query(
        """
        DELETE FROM scheduled_occurrences
        WHERE taskId = :taskId
          AND recurrenceRuleId = :ruleId
          AND scheduledDate > :currentOperationalDate
          AND status = 'PENDING'
        """,
    )
    suspend fun deleteFuturePendingOccurrences(
        taskId: Long,
        ruleId: Long,
        currentOperationalDate: LocalDate,
    ): Int

    @Query(
        """
        DELETE FROM scheduled_occurrences
        WHERE taskId = :taskId
          AND recurrenceRuleId = :ruleId
          AND operationalDate >= :fromDate
          AND status = 'PENDING'
        """,
    )
    suspend fun deletePendingOccurrencesForRuleFromDate(
        taskId: Long,
        ruleId: Long,
        fromDate: LocalDate,
    ): Int

    @Query(
        """
        UPDATE scheduled_occurrences
        SET status = 'MISSED', updatedAt = :updatedAt
        WHERE operationalDate < :currentOperationalDate
          AND status = 'PENDING'
          AND taskId IN (
              SELECT id FROM tasks
              WHERE isActive = 1
                AND archived = 0
          )
        """,
    )
    suspend fun markPendingBeforeDateMissed(
        currentOperationalDate: LocalDate,
        updatedAt: java.time.LocalDateTime,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: CompletionLogEntity): Long

    @Query("SELECT * FROM completion_logs WHERE taskId = :taskId ORDER BY timestamp DESC")
    suspend fun logsForTask(taskId: Long): List<CompletionLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSequence(sequence: WorkoutSequenceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSequenceItems(items: List<SequenceItemEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSequenceExercises(items: List<SequenceExerciseEntity>): List<Long>

    @Query("SELECT * FROM sequence_exercises WHERE sequenceItemId = :sequenceItemId ORDER BY position ASC")
    suspend fun sequenceExercises(sequenceItemId: Long): List<SequenceExerciseEntity>

    @Query("SELECT * FROM sequence_exercises")
    suspend fun allSequenceExercises(): List<SequenceExerciseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOccurrenceExerciseCheck(check: OccurrenceExerciseCheckEntity): Long

    @Query("SELECT * FROM occurrence_exercise_checks WHERE occurrenceId = :occurrenceId")
    suspend fun exerciseChecksForOccurrence(occurrenceId: Long): List<OccurrenceExerciseCheckEntity>

    @Query("SELECT * FROM occurrence_exercise_checks")
    suspend fun allOccurrenceExerciseChecks(): List<OccurrenceExerciseCheckEntity>

    @Query("DELETE FROM occurrence_exercise_checks WHERE occurrenceId = :occurrenceId AND sequenceExerciseId = :sequenceExerciseId")
    suspend fun deleteOccurrenceExerciseCheck(occurrenceId: Long, sequenceExerciseId: Long): Int

    @Query("DELETE FROM occurrence_exercise_checks WHERE occurrenceId IN (:occurrenceIds)")
    suspend fun deleteExerciseChecksForOccurrences(occurrenceIds: List<Long>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutinePlan(plan: RoutinePlanEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutinePhases(phases: List<RoutinePhaseEntity>): List<Long>

    @Update
    suspend fun updateRoutinePhase(phase: RoutinePhaseEntity)

    @Query("SELECT * FROM routine_plans")
    suspend fun allRoutinePlans(): List<RoutinePlanEntity>

    @Query("SELECT * FROM routine_phases ORDER BY routinePlanId ASC, position ASC")
    suspend fun allRoutinePhases(): List<RoutinePhaseEntity>

    @Query("SELECT * FROM routine_phases WHERE id = :phaseId")
    suspend fun routinePhaseById(phaseId: Long): RoutinePhaseEntity?

    @Query("SELECT * FROM routine_phases WHERE taskId = :taskId LIMIT 1")
    suspend fun routinePhaseForTask(taskId: Long): RoutinePhaseEntity?

    @Query("SELECT * FROM routine_phases WHERE routinePlanId = :planId ORDER BY position ASC")
    suspend fun routinePhasesForPlan(planId: Long): List<RoutinePhaseEntity>

    @Query("SELECT * FROM workout_sequences WHERE taskId = :taskId ORDER BY id DESC LIMIT 1")
    suspend fun sequenceForTask(taskId: Long): WorkoutSequenceEntity?

    @Query("UPDATE workout_sequences SET name = :name, updatedAt = :updatedAt WHERE taskId = :taskId")
    suspend fun updateSequenceNamesForTask(
        taskId: Long,
        name: String,
        updatedAt: java.time.LocalDateTime,
    ): Int

    @Query("SELECT * FROM sequence_items WHERE sequenceId = :sequenceId ORDER BY position ASC")
    suspend fun sequenceItems(sequenceId: Long): List<SequenceItemEntity>

    @Query("DELETE FROM workout_sequences WHERE taskId = :taskId")
    suspend fun deleteSequenceForTask(taskId: Long): Int

    @Query("SELECT * FROM scheduled_occurrences")
    suspend fun allOccurrences(): List<ScheduledOccurrenceEntity>

    @Query(
        """
        SELECT scheduled_occurrences.* FROM scheduled_occurrences
        INNER JOIN cycle_task_memberships
            ON cycle_task_memberships.taskId = scheduled_occurrences.taskId
        WHERE cycle_task_memberships.cycleGroupId = :cycleGroupId
        ORDER BY scheduled_occurrences.operationalDate ASC, scheduled_occurrences.id ASC
        """,
    )
    suspend fun occurrencesForCycleGroup(cycleGroupId: Long): List<ScheduledOccurrenceEntity>

    @Query(
        """
        SELECT * FROM scheduled_occurrences
        WHERE taskId = :taskId
          AND recurrenceRuleId = :ruleId
          AND operationalDate < :beforeDate
        ORDER BY operationalDate DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun latestOccurrenceBefore(
        taskId: Long,
        ruleId: Long,
        beforeDate: LocalDate,
    ): ScheduledOccurrenceEntity?

    @Query("SELECT * FROM completion_logs")
    suspend fun allLogs(): List<CompletionLogEntity>

    @Query("SELECT * FROM workout_sequences")
    suspend fun allSequences(): List<WorkoutSequenceEntity>

    @Query("SELECT * FROM sequence_items")
    suspend fun allSequenceItems(): List<SequenceItemEntity>

    @Query("DELETE FROM occurrence_exercise_checks")
    suspend fun deleteAllOccurrenceExerciseChecks()

    @Query("DELETE FROM sequence_exercises")
    suspend fun deleteAllSequenceExercises()

    @Query("DELETE FROM routine_phases")
    suspend fun deleteAllRoutinePhases()

    @Query("DELETE FROM routine_plans")
    suspend fun deleteAllRoutinePlans()

    @Query("DELETE FROM completion_logs")
    suspend fun deleteAllLogs()

    @Query("DELETE FROM cycle_logs")
    suspend fun deleteAllCycleLogs()

    @Query("DELETE FROM scheduled_occurrences")
    suspend fun deleteAllOccurrences()

    @Query("DELETE FROM sequence_items")
    suspend fun deleteAllSequenceItems()

    @Query("DELETE FROM workout_sequences")
    suspend fun deleteAllSequences()

    @Query("DELETE FROM cycle_task_memberships")
    suspend fun deleteAllCycleMemberships()

    @Query("DELETE FROM cycle_groups")
    suspend fun deleteAllCycleGroups()

    @Query("DELETE FROM recurrence_rules")
    suspend fun deleteAllRules()

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    @Query("DELETE FROM completion_logs WHERE taskId = :taskId")
    suspend fun deleteLogsForTask(taskId: Long): Int

    @Query("DELETE FROM scheduled_occurrences WHERE taskId = :taskId")
    suspend fun deleteOccurrencesForTask(taskId: Long): Int

    @Query("DELETE FROM scheduled_occurrences WHERE id = :occurrenceId")
    suspend fun deleteOccurrence(occurrenceId: Long): Int

    @Query("DELETE FROM scheduled_occurrences WHERE id IN (:occurrenceIds)")
    suspend fun deleteOccurrencesByIds(occurrenceIds: List<Long>): Int

    @Query("DELETE FROM recurrence_rules WHERE taskId = :taskId")
    suspend fun deleteRulesForTask(taskId: Long): Int

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: Long): Int

    @Query("DELETE FROM routine_plans WHERE id NOT IN (SELECT DISTINCT routinePlanId FROM routine_phases)")
    suspend fun deleteOrphanedRoutinePlans(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreTasks(tasks: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreRules(rules: List<RecurrenceRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreOccurrences(occurrences: List<ScheduledOccurrenceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreLogs(logs: List<CompletionLogEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreSequences(sequences: List<WorkoutSequenceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreSequenceItems(items: List<SequenceItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreSequenceExercises(items: List<SequenceExerciseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreOccurrenceExerciseChecks(items: List<OccurrenceExerciseCheckEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreRoutinePlans(items: List<RoutinePlanEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreRoutinePhases(items: List<RoutinePhaseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreCycleGroups(groups: List<CycleGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreCycleMemberships(memberships: List<CycleTaskMembershipEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreCycleLogs(logs: List<CycleLogEntity>)
}
