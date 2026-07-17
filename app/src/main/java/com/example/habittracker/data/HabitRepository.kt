package com.example.habittracker.data

import androidx.room.withTransaction
import com.example.habittracker.data.local.CycleGroupEntity
import com.example.habittracker.data.local.CycleLogEntity
import com.example.habittracker.data.local.CycleTaskMembershipEntity
import com.example.habittracker.data.local.CompletionLogEntity
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class HabitRepository(
    private val database: HabitDatabase,
    private val generator: OccurrenceGenerator = OccurrenceGenerator(),
    private val statsCalculator: StatsCalculator = StatsCalculator(),
) {
    private val dao = database.habitDao()

    suspend fun createTaskWithRule(
        task: TaskEntity,
        rule: RecurrenceRuleEntity,
        sequenceItems: List<SequenceItemEntity> = emptyList(),
        sequenceExercisesByPosition: Map<Int, List<SequenceExerciseEntity>> = emptyMap(),
        cycleGroup: CycleGroupEntity? = null,
        cycleMembership: CycleTaskMembershipEntity? = null,
        generateThrough: LocalDate,
        generatePendingOccurrences: Boolean = true,
    ): Long {
        return database.withTransaction {
            val taskId = dao.insertTask(task)
            val ruleToSave = resolveCycleRuleWindow(rule.copy(taskId = taskId))
            validateTaskRuleAndSequence(task, ruleToSave, sequenceItems, sequenceExercisesByPosition)
            val ruleId = dao.insertRule(ruleToSave)
            val insertedSequenceItemIds = if (sequenceItems.isNotEmpty()) {
                val sequenceId = dao.insertSequence(
                    WorkoutSequenceEntity(
                        taskId = taskId,
                        name = task.name,
                        createdAt = task.createdAt,
                        updatedAt = task.updatedAt,
                    ),
                )
                dao.insertSequenceItems(sequenceItems.map { it.copy(sequenceId = sequenceId) })
            } else {
                emptyList()
            }
            insertSequenceExercises(
                sequenceItems = sequenceItems,
                insertedSequenceItemIds = insertedSequenceItemIds,
                sequenceExercisesByPosition = sequenceExercisesByPosition,
            )
            upsertCycleAssignment(
                taskId = taskId,
                rule = ruleToSave.copy(id = ruleId),
                cycleGroup = cycleGroup,
                cycleMembership = cycleMembership,
                now = task.updatedAt,
            )
            if (generatePendingOccurrences) {
                generateOccurrences(taskId, ruleToSave.copy(id = ruleId), insertedSequenceItemIds, ruleToSave.startDate, generateThrough)
            }
            taskId
        }
    }

    suspend fun editRuleAndRegenerate(
        task: TaskEntity,
        rule: RecurrenceRuleEntity,
        sequenceItems: List<SequenceItemEntity> = emptyList(),
        sequenceExercisesByPosition: Map<Int, List<SequenceExerciseEntity>> = emptyMap(),
        cycleGroup: CycleGroupEntity? = null,
        cycleMembership: CycleTaskMembershipEntity? = null,
        currentOperationalDate: LocalDate,
        generateThrough: LocalDate = currentOperationalDate.plusDays(60),
    ) = database.withTransaction {
        val now = LocalDateTime.now()
        val existingTask = dao.taskById(task.id)
        val taskToSave = task.copy(
            createdAt = existingTask?.createdAt ?: task.createdAt,
            updatedAt = now,
        )
        val existingRule = dao.ruleForTask(task.id)
        val unresolvedRuleToSave = if (existingRule == null) {
            rule.copy(taskId = task.id)
        } else {
            rule.copy(id = existingRule.id, taskId = task.id, createdAt = existingRule.createdAt)
        }
        val ruleToSave = resolveCycleRuleWindow(unresolvedRuleToSave)
        validateTaskRuleAndSequence(taskToSave, ruleToSave, sequenceItems, sequenceExercisesByPosition)
        val scheduleChanged = hasScheduleChanged(
            existingTask = existingTask,
            newTask = taskToSave,
            existingRule = existingRule,
            newRule = ruleToSave,
            newSequenceItems = sequenceItems,
            newSequenceExercisesByPosition = sequenceExercisesByPosition,
        )
        dao.updateTask(taskToSave)
        upsertCycleAssignment(
            taskId = task.id,
            rule = ruleToSave,
            cycleGroup = cycleGroup,
            cycleMembership = cycleMembership,
            now = now,
        )
        if (!scheduleChanged) {
            if (taskToSave.taskType == TaskType.SEQUENCE_ROUTINE) {
                dao.updateSequenceNamesForTask(taskToSave.id, taskToSave.name, now)
            }
            dao.insertLog(
                CompletionLogEntity(
                    taskId = task.id,
                    occurrenceId = null,
                    action = LogAction.EDITED,
                    timestamp = now,
                    operationalDate = currentOperationalDate,
                    note = "Updated task metadata",
                    createdAt = now,
                ),
            )
            restartTaskCycleIfDue(
                taskId = task.id,
                currentOperationalDate = currentOperationalDate,
                now = now,
                restartWhenCycleEndedWithoutUpcoming = true,
            )
            return@withTransaction
        }
        val ruleId = if (existingRule == null) dao.insertRule(ruleToSave) else {
            dao.updateRule(ruleToSave)
            ruleToSave.id
        }
        val sequenceStartIndex = if (ruleToSave.ruleType == RuleType.SEQUENCE && sequenceItems.isNotEmpty()) {
            val previousSequenceItemId = dao.latestOccurrenceBefore(
                taskId = task.id,
                ruleId = ruleId,
                beforeDate = currentOperationalDate.plusDays(1),
            )?.sequenceItemId
            nextSequenceStartIndexByPosition(
                newSequenceSize = sequenceItems.size,
                previousSequenceItemId = previousSequenceItemId,
            )
        } else {
            0
        }
        dao.deleteFuturePendingOccurrences(task.id, ruleId, currentOperationalDate)

        val sequenceIds = replaceSequenceItems(taskToSave, sequenceItems, sequenceExercisesByPosition)
        generateOccurrences(
            taskId = task.id,
            rule = ruleToSave.copy(id = ruleId),
            sequenceItemIds = sequenceIds,
            fromDate = currentOperationalDate.plusDays(1),
            throughDate = generateThrough,
            sequenceStartIndex = sequenceStartIndex,
        )
        dao.insertLog(
            CompletionLogEntity(
                taskId = task.id,
                occurrenceId = null,
                action = LogAction.EDITED,
                timestamp = now,
                operationalDate = currentOperationalDate,
                note = "Regenerated future pending occurrences after rule edit",
                createdAt = now,
            ),
        )
        restartTaskCycleIfDue(
            taskId = task.id,
            currentOperationalDate = currentOperationalDate,
            now = now,
            restartWhenCycleEndedWithoutUpcoming = true,
        )
    }

    suspend fun completeOccurrence(occurrenceId: Long, currentOperationalDate: LocalDate, note: String = "") {
        updateOccurrenceStatus(occurrenceId, OccurrenceStatus.COMPLETED, LogAction.COMPLETED, note, currentOperationalDate)
    }

    suspend fun setExerciseCheckStatus(
        occurrenceId: Long,
        sequenceExerciseId: Long,
        status: ExerciseCheckStatus,
        currentOperationalDate: LocalDate,
    ) = database.withTransaction {
        val occurrence = dao.occurrenceById(occurrenceId) ?: return@withTransaction
        val sequenceItemId = occurrence.sequenceItemId ?: return@withTransaction
        val task = dao.taskById(occurrence.taskId) ?: return@withTransaction
        if (!task.isActive || task.archived) return@withTransaction
        if (occurrence.status !in setOf(OccurrenceStatus.PENDING, OccurrenceStatus.COMPLETED)) return@withTransaction
        val exercise = dao.sequenceExercises(sequenceItemId)
            .firstOrNull { it.id == sequenceExerciseId }
            ?: return@withTransaction
        val now = LocalDateTime.now()
        val existing = dao.exerciseChecksForOccurrence(occurrenceId)
            .firstOrNull { it.sequenceExerciseId == sequenceExerciseId }
        if (status == ExerciseCheckStatus.PENDING) {
            dao.deleteOccurrenceExerciseCheck(occurrenceId, sequenceExerciseId)
        } else {
            dao.insertOccurrenceExerciseCheck(
                OccurrenceExerciseCheckEntity(
                    id = existing?.id ?: 0,
                    occurrenceId = occurrenceId,
                    sequenceExerciseId = exercise.id,
                    status = status,
                    updatedAt = now,
                ),
            )
        }

        val exercises = dao.sequenceExercises(sequenceItemId)
        val checksByExerciseId = dao.exerciseChecksForOccurrence(occurrenceId)
            .associateBy { it.sequenceExerciseId }
        val requiredExercises = exercises.filter { it.requirement == ExerciseRequirement.REQUIRED }
        val allRequiredComplete = requiredExercises.isNotEmpty() && requiredExercises.all {
            checksByExerciseId[it.id]?.status == ExerciseCheckStatus.COMPLETED
        }
        val refreshedOccurrence = dao.occurrenceById(occurrenceId) ?: return@withTransaction
        when {
            allRequiredComplete && refreshedOccurrence.status == OccurrenceStatus.PENDING -> {
                dao.updateOccurrence(
                    refreshedOccurrence.copy(
                        status = OccurrenceStatus.COMPLETED,
                        updatedAt = now,
                    ),
                )
                dao.insertLog(
                    CompletionLogEntity(
                        taskId = refreshedOccurrence.taskId,
                        occurrenceId = occurrenceId,
                        action = LogAction.COMPLETED,
                        timestamp = now,
                        operationalDate = refreshedOccurrence.operationalDate,
                        note = "Completed all required exercises",
                        createdAt = now,
                    ),
                )
            }
            !allRequiredComplete && refreshedOccurrence.status == OccurrenceStatus.COMPLETED -> {
                dao.updateOccurrence(
                    refreshedOccurrence.copy(
                        status = OccurrenceStatus.PENDING,
                        updatedAt = now,
                    ),
                )
                dao.insertLog(
                    CompletionLogEntity(
                        taskId = refreshedOccurrence.taskId,
                        occurrenceId = occurrenceId,
                        action = LogAction.EDITED,
                        timestamp = now,
                        operationalDate = currentOperationalDate,
                        note = "Reopened workout day after an exercise was unchecked",
                        createdAt = now,
                    ),
                )
            }
        }
    }

    suspend fun createRoutinePlan(
        plan: RoutinePlanEntity,
        phases: List<RoutinePhaseEntity>,
    ): Long = database.withTransaction {
        require(phases.isNotEmpty()) { "A routine plan requires at least one phase" }
        require(phases.map { it.position }.distinct().size == phases.size) {
            "Routine phase positions must be unique"
        }
        require(phases.count { it.status == RoutinePhaseStatus.ACTIVE } == 1) {
            "A new routine plan requires exactly one active phase"
        }
        phases.forEach { phase ->
            require(phase.minimumDays >= 1) { "Routine phases require at least one day" }
            require(dao.taskById(phase.taskId) != null) { "Routine phase task does not exist" }
        }
        val planId = dao.insertRoutinePlan(plan.copy(id = 0))
        dao.insertRoutinePhases(phases.map { it.copy(id = 0, routinePlanId = planId) })
        planId
    }

    suspend fun extendRoutinePhaseOneWeek(
        phaseId: Long,
        currentOperationalDate: LocalDate,
    ) = database.withTransaction {
        val phase = dao.routinePhaseById(phaseId) ?: return@withTransaction
        val task = dao.taskById(phase.taskId) ?: return@withTransaction
        if (
            phase.status != RoutinePhaseStatus.ACTIVE ||
            phase.advanceMode != PhaseAdvanceMode.MANUAL ||
            !task.isActive ||
            task.archived
        ) {
            return@withTransaction
        }
        val now = LocalDateTime.now()
        dao.updateRoutinePhase(
            phase.copy(
                lastReviewedDate = currentOperationalDate,
                updatedAt = now,
            ),
        )
        dao.insertLog(
            CompletionLogEntity(
                taskId = task.id,
                occurrenceId = null,
                action = LogAction.EDITED,
                timestamp = now,
                operationalDate = currentOperationalDate,
                note = "Extended routine phase by 1 week",
                createdAt = now,
            ),
        )
    }

    suspend fun advanceRoutinePhase(
        phaseId: Long,
        currentOperationalDate: LocalDate,
    ): Boolean = database.withTransaction {
        val phase = dao.routinePhaseById(phaseId) ?: return@withTransaction false
        if (phase.status != RoutinePhaseStatus.ACTIVE) return@withTransaction false
        val task = dao.taskById(phase.taskId) ?: return@withTransaction false
        if (!task.isActive || task.archived) return@withTransaction false
        advanceRoutinePhaseLocked(
            phase = phase,
            currentPhaseEndDate = currentOperationalDate,
            requestedNextStartDate = currentOperationalDate.plusDays(1),
            now = LocalDateTime.now(),
        )
    }

    suspend fun advanceDueRoutinePhases(currentOperationalDate: LocalDate): Int = database.withTransaction {
        var advanced = 0
        var shouldContinue = true
        while (shouldContinue) {
            val eligibleTaskIds = dao.tasks(includeArchived = true)
                .filter { it.isActive && !it.archived }
                .map { it.id }
                .toSet()
            val duePhase = dao.allRoutinePhases()
                .filter {
                    it.status == RoutinePhaseStatus.ACTIVE &&
                        it.advanceMode == PhaseAdvanceMode.AUTOMATIC &&
                        it.activatedDate != null &&
                        it.taskId in eligibleTaskIds
                }
                .sortedBy { it.activatedDate }
                .firstOrNull { phase ->
                    val transitionDate = phase.activatedDate!!.plusDays(phase.minimumDays.toLong())
                    !transitionDate.isAfter(currentOperationalDate)
                }
            if (duePhase == null) {
                shouldContinue = false
            } else {
                val transitionDate = duePhase.activatedDate!!.plusDays(duePhase.minimumDays.toLong())
                if (
                    advanceRoutinePhaseLocked(
                        phase = duePhase,
                        currentPhaseEndDate = transitionDate.minusDays(1),
                        requestedNextStartDate = transitionDate,
                        now = LocalDateTime.now(),
                    )
                ) {
                    advanced += 1
                } else {
                    shouldContinue = false
                }
            }
        }
        advanced
    }

    private suspend fun advanceRoutinePhaseLocked(
        phase: RoutinePhaseEntity,
        currentPhaseEndDate: LocalDate,
        requestedNextStartDate: LocalDate,
        now: LocalDateTime,
    ): Boolean {
        val currentTask = dao.taskById(phase.taskId) ?: return false
        if (!currentTask.isActive || currentTask.archived) return false
        val currentRule = dao.ruleForTask(phase.taskId) ?: return false
        val phases = dao.routinePhasesForPlan(phase.routinePlanId)
        val nextPhase = phases.firstOrNull { it.position > phase.position }
        val nextTask = nextPhase?.let { dao.taskById(it.taskId) ?: return false }
        val nextRule = nextPhase?.let { dao.ruleForTask(it.taskId) ?: return false }
        val nextStartDate = nextTask?.let {
            generator.nextValidDate(requestedNextStartDate, it.blockedDays) ?: return false
        }
        val normalizedCurrentEnd = maxOf(currentRule.startDate, currentPhaseEndDate)
        dao.deletePendingOccurrencesAfterDate(
            currentTask.id,
            currentRule.id,
            normalizedCurrentEnd,
        )
        dao.updateRule(
            currentRule.copy(
                endDate = normalizedCurrentEnd,
                durationDays = ChronoUnit.DAYS.between(currentRule.startDate, normalizedCurrentEnd).toInt() + 1,
                lastGeneratedDate = normalizedCurrentEnd,
                updatedAt = now,
            ),
        )
        dao.updateTask(currentTask.copy(isActive = false, updatedAt = now))
        dao.updateRoutinePhase(
            phase.copy(
                status = RoutinePhaseStatus.COMPLETED,
                advancedAt = now,
                updatedAt = now,
            ),
        )
        dao.insertLog(
            CompletionLogEntity(
                taskId = currentTask.id,
                occurrenceId = null,
                action = LogAction.EDITED,
                timestamp = now,
                operationalDate = normalizedCurrentEnd,
                note = "Advanced routine phase",
                createdAt = now,
            ),
        )
        if (nextPhase == null) return true

        val taskToActivate = nextTask ?: return false
        val ruleToActivate = nextRule ?: return false
        val activationDate = nextStartDate ?: return false
        dao.deleteAllPendingOccurrencesForRule(taskToActivate.id, ruleToActivate.id)
        val nextEndDate = if (nextPhase.advanceMode == PhaseAdvanceMode.AUTOMATIC) {
            activationDate.plusDays((nextPhase.minimumDays - 1).toLong())
        } else {
            null
        }
        val activatedTask = taskToActivate.copy(isActive = true, archived = false, updatedAt = now)
        val activatedRule = ruleToActivate.copy(
            startDate = activationDate,
            endDate = nextEndDate,
            durationDays = nextEndDate?.let { nextPhase.minimumDays },
            startsAfterTaskId = null,
            lastGeneratedDate = null,
            updatedAt = now,
        )
        dao.updateTask(activatedTask)
        dao.updateRule(activatedRule)
        dao.updateRoutinePhase(
            nextPhase.copy(
                status = RoutinePhaseStatus.ACTIVE,
                activatedDate = activationDate,
                advancedAt = null,
                lastReviewedDate = null,
                updatedAt = now,
            ),
        )
        val sequenceItemIds = dao.sequenceForTask(taskToActivate.id)
            ?.let { sequence -> dao.sequenceItems(sequence.id).map { it.id } }
            .orEmpty()
        generateOccurrences(
            taskId = taskToActivate.id,
            rule = activatedRule,
            sequenceItemIds = sequenceItemIds,
            fromDate = activationDate,
            throughDate = nextEndDate ?: activationDate.plusDays(DEFAULT_GENERATION_WINDOW_DAYS),
        )
        dao.insertLog(
            CompletionLogEntity(
                taskId = taskToActivate.id,
                occurrenceId = null,
                action = LogAction.EDITED,
                timestamp = now,
                operationalDate = activationDate,
                note = "Activated routine phase",
                createdAt = now,
            ),
        )
        return true
    }

    suspend fun completeOccurrenceYesterday(occurrenceId: Long, currentOperationalDate: LocalDate) = database.withTransaction {
        val occurrence = dao.occurrenceById(occurrenceId) ?: return@withTransaction
        val task = dao.taskById(occurrence.taskId) ?: return@withTransaction
        if (
            !task.pushable ||
            occurrence.status != OccurrenceStatus.PENDING ||
            !task.isActive ||
            task.archived
        ) {
            return@withTransaction
        }
        val rule = dao.ruleById(occurrence.recurrenceRuleId) ?: return@withTransaction
        val completedDate = currentOperationalDate.minusDays(1)
        val nextPending = dao.futureOccurrencesForRule(
            taskId = occurrence.taskId,
            ruleId = occurrence.recurrenceRuleId,
            fromDate = completedDate.plusDays(1),
        ).firstOrNull()
        if (nextPending?.id != occurrence.id) return@withTransaction

        val allForRule = dao.occurrencesForTask(occurrence.taskId)
            .filter { it.recurrenceRuleId == occurrence.recurrenceRuleId }
        val completedDateOccurrence = allForRule.firstOrNull {
            it.id != occurrence.id && it.operationalDate == completedDate
        }
        if (
            completedDateOccurrence != null &&
            (
                !completedDateOccurrence.isShifted ||
                    completedDateOccurrence.status !in setOf(OccurrenceStatus.SHIFTED, OccurrenceStatus.MISSED)
            )
        ) {
            return@withTransaction
        }
        val futurePending = dao.futureOccurrencesForRule(
            taskId = occurrence.taskId,
            ruleId = occurrence.recurrenceRuleId,
            fromDate = occurrence.operationalDate.plusDays(1),
        )
        val pendingToRealign = if (completedDateOccurrence != null) {
            listOf(occurrence) + futurePending
        } else {
            futurePending
        }
        val pendingToRealignIds = pendingToRealign.map { it.id }.toSet()
        val occupiedDates = allForRule
            .filter {
                it.id !in pendingToRealignIds &&
                    it.id != completedDateOccurrence?.id &&
                    it.id != occurrence.id
            }
            .map { it.operationalDate }
            .toMutableSet()
        occupiedDates.add(completedDate)

        val now = LocalDateTime.now()
        val spacingDays = scheduleSpacingDays(task, rule)
        var cursor = completedDate.plusDays(spacingDays.toLong())
        val shiftedPending = mutableListOf<ScheduledOccurrenceEntity>()
        pendingToRealign.forEach { item ->
            val newDate = nextAvailableValidDate(
                startDate = cursor,
                blockedDays = task.blockedDays,
                occupiedDates = occupiedDates,
            ) ?: return@withTransaction
            occupiedDates.add(newDate)
            cursor = newDate.plusDays(spacingDays.toLong())
            shiftedPending.add(
                item.copy(
                    scheduledDate = newDate,
                    operationalDate = newDate,
                    isShifted = true,
                    originalDate = item.originalDate ?: item.operationalDate,
                    updatedAt = now,
                ),
            )
        }

        val completedOccurrenceId = if (completedDateOccurrence != null) {
            dao.updateOccurrence(
                completedDateOccurrence.copy(
                    status = OccurrenceStatus.COMPLETED,
                    note = completedDateOccurrence.note.ifBlank { "Completed yesterday" },
                    updatedAt = now,
                ),
            )
            completedDateOccurrence.id
        } else {
            dao.updateOccurrence(
                occurrence.copy(
                    scheduledDate = completedDate,
                    operationalDate = completedDate,
                    status = OccurrenceStatus.COMPLETED,
                    isShifted = true,
                    originalDate = occurrence.originalDate ?: occurrence.operationalDate,
                    note = occurrence.note.ifBlank { "Completed yesterday" },
                    updatedAt = now,
                ),
            )
            occurrence.id
        }
        dao.updateOccurrences(
            if (completedDateOccurrence != null) {
                shiftedPending.asReversed()
            } else {
                shiftedPending
            },
        )
        moveRuleStartDateBackIfNeeded(occurrence.recurrenceRuleId, completedDate, now)
        shiftDurationWindowBy(
            taskId = occurrence.taskId,
            ruleId = occurrence.recurrenceRuleId,
            dayDelta = ChronoUnit.DAYS.between(occurrence.operationalDate, completedDate),
            currentOperationalDate = currentOperationalDate,
            now = now,
        )
        dao.insertLog(
            CompletionLogEntity(
                taskId = occurrence.taskId,
                occurrenceId = completedOccurrenceId,
                action = LogAction.COMPLETED,
                timestamp = now,
                operationalDate = completedDate,
                note = "Completed yesterday; schedule shifted from ${occurrence.operationalDate}",
                createdAt = now,
            ),
        )
        handleCycleRestartForTask(occurrence.taskId, currentOperationalDate, now)
    }

    suspend fun skipOccurrence(occurrenceId: Long, currentOperationalDate: LocalDate, note: String = "") {
        updateOccurrenceStatus(occurrenceId, OccurrenceStatus.SKIPPED, LogAction.SKIPPED, note, currentOperationalDate)
    }

    suspend fun pushOccurrenceForward(occurrenceId: Long, currentOperationalDate: LocalDate) = database.withTransaction {
        val occurrence = dao.occurrenceById(occurrenceId) ?: return@withTransaction
        val task = dao.taskById(occurrence.taskId) ?: return@withTransaction
        if (
            !task.pushable ||
            occurrence.status !in setOf(OccurrenceStatus.PENDING, OccurrenceStatus.MISSED) ||
            !task.isActive ||
            task.archived
        ) {
            return@withTransaction
        }
        val rule = dao.ruleById(occurrence.recurrenceRuleId) ?: return@withTransaction
        val futurePending = dao.futureOccurrencesForRule(
            taskId = occurrence.taskId,
            ruleId = occurrence.recurrenceRuleId,
            fromDate = occurrence.operationalDate.plusDays(1),
        )
        val futurePendingIds = futurePending.map { it.id }.toSet()
        val occupiedDates = dao.occurrencesForTask(occurrence.taskId)
            .filter { it.recurrenceRuleId == occurrence.recurrenceRuleId }
            .filter { it.id != occurrence.id && it.id !in futurePendingIds }
            .map { it.operationalDate }
            .toMutableSet()

        val now = LocalDateTime.now()
        val replacementDate = nextAvailableValidDate(
            startDate = occurrence.operationalDate.plusDays(1),
            blockedDays = task.blockedDays,
            occupiedDates = occupiedDates,
        ) ?: return@withTransaction
        occupiedDates.add(replacementDate)

        val spacingDays = scheduleSpacingDays(task, rule)
        var cursor = replacementDate.plusDays(spacingDays.toLong())
        val shiftedFuture = mutableListOf<ScheduledOccurrenceEntity>()
        futurePending.forEach { item ->
            val newDate = nextAvailableValidDate(
                startDate = cursor,
                blockedDays = task.blockedDays,
                occupiedDates = occupiedDates,
            ) ?: return@withTransaction
            occupiedDates.add(newDate)
            cursor = newDate.plusDays(spacingDays.toLong())
            shiftedFuture.add(
                item.copy(
                    scheduledDate = newDate,
                    operationalDate = newDate,
                    isShifted = true,
                    originalDate = item.originalDate ?: item.operationalDate,
                    updatedAt = now,
                ),
            )
        }

        dao.updateOccurrence(
            occurrence.copy(
                status = if (occurrence.status == OccurrenceStatus.MISSED) {
                    OccurrenceStatus.MISSED
                } else {
                    OccurrenceStatus.SHIFTED
                },
                isShifted = true,
                originalDate = occurrence.originalDate ?: occurrence.operationalDate,
                note = occurrence.note.ifBlank { "Pushed forward" },
                updatedAt = now,
            ),
        )
        dao.updateOccurrences(shiftedFuture.asReversed())
        val replacementOccurrenceId = dao.insertOccurrences(
            listOf(
                occurrence.copy(
                    id = 0,
                    scheduledDate = replacementDate,
                    operationalDate = replacementDate,
                    status = OccurrenceStatus.PENDING,
                    isShifted = true,
                    originalDate = occurrence.operationalDate,
                    note = "",
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        ).single()
        copyExerciseChecks(occurrence.id, replacementOccurrenceId, now)
        val latestShiftedDate = shiftedFuture
            .map { it.operationalDate }
            .plus(replacementDate)
            .maxOrNull()
        if (latestShiftedDate != null && (rule.lastGeneratedDate == null || latestShiftedDate.isAfter(rule.lastGeneratedDate))) {
            dao.updateRule(rule.copy(lastGeneratedDate = latestShiftedDate, updatedAt = now))
        }
        shiftDurationWindowAfterPush(
            taskId = occurrence.taskId,
            ruleId = occurrence.recurrenceRuleId,
            shiftedFrom = occurrence.operationalDate,
            shiftedTo = replacementDate,
            currentOperationalDate = currentOperationalDate,
            now = now,
        )
        dao.insertLog(
            CompletionLogEntity(
                taskId = occurrence.taskId,
                occurrenceId = occurrence.id,
                action = LogAction.SHIFTED_FORWARD,
                timestamp = now,
                operationalDate = occurrence.operationalDate,
                note = "Pushed to ${replacementDate}",
                createdAt = now,
            ),
        )
        handleCycleRestartForTask(occurrence.taskId, currentOperationalDate, now)
    }

    suspend fun undoOccurrenceDecision(occurrenceId: Long, currentOperationalDate: LocalDate) = database.withTransaction {
        val occurrence = dao.occurrenceById(occurrenceId) ?: return@withTransaction
        val task = dao.taskById(occurrence.taskId) ?: return@withTransaction
        if (!task.isActive || task.archived) return@withTransaction
        val now = LocalDateTime.now()
        if (
            task.taskType == TaskType.LONG_TERM &&
            occurrence.status == OccurrenceStatus.COMPLETED &&
            occurrence.originalDate != null
        ) {
            val rule = dao.ruleById(occurrence.recurrenceRuleId) ?: return@withTransaction
            undoCompletionAnchoredLongTermOccurrence(
                task = task,
                rule = rule,
                occurrence = occurrence,
                currentOperationalDate = currentOperationalDate,
                now = now,
            )
            return@withTransaction
        }
        if (
            occurrence.status == OccurrenceStatus.COMPLETED &&
            occurrence.isShifted &&
            occurrence.originalDate != null &&
            occurrence.originalDate.isAfter(occurrence.operationalDate)
        ) {
            undoCompletedYesterdayOccurrence(occurrence, currentOperationalDate, now)
            return@withTransaction
        }
        if (occurrence.status == OccurrenceStatus.SHIFTED && occurrence.isShifted) {
            undoPushedOccurrence(occurrence, currentOperationalDate, now)
            return@withTransaction
        }
        if (occurrence.status !in setOf(OccurrenceStatus.COMPLETED, OccurrenceStatus.SKIPPED, OccurrenceStatus.MISSED)) {
            return@withTransaction
        }
        dao.updateOccurrence(
            occurrence.copy(
                status = OccurrenceStatus.PENDING,
                note = occurrence.note.takeUnless { it in ACTION_GENERATED_NOTES }.orEmpty(),
                updatedAt = now,
            ),
        )
        dao.insertLog(
            CompletionLogEntity(
                taskId = occurrence.taskId,
                occurrenceId = occurrence.id,
                action = LogAction.EDITED,
                timestamp = now,
                operationalDate = occurrence.operationalDate,
                note = "Reset checklist decision",
                createdAt = now,
            ),
        )
        handleCycleRestartForTask(occurrence.taskId, currentOperationalDate, now)
    }

    suspend fun updateOccurrenceNote(occurrenceId: Long, note: String) = database.withTransaction {
        val occurrence = dao.occurrenceById(occurrenceId) ?: return@withTransaction
        val task = dao.taskById(occurrence.taskId) ?: return@withTransaction
        if (!task.isActive || task.archived) return@withTransaction
        val trimmedNote = note.trim()
        val now = LocalDateTime.now()
        dao.updateOccurrence(occurrence.copy(note = trimmedNote, updatedAt = now))
        dao.insertLog(
            CompletionLogEntity(
                taskId = occurrence.taskId,
                occurrenceId = occurrence.id,
                action = LogAction.EDITED,
                timestamp = now,
                operationalDate = occurrence.operationalDate,
                note = "Updated occurrence note",
                createdAt = now,
            ),
        )
    }

    suspend fun swapSequenceOccurrenceItems(
        occurrenceId: Long,
        targetOccurrenceId: Long,
        currentOperationalDate: LocalDate,
    ) = database.withTransaction {
        if (occurrenceId == targetOccurrenceId) return@withTransaction
        val occurrence = dao.occurrenceById(occurrenceId) ?: return@withTransaction
        val target = dao.occurrenceById(targetOccurrenceId) ?: return@withTransaction
        val task = dao.taskById(occurrence.taskId) ?: return@withTransaction
        val rule = dao.ruleById(occurrence.recurrenceRuleId) ?: return@withTransaction
        if (
            task.taskType != TaskType.SEQUENCE_ROUTINE ||
            !task.isActive ||
            task.archived ||
            occurrence.taskId != target.taskId ||
            occurrence.recurrenceRuleId != target.recurrenceRuleId ||
            occurrence.status != OccurrenceStatus.PENDING ||
            target.status != OccurrenceStatus.PENDING ||
            target.operationalDate.isBefore(rule.startDate) ||
            target.operationalDate.isBefore(occurrence.operationalDate)
        ) {
            return@withTransaction
        }
        val occurrenceSequenceItemId = occurrence.sequenceItemId ?: return@withTransaction
        val targetSequenceItemId = target.sequenceItemId ?: return@withTransaction
        if (occurrenceSequenceItemId == targetSequenceItemId) return@withTransaction

        val now = LocalDateTime.now()
        val occurrenceChecks = dao.exerciseChecksForOccurrence(occurrence.id)
        val targetChecks = dao.exerciseChecksForOccurrence(target.id)
        dao.deleteExerciseChecksForOccurrences(listOf(occurrence.id, target.id))
        dao.updateOccurrences(
            listOf(
                occurrence.copy(
                    sequenceItemId = targetSequenceItemId,
                    note = target.note,
                    updatedAt = now,
                ),
                target.copy(
                    sequenceItemId = occurrenceSequenceItemId,
                    note = occurrence.note,
                    updatedAt = now,
                ),
            ),
        )
        occurrenceChecks.forEach { check ->
            dao.insertOccurrenceExerciseCheck(check.copy(id = 0, occurrenceId = target.id, updatedAt = now))
        }
        targetChecks.forEach { check ->
            dao.insertOccurrenceExerciseCheck(check.copy(id = 0, occurrenceId = occurrence.id, updatedAt = now))
        }
        dao.insertLog(
            CompletionLogEntity(
                taskId = occurrence.taskId,
                occurrenceId = occurrence.id,
                action = LogAction.EDITED,
                timestamp = now,
                operationalDate = currentOperationalDate,
                note = "Swapped sequence item with ${target.operationalDate}",
                createdAt = now,
            ),
        )
    }

    suspend fun setSequenceOccurrencePoint(
        occurrenceId: Long,
        targetSequenceIndex: Int,
        currentOperationalDate: LocalDate,
    ) = database.withTransaction {
        val occurrence = dao.occurrenceById(occurrenceId) ?: return@withTransaction
        val task = dao.taskById(occurrence.taskId) ?: return@withTransaction
        val rule = dao.ruleById(occurrence.recurrenceRuleId) ?: return@withTransaction
        if (
            task.taskType != TaskType.SEQUENCE_ROUTINE ||
            rule.ruleType != RuleType.SEQUENCE ||
            !task.isActive ||
            task.archived ||
            occurrence.status != OccurrenceStatus.PENDING
        ) {
            return@withTransaction
        }
        val sequence = dao.sequenceForTask(task.id) ?: return@withTransaction
        val sequenceItems = dao.sequenceItems(sequence.id).sortedBy { it.position }
        if (targetSequenceIndex !in sequenceItems.indices) return@withTransaction

        val now = LocalDateTime.now()
        val updatedOccurrences = mutableListOf(
            occurrence.copy(
                sequenceItemId = sequenceItems[targetSequenceIndex].id,
                updatedAt = now,
            ),
        )
        dao.futureOccurrencesForRule(
            taskId = occurrence.taskId,
            ruleId = occurrence.recurrenceRuleId,
            fromDate = occurrence.operationalDate.plusDays(1),
        ).forEachIndexed { offset, futureOccurrence ->
            val sequenceIndex = Math.floorMod(targetSequenceIndex + offset + 1, sequenceItems.size)
            updatedOccurrences.add(
                futureOccurrence.copy(
                    sequenceItemId = sequenceItems[sequenceIndex].id,
                    updatedAt = now,
                ),
            )
        }
        dao.deleteExerciseChecksForOccurrences(updatedOccurrences.map { it.id })
        dao.updateOccurrences(updatedOccurrences)
        dao.insertLog(
            CompletionLogEntity(
                taskId = occurrence.taskId,
                occurrenceId = occurrence.id,
                action = LogAction.EDITED,
                timestamp = now,
                operationalDate = currentOperationalDate,
                note = "Set sequence point to ${sequenceItems[targetSequenceIndex].name}",
                createdAt = now,
            ),
        )
    }

    suspend fun setTaskArchived(taskId: Long, archived: Boolean, currentOperationalDate: LocalDate) = database.withTransaction {
        val task = dao.taskById(taskId) ?: return@withTransaction
        val now = LocalDateTime.now()
        val note = if (archived) "Archived task" else "Restored task"
        dao.updateTask(task.copy(archived = archived, isActive = !archived, updatedAt = now))
        dao.insertLog(
            CompletionLogEntity(
                taskId = taskId,
                occurrenceId = null,
                action = LogAction.EDITED,
                timestamp = now,
                operationalDate = currentOperationalDate,
                note = note,
                createdAt = now,
            ),
        )
    }

    suspend fun setRoutinePlanArchived(
        planId: Long,
        archived: Boolean,
        currentOperationalDate: LocalDate,
    ) = database.withTransaction {
        val phases = dao.routinePhasesForPlan(planId)
        if (phases.isEmpty()) return@withTransaction
        val now = LocalDateTime.now()
        val note = if (archived) "Archived phased routine" else "Restored phased routine"
        phases.forEach { phase ->
            val task = dao.taskById(phase.taskId) ?: return@forEach
            dao.updateTask(
                task.copy(
                    archived = archived,
                    isActive = !archived && phase.status == RoutinePhaseStatus.ACTIVE,
                    updatedAt = now,
                ),
            )
            dao.insertLog(
                CompletionLogEntity(
                    taskId = task.id,
                    occurrenceId = null,
                    action = LogAction.EDITED,
                    timestamp = now,
                    operationalDate = currentOperationalDate,
                    note = note,
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun deleteTaskPermanently(taskId: Long) = database.withTransaction {
        dao.clearRulesStartingAfterTask(taskId, LocalDateTime.now())
        dao.deleteLogsForTask(taskId)
        dao.deleteOccurrencesForTask(taskId)
        dao.deleteRulesForTask(taskId)
        dao.deleteSequenceForTask(taskId)
        dao.deleteTask(taskId)
        dao.deleteOrphanedRoutinePlans()
    }

    suspend fun deleteRoutinePlanPermanently(planId: Long) = database.withTransaction {
        val taskIds = dao.routinePhasesForPlan(planId).map { it.taskId }
        val now = LocalDateTime.now()
        taskIds.forEach { taskId ->
            dao.clearRulesStartingAfterTask(taskId, now)
            dao.deleteLogsForTask(taskId)
            dao.deleteOccurrencesForTask(taskId)
            dao.deleteRulesForTask(taskId)
            dao.deleteSequenceForTask(taskId)
            dao.deleteTask(taskId)
        }
        dao.deleteOrphanedRoutinePlans()
    }

    suspend fun repairDerivedDataConsistency(): Int = database.withTransaction {
        val now = LocalDateTime.now()
        var repaired = dao.clearInvalidStartsAfterReferences(now)

        val completedPhaseTaskIds = dao.allRoutinePhases()
            .asSequence()
            .filter { it.status == RoutinePhaseStatus.COMPLETED }
            .map { it.taskId }
            .toSet()
        val ruleById = dao.allRules().associateBy { it.id }
        val protectedOccurrenceIds = buildSet {
            dao.allLogs().mapNotNullTo(this) { it.occurrenceId }
            dao.allOccurrenceExerciseChecks().mapTo(this) { it.occurrenceId }
        }
        val stalePendingIds = dao.allOccurrences()
            .asSequence()
            .filter { it.taskId in completedPhaseTaskIds }
            .filter { it.status == OccurrenceStatus.PENDING }
            .filter { it.id !in protectedOccurrenceIds }
            .filter { occurrence ->
                val rule = ruleById[occurrence.recurrenceRuleId] ?: return@filter false
                val afterEnd = rule.endDate?.let { endDate ->
                    occurrence.scheduledDate.isAfter(endDate) || occurrence.operationalDate.isAfter(endDate)
                } == true
                occurrence.scheduledDate.isBefore(rule.startDate) ||
                    occurrence.operationalDate.isBefore(rule.startDate) ||
                    afterEnd
            }
            .map { it.id }
            .toSet()
        repaired += deletePendingRepairRows(stalePendingIds)

        val nonSequenceTaskIds = dao.tasks(includeArchived = true)
            .filter { it.taskType != TaskType.SEQUENCE_ROUTINE }
            .map { it.id }
        nonSequenceTaskIds.forEach { taskId ->
            repaired += deleteUnreferencedSequencesForTask(taskId)
        }
        repaired += dao.deleteOrphanedRoutinePlans()
        repaired
    }

    suspend fun markOverduePendingMissed(currentOperationalDate: LocalDate): Int {
        return database.withTransaction {
            val now = LocalDateTime.now()
            var handled = 0
            while (true) {
                // A push moves every later pending row. Re-query after each decision so
                // the next push uses those updated dates instead of a stale snapshot.
                val occurrence = dao.nextActionablePendingOccurrenceBeforeDate(currentOperationalDate) ?: break
                val task = dao.taskById(occurrence.taskId) ?: break
                when (task.noActionBehavior) {
                    NoActionBehavior.AUTO_SKIP -> {
                        dao.updateOccurrence(
                            occurrence.copy(
                                status = OccurrenceStatus.SKIPPED,
                                note = occurrence.note.ifBlank { "Auto-skipped after day reset" },
                                updatedAt = now,
                            ),
                        )
                        dao.insertLog(
                            CompletionLogEntity(
                                taskId = occurrence.taskId,
                                occurrenceId = occurrence.id,
                                action = LogAction.SKIPPED,
                                timestamp = now,
                                operationalDate = occurrence.operationalDate,
                                note = "Auto-skipped after day reset",
                                createdAt = now,
                            ),
                        )
                        handled += 1
                        handleCycleRestartForTask(occurrence.taskId, currentOperationalDate, now)
                    }
                    NoActionBehavior.AUTO_PUSH -> {
                        val pushed = if (task.pushable) {
                            pushOccurrenceForwardLocked(occurrence, currentOperationalDate, now, "Auto-pushed after day reset")
                        } else {
                            false
                        }
                        if (!pushed) {
                            markOccurrenceMissed(occurrence, now)
                            handleCycleRestartForTask(occurrence.taskId, currentOperationalDate, now)
                        }
                        handled += 1
                    }
                    NoActionBehavior.MARK_MISSED -> {
                        markOccurrenceMissed(occurrence, now)
                        handleCycleRestartForTask(occurrence.taskId, currentOperationalDate, now)
                        handled += 1
                    }
                }
            }
            handled
        }
    }

    suspend fun extendGeneratedOccurrences(
        currentOperationalDate: LocalDate,
        daysAhead: Long = DEFAULT_GENERATION_WINDOW_DAYS,
    ) = database.withTransaction {
        dao.allRules().forEach { rule ->
            val task = dao.taskById(rule.taskId) ?: return@forEach
            if (!task.isActive || task.archived) return@forEach
            val throughDate = currentOperationalDate.plusDays(generationWindowDays(task, daysAhead))
            val fromDate = rule.lastGeneratedDate
                ?.plusDays(1)
                ?.takeIf { !it.isBefore(currentOperationalDate) }
                ?: maxOf(rule.startDate, currentOperationalDate)
            if (fromDate.isAfter(throughDate)) return@forEach
            val sequenceIds = dao.sequenceForTask(task.id)
                ?.let { dao.sequenceItems(it.id).map { item -> item.id } }
                .orEmpty()
            val sequenceStartIndex = if (rule.ruleType == RuleType.SEQUENCE && sequenceIds.isNotEmpty()) {
                val previousSequenceItemId = dao.latestOccurrenceBefore(task.id, rule.id, fromDate)?.sequenceItemId
                nextSequenceStartIndex(sequenceIds, previousSequenceItemId)
            } else {
                0
            }
            generateOccurrences(
                taskId = task.id,
                rule = rule,
                sequenceItemIds = sequenceIds,
                fromDate = fromDate,
                throughDate = throughDate,
                sequenceStartIndex = sequenceStartIndex,
            )
        }
    }

    suspend fun repairPendingCadences(currentOperationalDate: LocalDate): Int = database.withTransaction {
        val now = LocalDateTime.now()
        var repaired = 0
        dao.allRules().forEach { rule ->
            val task = dao.taskById(rule.taskId) ?: return@forEach
            repaired += repairPendingCadenceForRule(task, rule, currentOperationalDate, now)
        }
        repaired
    }

    suspend fun restartEndedCycles(currentOperationalDate: LocalDate): Int = database.withTransaction {
        val now = LocalDateTime.now()
        var restarted = 0
        dao.allCycleMemberships()
            .map { it.taskId }
            .distinct()
            .forEach { taskId ->
                if (
                    restartTaskCycleIfDue(
                        taskId = taskId,
                        currentOperationalDate = currentOperationalDate,
                        now = now,
                        restartWhenCycleEndedWithoutUpcoming = true,
                    )
                ) {
                    restarted += 1
                }
            }
        restarted
    }

    suspend fun shiftSequenceForward(occurrenceId: Long, currentOperationalDate: LocalDate) = database.withTransaction {
        val occurrence = dao.occurrenceById(occurrenceId) ?: return@withTransaction
        val task = dao.taskById(occurrence.taskId) ?: return@withTransaction
        if (
            task.taskType != TaskType.SEQUENCE_ROUTINE ||
            occurrence.status !in setOf(OccurrenceStatus.PENDING, OccurrenceStatus.MISSED) ||
            !task.isActive ||
            task.archived
        ) {
            return@withTransaction
        }
        val futurePending = dao.futureOccurrencesForRule(
            taskId = occurrence.taskId,
            ruleId = occurrence.recurrenceRuleId,
            fromDate = occurrence.operationalDate.plusDays(1),
        )
        val futurePendingIds = futurePending.map { it.id }.toSet()
        val occupiedDates = dao.occurrencesForTask(occurrence.taskId)
            .filter { it.recurrenceRuleId == occurrence.recurrenceRuleId }
            .filter { it.id != occurrence.id && it.id !in futurePendingIds }
            .map { it.operationalDate }
            .toMutableSet()

        val now = LocalDateTime.now()
        val replacementDate = nextAvailableValidDate(
            startDate = occurrence.operationalDate.plusDays(1),
            blockedDays = task.blockedDays,
            occupiedDates = occupiedDates,
        ) ?: return@withTransaction
        occupiedDates.add(replacementDate)
        val rule = dao.ruleById(occurrence.recurrenceRuleId)
        val spacingDays = scheduleSpacingDays(task, rule)
        var cursor = replacementDate.plusDays(spacingDays.toLong())
        val shiftedFuture = mutableListOf<ScheduledOccurrenceEntity>()
        futurePending.forEach { item ->
            val newDate = nextAvailableValidDate(
                startDate = cursor,
                blockedDays = task.blockedDays,
                occupiedDates = occupiedDates,
            ) ?: return@withTransaction
            occupiedDates.add(newDate)
            cursor = newDate.plusDays(spacingDays.toLong())
            shiftedFuture.add(
                item.copy(
                    scheduledDate = newDate,
                    operationalDate = newDate,
                    isShifted = true,
                    originalDate = item.originalDate ?: item.operationalDate,
                    updatedAt = now,
                ),
            )
        }

        val shiftedPlaceholder = occurrence.copy(
            status = if (occurrence.status == OccurrenceStatus.MISSED) {
                OccurrenceStatus.MISSED
            } else {
                OccurrenceStatus.SHIFTED
            },
            isShifted = true,
            originalDate = occurrence.originalDate ?: occurrence.operationalDate,
            note = occurrence.note.ifBlank { "Pushed forward" },
            updatedAt = now,
        )
        dao.updateOccurrence(shiftedPlaceholder)
        // Move the farthest rows first so each unique rule/date slot is freed
        // before the previous row moves into it.
        dao.updateOccurrences(shiftedFuture.asReversed())

        val replacementOccurrenceId = dao.insertOccurrences(
            listOf(
                occurrence.copy(
                    id = 0,
                    scheduledDate = replacementDate,
                    operationalDate = replacementDate,
                    status = OccurrenceStatus.PENDING,
                    isShifted = true,
                    originalDate = occurrence.operationalDate,
                    note = "",
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        ).single()
        copyExerciseChecks(occurrence.id, replacementOccurrenceId, now)
        val latestShiftedDate = shiftedFuture
            .map { it.operationalDate }
            .plus(replacementDate)
            .maxOrNull()
        if (latestShiftedDate != null && rule != null && (rule.lastGeneratedDate == null || latestShiftedDate.isAfter(rule.lastGeneratedDate))) {
            dao.updateRule(rule.copy(lastGeneratedDate = latestShiftedDate, updatedAt = now))
        }
        shiftDurationWindowAfterPush(
            taskId = occurrence.taskId,
            ruleId = occurrence.recurrenceRuleId,
            shiftedFrom = occurrence.operationalDate,
            shiftedTo = replacementDate,
            currentOperationalDate = currentOperationalDate,
            now = now,
        )
        dao.insertLog(
            CompletionLogEntity(
                taskId = occurrence.taskId,
                occurrenceId = occurrence.id,
                action = LogAction.SHIFTED_FORWARD,
                timestamp = now,
                operationalDate = occurrence.operationalDate,
                note = "Pushed current and future sequence items forward",
                createdAt = now,
            ),
        )
        handleCycleRestartForTask(occurrence.taskId, currentOperationalDate, now)
    }

    private suspend fun pushOccurrenceForwardLocked(
        occurrence: ScheduledOccurrenceEntity,
        currentOperationalDate: LocalDate,
        now: LocalDateTime,
        logNote: String,
    ): Boolean {
        val task = dao.taskById(occurrence.taskId) ?: return false
        if (
            !task.pushable ||
            occurrence.status !in setOf(OccurrenceStatus.PENDING, OccurrenceStatus.MISSED) ||
            !task.isActive ||
            task.archived
        ) {
            return false
        }
        val rule = dao.ruleById(occurrence.recurrenceRuleId) ?: return false
        val futurePending = dao.futureOccurrencesForRule(
            taskId = occurrence.taskId,
            ruleId = occurrence.recurrenceRuleId,
            fromDate = occurrence.operationalDate.plusDays(1),
        )
        val futurePendingIds = futurePending.map { it.id }.toSet()
        val occupiedDates = dao.occurrencesForTask(occurrence.taskId)
            .filter { it.recurrenceRuleId == occurrence.recurrenceRuleId }
            .filter { it.id != occurrence.id && it.id !in futurePendingIds }
            .map { it.operationalDate }
            .toMutableSet()

        val replacementDate = nextAvailableValidDate(
            startDate = occurrence.operationalDate.plusDays(1),
            blockedDays = task.blockedDays,
            occupiedDates = occupiedDates,
        ) ?: return false
        occupiedDates.add(replacementDate)

        val spacingDays = scheduleSpacingDays(task, rule)
        var cursor = replacementDate.plusDays(spacingDays.toLong())
        val shiftedFuture = mutableListOf<ScheduledOccurrenceEntity>()
        futurePending.forEach { item ->
            val newDate = nextAvailableValidDate(
                startDate = cursor,
                blockedDays = task.blockedDays,
                occupiedDates = occupiedDates,
            ) ?: return false
            occupiedDates.add(newDate)
            cursor = newDate.plusDays(spacingDays.toLong())
            shiftedFuture.add(
                item.copy(
                    scheduledDate = newDate,
                    operationalDate = newDate,
                    isShifted = true,
                    originalDate = item.originalDate ?: item.operationalDate,
                    updatedAt = now,
                ),
            )
        }

        dao.updateOccurrence(
            occurrence.copy(
                status = if (occurrence.status == OccurrenceStatus.MISSED) {
                    OccurrenceStatus.MISSED
                } else {
                    OccurrenceStatus.SHIFTED
                },
                isShifted = true,
                originalDate = occurrence.originalDate ?: occurrence.operationalDate,
                note = occurrence.note.ifBlank { "Pushed forward" },
                updatedAt = now,
            ),
        )
        dao.updateOccurrences(shiftedFuture.asReversed())
        val replacementOccurrenceId = dao.insertOccurrences(
            listOf(
                occurrence.copy(
                    id = 0,
                    scheduledDate = replacementDate,
                    operationalDate = replacementDate,
                    status = OccurrenceStatus.PENDING,
                    isShifted = true,
                    originalDate = occurrence.operationalDate,
                    note = "",
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        ).single()
        copyExerciseChecks(occurrence.id, replacementOccurrenceId, now)
        val latestShiftedDate = shiftedFuture
            .map { it.operationalDate }
            .plus(replacementDate)
            .maxOrNull()
        if (latestShiftedDate != null && (rule.lastGeneratedDate == null || latestShiftedDate.isAfter(rule.lastGeneratedDate))) {
            dao.updateRule(rule.copy(lastGeneratedDate = latestShiftedDate, updatedAt = now))
        }
        shiftDurationWindowAfterPush(
            taskId = occurrence.taskId,
            ruleId = occurrence.recurrenceRuleId,
            shiftedFrom = occurrence.operationalDate,
            shiftedTo = replacementDate,
            currentOperationalDate = currentOperationalDate,
            now = now,
        )
        dao.insertLog(
            CompletionLogEntity(
                taskId = occurrence.taskId,
                occurrenceId = occurrence.id,
                action = LogAction.SHIFTED_FORWARD,
                timestamp = now,
                operationalDate = occurrence.operationalDate,
                note = logNote,
                createdAt = now,
            ),
        )
        handleCycleRestartForTask(occurrence.taskId, currentOperationalDate, now)
        return true
    }

    private suspend fun markOccurrenceMissed(
        occurrence: ScheduledOccurrenceEntity,
        now: LocalDateTime,
    ) {
        dao.updateOccurrence(
            occurrence.copy(
                status = OccurrenceStatus.MISSED,
                note = occurrence.note.ifBlank { "Marked missed after day reset" },
                updatedAt = now,
            ),
        )
        dao.insertLog(
            CompletionLogEntity(
                taskId = occurrence.taskId,
                occurrenceId = occurrence.id,
                action = LogAction.MARKED_MISSED,
                timestamp = now,
                operationalDate = occurrence.operationalDate,
                note = "Marked missed after day reset",
                createdAt = now,
            ),
        )
    }

    private suspend fun handleCycleRestartForTask(
        taskId: Long,
        currentOperationalDate: LocalDate,
        now: LocalDateTime,
    ) {
        restartTaskCycleIfDue(
            taskId = taskId,
            currentOperationalDate = currentOperationalDate,
            now = now,
            restartWhenCycleEndedWithoutUpcoming = false,
        )
    }

    private suspend fun restartTaskCycleIfDue(
        taskId: Long,
        currentOperationalDate: LocalDate,
        now: LocalDateTime,
        restartWhenCycleEndedWithoutUpcoming: Boolean,
    ): Boolean {
        val membership = dao.cycleMembershipForTask(taskId) ?: return false
        val group = dao.cycleGroupById(membership.cycleGroupId) ?: return false
        if (group.restartBehavior == CycleRestartBehavior.OFF) return false
        val task = dao.taskById(taskId) ?: return false
        if (!task.isActive || task.archived) return false
        val rule = dao.ruleForTask(taskId) ?: return false

        val disruptedDates = disruptedDatesForTaskCycle(taskId, group)
        val thresholdDays = cycleThresholdDays(group)
        val cycleEnd = rule.endDate ?: cycleEndDate(group)
        val reachedDisruptionThreshold = disruptedDates.size >= thresholdDays
        val cycleEndedWithoutUpcoming = restartWhenCycleEndedWithoutUpcoming &&
            currentOperationalDate.isAfter(cycleEnd) &&
            !hasUpcomingPendingForTaskCycle(taskId, currentOperationalDate)
        if (!reachedDisruptionThreshold && !cycleEndedWithoutUpcoming) return false

        return when (group.restartBehavior) {
            CycleRestartBehavior.OFF -> false
            CycleRestartBehavior.SUGGEST_RESTART -> {
                insertCycleSuggestionIfNeeded(group, disruptedDates.size, thresholdDays, currentOperationalDate, now)
                true
            }
            CycleRestartBehavior.AUTO_RESTART -> {
                autoRestartTaskCycle(
                    task = task,
                    rule = rule,
                    group = group,
                    currentOperationalDate = currentOperationalDate,
                    now = now,
                    note = if (reachedDisruptionThreshold) {
                        "Cycle automatically restarted: ${disruptedDates.size} disrupted days reached $thresholdDays day threshold"
                    } else {
                        "Cycle automatically restarted: prior cycle ended with no upcoming pending occurrences"
                    },
                )
                true
            }
        }
    }

    private fun cycleEndDate(group: CycleGroupEntity): LocalDate {
        return group.currentStartDate.plusDays((group.durationDays.coerceAtLeast(1) - 1).toLong())
    }

    private suspend fun hasUpcomingPendingForTaskCycle(taskId: Long, currentOperationalDate: LocalDate): Boolean {
        return dao.occurrencesForTask(taskId).any {
            it.status == OccurrenceStatus.PENDING && !it.operationalDate.isBefore(currentOperationalDate)
        }
    }

    private suspend fun disruptedDatesForTaskCycle(taskId: Long, group: CycleGroupEntity): Set<LocalDate> {
        val cycleStart = group.currentStartDate
        val cycleEnd = cycleEndDate(group)
        return dao.occurrencesForTask(taskId)
            .filter { occurrence ->
                occurrence.status in DISRUPTED_STATUSES &&
                    !occurrence.operationalDate.isBefore(cycleStart) &&
                    !occurrence.operationalDate.isAfter(cycleEnd)
            }
            .map { it.operationalDate }
            .toSet()
    }

    private suspend fun insertCycleSuggestionIfNeeded(
        group: CycleGroupEntity,
        disruptedDays: Int,
        thresholdDays: Int,
        currentOperationalDate: LocalDate,
        now: LocalDateTime,
    ) {
        val alreadySuggested = dao.cycleLogsForGroup(group.id).any {
            it.operationalDate >= group.currentStartDate &&
                it.note.startsWith("Cycle restart suggested")
        }
        if (alreadySuggested) return
        dao.insertCycleLog(
            CycleLogEntity(
                cycleGroupId = group.id,
                timestamp = now,
                operationalDate = currentOperationalDate,
                note = "Cycle restart suggested for ${group.name}: $disruptedDays disrupted days reached $thresholdDays day threshold",
                createdAt = now,
            ),
        )
    }

    private suspend fun autoRestartTaskCycle(
        task: TaskEntity,
        rule: RecurrenceRuleEntity,
        group: CycleGroupEntity,
        currentOperationalDate: LocalDate,
        now: LocalDateTime,
        note: String,
    ) {
        val restartDate = restartDateFor(group, currentOperationalDate)
        dao.deletePendingOccurrencesForRuleFromDate(task.id, rule.id, restartDate)
        val updatedRule = rule.copy(
            startDate = restartDate,
            endDate = shiftedCycleEndDate(rule, restartDate),
            lastGeneratedDate = null,
            updatedAt = now,
        )
        dao.updateRule(updatedRule)
        val sequenceIds = dao.sequenceForTask(task.id)
            ?.let { sequence -> dao.sequenceItems(sequence.id).map { item -> item.id } }
            .orEmpty()
        generateOccurrences(
            taskId = task.id,
            rule = updatedRule,
            sequenceItemIds = sequenceIds,
            fromDate = restartDate,
            throughDate = restartDate.plusDays((group.durationDays.coerceAtLeast(1) - 1).toLong()),
            sequenceStartIndex = 0,
        )

        dao.updateCycleGroup(
            group.copy(
                currentStartDate = restartDate,
                lastRestartedAt = now,
                updatedAt = now,
            ),
        )
        dao.insertCycleLog(
            CycleLogEntity(
                cycleGroupId = group.id,
                timestamp = now,
                operationalDate = currentOperationalDate,
                note = note,
                createdAt = now,
            ),
        )
        dao.insertLog(
            CompletionLogEntity(
                taskId = task.id,
                occurrenceId = null,
                action = LogAction.EDITED,
                timestamp = now,
                operationalDate = currentOperationalDate,
                note = "Auto restarted cycle timing",
                createdAt = now,
            ),
        )
        realignDependentCycleRules(task.id, currentOperationalDate, now)
    }

    private fun cycleThresholdDays(group: CycleGroupEntity): Int {
        val duration = group.durationDays.coerceAtLeast(1)
        val threshold = group.resetThresholdPercent.coerceIn(1, 100)
        return ((duration * threshold) + 99) / 100
    }

    private fun restartDateFor(group: CycleGroupEntity, currentOperationalDate: LocalDate): LocalDate {
        val baseDate = when (group.restartTiming) {
            CycleRestartTiming.TODAY -> currentOperationalDate
            CycleRestartTiming.TOMORROW -> currentOperationalDate.plusDays(1)
            CycleRestartTiming.NEXT_VALID_DAY -> currentOperationalDate
        }
        return nextCycleRestartDate(baseDate, group.blockedDays)
    }

    private fun nextCycleRestartDate(startDate: LocalDate, blockedDays: Set<java.time.DayOfWeek>): LocalDate {
        var cursor = startDate
        repeat(8) {
            if (cursor.dayOfWeek !in blockedDays) return cursor
            cursor = cursor.plusDays(1)
        }
        return startDate
    }

    private fun shiftedCycleEndDate(rule: RecurrenceRuleEntity, newStartDate: LocalDate): LocalDate? {
        rule.durationDays?.let { return newStartDate.plusDays((it.coerceAtLeast(1) - 1).toLong()) }
        val endDate = rule.endDate ?: return null
        val span = ChronoUnit.DAYS.between(rule.startDate, endDate).coerceAtLeast(0)
        return newStartDate.plusDays(span)
    }

    private suspend fun undoCompletedYesterdayOccurrence(
        occurrence: ScheduledOccurrenceEntity,
        currentOperationalDate: LocalDate,
        now: LocalDateTime,
    ) {
        val restoredDate = occurrence.originalDate ?: return
        val shiftedDays = ChronoUnit.DAYS.between(occurrence.operationalDate, restoredDate)
        val futureToRestore = dao.futureOccurrencesForRule(
            taskId = occurrence.taskId,
            ruleId = occurrence.recurrenceRuleId,
            fromDate = occurrence.operationalDate.plusDays(1),
        )
            .mapNotNull { item ->
                item.originalDate?.let { originalDate ->
                    item.copy(
                        scheduledDate = originalDate,
                        operationalDate = originalDate,
                        isShifted = false,
                        originalDate = null,
                        updatedAt = now,
                    )
                }
            }

        dao.updateOccurrences(futureToRestore.asReversed())
        dao.updateOccurrence(
            occurrence.copy(
                scheduledDate = restoredDate,
                operationalDate = restoredDate,
                status = OccurrenceStatus.PENDING,
                isShifted = false,
                originalDate = null,
                note = occurrence.note.takeUnless { it in ACTION_GENERATED_NOTES }.orEmpty(),
                updatedAt = now,
            ),
        )
        restoreRuleStartDateAfterCompletedYesterdayUndo(
            ruleId = occurrence.recurrenceRuleId,
            completedDate = occurrence.operationalDate,
            restoredDate = restoredDate,
            now = now,
        )
        if (shiftedDays != 0L) {
            shiftDurationWindowBy(
                taskId = occurrence.taskId,
                ruleId = occurrence.recurrenceRuleId,
                dayDelta = shiftedDays,
                currentOperationalDate = currentOperationalDate,
                now = now,
            )
        }
        dao.insertLog(
            CompletionLogEntity(
                taskId = occurrence.taskId,
                occurrenceId = occurrence.id,
                action = LogAction.EDITED,
                timestamp = now,
                operationalDate = occurrence.operationalDate,
                note = "Undid completed-yesterday shift",
                createdAt = now,
            ),
        )
    }

    private suspend fun undoPushedOccurrence(
        occurrence: ScheduledOccurrenceEntity,
        currentOperationalDate: LocalDate,
        now: LocalDateTime,
    ) {
        val replacement = dao.occurrencesForTask(occurrence.taskId)
            .firstOrNull {
                it.id != occurrence.id &&
                    it.recurrenceRuleId == occurrence.recurrenceRuleId &&
                    it.status == OccurrenceStatus.PENDING &&
                    it.originalDate == occurrence.operationalDate
            }
        val shiftedDays = replacement?.let {
            ChronoUnit.DAYS.between(occurrence.operationalDate, it.operationalDate)
        } ?: 0L
        val futureToRestore = replacement
            ?.let {
                dao.futureOccurrencesForRule(
                    taskId = occurrence.taskId,
                    ruleId = occurrence.recurrenceRuleId,
                    fromDate = it.operationalDate.plusDays(1),
                )
            }
            .orEmpty()
            .mapNotNull { item ->
                item.originalDate?.let { originalDate ->
                    item.copy(
                        scheduledDate = originalDate,
                        operationalDate = originalDate,
                        isShifted = false,
                        originalDate = null,
                        updatedAt = now,
                    )
                }
            }

        if (replacement != null) {
            dao.deleteOccurrence(replacement.id)
        }
        dao.updateOccurrences(futureToRestore)
        dao.updateOccurrence(
            occurrence.copy(
                status = OccurrenceStatus.PENDING,
                isShifted = false,
                originalDate = null,
                note = occurrence.note.takeUnless { it in ACTION_GENERATED_NOTES }.orEmpty(),
                updatedAt = now,
            ),
        )
        if (shiftedDays != 0L) {
            shiftDurationWindowBy(
                taskId = occurrence.taskId,
                ruleId = occurrence.recurrenceRuleId,
                dayDelta = -shiftedDays,
                currentOperationalDate = currentOperationalDate,
                now = now,
            )
        }
        dao.insertLog(
            CompletionLogEntity(
                taskId = occurrence.taskId,
                occurrenceId = occurrence.id,
                action = LogAction.EDITED,
                timestamp = now,
                operationalDate = occurrence.operationalDate,
                note = "Undid push",
                createdAt = now,
            ),
        )
    }

    private suspend fun shiftDurationWindowAfterPush(
        taskId: Long,
        ruleId: Long,
        shiftedFrom: LocalDate,
        shiftedTo: LocalDate,
        currentOperationalDate: LocalDate,
        now: LocalDateTime,
    ) {
        val dayDelta = ChronoUnit.DAYS.between(shiftedFrom, shiftedTo)
        if (dayDelta == 0L) return
        shiftDurationWindowBy(taskId, ruleId, dayDelta, currentOperationalDate, now)
    }

    private suspend fun shiftDurationWindowBy(
        taskId: Long,
        ruleId: Long,
        dayDelta: Long,
        currentOperationalDate: LocalDate,
        now: LocalDateTime,
    ) {
        val rule = dao.ruleById(ruleId) ?: return
        val task = dao.taskById(taskId) ?: return
        if (isQuickOneTimeRule(task, rule)) {
            dao.updateRule(
                rule.copy(
                    startDate = rule.startDate.plusDays(dayDelta),
                    endDate = (rule.endDate ?: rule.startDate).plusDays(dayDelta),
                    updatedAt = now,
                ),
            )
            return
        }
        val durationDays = rule.durationDays ?: return
        val currentEndDate = rule.endDate ?: rule.startDate.plusDays((durationDays - 1).toLong())
        dao.updateRule(
            rule.copy(
                endDate = currentEndDate.plusDays(dayDelta),
                updatedAt = now,
            ),
        )
        realignDependentCycleRules(taskId, currentOperationalDate, now)
    }

    private suspend fun moveRuleStartDateBackIfNeeded(
        ruleId: Long,
        completedDate: LocalDate,
        now: LocalDateTime,
    ) {
        val rule = dao.ruleById(ruleId) ?: return
        if (!completedDate.isBefore(rule.startDate)) return
        dao.updateRule(
            rule.copy(
                startDate = completedDate,
                updatedAt = now,
            ),
        )
    }

    private suspend fun restoreRuleStartDateAfterCompletedYesterdayUndo(
        ruleId: Long,
        completedDate: LocalDate,
        restoredDate: LocalDate,
        now: LocalDateTime,
    ) {
        val rule = dao.ruleById(ruleId) ?: return
        if (rule.startDate != completedDate || !restoredDate.isAfter(completedDate)) return
        dao.updateRule(
            rule.copy(
                startDate = restoredDate,
                updatedAt = now,
            ),
        )
    }

    private suspend fun realignDependentCycleRules(
        parentTaskId: Long,
        currentOperationalDate: LocalDate,
        now: LocalDateTime,
    ) {
        dao.rulesStartingAfterTask(parentTaskId).forEach { dependentRule ->
            val resolvedRule = resolveCycleRuleWindow(dependentRule).copy(updatedAt = now)
            val windowChanged = dependentRule.startDate != resolvedRule.startDate ||
                dependentRule.endDate != resolvedRule.endDate
            if (windowChanged) {
                dao.updateRule(resolvedRule)
                regenerateFuturePendingForRule(resolvedRule, currentOperationalDate)
            }
            realignDependentCycleRules(dependentRule.taskId, currentOperationalDate, now)
        }
    }

    private suspend fun regenerateFuturePendingForRule(
        rule: RecurrenceRuleEntity,
        currentOperationalDate: LocalDate,
    ) {
        val task = dao.taskById(rule.taskId) ?: return
        if (!task.isActive || task.archived) return
        dao.deleteFuturePendingOccurrences(task.id, rule.id, currentOperationalDate)
        val sequenceIds = dao.sequenceForTask(task.id)
            ?.let { dao.sequenceItems(it.id).map { item -> item.id } }
            .orEmpty()
        val sequenceStartIndex = if (rule.ruleType == RuleType.SEQUENCE && sequenceIds.isNotEmpty()) {
            val previousSequenceItemId = dao.latestOccurrenceBefore(task.id, rule.id, currentOperationalDate.plusDays(1))?.sequenceItemId
            nextSequenceStartIndex(sequenceIds, previousSequenceItemId)
        } else {
            0
        }
        val fromDate = maxOf(rule.startDate, currentOperationalDate.plusDays(1))
        val throughDate = rule.endDate?.let { minOf(it, currentOperationalDate.plusDays(60)) }
            ?: currentOperationalDate.plusDays(60)
        if (!fromDate.isAfter(throughDate)) {
            generateOccurrences(
                taskId = task.id,
                rule = rule,
                sequenceItemIds = sequenceIds,
                fromDate = fromDate,
                throughDate = throughDate,
                sequenceStartIndex = sequenceStartIndex,
            )
        }
    }

    private suspend fun resolveCycleRuleWindow(rule: RecurrenceRuleEntity): RecurrenceRuleEntity {
        val linkedStartDate = rule.startsAfterTaskId
            ?.let { dao.ruleForTask(it) }
            ?.endDate
            ?.plusDays(1)
        val resolvedStartDate = linkedStartDate ?: rule.startDate
        val resolvedEndDate = rule.durationDays
            ?.coerceAtLeast(1)
            ?.let { resolvedStartDate.plusDays((it - 1).toLong()) }
            ?: rule.endDate?.let { maxOf(it, resolvedStartDate) }
        return rule.copy(
            startDate = resolvedStartDate,
            endDate = resolvedEndDate,
            durationDays = rule.durationDays?.coerceAtLeast(1),
        )
    }

    private suspend fun replaceSequenceItems(
        task: TaskEntity,
        sequenceItems: List<SequenceItemEntity>,
        sequenceExercisesByPosition: Map<Int, List<SequenceExerciseEntity>>,
    ): List<Long> {
        if (sequenceItems.isEmpty()) {
            deleteUnreferencedSequencesForTask(task.id)
            return emptyList()
        }
        val now = LocalDateTime.now()
        val sequenceId = dao.insertSequence(
            WorkoutSequenceEntity(
                taskId = task.id,
                name = task.name,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val insertedItemIds = dao.insertSequenceItems(sequenceItems.map { it.copy(sequenceId = sequenceId) })
        insertSequenceExercises(sequenceItems, insertedItemIds, sequenceExercisesByPosition)
        return insertedItemIds
    }

    private suspend fun insertSequenceExercises(
        sequenceItems: List<SequenceItemEntity>,
        insertedSequenceItemIds: List<Long>,
        sequenceExercisesByPosition: Map<Int, List<SequenceExerciseEntity>>,
    ) {
        if (sequenceExercisesByPosition.isEmpty()) return
        val itemIdByPosition = sequenceItems.zip(insertedSequenceItemIds)
            .associate { (item, itemId) -> item.position to itemId }
        val exercises = sequenceExercisesByPosition
            .toSortedMap()
            .flatMap { (itemPosition, itemExercises) ->
                val sequenceItemId = itemIdByPosition[itemPosition] ?: return@flatMap emptyList()
                itemExercises.sortedBy { it.position }.map { exercise ->
                    exercise.copy(id = 0, sequenceItemId = sequenceItemId)
                }
            }
        if (exercises.isNotEmpty()) dao.insertSequenceExercises(exercises)
    }

    private suspend fun upsertCycleAssignment(
        taskId: Long,
        rule: RecurrenceRuleEntity,
        cycleGroup: CycleGroupEntity?,
        cycleMembership: CycleTaskMembershipEntity?,
        now: LocalDateTime,
    ) {
        if (cycleGroup == null) {
            dao.deleteCycleMembershipForTask(taskId)
            return
        }
        val normalizedGroup = cycleGroup.copy(
            durationDays = cycleGroup.durationDays.coerceAtLeast(1),
            resetThresholdPercent = cycleGroup.resetThresholdPercent.coerceIn(1, 100),
            updatedAt = now,
        )
        val cycleGroupId = if (normalizedGroup.id == 0L || dao.cycleGroupById(normalizedGroup.id) == null) {
            dao.insertCycleGroup(normalizedGroup.copy(id = 0, createdAt = now, updatedAt = now))
        } else {
            dao.updateCycleGroup(normalizedGroup)
            normalizedGroup.id
        }
        val existingMembership = dao.cycleMembershipForTask(taskId)
        val offsetDays = cycleMembership?.startOffsetDays
            ?: ChronoUnit.DAYS.between(normalizedGroup.currentStartDate, rule.startDate)
                .toInt()
                .coerceAtLeast(0)
        val membershipToSave = CycleTaskMembershipEntity(
            id = existingMembership?.id ?: cycleMembership?.id?.takeIf { it != 0L } ?: 0,
            cycleGroupId = cycleGroupId,
            taskId = taskId,
            startOffsetDays = offsetDays,
            createdAt = existingMembership?.createdAt ?: cycleMembership?.createdAt ?: now,
            updatedAt = now,
        )
        if (membershipToSave.id == 0L) {
            dao.insertCycleMembership(membershipToSave)
        } else {
            dao.updateCycleMembership(membershipToSave)
        }
    }

    private suspend fun hasScheduleChanged(
        existingTask: TaskEntity?,
        newTask: TaskEntity,
        existingRule: RecurrenceRuleEntity?,
        newRule: RecurrenceRuleEntity,
        newSequenceItems: List<SequenceItemEntity>,
        newSequenceExercisesByPosition: Map<Int, List<SequenceExerciseEntity>>,
    ): Boolean {
        if (existingTask == null || existingRule == null) return true
        if (existingTask.taskType != newTask.taskType) return true
        if (existingTask.blockedDays != newTask.blockedDays) return true
        if (!existingRule.sameScheduleDefinitionAs(newRule)) return true

        val existingSequenceNames = if (existingRule.ruleType == RuleType.SEQUENCE || newRule.ruleType == RuleType.SEQUENCE) {
            dao.sequenceForTask(existingTask.id)
                ?.let { sequence -> dao.sequenceItems(sequence.id) }
                .orEmpty()
                .sortedBy { it.position }
                .map { it.name }
        } else {
            emptyList()
        }
        val newSequenceNames = if (newRule.ruleType == RuleType.SEQUENCE) {
            newSequenceItems.sortedBy { it.position }.map { it.name }
        } else {
            emptyList()
        }
        if (existingSequenceNames != newSequenceNames) return true
        if (newRule.ruleType != RuleType.SEQUENCE) return false

        val currentSequence = dao.sequenceForTask(existingTask.id) ?: return newSequenceExercisesByPosition.isNotEmpty()
        val currentItems = dao.sequenceItems(currentSequence.id)
        val currentExercises = currentItems.associate { item ->
            item.position to dao.sequenceExercises(item.id)
                .sortedBy { it.position }
                .map { it.scheduleDefinition() }
        }.filterValues { it.isNotEmpty() }
        val newExercises = newSequenceExercisesByPosition
            .mapValues { (_, exercises) -> exercises.sortedBy { it.position }.map { it.scheduleDefinition() } }
            .filterValues { it.isNotEmpty() }
        return currentExercises != newExercises
    }

    private fun RecurrenceRuleEntity.sameScheduleDefinitionAs(other: RecurrenceRuleEntity): Boolean {
        return ruleType == other.ruleType &&
            intervalDays == other.intervalDays &&
            weekdays == other.weekdays &&
            startDate == other.startDate &&
            endDate == other.endDate &&
            durationDays == other.durationDays &&
            startsAfterTaskId == other.startsAfterTaskId &&
            cycleDefinition == other.cycleDefinition &&
            skipBlockedDaysBehavior == other.skipBlockedDaysBehavior
    }

    private fun RecurrenceRuleEntity.longTermRecurrenceAnchor(): LongTermRecurrenceAnchor {
        if (cycleDefinition.isBlank()) return LongTermRecurrenceAnchor.COMPLETION_DATE
        return runCatching { LongTermRecurrenceAnchor.valueOf(cycleDefinition) }
            .getOrDefault(LongTermRecurrenceAnchor.COMPLETION_DATE)
    }

    private suspend fun deleteUnreferencedSequencesForTask(taskId: Long): Int {
        val sequences = dao.allSequences().filter { it.taskId == taskId }
        if (sequences.isEmpty()) return 0
        val sequenceItemsBySequenceId = dao.allSequenceItems()
            .filter { item -> sequences.any { it.id == item.sequenceId } }
            .groupBy { it.sequenceId }
        val referencedItemIds = dao.occurrencesForTask(taskId)
            .mapNotNull { it.sequenceItemId }
            .toSet()
        val checkedExerciseIds = dao.allOccurrenceExerciseChecks()
            .map { it.sequenceExerciseId }
            .toSet()
        val checkedItemIds = dao.allSequenceExercises()
            .filter { it.id in checkedExerciseIds }
            .map { it.sequenceItemId }
            .toSet()
        var deleted = 0
        sequences.forEach { sequence ->
            val itemIds = sequenceItemsBySequenceId[sequence.id].orEmpty().map { it.id }
            if (itemIds.none { it in referencedItemIds || it in checkedItemIds }) {
                deleted += dao.deleteSequenceById(sequence.id)
            }
        }
        return deleted
    }

    private fun validateTaskRuleAndSequence(
        task: TaskEntity,
        rule: RecurrenceRuleEntity,
        sequenceItems: List<SequenceItemEntity>,
        sequenceExercisesByPosition: Map<Int, List<SequenceExerciseEntity>> = emptyMap(),
    ) {
        require(ruleTypeMatchesTaskType(task.taskType, rule.ruleType)) {
            "Recurrence rule type ${rule.ruleType} does not match task type ${task.taskType}"
        }
        require(rule.endDate == null || !rule.endDate.isBefore(rule.startDate)) {
            "Recurrence end date cannot be before start date"
        }
        require(rule.durationDays == null || rule.durationDays >= 1) {
            "Duration must be at least one day"
        }
        require(rule.startsAfterTaskId == null || rule.startsAfterTaskId != task.id) {
            "A task cannot start after itself"
        }
        when (rule.ruleType) {
            RuleType.EVERY_X_DAYS -> {
                val minimumInterval = if (task.taskType == TaskType.LONG_TERM) 1 else 2
                require((rule.intervalDays ?: 0) >= minimumInterval) {
                    "Interval recurrence requires intervalDays >= $minimumInterval"
                }
            }
            RuleType.EVERY_X_WEEKS,
            RuleType.EVERY_X_MONTHS -> require((rule.intervalDays ?: 0) >= 1) {
                "Long-term recurrence requires intervalDays >= 1"
            }
            RuleType.EVERY_X_YEARS -> require((rule.intervalDays ?: 0) >= 1) {
                "Long-term recurrence requires intervalDays >= 1"
            }
            RuleType.WEEKDAYS -> require(rule.weekdays.isNotEmpty()) {
                "Weekday recurrence requires at least one weekday"
            }
            RuleType.SEQUENCE -> require(sequenceItems.isNotEmpty()) {
                "Sequence routines require at least one sequence item"
            }
            RuleType.DAILY -> Unit
        }
        require(task.taskType == TaskType.SEQUENCE_ROUTINE || sequenceItems.isEmpty()) {
            "Only sequence tasks can have sequence items"
        }
        val sequencePositions = sequenceItems.map { it.position }.toSet()
        require(sequenceExercisesByPosition.keys.all { it in sequencePositions }) {
            "Exercises must belong to a sequence item"
        }
        require(
            sequenceExercisesByPosition.values.flatten().all { exercise ->
                exercise.name.isNotBlank() && exercise.position >= 0
            },
        ) {
            "Exercises require a name and valid position"
        }
        require(
            sequenceExercisesByPosition.values.none { exercises ->
                exercises.groupBy { it.position }.any { it.value.size > 1 }
            },
        ) {
            "Exercise positions must be unique within a workout day"
        }
    }

    private fun SequenceExerciseEntity.scheduleDefinition(): ExerciseScheduleDefinition {
        return ExerciseScheduleDefinition(
            position = position,
            name = name,
            prescription = prescription,
            instructions = instructions,
            requirement = requirement,
        )
    }

    private fun ruleTypeMatchesTaskType(taskType: TaskType, ruleType: RuleType): Boolean {
        return when (taskType) {
            TaskType.SIMPLE_HABIT -> ruleType == RuleType.DAILY
            TaskType.QUICK_ONE_TIME -> ruleType == RuleType.DAILY
            TaskType.INTERVAL -> ruleType == RuleType.EVERY_X_DAYS
            TaskType.WEEKDAY_BASED -> ruleType == RuleType.WEEKDAYS
            TaskType.SEQUENCE_ROUTINE -> ruleType == RuleType.SEQUENCE
            TaskType.LONG_TERM -> ruleType in LONG_TERM_RULE_TYPES
        }
    }

    private fun isQuickOneTimeRule(task: TaskEntity, rule: RecurrenceRuleEntity): Boolean {
        return task.taskType == TaskType.QUICK_ONE_TIME ||
            (
                task.taskType == TaskType.SIMPLE_HABIT &&
                    task.pushable &&
                    task.noActionBehavior == NoActionBehavior.AUTO_PUSH &&
                    rule.ruleType == RuleType.DAILY &&
                    rule.durationDays == 1 &&
                    rule.endDate != null
            )
    }

    suspend fun statsForTask(taskId: Long, currentOperationalDate: LocalDate): TaskStats {
        return statsCalculator.calculate(dao.occurrencesForTask(taskId), currentOperationalDate)
    }

    private suspend fun updateOccurrenceStatus(
        occurrenceId: Long,
        status: OccurrenceStatus,
        action: LogAction,
        note: String,
        currentOperationalDate: LocalDate,
    ) = database.withTransaction {
        val occurrence = dao.occurrenceById(occurrenceId) ?: return@withTransaction
        val task = dao.taskById(occurrence.taskId) ?: return@withTransaction
        if (task.taskType == TaskType.LONG_TERM && status != OccurrenceStatus.COMPLETED) return@withTransaction
        if (!task.isActive || task.archived) return@withTransaction
        if (occurrence.status != OccurrenceStatus.PENDING) return@withTransaction
        val completionAnchoredLongTermRule = if (
            task.taskType == TaskType.LONG_TERM &&
            status == OccurrenceStatus.COMPLETED
        ) {
            dao.ruleById(occurrence.recurrenceRuleId)
                ?.takeIf { it.longTermRecurrenceAnchor() == LongTermRecurrenceAnchor.COMPLETION_DATE }
        } else {
            null
        }
        val now = LocalDateTime.now()
        dao.updateOccurrence(
            occurrence.copy(
                status = status,
                note = occurrence.note.ifBlank { note },
                originalDate = completionAnchoredLongTermRule?.let { occurrence.originalDate ?: it.startDate }
                    ?: occurrence.originalDate,
                updatedAt = now,
            ),
        )
        if (completionAnchoredLongTermRule != null) {
            realignLongTermFutureAfterCompletion(
                task = task,
                rule = completionAnchoredLongTermRule,
                completedOccurrenceDate = occurrence.operationalDate,
                completionDate = currentOperationalDate,
                now = now,
            )
        }
        dao.insertLog(
            CompletionLogEntity(
                taskId = occurrence.taskId,
                occurrenceId = occurrence.id,
                action = action,
                timestamp = now,
                operationalDate = occurrence.operationalDate,
                note = note,
                createdAt = now,
            ),
        )
        handleCycleRestartForTask(occurrence.taskId, currentOperationalDate, now)
    }

    private suspend fun realignLongTermFutureAfterCompletion(
        task: TaskEntity,
        rule: RecurrenceRuleEntity,
        completedOccurrenceDate: LocalDate,
        completionDate: LocalDate,
        now: LocalDateTime,
    ) {
        dao.deleteFuturePendingOccurrences(task.id, rule.id, completedOccurrenceDate)
        val anchoredRule = rule.copy(
            startDate = completionDate,
            lastGeneratedDate = null,
            updatedAt = now,
        )
        generateFuturePendingForRule(
            task = task,
            rule = anchoredRule,
            fromDate = completionDate.plusDays(1),
            throughDate = completionDate.plusDays(generationWindowDays(task)),
            now = now,
        )
    }

    private suspend fun undoCompletionAnchoredLongTermOccurrence(
        task: TaskEntity,
        rule: RecurrenceRuleEntity,
        occurrence: ScheduledOccurrenceEntity,
        currentOperationalDate: LocalDate,
        now: LocalDateTime,
    ) {
        val restoredStartDate = occurrence.originalDate ?: return
        dao.deleteFuturePendingOccurrences(task.id, rule.id, occurrence.operationalDate)
        dao.updateOccurrence(
            occurrence.copy(
                status = OccurrenceStatus.PENDING,
                originalDate = null,
                note = occurrence.note.takeUnless { it in ACTION_GENERATED_NOTES }.orEmpty(),
                updatedAt = now,
            ),
        )
        val restoredRule = rule.copy(
            startDate = restoredStartDate,
            lastGeneratedDate = null,
            updatedAt = now,
        )
        generateFuturePendingForRule(
            task = task,
            rule = restoredRule,
            fromDate = maxOf(restoredStartDate, occurrence.operationalDate.plusDays(1)),
            throughDate = currentOperationalDate.plusDays(generationWindowDays(task)),
            now = now,
        )
        dao.insertLog(
            CompletionLogEntity(
                taskId = occurrence.taskId,
                occurrenceId = occurrence.id,
                action = LogAction.EDITED,
                timestamp = now,
                operationalDate = occurrence.operationalDate,
                note = "Undid long-term completion",
                createdAt = now,
            ),
        )
    }

    private suspend fun generateFuturePendingForRule(
        task: TaskEntity,
        rule: RecurrenceRuleEntity,
        fromDate: LocalDate,
        throughDate: LocalDate,
        now: LocalDateTime,
    ) {
        val boundedThroughDate = rule.endDate?.let { minOf(it, throughDate) } ?: throughDate
        if (fromDate.isAfter(boundedThroughDate)) {
            dao.updateRule(rule.copy(lastGeneratedDate = boundedThroughDate, updatedAt = now))
            return
        }
        generateOccurrences(
            taskId = task.id,
            rule = rule,
            sequenceItemIds = emptyList(),
            fromDate = fromDate,
            throughDate = boundedThroughDate,
        )
    }

    private suspend fun generateOccurrences(
        taskId: Long,
        rule: RecurrenceRuleEntity,
        sequenceItemIds: List<Long>,
        fromDate: LocalDate,
        throughDate: LocalDate,
        sequenceStartIndex: Int = 0,
    ): Int {
        val task = dao.taskById(taskId) ?: return 0
        val request = generationRequest(task, rule, sequenceItemIds, sequenceStartIndex)
        val existingOperationalDates = dao.occurrencesForTask(taskId)
            .asSequence()
            .filter { it.recurrenceRuleId == rule.id }
            .map { it.operationalDate }
            .toSet()
        val occurrences = generator
            .generate(request, fromDate, throughDate)
            .filter { it.operationalDate !in existingOperationalDates }
        if (occurrences.isNotEmpty()) {
            dao.insertOccurrences(occurrences)
        }
        dao.updateRule(rule.copy(lastGeneratedDate = throughDate, updatedAt = LocalDateTime.now()))
        return occurrences.size
    }

    private suspend fun repairPendingCadenceForRule(
        task: TaskEntity,
        rule: RecurrenceRuleEntity,
        currentOperationalDate: LocalDate,
        now: LocalDateTime,
    ): Int {
        if (!task.isActive || task.archived) return 0
        var repaired = repairMissingPendingOccurrencesForRule(task, rule, currentOperationalDate, now)
        val spacingDays = scheduleSpacingDays(task, rule)
        if (spacingDays <= 1) return repaired
        repaired += repairMissingPendingRowsAfterLatestNonPending(task, rule, currentOperationalDate, spacingDays, now)
        val pending = dao.futureOccurrencesForRule(
            taskId = task.id,
            ruleId = rule.id,
            fromDate = currentOperationalDate,
        )
        if (pending.size <= 1) return repaired

        val shiftedCadenceAnchorDate = pending
            .firstOrNull { it.isShifted || it.originalDate != null }
            ?.operationalDate
        val latestNonPendingBeforeShiftedAnchor = shiftedCadenceAnchorDate
            ?.let { anchorDate ->
                dao.occurrencesForTask(task.id)
                    .asSequence()
                    .filter { it.recurrenceRuleId == rule.id }
                    .filter { it.status != OccurrenceStatus.PENDING }
                    .filter { it.operationalDate.isBefore(anchorDate) }
                    .maxByOrNull { it.operationalDate }
                    ?.operationalDate
            }
        val staleBeforeShiftedCadenceIds = shiftedCadenceAnchorDate
            ?.let { anchorDate ->
                val afterDate = latestNonPendingBeforeShiftedAnchor ?: return@let emptyList()
                pending
                    .filter {
                        it.operationalDate.isAfter(afterDate) &&
                            it.operationalDate.isBefore(anchorDate) &&
                            !it.isShifted &&
                            it.originalDate == null
                    }
                    .map { it.id }
            }
            .orEmpty()
        val pendingDates = pending.map { it.operationalDate }.toSet()
        val staleReplacementIds = pending
            .filter { item ->
                item.originalDate?.let { originalDate ->
                    originalDate in pendingDates &&
                        !originalDate.isAfter(item.operationalDate)
                } == true
            }
            .map { it.id }
            .toMutableSet()
        staleReplacementIds.addAll(staleBeforeShiftedCadenceIds)
        val candidates = pending
            .filter { it.id !in staleReplacementIds }
            .sortedWith(compareBy<ScheduledOccurrenceEntity> { it.operationalDate }.thenBy { it.id })
        if (candidates.size <= 1) {
            return repaired + deletePendingRepairRows(staleReplacementIds)
        }

        val occupiedDates = dao.occurrencesForTask(task.id)
            .asSequence()
            .filter { it.recurrenceRuleId == rule.id }
            .filter { it.status != OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .toMutableSet()
        val candidatesByDate = candidates.associateBy { it.operationalDate }
        val consumedIds = mutableSetOf<Long>()
        val deleteIds = staleReplacementIds.toMutableSet()
        val updates = mutableListOf<ScheduledOccurrenceEntity>()
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
                updates.add(
                    item.copy(
                        scheduledDate = expectedDate,
                        operationalDate = expectedDate,
                        isShifted = true,
                        originalDate = item.originalDate ?: item.operationalDate,
                        updatedAt = now,
                    ),
                )
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
        val deleted = deletePendingRepairRows(deleteIds)
        if (updates.isNotEmpty()) {
            dao.updateOccurrences(updates)
        }
        repaired += deleted + updates.size
        return repaired
    }

    private suspend fun repairMissingPendingRowsAfterLatestNonPending(
        task: TaskEntity,
        rule: RecurrenceRuleEntity,
        currentOperationalDate: LocalDate,
        spacingDays: Int,
        now: LocalDateTime,
    ): Int {
        val allForRule = dao.occurrencesForTask(task.id)
            .filter { it.recurrenceRuleId == rule.id }
        val pending = allForRule
            .filter { it.status == OccurrenceStatus.PENDING }
            .filter { !it.operationalDate.isBefore(currentOperationalDate) }
            .sortedWith(compareBy<ScheduledOccurrenceEntity> { it.operationalDate }.thenBy { it.id })
        if (pending.isEmpty()) return 0

        val firstPendingDate = pending.first().operationalDate
        val latestNonPending = allForRule
            .filter { it.status != OccurrenceStatus.PENDING }
            .filter { it.operationalDate.isBefore(firstPendingDate) }
            .maxWithOrNull(compareBy<ScheduledOccurrenceEntity> { it.operationalDate }.thenBy { it.id })
            ?: return 0
        val sequenceIds = if (rule.ruleType == RuleType.SEQUENCE) {
            dao.sequenceForTask(task.id)
                ?.let { dao.sequenceItems(it.id).map { item -> item.id } }
                .orEmpty()
        } else {
            emptyList()
        }
        var sequenceIndex = if (sequenceIds.isNotEmpty()) {
            nextSequenceStartIndex(sequenceIds, latestNonPending.sequenceItemId)
        } else {
            0
        }

        val occupiedDates = allForRule
            .map { it.operationalDate }
            .toMutableSet()
        val insertions = mutableListOf<ScheduledOccurrenceEntity>()
        var expectedDate = nextAvailableValidDate(
            startDate = latestNonPending.operationalDate.plusDays(spacingDays.toLong()),
            blockedDays = task.blockedDays,
            occupiedDates = allForRule
                .filter { it.status != OccurrenceStatus.PENDING }
                .map { it.operationalDate }
                .toSet(),
        ) ?: return 0

        while (true) {
            val nextPendingDate = pending
                .firstOrNull { !it.operationalDate.isBefore(expectedDate) }
                ?.operationalDate
                ?: break
            if (!expectedDate.isBefore(nextPendingDate)) break
            val withinEndDate = rule.endDate == null || !expectedDate.isAfter(rule.endDate)
            if (
                withinEndDate &&
                !expectedDate.isBefore(currentOperationalDate) &&
                expectedDate !in occupiedDates
            ) {
                insertions.add(
                    ScheduledOccurrenceEntity(
                        taskId = task.id,
                        recurrenceRuleId = rule.id,
                        scheduledDate = expectedDate,
                        operationalDate = expectedDate,
                        sequenceItemId = sequenceIds.getOrNull(sequenceIndex % sequenceIds.size.coerceAtLeast(1)),
                        isShifted = true,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                occupiedDates.add(expectedDate)
            }
            if (sequenceIds.isNotEmpty()) {
                sequenceIndex += 1
            }
            expectedDate = nextAvailableValidDate(
                startDate = expectedDate.plusDays(spacingDays.toLong()),
                blockedDays = task.blockedDays,
                occupiedDates = occupiedDates,
            ) ?: break
        }
        if (insertions.isEmpty()) return 0

        dao.insertOccurrences(insertions)
        return insertions.size
    }

    private suspend fun repairMissingPendingOccurrencesForRule(
        task: TaskEntity,
        rule: RecurrenceRuleEntity,
        currentOperationalDate: LocalDate,
        now: LocalDateTime,
    ): Int {
        val generatedThrough = rule.lastGeneratedDate ?: currentOperationalDate.plusDays(generationWindowDays(task))
        val throughWindow = minOf(generatedThrough, currentOperationalDate.plusDays(generationWindowDays(task)))
        val throughDate = rule.endDate?.let { minOf(it, throughWindow) } ?: throughWindow
        val shiftedCadenceAnchor = dao.futureOccurrencesForRule(
            taskId = task.id,
            ruleId = rule.id,
            fromDate = currentOperationalDate,
        ).firstOrNull { it.isShifted || it.originalDate != null }?.operationalDate
        val fromDate = maxOf(rule.startDate, currentOperationalDate, shiftedCadenceAnchor ?: currentOperationalDate)
        if (fromDate.isAfter(throughDate)) return 0

        val sequenceIds = dao.sequenceForTask(task.id)
            ?.let { dao.sequenceItems(it.id).map { item -> item.id } }
            .orEmpty()
        val sequenceStartIndex = if (rule.ruleType == RuleType.SEQUENCE && sequenceIds.isNotEmpty()) {
            val previousSequenceItemId = dao.latestOccurrenceBefore(task.id, rule.id, fromDate)?.sequenceItemId
            nextSequenceStartIndex(sequenceIds, previousSequenceItemId)
        } else {
            0
        }
        val existingCoveredDates = dao.occurrencesForTask(task.id)
            .asSequence()
            .filter { it.recurrenceRuleId == rule.id }
            .flatMap { occurrence ->
                sequenceOf(occurrence.operationalDate, occurrence.originalDate).filterNotNull()
            }
            .toSet()
        val request = generationRequest(task, rule, sequenceIds, sequenceStartIndex)
        val missingOccurrences = generator
            .generate(request, fromDate, throughDate, now)
            .filter { it.operationalDate !in existingCoveredDates }
        if (missingOccurrences.isEmpty()) return 0

        dao.insertOccurrences(missingOccurrences)
        return missingOccurrences.size
    }

    private fun generationRequest(
        task: TaskEntity,
        rule: RecurrenceRuleEntity,
        sequenceItemIds: List<Long>,
        sequenceStartIndex: Int,
    ) = GenerationRequest(
        taskId = task.id,
        recurrenceRuleId = rule.id,
        taskType = task.taskType,
        ruleType = rule.ruleType,
        intervalDays = rule.intervalDays,
        weekdays = rule.weekdays,
        blockedDays = task.blockedDays,
        startDate = rule.startDate,
        endDate = rule.endDate,
        skipBlockedDaysBehavior = rule.skipBlockedDaysBehavior,
        sequenceItems = sequenceItemIds,
        sequenceStartIndex = sequenceStartIndex,
    )

    private suspend fun deletePendingRepairRows(ids: Set<Long>): Int {
        return if (ids.isEmpty()) 0 else dao.deleteOccurrencesByIds(ids.toList())
    }

    private suspend fun copyExerciseChecks(
        sourceOccurrenceId: Long,
        targetOccurrenceId: Long,
        now: LocalDateTime,
    ) {
        val copiedChecks = dao.exerciseChecksForOccurrence(sourceOccurrenceId).map { check ->
            check.copy(
                id = 0,
                occurrenceId = targetOccurrenceId,
                updatedAt = now,
            )
        }
        copiedChecks.forEach { dao.insertOccurrenceExerciseCheck(it) }
    }

    private fun nextSequenceStartIndex(sequenceItemIds: List<Long>, previousSequenceItemId: Long?): Int {
        val previousIndex = sequenceItemIds.indexOf(previousSequenceItemId)
        return if (previousIndex == -1) 0 else (previousIndex + 1) % sequenceItemIds.size
    }

    private fun scheduleSpacingDays(task: TaskEntity, rule: RecurrenceRuleEntity?): Int {
        return when (task.taskType) {
            TaskType.SEQUENCE_ROUTINE -> (rule?.intervalDays ?: 1).coerceAtLeast(1)
            TaskType.INTERVAL -> (rule?.intervalDays ?: 2).coerceAtLeast(2)
            TaskType.QUICK_ONE_TIME,
            TaskType.LONG_TERM,
            TaskType.SIMPLE_HABIT,
            TaskType.WEEKDAY_BASED,
            -> 1
        }
    }

    private fun generationWindowDays(task: TaskEntity, requestedDaysAhead: Long = DEFAULT_GENERATION_WINDOW_DAYS): Long {
        return if (task.taskType == TaskType.LONG_TERM) {
            maxOf(requestedDaysAhead, LONG_TERM_GENERATION_WINDOW_DAYS)
        } else {
            requestedDaysAhead
        }
    }

    private fun nextAvailableValidDate(
        startDate: LocalDate,
        blockedDays: Set<java.time.DayOfWeek>,
        occupiedDates: Set<LocalDate>,
    ): LocalDate? {
        var cursor = startDate
        repeat(MAX_SHIFT_DATE_SEARCH_DAYS) {
            val validDate = generator.nextValidDate(cursor, blockedDays) ?: return null
            if (validDate !in occupiedDates) return validDate
            cursor = validDate.plusDays(1)
        }
        return null
    }

    private suspend fun nextSequenceStartIndexByPosition(
        newSequenceSize: Int,
        previousSequenceItemId: Long?,
    ): Int {
        if (newSequenceSize == 0 || previousSequenceItemId == null) return 0
        val previousPosition = dao.allSequenceItems().firstOrNull { it.id == previousSequenceItemId }?.position ?: return 0
        return (previousPosition + 1) % newSequenceSize
    }

    private data class ExerciseScheduleDefinition(
        val position: Int,
        val name: String,
        val prescription: String,
        val instructions: String,
        val requirement: ExerciseRequirement,
    )

    private companion object {
        const val DEFAULT_GENERATION_WINDOW_DAYS = 60L
        const val LONG_TERM_GENERATION_WINDOW_DAYS = 3655L
        const val MAX_SHIFT_DATE_SEARCH_DAYS = 366
        val LONG_TERM_RULE_TYPES = setOf(
            RuleType.EVERY_X_DAYS,
            RuleType.EVERY_X_WEEKS,
            RuleType.EVERY_X_MONTHS,
            RuleType.EVERY_X_YEARS,
        )
        val DISRUPTED_STATUSES = setOf(
            OccurrenceStatus.SKIPPED,
            OccurrenceStatus.MISSED,
            OccurrenceStatus.SHIFTED,
        )
        val ACTION_GENERATED_NOTES = setOf(
            "Marked complete",
            "Completed yesterday",
            "Skipped intentionally",
            "Pushed forward",
            "Marked missed after day reset",
            "Auto-skipped after day reset",
        )
    }
}
