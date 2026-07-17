package com.example.habittracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.habittracker.data.local.CompletionLogEntity
import com.example.habittracker.data.local.CycleGroupEntity
import com.example.habittracker.data.local.CycleTaskMembershipEntity
import com.example.habittracker.data.local.HabitDatabase
import com.example.habittracker.data.local.RecurrenceRuleEntity
import com.example.habittracker.data.local.RoutinePhaseEntity
import com.example.habittracker.data.local.RoutinePlanEntity
import com.example.habittracker.data.local.ScheduledOccurrenceEntity
import com.example.habittracker.data.local.SequenceExerciseEntity
import com.example.habittracker.data.local.SequenceItemEntity
import com.example.habittracker.data.local.TaskEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HabitRepositoryTest {
    private lateinit var database: HabitDatabase
    private lateinit var repository: HabitRepository

    private val dao get() = database.habitDao()
    private val now = LocalDateTime.of(2026, 5, 20, 12, 0)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HabitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = HabitRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun ruleEditDeletesFuturePendingOnlyAndRegeneratesWithoutDuplicatingPreservedDates() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Cardio"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 25),
        )
        val beforeEdit = dao.occurrencesForTask(taskId).sortedBy { it.operationalDate }
        val completed = beforeEdit.first { it.operationalDate == LocalDate.of(2026, 5, 20) }
        val skipped = beforeEdit.first { it.operationalDate == LocalDate.of(2026, 5, 21) }
        val currentPending = beforeEdit.first { it.operationalDate == LocalDate.of(2026, 5, 22) }
        val missed = beforeEdit.first { it.operationalDate == LocalDate.of(2026, 5, 23) }
        val shifted = beforeEdit.first { it.operationalDate == LocalDate.of(2026, 5, 24) }
        val futurePendingIds = beforeEdit
            .filter { it.scheduledDate.isAfter(LocalDate.of(2026, 5, 22)) }
            .filter { it.id !in setOf(missed.id, shifted.id) }
            .map { it.id }
            .toSet()
        dao.updateOccurrence(completed.copy(status = OccurrenceStatus.COMPLETED))
        dao.updateOccurrence(skipped.copy(status = OccurrenceStatus.SKIPPED))
        dao.updateOccurrence(missed.copy(status = OccurrenceStatus.MISSED))
        dao.updateOccurrence(shifted.copy(status = OccurrenceStatus.SHIFTED, isShifted = true))

        repository.editRuleAndRegenerate(
            task = dao.taskById(taskId)!!.copy(name = "Cardio updated", taskType = TaskType.INTERVAL),
            rule = dao.ruleForTask(taskId)!!.copy(ruleType = RuleType.EVERY_X_DAYS, intervalDays = 2),
            currentOperationalDate = LocalDate.of(2026, 5, 22),
            generateThrough = LocalDate.of(2026, 5, 28),
        )

        val afterEdit = dao.occurrencesForTask(taskId)
        assertTrue(afterEdit.any { it.id == completed.id && it.status == OccurrenceStatus.COMPLETED })
        assertTrue(afterEdit.any { it.id == skipped.id && it.status == OccurrenceStatus.SKIPPED })
        assertTrue(afterEdit.any { it.id == missed.id && it.status == OccurrenceStatus.MISSED })
        assertTrue(afterEdit.any { it.id == shifted.id && it.status == OccurrenceStatus.SHIFTED })
        assertTrue(afterEdit.any { it.id == currentPending.id && it.status == OccurrenceStatus.PENDING })
        assertFalse(afterEdit.any { it.id in futurePendingIds })
        assertEquals(
            listOf(LocalDate.of(2026, 5, 26), LocalDate.of(2026, 5, 28)),
            afterEdit
                .filter { it.operationalDate.isAfter(LocalDate.of(2026, 5, 22)) }
                .filter { it.status == OccurrenceStatus.PENDING }
                .sortedBy { it.operationalDate }
                .map { it.operationalDate },
        )
        assertEquals(LocalDate.of(2026, 5, 28), dao.ruleForTask(taskId)!!.lastGeneratedDate)
    }

    @Test
    fun nestedExerciseChecksCompleteAndReopenWorkoutDay() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Rehab", taskType = TaskType.SEQUENCE_ROUTINE, pushable = true),
            rule = rule(
                ruleType = RuleType.SEQUENCE,
                intervalDays = 1,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            sequenceItems = listOf(sequenceItem("Day 1 - Strength", 0)),
            sequenceExercisesByPosition = mapOf(
                0 to listOf(
                    SequenceExerciseEntity(
                        sequenceItemId = 0,
                        position = 0,
                        name = "Straight-knee calf raise",
                        prescription = "4 sets x 8 reps",
                        requirement = ExerciseRequirement.REQUIRED,
                    ),
                    SequenceExerciseEntity(
                        sequenceItemId = 0,
                        position = 1,
                        name = "Bent-knee calf raise",
                        prescription = "4 sets x 8 reps",
                        requirement = ExerciseRequirement.REQUIRED,
                    ),
                    SequenceExerciseEntity(
                        sequenceItemId = 0,
                        position = 2,
                        name = "Isometric hold",
                        prescription = "5 sets x 45 seconds",
                        requirement = ExerciseRequirement.CONDITIONAL,
                    ),
                ),
            ),
            generateThrough = LocalDate.of(2026, 5, 20),
        )
        val occurrence = dao.occurrencesForTask(taskId).single()
        val exercises = dao.sequenceExercises(occurrence.sequenceItemId!!)

        repository.setExerciseCheckStatus(
            occurrence.id,
            exercises[0].id,
            ExerciseCheckStatus.COMPLETED,
            LocalDate.of(2026, 5, 20),
        )
        repository.setExerciseCheckStatus(
            occurrence.id,
            exercises[2].id,
            ExerciseCheckStatus.NOT_NEEDED,
            LocalDate.of(2026, 5, 20),
        )
        assertEquals(OccurrenceStatus.PENDING, dao.occurrenceById(occurrence.id)!!.status)

        repository.setExerciseCheckStatus(
            occurrence.id,
            exercises[1].id,
            ExerciseCheckStatus.COMPLETED,
            LocalDate.of(2026, 5, 20),
        )
        assertEquals(OccurrenceStatus.COMPLETED, dao.occurrenceById(occurrence.id)!!.status)

        repository.setExerciseCheckStatus(
            occurrence.id,
            exercises[0].id,
            ExerciseCheckStatus.PENDING,
            LocalDate.of(2026, 5, 20),
        )
        assertEquals(OccurrenceStatus.PENDING, dao.occurrenceById(occurrence.id)!!.status)
        assertEquals(ExerciseCheckStatus.NOT_NEEDED, dao.exerciseChecksForOccurrence(occurrence.id).single { it.sequenceExerciseId == exercises[2].id }.status)
    }

    @Test
    fun pushCarriesPartialExerciseChecksToReplacementDay() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Rehab", taskType = TaskType.SEQUENCE_ROUTINE, pushable = true),
            rule = rule(
                ruleType = RuleType.SEQUENCE,
                intervalDays = 1,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            sequenceItems = listOf(sequenceItem("Day 1 - Strength", 0)),
            sequenceExercisesByPosition = mapOf(
                0 to listOf(
                    SequenceExerciseEntity(
                        sequenceItemId = 0,
                        position = 0,
                        name = "Calf raise",
                        prescription = "4 sets x 8 reps",
                    ),
                ),
            ),
            generateThrough = LocalDate.of(2026, 5, 20),
        )
        val original = dao.occurrencesForTask(taskId).single()
        val exercise = dao.sequenceExercises(original.sequenceItemId!!).single()
        repository.setExerciseCheckStatus(
            original.id,
            exercise.id,
            ExerciseCheckStatus.COMPLETED,
            LocalDate.of(2026, 5, 20),
        )
        repository.undoOccurrenceDecision(original.id, LocalDate.of(2026, 5, 20))

        repository.pushOccurrenceForward(original.id, LocalDate.of(2026, 5, 20))

        val replacement = dao.occurrencesForTask(taskId).single { it.status == OccurrenceStatus.PENDING }
        assertEquals(LocalDate.of(2026, 5, 21), replacement.operationalDate)
        assertEquals(
            ExerciseCheckStatus.COMPLETED,
            dao.exerciseChecksForOccurrence(replacement.id).single().status,
        )
    }

    @Test
    fun manualRoutineAdvancePreservesHistoryAndActivatesNextPhaseTomorrow() = runTest {
        val firstTaskId = repository.createTaskWithRule(
            task = task(name = "Foundation"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 6, 15),
        )
        val secondTaskId = repository.createTaskWithRule(
            task = task(name = "Pogo", isActive = false),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 6, 3)),
            generateThrough = LocalDate.of(2026, 6, 15),
            generatePendingOccurrences = false,
        )
        val completed = dao.occurrencesForTask(firstTaskId).first()
        repository.completeOccurrence(completed.id, LocalDate.of(2026, 5, 20), "Done")
        val planId = repository.createRoutinePlan(
            plan = RoutinePlanEntity(name = "Achilles", createdAt = now, updatedAt = now),
            phases = listOf(
                RoutinePhaseEntity(
                    routinePlanId = 0,
                    taskId = firstTaskId,
                    position = 0,
                    advanceMode = PhaseAdvanceMode.MANUAL,
                    minimumDays = 14,
                    progressionNote = "Has soreness remained stable?",
                    status = RoutinePhaseStatus.ACTIVE,
                    activatedDate = LocalDate.of(2026, 5, 20),
                    createdAt = now,
                    updatedAt = now,
                ),
                RoutinePhaseEntity(
                    routinePlanId = 0,
                    taskId = secondTaskId,
                    position = 1,
                    advanceMode = PhaseAdvanceMode.AUTOMATIC,
                    minimumDays = 14,
                    status = RoutinePhaseStatus.UPCOMING,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        val firstPhase = dao.routinePhasesForPlan(planId).first()

        repository.extendRoutinePhaseOneWeek(firstPhase.id, LocalDate.of(2026, 6, 3))
        assertEquals(LocalDate.of(2026, 6, 3), dao.routinePhaseById(firstPhase.id)!!.lastReviewedDate)
        assertEquals("Extended routine phase by 1 week", dao.allLogs().last().note)
        assertTrue(repository.advanceRoutinePhase(firstPhase.id, LocalDate.of(2026, 6, 3)))

        val phases = dao.routinePhasesForPlan(planId)
        assertEquals(RoutinePhaseStatus.COMPLETED, phases[0].status)
        assertEquals(RoutinePhaseStatus.ACTIVE, phases[1].status)
        assertFalse(dao.taskById(firstTaskId)!!.isActive)
        assertTrue(dao.taskById(secondTaskId)!!.isActive)
        assertEquals(LocalDate.of(2026, 6, 4), dao.ruleForTask(secondTaskId)!!.startDate)
        assertEquals(LocalDate.of(2026, 6, 17), dao.ruleForTask(secondTaskId)!!.endDate)
        assertTrue(dao.occurrencesForTask(firstTaskId).any { it.id == completed.id && it.status == OccurrenceStatus.COMPLETED })
        assertEquals(LocalDate.of(2026, 6, 4), dao.occurrencesForTask(secondTaskId).minOf { it.operationalDate })
    }

    @Test
    fun routinePlanArchiveRestoreAndDeleteApplyToEveryPhaseWithoutAdvancingWhileArchived() = runTest {
        val firstTaskId = repository.createTaskWithRule(
            task = task(name = "Foundation"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 1)),
            generateThrough = LocalDate.of(2026, 5, 20),
        )
        val secondTaskId = repository.createTaskWithRule(
            task = task(name = "Pogo hops", isActive = false),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 15)),
            generateThrough = LocalDate.of(2026, 5, 30),
            generatePendingOccurrences = false,
        )
        val standaloneTaskId = repository.createTaskWithRule(
            task = task(name = "Standalone"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 20),
        )
        val planId = repository.createRoutinePlan(
            plan = RoutinePlanEntity(name = "Achilles", createdAt = now, updatedAt = now),
            phases = listOf(
                RoutinePhaseEntity(
                    routinePlanId = 0,
                    taskId = firstTaskId,
                    position = 0,
                    advanceMode = PhaseAdvanceMode.AUTOMATIC,
                    minimumDays = 14,
                    status = RoutinePhaseStatus.ACTIVE,
                    activatedDate = LocalDate.of(2026, 5, 1),
                    createdAt = now,
                    updatedAt = now,
                ),
                RoutinePhaseEntity(
                    routinePlanId = 0,
                    taskId = secondTaskId,
                    position = 1,
                    advanceMode = PhaseAdvanceMode.MANUAL,
                    minimumDays = 14,
                    status = RoutinePhaseStatus.UPCOMING,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        val activePhaseId = dao.routinePhasesForPlan(planId).first().id

        repository.setRoutinePlanArchived(planId, archived = true, currentOperationalDate = LocalDate.of(2026, 5, 20))

        assertTrue(listOf(firstTaskId, secondTaskId).all { dao.taskById(it)!!.archived })
        assertTrue(listOf(firstTaskId, secondTaskId).none { dao.taskById(it)!!.isActive })
        assertFalse(repository.advanceRoutinePhase(activePhaseId, LocalDate.of(2026, 5, 20)))
        assertEquals(0, repository.advanceDueRoutinePhases(LocalDate.of(2026, 5, 20)))
        assertEquals(RoutinePhaseStatus.ACTIVE, dao.routinePhaseById(activePhaseId)!!.status)

        repository.setRoutinePlanArchived(planId, archived = false, currentOperationalDate = LocalDate.of(2026, 5, 20))

        assertFalse(dao.taskById(firstTaskId)!!.archived)
        assertTrue(dao.taskById(firstTaskId)!!.isActive)
        assertFalse(dao.taskById(secondTaskId)!!.archived)
        assertFalse(dao.taskById(secondTaskId)!!.isActive)
        assertEquals(2, dao.allLogs().count { it.note == "Archived phased routine" })
        assertEquals(2, dao.allLogs().count { it.note == "Restored phased routine" })

        repository.deleteRoutinePlanPermanently(planId)

        assertEquals(null, dao.taskById(firstTaskId))
        assertEquals(null, dao.taskById(secondTaskId))
        assertTrue(dao.allRoutinePlans().isEmpty())
        assertTrue(dao.allRoutinePhases().isEmpty())
        assertEquals("Standalone", dao.taskById(standaloneTaskId)!!.name)
    }

    @Test
    fun metadataOnlyTaskEditPreservesGeneratedOccurrencesAndRuleWindow() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Supplements"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 25),
        )
        val beforeOccurrences = dao.occurrencesForTask(taskId).sortedBy { it.operationalDate }
        val beforeRule = dao.ruleForTask(taskId)!!

        repository.editRuleAndRegenerate(
            task = dao.taskById(taskId)!!.copy(
                name = "Supplements with breakfast",
                notes = "Take after meal",
                defaultReminderEnabled = false,
                calendarVisible = false,
            ),
            rule = beforeRule,
            currentOperationalDate = LocalDate.of(2026, 5, 20),
            generateThrough = LocalDate.of(2026, 5, 30),
        )

        val updatedTask = dao.taskById(taskId)!!
        val afterRule = dao.ruleForTask(taskId)!!
        assertEquals("Supplements with breakfast", updatedTask.name)
        assertEquals("Take after meal", updatedTask.notes)
        assertFalse(updatedTask.defaultReminderEnabled)
        assertFalse(updatedTask.calendarVisible)
        assertEquals(
            beforeOccurrences.map { it.id to it.operationalDate },
            dao.occurrencesForTask(taskId).sortedBy { it.operationalDate }.map { it.id to it.operationalDate },
        )
        assertEquals(beforeRule.lastGeneratedDate, afterRule.lastGeneratedDate)
        assertEquals(1, dao.logsForTask(taskId).count { it.note == "Updated task metadata" })
        assertFalse(dao.logsForTask(taskId).any { it.note == "Regenerated future pending occurrences after rule edit" })
    }

    @Test
    fun missedMaintenanceMarksOnlyOverduePendingAndCreatesLogs() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Supplements"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 22),
        )

        val marked = repository.markOverduePendingMissed(LocalDate.of(2026, 5, 22))

        val occurrences = dao.occurrencesForTask(taskId)
        val logs = dao.logsForTask(taskId)
        assertEquals(2, marked)
        assertEquals(2, occurrences.count { it.status == OccurrenceStatus.MISSED })
        assertEquals(1, occurrences.count { it.operationalDate == LocalDate.of(2026, 5, 22) && it.status == OccurrenceStatus.PENDING })
        assertEquals(2, logs.count { it.action == LogAction.MARKED_MISSED })
        assertEquals(
            occurrences.filter { it.status == OccurrenceStatus.MISSED }.map { it.id }.toSet(),
            logs.mapNotNull { it.occurrenceId }.toSet(),
        )
    }

    @Test
    fun missedMaintenanceIgnoresArchivedTasks() = runTest {
        val activeTaskId = repository.createTaskWithRule(
            task = task(name = "Active"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 20),
        )
        val archivedTaskId = repository.createTaskWithRule(
            task = task(name = "Archived", archived = true, isActive = false),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 20),
        )

        val marked = repository.markOverduePendingMissed(LocalDate.of(2026, 5, 21))

        assertEquals(1, marked)
        assertEquals(OccurrenceStatus.MISSED, dao.occurrencesForTask(activeTaskId).single().status)
        assertEquals(OccurrenceStatus.PENDING, dao.occurrencesForTask(archivedTaskId).single().status)
        assertEquals(1, dao.logsForTask(activeTaskId).count { it.action == LogAction.MARKED_MISSED })
        assertTrue(dao.logsForTask(archivedTaskId).isEmpty())
    }

    @Test
    fun longTermOverdueTaskStaysPendingUntilCompletedAndCannotBeSkipped() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Change filters", taskType = TaskType.LONG_TERM),
            rule = rule(
                ruleType = RuleType.EVERY_X_MONTHS,
                intervalDays = 6,
                startDate = LocalDate.of(2026, 1, 15),
            ),
            generateThrough = LocalDate.of(2026, 1, 15),
        )
        val occurrence = dao.occurrencesForTask(taskId).single()

        val handled = repository.markOverduePendingMissed(LocalDate.of(2026, 6, 15))
        repository.skipOccurrence(occurrence.id, LocalDate.of(2026, 6, 15))

        assertEquals(0, handled)
        assertEquals(OccurrenceStatus.PENDING, dao.occurrenceById(occurrence.id)!!.status)

        repository.completeOccurrence(occurrence.id, LocalDate.of(2026, 6, 15), "Filters changed")

        assertEquals(OccurrenceStatus.COMPLETED, dao.occurrenceById(occurrence.id)!!.status)
        assertEquals(1, dao.logsForTask(taskId).count { it.action == LogAction.COMPLETED })
    }

    @Test
    fun completionAnchoredLongTermTaskRepeatsFromCompletionDateAndUndoRestoresDueDateSchedule() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Change filters", taskType = TaskType.LONG_TERM),
            rule = rule(
                ruleType = RuleType.EVERY_X_MONTHS,
                intervalDays = 6,
                startDate = LocalDate.of(2026, 1, 15),
            ),
            generateThrough = LocalDate.of(2026, 7, 15),
        )
        val occurrence = dao.occurrencesForTask(taskId).single { it.operationalDate == LocalDate.of(2026, 1, 15) }

        repository.completeOccurrence(occurrence.id, LocalDate.of(2026, 6, 15), "Filters changed")

        val completed = dao.occurrenceById(occurrence.id)!!
        val pendingDates = dao.occurrencesForTask(taskId)
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .sorted()
        assertEquals(OccurrenceStatus.COMPLETED, completed.status)
        assertEquals(LocalDate.of(2026, 1, 15), completed.originalDate)
        assertEquals(LocalDate.of(2026, 6, 15), dao.ruleForTask(taskId)!!.startDate)
        assertFalse(pendingDates.contains(LocalDate.of(2026, 7, 15)))
        assertEquals(LocalDate.of(2026, 12, 15), pendingDates.first())

        repository.undoOccurrenceDecision(occurrence.id, LocalDate.of(2026, 6, 15))

        val reset = dao.occurrenceById(occurrence.id)!!
        val restoredPendingDates = dao.occurrencesForTask(taskId)
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .sorted()
        assertEquals(OccurrenceStatus.PENDING, reset.status)
        assertEquals(null, reset.originalDate)
        assertEquals(LocalDate.of(2026, 1, 15), dao.ruleForTask(taskId)!!.startDate)
        assertEquals(LocalDate.of(2026, 1, 15), restoredPendingDates.first())
        assertTrue(restoredPendingDates.contains(LocalDate.of(2026, 7, 15)))
        assertFalse(restoredPendingDates.contains(LocalDate.of(2026, 12, 15)))
    }

    @Test
    fun dueDateAnchoredLongTermTaskKeepsOriginalFutureDueDatesAfterCompletion() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Change filters", taskType = TaskType.LONG_TERM),
            rule = rule(
                ruleType = RuleType.EVERY_X_MONTHS,
                intervalDays = 6,
                startDate = LocalDate.of(2026, 1, 15),
                cycleDefinition = LongTermRecurrenceAnchor.DUE_DATE.name,
            ),
            generateThrough = LocalDate.of(2026, 7, 15),
        )
        val occurrence = dao.occurrencesForTask(taskId).single { it.operationalDate == LocalDate.of(2026, 1, 15) }

        repository.completeOccurrence(occurrence.id, LocalDate.of(2026, 6, 15), "Filters changed")

        val completed = dao.occurrenceById(occurrence.id)!!
        val pendingDates = dao.occurrencesForTask(taskId)
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .sorted()
        assertEquals(OccurrenceStatus.COMPLETED, completed.status)
        assertEquals(null, completed.originalDate)
        assertEquals(LocalDate.of(2026, 1, 15), dao.ruleForTask(taskId)!!.startDate)
        assertTrue(pendingDates.contains(LocalDate.of(2026, 7, 15)))
    }

    @Test
    fun longTermScheduleExtensionUsesLongPlanningWindow() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Change filters", taskType = TaskType.LONG_TERM),
            rule = rule(
                ruleType = RuleType.EVERY_X_MONTHS,
                intervalDays = 6,
                startDate = LocalDate.of(2026, 1, 15),
            ),
            generateThrough = LocalDate.of(2026, 1, 15),
        )

        repository.extendGeneratedOccurrences(
            currentOperationalDate = LocalDate.of(2026, 2, 1),
            daysAhead = 60,
        )

        val occurrenceDates = dao.occurrencesForTask(taskId).map { it.operationalDate }.sorted()
        assertEquals(
            listOf(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 7, 15), LocalDate.of(2027, 1, 15)),
            occurrenceDates.take(3),
        )
        assertEquals(LocalDate.of(2036, 1, 15), occurrenceDates.last())
        assertEquals(LocalDate.of(2026, 2, 1).plusDays(3655), dao.ruleForTask(taskId)!!.lastGeneratedDate)
    }

    @Test
    fun longTermTasksCanUseDayWeekAndYearRules() = runTest {
        val dailyTaskId = repository.createTaskWithRule(
            task = task(name = "Water heater check", taskType = TaskType.LONG_TERM),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 1,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            generateThrough = LocalDate.of(2026, 5, 22),
        )
        val weeklyTaskId = repository.createTaskWithRule(
            task = task(name = "Pool service", taskType = TaskType.LONG_TERM),
            rule = rule(
                ruleType = RuleType.EVERY_X_WEEKS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            generateThrough = LocalDate.of(2026, 6, 20),
        )
        val yearlyTaskId = repository.createTaskWithRule(
            task = task(name = "Renew permit", taskType = TaskType.LONG_TERM),
            rule = rule(
                ruleType = RuleType.EVERY_X_YEARS,
                intervalDays = 1,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            generateThrough = LocalDate.of(2028, 5, 20),
        )

        assertEquals(
            listOf(LocalDate.of(2026, 5, 20), LocalDate.of(2026, 5, 21), LocalDate.of(2026, 5, 22)),
            dao.occurrencesForTask(dailyTaskId).map { it.operationalDate }.sorted(),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 5, 20), LocalDate.of(2026, 6, 3), LocalDate.of(2026, 6, 17)),
            dao.occurrencesForTask(weeklyTaskId).map { it.operationalDate }.sorted(),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 5, 20), LocalDate.of(2027, 5, 20), LocalDate.of(2028, 5, 20)),
            dao.occurrencesForTask(yearlyTaskId).map { it.operationalDate }.sorted(),
        )
    }

    @Test
    fun archivedTaskOccurrencesIgnoreDirectStatusAndShiftActions() = runTest {
        val archivedSimpleTaskId = repository.createTaskWithRule(
            task = task(name = "Archived simple", archived = true, isActive = false),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 20),
        )
        val archivedSequenceTaskId = repository.createTaskWithRule(
            task = task(name = "Archived sequence", taskType = TaskType.SEQUENCE_ROUTINE, archived = true, isActive = false),
            rule = rule(ruleType = RuleType.SEQUENCE, startDate = LocalDate.of(2026, 5, 20)),
            sequenceItems = listOf(sequenceItem("Push", 0), sequenceItem("Pull", 1)),
            generateThrough = LocalDate.of(2026, 5, 21),
        )
        val simpleOccurrence = dao.occurrencesForTask(archivedSimpleTaskId).single()
        val sequenceOccurrence = dao.occurrencesForTask(archivedSequenceTaskId).first()

        repository.completeOccurrence(simpleOccurrence.id, LocalDate.of(2026, 5, 20))
        repository.skipOccurrence(simpleOccurrence.id, LocalDate.of(2026, 5, 20))
        repository.shiftSequenceForward(sequenceOccurrence.id, LocalDate.of(2026, 5, 20))

        assertEquals(OccurrenceStatus.PENDING, dao.occurrenceById(simpleOccurrence.id)!!.status)
        assertEquals(OccurrenceStatus.PENDING, dao.occurrenceById(sequenceOccurrence.id)!!.status)
        assertTrue(dao.logsForTask(archivedSimpleTaskId).isEmpty())
        assertTrue(dao.logsForTask(archivedSequenceTaskId).isEmpty())
    }

    @Test
    fun completeLogUsesOccurrenceOperationalDate() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Supplements"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 19)),
            generateThrough = LocalDate.of(2026, 5, 19),
        )
        val occurrence = dao.occurrencesForTask(taskId).single()

        repository.completeOccurrence(
            occurrenceId = occurrence.id,
            currentOperationalDate = LocalDate.of(2026, 5, 20),
            note = "Completed after midnight",
        )

        assertEquals(LocalDate.of(2026, 5, 19), dao.logsForTask(taskId).single().operationalDate)
    }

    @Test
    fun completeAndSkipActionsUpdateStatusNotesAndLogs() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Supplements"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 21),
        )
        val occurrences = dao.occurrencesForTask(taskId).sortedBy { it.operationalDate }
        val completeOccurrence = occurrences.first()
        val skipOccurrence = occurrences.last()

        repository.completeOccurrence(
            occurrenceId = completeOccurrence.id,
            currentOperationalDate = LocalDate.of(2026, 5, 20),
            note = "Completed after breakfast",
        )
        repository.skipOccurrence(
            occurrenceId = skipOccurrence.id,
            currentOperationalDate = LocalDate.of(2026, 5, 21),
            note = "Intentional recovery",
        )

        val completed = dao.occurrenceById(completeOccurrence.id)!!
        val skipped = dao.occurrenceById(skipOccurrence.id)!!
        val logsByOccurrenceId = dao.logsForTask(taskId).associateBy { it.occurrenceId }

        assertEquals(OccurrenceStatus.COMPLETED, completed.status)
        assertEquals("Completed after breakfast", completed.note)
        assertEquals(LogAction.COMPLETED, logsByOccurrenceId[completeOccurrence.id]!!.action)
        assertEquals("Completed after breakfast", logsByOccurrenceId[completeOccurrence.id]!!.note)
        assertEquals(OccurrenceStatus.SKIPPED, skipped.status)
        assertEquals("Intentional recovery", skipped.note)
        assertEquals(LogAction.SKIPPED, logsByOccurrenceId[skipOccurrence.id]!!.action)
        assertEquals("Intentional recovery", logsByOccurrenceId[skipOccurrence.id]!!.note)
    }

    @Test
    fun completeAndSkipActionsPreserveExistingOccurrenceNotes() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Supplements"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 21),
        )
        val occurrences = dao.occurrencesForTask(taskId).sortedBy { it.operationalDate }
        val completeOccurrence = occurrences.first()
        val skipOccurrence = occurrences.last()
        dao.updateOccurrence(completeOccurrence.copy(note = "Taken after breakfast"))
        dao.updateOccurrence(skipOccurrence.copy(note = "Travel day"))

        repository.completeOccurrence(
            occurrenceId = completeOccurrence.id,
            currentOperationalDate = LocalDate.of(2026, 5, 20),
            note = "Completed action note",
        )
        repository.skipOccurrence(
            occurrenceId = skipOccurrence.id,
            currentOperationalDate = LocalDate.of(2026, 5, 21),
            note = "Skipped action note",
        )

        val completed = dao.occurrenceById(completeOccurrence.id)!!
        val skipped = dao.occurrenceById(skipOccurrence.id)!!
        val logsByOccurrenceId = dao.logsForTask(taskId).associateBy { it.occurrenceId }

        assertEquals("Taken after breakfast", completed.note)
        assertEquals("Completed action note", logsByOccurrenceId[completeOccurrence.id]!!.note)
        assertEquals("Travel day", skipped.note)
        assertEquals("Skipped action note", logsByOccurrenceId[skipOccurrence.id]!!.note)
    }

    @Test
    fun updateOccurrenceNoteTrimsAndLogsOccurrenceOperationalDate() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Supplements"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 19)),
            generateThrough = LocalDate.of(2026, 5, 19),
        )
        val occurrence = dao.occurrencesForTask(taskId).single()

        repository.updateOccurrenceNote(occurrence.id, "  Taken after breakfast  ")

        val updated = dao.occurrenceById(occurrence.id)!!
        val log = dao.logsForTask(taskId).single()
        assertEquals("Taken after breakfast", updated.note)
        assertEquals(LogAction.EDITED, log.action)
        assertEquals(LocalDate.of(2026, 5, 19), log.operationalDate)
        assertEquals("Updated occurrence note", log.note)
    }

    @Test
    fun setTaskArchivedTogglesActiveStateAndLogsArchiveAndRestore() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Supplements"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 20),
        )

        repository.setTaskArchived(taskId, archived = true, currentOperationalDate = LocalDate.of(2026, 5, 20))
        repository.setTaskArchived(taskId, archived = false, currentOperationalDate = LocalDate.of(2026, 5, 21))

        val restored = dao.taskById(taskId)!!
        val logs = dao.logsForTask(taskId).sortedBy { it.operationalDate }
        assertEquals(false, restored.archived)
        assertEquals(true, restored.isActive)
        assertEquals(listOf("Archived task", "Restored task"), logs.map { it.note })
        assertEquals(listOf(LocalDate.of(2026, 5, 20), LocalDate.of(2026, 5, 21)), logs.map { it.operationalDate })
    }

    @Test
    fun pushableTaskMovesCurrentAndFuturePendingRowsForward() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Cardio", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            generateThrough = LocalDate.of(2026, 5, 24),
        )
        val first = dao.occurrencesForTask(taskId).first { it.operationalDate == LocalDate.of(2026, 5, 20) }

        repository.pushOccurrenceForward(first.id, LocalDate.of(2026, 5, 20))

        val afterPush = dao.occurrencesForTask(taskId)
        assertTrue(afterPush.any { it.id == first.id && it.status == OccurrenceStatus.SHIFTED && it.originalDate == LocalDate.of(2026, 5, 20) })
        assertTrue(afterPush.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 21) && it.originalDate == LocalDate.of(2026, 5, 20) })
        assertTrue(afterPush.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 23) && it.originalDate == LocalDate.of(2026, 5, 22) })
        assertTrue(afterPush.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 25) && it.originalDate == LocalDate.of(2026, 5, 24) })
        assertEquals(1, dao.logsForTask(taskId).count { it.note == "Pushed to 2026-05-21" })
    }

    @Test
    fun cycleAutoRestartTriggersAtThresholdAndRegeneratesOnlyThatTask() = runTest {
        val firstTaskId = repository.createTaskWithRule(
            task = task(name = "CO2 table"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 6, 10),
        )
        val secondTaskId = repository.createTaskWithRule(
            task = task(name = "O2 table"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 21)),
            generateThrough = LocalDate.of(2026, 6, 10),
        )
        val groupId = dao.insertCycleGroup(
            cycleGroup(
                name = "Breath tables",
                durationDays = 14,
                resetThresholdPercent = 50,
                restartBehavior = CycleRestartBehavior.AUTO_RESTART,
                currentStartDate = LocalDate.of(2026, 5, 20),
            ),
        )
        dao.insertCycleMembership(cycleMembership(groupId, firstTaskId, startOffsetDays = 0))
        dao.insertCycleMembership(cycleMembership(groupId, secondTaskId, startOffsetDays = 1))
        val oldFirstFutureId = dao.occurrencesForTask(firstTaskId).single { it.operationalDate == LocalDate.of(2026, 5, 27) }.id
        val oldSecondFutureId = dao.occurrencesForTask(secondTaskId).single { it.operationalDate == LocalDate.of(2026, 5, 27) }.id

        (0L..6L).forEach { offset ->
            val date = LocalDate.of(2026, 5, 20).plusDays(offset)
            val occurrence = dao.occurrencesForTask(firstTaskId).single { it.operationalDate == date }
            repository.skipOccurrence(occurrence.id, date)
        }

        val restartedGroup = dao.cycleGroupById(groupId)!!
        val firstOccurrences = dao.occurrencesForTask(firstTaskId)
        val secondOccurrences = dao.occurrencesForTask(secondTaskId)
        assertEquals(LocalDate.of(2026, 5, 26), restartedGroup.currentStartDate)
        assertFalse(firstOccurrences.any { it.id == oldFirstFutureId })
        assertTrue(secondOccurrences.any { it.id == oldSecondFutureId })
        assertEquals(
            (0L..6L).map { LocalDate.of(2026, 5, 20).plusDays(it) },
            firstOccurrences
                .filter { it.status == OccurrenceStatus.SKIPPED }
                .sortedBy { it.operationalDate }
                .map { it.operationalDate },
        )
        assertTrue(firstOccurrences.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 27) })
        assertTrue(secondOccurrences.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 27) })
        assertTrue(
            dao.cycleLogsForGroup(groupId).any {
                it.note == "Cycle automatically restarted: 7 disrupted days reached 7 day threshold"
            },
        )
    }

    @Test
    fun editingDisruptedDurationTaskWithAutoRestartStartsNewCycleWindow() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "CO2 tables", taskType = TaskType.INTERVAL, pushable = true, noActionBehavior = NoActionBehavior.AUTO_PUSH),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 24),
                skipBlockedDaysBehavior = SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY,
            ).copy(durationDays = 14),
            generateThrough = LocalDate.of(2026, 6, 20),
        )
        dao.occurrencesForTask(taskId)
            .filter { !it.operationalDate.isAfter(LocalDate.of(2026, 6, 5)) }
            .forEach { occurrence ->
                dao.updateOccurrence(occurrence.copy(status = OccurrenceStatus.SKIPPED, note = "Pushed forward"))
            }
        val existingTask = dao.taskById(taskId)!!
        val existingRule = dao.ruleForTask(taskId)!!

        repository.editRuleAndRegenerate(
            task = existingTask,
            rule = existingRule,
            cycleGroup = cycleGroup(
                name = "CO2 tables auto restart",
                durationDays = 14,
                resetThresholdPercent = 50,
                restartBehavior = CycleRestartBehavior.AUTO_RESTART,
                restartTiming = CycleRestartTiming.TODAY,
                currentStartDate = LocalDate.of(2026, 5, 24),
            ),
            cycleMembership = cycleMembership(cycleGroupId = 0, taskId = taskId),
            currentOperationalDate = LocalDate.of(2026, 6, 16),
            generateThrough = LocalDate.of(2026, 8, 15),
        )

        val updatedRule = dao.ruleForTask(taskId)!!
        val pendingDates = dao.occurrencesForTask(taskId)
            .filter { it.status == OccurrenceStatus.PENDING && !it.operationalDate.isBefore(LocalDate.of(2026, 6, 16)) }
            .map { it.operationalDate }
            .sorted()
        assertEquals(LocalDate.of(2026, 6, 16), updatedRule.startDate)
        assertEquals(LocalDate.of(2026, 6, 29), updatedRule.endDate)
        assertEquals(
            listOf(
                LocalDate.of(2026, 6, 16),
                LocalDate.of(2026, 6, 18),
                LocalDate.of(2026, 6, 20),
                LocalDate.of(2026, 6, 22),
                LocalDate.of(2026, 6, 24),
                LocalDate.of(2026, 6, 26),
                LocalDate.of(2026, 6, 28),
            ),
            pendingDates,
        )
        assertTrue(
            dao.cycleLogsForGroup(dao.allCycleGroups().single().id).any {
                it.note == "Cycle automatically restarted: 7 disrupted days reached 7 day threshold"
            },
        )
    }

    @Test
    fun endedDurationTaskWithAutoRestartCreatesNewCycleOnCurrentDay() = runTest {
        val startDate = LocalDate.of(2026, 6, 24)
        val currentDate = LocalDate.of(2026, 7, 8)
        val taskId = repository.createTaskWithRule(
            task = task(
                name = "CO2 tables",
                taskType = TaskType.INTERVAL,
                pushable = true,
                noActionBehavior = NoActionBehavior.AUTO_PUSH,
            ),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = startDate,
                skipBlockedDaysBehavior = SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY,
            ).copy(
                endDate = startDate.plusDays(13),
                durationDays = 14,
            ),
            cycleGroup = cycleGroup(
                name = "CO2 tables auto restart",
                durationDays = 14,
                resetThresholdPercent = 50,
                restartBehavior = CycleRestartBehavior.AUTO_RESTART,
                restartTiming = CycleRestartTiming.TODAY,
                currentStartDate = startDate,
            ),
            cycleMembership = cycleMembership(cycleGroupId = 0, taskId = 0),
            generateThrough = startDate.plusDays(13),
        )
        dao.occurrencesForTask(taskId).forEach { occurrence ->
            dao.updateOccurrence(occurrence.copy(status = OccurrenceStatus.COMPLETED))
        }

        val restarted = repository.restartEndedCycles(currentDate)

        val updatedRule = dao.ruleForTask(taskId)!!
        val pendingDates = dao.occurrencesForTask(taskId)
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .sorted()
        assertEquals(1, restarted)
        assertEquals(currentDate, updatedRule.startDate)
        assertEquals(LocalDate.of(2026, 7, 21), updatedRule.endDate)
        assertEquals(
            listOf(
                LocalDate.of(2026, 7, 8),
                LocalDate.of(2026, 7, 10),
                LocalDate.of(2026, 7, 12),
                LocalDate.of(2026, 7, 14),
                LocalDate.of(2026, 7, 16),
                LocalDate.of(2026, 7, 18),
                LocalDate.of(2026, 7, 20),
            ),
            pendingDates,
        )
        assertTrue(
            dao.allCycleLogs().any {
                it.note == "Cycle automatically restarted: prior cycle ended with no upcoming pending occurrences"
            },
        )
    }

    @Test
    fun cycleAutoRestartMovesRestartDateOffBlockedSunday() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Mobility"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 6, 10),
        )
        val groupId = dao.insertCycleGroup(
            cycleGroup(
                durationDays = 14,
                resetThresholdPercent = 1,
                restartBehavior = CycleRestartBehavior.AUTO_RESTART,
                restartTiming = CycleRestartTiming.TODAY,
                blockedDays = setOf(DayOfWeek.SUNDAY),
                currentStartDate = LocalDate.of(2026, 5, 20),
            ),
        )
        dao.insertCycleMembership(cycleMembership(groupId, taskId))
        val sunday = LocalDate.of(2026, 5, 24)
        val oldMondayPendingId = dao.occurrencesForTask(taskId).single { it.operationalDate == LocalDate.of(2026, 5, 25) }.id

        val sundayOccurrence = dao.occurrencesForTask(taskId).single { it.operationalDate == sunday }
        repository.skipOccurrence(sundayOccurrence.id, sunday)

        val occurrences = dao.occurrencesForTask(taskId)
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)
        assertEquals(LocalDate.of(2026, 5, 25), dao.cycleGroupById(groupId)!!.currentStartDate)
        assertFalse(occurrences.any { it.id == oldMondayPendingId })
        assertTrue(occurrences.any { it.id == sundayOccurrence.id && it.status == OccurrenceStatus.SKIPPED })
        assertTrue(occurrences.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 25) })
        assertTrue(dao.cycleLogsForGroup(groupId).single().note.startsWith("Cycle automatically restarted"))
    }

    @Test
    fun cycleAutoRestartCountsPushedDaysAsDisruption() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Cardio", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            generateThrough = LocalDate.of(2026, 5, 30),
        )
        val groupId = dao.insertCycleGroup(
            cycleGroup(
                durationDays = 14,
                resetThresholdPercent = 1,
                restartBehavior = CycleRestartBehavior.AUTO_RESTART,
                currentStartDate = LocalDate.of(2026, 5, 20),
            ),
        )
        dao.insertCycleMembership(cycleMembership(groupId, taskId))
        val first = dao.occurrencesForTask(taskId).single { it.operationalDate == LocalDate.of(2026, 5, 20) }

        repository.pushOccurrenceForward(first.id, LocalDate.of(2026, 5, 20))

        val occurrences = dao.occurrencesForTask(taskId)
        assertEquals(LocalDate.of(2026, 5, 20), dao.cycleGroupById(groupId)!!.currentStartDate)
        assertTrue(occurrences.any { it.id == first.id && it.status == OccurrenceStatus.SHIFTED })
        assertTrue(dao.cycleLogsForGroup(groupId).any { it.note.contains("disrupted days reached 1 day threshold") })
    }

    @Test
    fun onePushDoesNotCountShiftedFutureCadenceAsMultipleCycleDisruptions() = runTest {
        val cycleStart = LocalDate.of(2026, 7, 13)
        val taskId = repository.createTaskWithRule(
            task = task(name = "CO2 tables", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = cycleStart,
            ).copy(
                endDate = cycleStart.plusDays(13),
                durationDays = 14,
            ),
            generateThrough = cycleStart.plusDays(13),
        )
        val groupId = dao.insertCycleGroup(
            cycleGroup(
                name = "CO2 tables auto restart",
                durationDays = 14,
                resetThresholdPercent = 50,
                restartBehavior = CycleRestartBehavior.AUTO_RESTART,
                restartTiming = CycleRestartTiming.TODAY,
                currentStartDate = cycleStart,
            ),
        )
        dao.insertCycleMembership(cycleMembership(groupId, taskId))
        val first = dao.occurrencesForTask(taskId).single { it.operationalDate == cycleStart }

        repository.pushOccurrenceForward(first.id, cycleStart)

        val nextPending = dao.occurrencesForTask(taskId)
            .filter { it.status == OccurrenceStatus.PENDING }
            .minBy { it.operationalDate }
        assertEquals(cycleStart.plusDays(1), nextPending.operationalDate)
        assertEquals(cycleStart, nextPending.originalDate)
        assertTrue(dao.cycleLogsForGroup(groupId).isEmpty())
    }

    @Test
    fun completingPushableOccurrenceYesterdayMovesRowBackAndPullsFuturePendingRows() = runTest {
        val parentId = repository.createTaskWithRule(
            task = task(name = "CO2 table", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 19),
            ).copy(
                endDate = LocalDate.of(2026, 6, 1),
                durationDays = 14,
            ),
            generateThrough = LocalDate.of(2026, 6, 1),
        )
        val childId = repository.createTaskWithRule(
            task = task(name = "O2 table"),
            rule = rule(
                ruleType = RuleType.DAILY,
                startDate = LocalDate.of(2026, 5, 20),
            ).copy(
                durationDays = 14,
                startsAfterTaskId = parentId,
            ),
            generateThrough = LocalDate.of(2026, 6, 20),
        )
        val current = dao.occurrencesForTask(parentId).first { it.operationalDate == LocalDate.of(2026, 5, 21) }

        repository.completeOccurrenceYesterday(current.id, LocalDate.of(2026, 5, 21))

        val after = dao.occurrencesForTask(parentId)
        val completedYesterday = dao.occurrenceById(current.id)!!
        assertEquals(OccurrenceStatus.COMPLETED, completedYesterday.status)
        assertEquals(LocalDate.of(2026, 5, 20), completedYesterday.operationalDate)
        assertEquals(LocalDate.of(2026, 5, 21), completedYesterday.originalDate)
        assertTrue(after.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 22) && it.originalDate == LocalDate.of(2026, 5, 23) })
        assertTrue(after.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 24) && it.originalDate == LocalDate.of(2026, 5, 25) })
        assertEquals(LocalDate.of(2026, 5, 31), dao.ruleForTask(parentId)!!.endDate)
        assertEquals(LocalDate.of(2026, 6, 1), dao.ruleForTask(parentId)!!.lastGeneratedDate)
        assertEquals(LocalDate.of(2026, 6, 1), dao.ruleForTask(childId)!!.startDate)
        assertEquals(LocalDate.of(2026, 6, 14), dao.ruleForTask(childId)!!.endDate)
        assertTrue(
            dao.logsForTask(parentId).any {
                it.action == LogAction.COMPLETED &&
                    it.operationalDate == LocalDate.of(2026, 5, 20) &&
                    it.note == "Completed yesterday; schedule shifted from 2026-05-21"
            },
        )
    }

    @Test
    fun completingFirstEveryTwoDayOccurrenceYesterdayMovesRuleStartDateBack() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Cardio", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            generateThrough = LocalDate.of(2026, 5, 24),
        )
        val first = dao.occurrencesForTask(taskId).first { it.operationalDate == LocalDate.of(2026, 5, 20) }

        repository.completeOccurrenceYesterday(first.id, LocalDate.of(2026, 5, 20))

        val after = dao.occurrencesForTask(taskId)
        val completedYesterday = dao.occurrenceById(first.id)!!
        val rule = dao.ruleForTask(taskId)!!
        assertEquals(OccurrenceStatus.COMPLETED, completedYesterday.status)
        assertEquals(LocalDate.of(2026, 5, 19), completedYesterday.operationalDate)
        assertEquals(LocalDate.of(2026, 5, 20), completedYesterday.originalDate)
        assertTrue(after.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 21) && it.originalDate == LocalDate.of(2026, 5, 22) })
        assertTrue(after.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 23) && it.originalDate == LocalDate.of(2026, 5, 24) })
        assertEquals(LocalDate.of(2026, 5, 19), rule.startDate)
    }

    @Test
    fun completingEveryTwoDayOccurrenceDueTomorrowYesterdayPullsScheduleBackFromCurrentDate() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Cardio", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 21),
            ),
            generateThrough = LocalDate.of(2026, 5, 24),
        )
        val tomorrow = dao.occurrencesForTask(taskId).first { it.operationalDate == LocalDate.of(2026, 5, 21) }

        repository.completeOccurrenceYesterday(tomorrow.id, LocalDate.of(2026, 5, 20))

        val after = dao.occurrencesForTask(taskId)
        val completedYesterday = dao.occurrenceById(tomorrow.id)!!
        assertEquals(OccurrenceStatus.COMPLETED, completedYesterday.status)
        assertEquals(LocalDate.of(2026, 5, 19), completedYesterday.operationalDate)
        assertEquals(LocalDate.of(2026, 5, 21), completedYesterday.originalDate)
        assertTrue(after.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 21) && it.originalDate == LocalDate.of(2026, 5, 23) })
        assertEquals(LocalDate.of(2026, 5, 19), dao.ruleForTask(taskId)!!.startDate)
    }

    @Test
    fun completingYesterdayUsesExistingShiftedPlaceholderWhenYesterdayIsOccupied() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Cardio", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 18),
            ),
            generateThrough = LocalDate.of(2026, 5, 22),
        )
        val ruleId = dao.ruleForTask(taskId)!!.id
        val shiftedPlaceholderId = dao.insertOccurrences(
            listOf(
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 5, 19),
                    operationalDate = LocalDate.of(2026, 5, 19),
                    status = OccurrenceStatus.SHIFTED,
                    isShifted = true,
                    originalDate = LocalDate.of(2026, 5, 18),
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        ).single()
        val current = dao.occurrencesForTask(taskId).first { it.operationalDate == LocalDate.of(2026, 5, 20) }

        repository.completeOccurrenceYesterday(current.id, LocalDate.of(2026, 5, 20))

        val completedYesterday = dao.occurrenceById(shiftedPlaceholderId)!!
        val shiftedCurrent = dao.occurrenceById(current.id)!!
        val after = dao.occurrencesForTask(taskId)
        assertEquals(OccurrenceStatus.COMPLETED, completedYesterday.status)
        assertEquals(LocalDate.of(2026, 5, 19), completedYesterday.operationalDate)
        assertEquals(OccurrenceStatus.PENDING, shiftedCurrent.status)
        assertEquals(LocalDate.of(2026, 5, 21), shiftedCurrent.operationalDate)
        assertEquals(LocalDate.of(2026, 5, 20), shiftedCurrent.originalDate)
        assertTrue(after.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 23) && it.originalDate == LocalDate.of(2026, 5, 22) })
        assertTrue(dao.logsForTask(taskId).any { it.action == LogAction.COMPLETED && it.occurrenceId == shiftedPlaceholderId })
    }

    @Test
    fun undoCompletedYesterdayRestoresFirstEveryTwoDayRuleStartDate() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Cardio", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            generateThrough = LocalDate.of(2026, 5, 24),
        )
        val first = dao.occurrencesForTask(taskId).first { it.operationalDate == LocalDate.of(2026, 5, 20) }
        repository.completeOccurrenceYesterday(first.id, LocalDate.of(2026, 5, 20))

        repository.undoOccurrenceDecision(first.id, LocalDate.of(2026, 5, 20))

        val restored = dao.occurrenceById(first.id)!!
        val after = dao.occurrencesForTask(taskId)
        assertEquals(OccurrenceStatus.PENDING, restored.status)
        assertEquals(LocalDate.of(2026, 5, 20), restored.operationalDate)
        assertEquals(null, restored.originalDate)
        assertTrue(after.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 22) && it.originalDate == null })
        assertEquals(LocalDate.of(2026, 5, 20), dao.ruleForTask(taskId)!!.startDate)
    }

    @Test
    fun undoCompletedYesterdayRestoresPersistedPulledSchedule() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Cardio", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 19),
            ).copy(
                endDate = LocalDate.of(2026, 6, 1),
                durationDays = 14,
            ),
            generateThrough = LocalDate.of(2026, 6, 1),
        )
        val current = dao.occurrencesForTask(taskId).first { it.operationalDate == LocalDate.of(2026, 5, 21) }
        repository.completeOccurrenceYesterday(current.id, LocalDate.of(2026, 5, 21))

        repository.undoOccurrenceDecision(current.id, LocalDate.of(2026, 5, 21))

        val restored = dao.occurrenceById(current.id)!!
        val after = dao.occurrencesForTask(taskId)
        assertEquals(OccurrenceStatus.PENDING, restored.status)
        assertEquals(LocalDate.of(2026, 5, 21), restored.operationalDate)
        assertEquals(null, restored.originalDate)
        assertTrue(after.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 23) && it.originalDate == null })
        assertEquals(LocalDate.of(2026, 6, 1), dao.ruleForTask(taskId)!!.endDate)
        assertEquals(LocalDate.of(2026, 6, 1), dao.ruleForTask(taskId)!!.lastGeneratedDate)
        assertTrue(dao.logsForTask(taskId).any { it.note == "Undid completed-yesterday shift" })
    }

    @Test
    fun pushingDurationTaskShiftsEndDateAndDependentCycleTaskWindow() = runTest {
        val parentId = repository.createTaskWithRule(
            task = task(name = "CO2 table", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 20),
            ).copy(
                endDate = LocalDate.of(2026, 6, 2),
                durationDays = 14,
            ),
            generateThrough = LocalDate.of(2026, 6, 2),
        )
        val childId = repository.createTaskWithRule(
            task = task(name = "O2 table"),
            rule = rule(
                ruleType = RuleType.DAILY,
                startDate = LocalDate.of(2026, 5, 20),
            ).copy(
                durationDays = 14,
                startsAfterTaskId = parentId,
            ),
            generateThrough = LocalDate.of(2026, 6, 20),
        )
        val first = dao.occurrencesForTask(parentId).first { it.operationalDate == LocalDate.of(2026, 5, 20) }

        repository.pushOccurrenceForward(first.id, LocalDate.of(2026, 5, 20))

        val parentRule = dao.ruleForTask(parentId)!!
        val childRule = dao.ruleForTask(childId)!!
        assertEquals(LocalDate.of(2026, 6, 3), parentRule.endDate)
        assertEquals(LocalDate.of(2026, 6, 4), childRule.startDate)
        assertEquals(LocalDate.of(2026, 6, 17), childRule.endDate)
        assertEquals(
            LocalDate.of(2026, 6, 4),
            dao.occurrencesForTask(childId).minOf { it.operationalDate },
        )
    }

    @Test
    fun overdueMaintenanceCanAutoSkipOrAutoPushByTaskSetting() = runTest {
        val skipTaskId = repository.createTaskWithRule(
            task = task(name = "Recovery", noActionBehavior = NoActionBehavior.AUTO_SKIP),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 20),
        )
        val pushTaskId = repository.createTaskWithRule(
            task = task(
                name = "Cardio",
                taskType = TaskType.INTERVAL,
                pushable = true,
                noActionBehavior = NoActionBehavior.AUTO_PUSH,
            ),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            generateThrough = LocalDate.of(2026, 5, 22),
        )

        val handled = repository.markOverduePendingMissed(LocalDate.of(2026, 5, 21))

        assertEquals(2, handled)
        assertEquals(OccurrenceStatus.SKIPPED, dao.occurrencesForTask(skipTaskId).single().status)
        val pushOccurrences = dao.occurrencesForTask(pushTaskId)
        assertTrue(pushOccurrences.any { it.status == OccurrenceStatus.SHIFTED && it.operationalDate == LocalDate.of(2026, 5, 20) })
        assertTrue(pushOccurrences.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 21) })
        assertTrue(dao.logsForTask(skipTaskId).any { it.action == LogAction.SKIPPED && it.note == "Auto-skipped after day reset" })
        assertTrue(dao.logsForTask(pushTaskId).any { it.action == LogAction.SHIFTED_FORWARD && it.note == "Auto-pushed after day reset" })
    }

    @Test
    fun overdueMaintenanceAutoPushesOneTimeTaskUntilCompleted() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(
                name = "Call plumber",
                pushable = true,
                noActionBehavior = NoActionBehavior.AUTO_PUSH,
            ),
            rule = rule(
                ruleType = RuleType.DAILY,
                startDate = LocalDate.of(2026, 5, 20),
            ).copy(
                endDate = LocalDate.of(2026, 5, 20),
                durationDays = 1,
            ),
            generateThrough = LocalDate.of(2026, 5, 20),
        )

        val firstHandled = repository.markOverduePendingMissed(LocalDate.of(2026, 5, 21))

        assertEquals(1, firstHandled)
        assertEquals(LocalDate.of(2026, 5, 21), dao.ruleForTask(taskId)!!.endDate)
        assertTrue(
            dao.occurrencesForTask(taskId).any {
                it.status == OccurrenceStatus.SHIFTED &&
                    it.operationalDate == LocalDate.of(2026, 5, 20)
            },
        )
        assertTrue(
            dao.occurrencesForTask(taskId).any {
                it.status == OccurrenceStatus.PENDING &&
                    it.operationalDate == LocalDate.of(2026, 5, 21)
            },
        )

        val secondHandled = repository.markOverduePendingMissed(LocalDate.of(2026, 5, 22))
        val finalPending = dao.occurrencesForTask(taskId)
            .single {
                it.status == OccurrenceStatus.PENDING &&
                    it.operationalDate == LocalDate.of(2026, 5, 22)
            }
        repository.completeOccurrence(finalPending.id, LocalDate.of(2026, 5, 22))
        val afterCompletionHandled = repository.markOverduePendingMissed(LocalDate.of(2026, 5, 23))

        assertEquals(1, secondHandled)
        assertEquals(0, afterCompletionHandled)
        assertEquals(LocalDate.of(2026, 5, 22), dao.ruleForTask(taskId)!!.endDate)
        assertFalse(dao.occurrencesForTask(taskId).any { it.status == OccurrenceStatus.MISSED })
        assertFalse(dao.occurrencesForTask(taskId).any { it.status == OccurrenceStatus.SKIPPED })
        assertEquals(2, dao.logsForTask(taskId).count { it.action == LogAction.SHIFTED_FORWARD })
        assertEquals(1, dao.logsForTask(taskId).count { it.action == LogAction.COMPLETED })
    }

    @Test
    fun undoRestoresCompletedSkippedAndPushedOccurrencesToPending() = runTest {
        val simpleTaskId = repository.createTaskWithRule(
            task = task(name = "Supplements"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 20),
        )
        val pushTaskId = repository.createTaskWithRule(
            task = task(name = "Cardio", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            generateThrough = LocalDate.of(2026, 5, 22),
        )
        val simpleOccurrence = dao.occurrencesForTask(simpleTaskId).single()
        val pushOccurrence = dao.occurrencesForTask(pushTaskId).first { it.operationalDate == LocalDate.of(2026, 5, 20) }
        repository.completeOccurrence(simpleOccurrence.id, LocalDate.of(2026, 5, 20), "Marked complete")
        repository.pushOccurrenceForward(pushOccurrence.id, LocalDate.of(2026, 5, 20))

        repository.undoOccurrenceDecision(simpleOccurrence.id, LocalDate.of(2026, 5, 20))
        repository.undoOccurrenceDecision(pushOccurrence.id, LocalDate.of(2026, 5, 20))

        assertEquals(OccurrenceStatus.PENDING, dao.occurrenceById(simpleOccurrence.id)!!.status)
        val pushOccurrences = dao.occurrencesForTask(pushTaskId)
        assertEquals(OccurrenceStatus.PENDING, dao.occurrenceById(pushOccurrence.id)!!.status)
        assertFalse(pushOccurrences.any { it.originalDate == LocalDate.of(2026, 5, 20) && it.id != pushOccurrence.id })
        assertTrue(dao.logsForTask(simpleTaskId).any { it.note == "Reset checklist decision" })
        assertTrue(dao.logsForTask(pushTaskId).any { it.note == "Undid push" })
    }

    @Test
    fun repairPendingCadencesRemovesStaleShiftedRowsForEveryTwoDayTask() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Red Light Scalp", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 6, 4),
            ),
            generateThrough = LocalDate.of(2026, 6, 12),
        )
        val ruleId = dao.ruleForTask(taskId)!!.id
        dao.insertOccurrences(
            listOf(
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 6, 5),
                    operationalDate = LocalDate.of(2026, 6, 5),
                    status = OccurrenceStatus.PENDING,
                    isShifted = true,
                    originalDate = LocalDate.of(2026, 6, 4),
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 6, 7),
                    operationalDate = LocalDate.of(2026, 6, 7),
                    status = OccurrenceStatus.PENDING,
                    isShifted = true,
                    originalDate = LocalDate.of(2026, 6, 2),
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )

        val repaired = repository.repairPendingCadences(LocalDate.of(2026, 6, 4))

        val pendingDates = dao.occurrencesForTask(taskId)
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .sorted()
        assertEquals(2, repaired)
        assertEquals(
            listOf(
                LocalDate.of(2026, 6, 4),
                LocalDate.of(2026, 6, 6),
                LocalDate.of(2026, 6, 8),
                LocalDate.of(2026, 6, 10),
                LocalDate.of(2026, 6, 12),
            ),
            pendingDates,
        )
    }

    @Test
    fun repairPendingCadencesKeepsActiveShiftedEveryTwoDayCadence() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Red Light Scalp", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 6, 4),
            ),
            generateThrough = LocalDate.of(2026, 6, 10),
        )
        val first = dao.occurrencesForTask(taskId).first { it.operationalDate == LocalDate.of(2026, 6, 4) }
        dao.updateOccurrence(
            first.copy(
                status = OccurrenceStatus.SHIFTED,
                isShifted = true,
                originalDate = LocalDate.of(2026, 6, 4),
            ),
        )
        dao.insertOccurrences(
            listOf(
                first.copy(
                    id = 0,
                    scheduledDate = LocalDate.of(2026, 6, 5),
                    operationalDate = LocalDate.of(2026, 6, 5),
                    status = OccurrenceStatus.PENDING,
                    isShifted = true,
                    originalDate = LocalDate.of(2026, 6, 4),
                ),
            ),
        )

        repository.repairPendingCadences(LocalDate.of(2026, 6, 4))

        val pendingDates = dao.occurrencesForTask(taskId)
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .sorted()
        assertEquals(
            listOf(
                LocalDate.of(2026, 6, 5),
                LocalDate.of(2026, 6, 7),
                LocalDate.of(2026, 6, 9),
                LocalDate.of(2026, 6, 11),
            ),
            pendingDates,
        )
        assertEquals(OccurrenceStatus.SHIFTED, dao.occurrenceById(first.id)!!.status)
    }

    @Test
    fun repairPendingCadencesDoesNotBackfillOriginalDateBeforeShiftedEveryTwoDayCadence() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(
                name = "Red Light Scalp",
                taskType = TaskType.INTERVAL,
                pushable = true,
                noActionBehavior = NoActionBehavior.AUTO_PUSH,
            ),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 21),
            ),
            generateThrough = LocalDate.of(2026, 6, 21),
        )
        val ruleId = dao.ruleForTask(taskId)!!.id
        val rowsToReplace = dao.occurrencesForTask(taskId)
            .filter { !it.operationalDate.isBefore(LocalDate.of(2026, 6, 14)) }
        dao.deleteOccurrencesByIds(rowsToReplace.map { it.id })
        dao.insertOccurrences(
            listOf(
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 6, 14),
                    operationalDate = LocalDate.of(2026, 6, 14),
                    status = OccurrenceStatus.SHIFTED,
                    isShifted = true,
                    originalDate = LocalDate.of(2026, 6, 14),
                    note = "Pushed forward",
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 6, 15),
                    operationalDate = LocalDate.of(2026, 6, 15),
                    status = OccurrenceStatus.COMPLETED,
                    isShifted = true,
                    originalDate = LocalDate.of(2026, 6, 14),
                    note = "Marked complete",
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 6, 17),
                    operationalDate = LocalDate.of(2026, 6, 17),
                    status = OccurrenceStatus.PENDING,
                    isShifted = true,
                    originalDate = LocalDate.of(2026, 8, 7),
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 6, 16),
                    operationalDate = LocalDate.of(2026, 6, 16),
                    status = OccurrenceStatus.PENDING,
                    isShifted = false,
                    originalDate = null,
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 6, 19),
                    operationalDate = LocalDate.of(2026, 6, 19),
                    status = OccurrenceStatus.PENDING,
                    isShifted = true,
                    originalDate = LocalDate.of(2026, 8, 9),
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 6, 21),
                    operationalDate = LocalDate.of(2026, 6, 21),
                    status = OccurrenceStatus.PENDING,
                    isShifted = true,
                    originalDate = LocalDate.of(2026, 8, 11),
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 8, 7),
                    operationalDate = LocalDate.of(2026, 8, 7),
                    status = OccurrenceStatus.PENDING,
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 8, 9),
                    operationalDate = LocalDate.of(2026, 8, 9),
                    status = OccurrenceStatus.PENDING,
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 8, 11),
                    operationalDate = LocalDate.of(2026, 8, 11),
                    status = OccurrenceStatus.PENDING,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )

        repository.repairPendingCadences(LocalDate.of(2026, 6, 16))

        val pendingDates = dao.occurrencesForTask(taskId)
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .filter { !it.isBefore(LocalDate.of(2026, 6, 16)) }
            .sorted()
        assertEquals(
            listOf(
                LocalDate.of(2026, 6, 17),
                LocalDate.of(2026, 6, 19),
                LocalDate.of(2026, 6, 21),
                LocalDate.of(2026, 6, 23),
                LocalDate.of(2026, 6, 25),
                LocalDate.of(2026, 6, 27),
            ),
            pendingDates.take(6),
        )
        assertFalse(pendingDates.contains(LocalDate.of(2026, 6, 16)))
        assertFalse(pendingDates.contains(LocalDate.of(2026, 6, 22)))
    }

    @Test
    fun repairPendingCadencesRestoresMissingRowsWhenNextPendingDateJumpedToJuneTwentySecond() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(
                name = "Red Light Scalp",
                taskType = TaskType.INTERVAL,
                pushable = true,
                noActionBehavior = NoActionBehavior.AUTO_PUSH,
            ),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 5, 21),
            ),
            generateThrough = LocalDate.of(2026, 6, 30),
        )
        val ruleId = dao.ruleForTask(taskId)!!.id
        val damagedFutureRows = dao.occurrencesForTask(taskId)
            .filter { !it.operationalDate.isBefore(LocalDate.of(2026, 6, 14)) }
        dao.deleteOccurrencesByIds(damagedFutureRows.map { it.id })
        dao.insertOccurrences(
            listOf(
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 6, 14),
                    operationalDate = LocalDate.of(2026, 6, 14),
                    status = OccurrenceStatus.SHIFTED,
                    isShifted = true,
                    originalDate = LocalDate.of(2026, 6, 14),
                    note = "Pushed forward",
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 6, 15),
                    operationalDate = LocalDate.of(2026, 6, 15),
                    status = OccurrenceStatus.COMPLETED,
                    isShifted = true,
                    originalDate = LocalDate.of(2026, 6, 14),
                    note = "Marked complete",
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    taskId = taskId,
                    recurrenceRuleId = ruleId,
                    scheduledDate = LocalDate.of(2026, 6, 22),
                    operationalDate = LocalDate.of(2026, 6, 22),
                    status = OccurrenceStatus.PENDING,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )

        val repaired = repository.repairPendingCadences(LocalDate.of(2026, 6, 16))

        val pendingDates = dao.occurrencesForTask(taskId)
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .filter { !it.isBefore(LocalDate.of(2026, 6, 16)) }
            .sorted()
        assertTrue(repaired > 0)
        assertEquals(
            listOf(
                LocalDate.of(2026, 6, 17),
                LocalDate.of(2026, 6, 19),
                LocalDate.of(2026, 6, 21),
                LocalDate.of(2026, 6, 23),
                LocalDate.of(2026, 6, 25),
                LocalDate.of(2026, 6, 27),
            ),
            pendingDates.take(6),
        )
        assertFalse(pendingDates.contains(LocalDate.of(2026, 6, 16)))
        assertFalse(pendingDates.contains(LocalDate.of(2026, 6, 22)))
    }

    @Test
    fun repairPendingCadencesRegeneratesMissingRowsInsideAdvancedRuleWindow() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Hair Oil", taskType = TaskType.INTERVAL, pushable = true),
            rule = rule(
                ruleType = RuleType.EVERY_X_DAYS,
                intervalDays = 3,
                startDate = LocalDate.of(2026, 5, 21),
                skipBlockedDaysBehavior = SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY,
            ),
            generateThrough = LocalDate.of(2026, 8, 11),
        )
        val rule = dao.ruleForTask(taskId)!!
        val removedFutureRows = dao.occurrencesForTask(taskId)
            .filter { occurrence ->
                !occurrence.operationalDate.isBefore(LocalDate.of(2026, 6, 14)) &&
                    occurrence.operationalDate.isBefore(LocalDate.of(2026, 8, 10))
            }
        dao.deleteOccurrencesByIds(removedFutureRows.map { it.id })

        val repaired = repository.repairPendingCadences(LocalDate.of(2026, 6, 12))

        val futurePendingDates = dao.occurrencesForTask(taskId)
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .filter { !it.isBefore(LocalDate.of(2026, 6, 12)) }
            .sorted()
        assertEquals(removedFutureRows.size, repaired)
        assertEquals(
            listOf(
                LocalDate.of(2026, 6, 14),
                LocalDate.of(2026, 6, 17),
                LocalDate.of(2026, 6, 20),
                LocalDate.of(2026, 6, 23),
            ),
            futurePendingDates.take(4),
        )
        assertEquals(LocalDate.of(2026, 8, 10), futurePendingDates.last())
        assertTrue(futurePendingDates.containsAll(removedFutureRows.map { it.operationalDate }))
        assertEquals(futurePendingDates.toSet().size, futurePendingDates.size)
        assertEquals(rule.lastGeneratedDate, dao.ruleForTask(taskId)!!.lastGeneratedDate)
    }

    @Test
    fun repairPendingCadencesAppliesToMultiDaySequenceRoutines() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE, pushable = true),
            rule = rule(
                ruleType = RuleType.SEQUENCE,
                intervalDays = 2,
                startDate = LocalDate.of(2026, 6, 4),
            ),
            sequenceItems = listOf(sequenceItem("Push", 0), sequenceItem("Pull", 1)),
            generateThrough = LocalDate.of(2026, 6, 10),
        )
        val first = dao.occurrencesForTask(taskId).first { it.operationalDate == LocalDate.of(2026, 6, 4) }
        dao.updateOccurrence(
            first.copy(
                status = OccurrenceStatus.SHIFTED,
                isShifted = true,
                originalDate = LocalDate.of(2026, 6, 4),
            ),
        )
        dao.insertOccurrences(
            listOf(
                first.copy(
                    id = 0,
                    scheduledDate = LocalDate.of(2026, 6, 5),
                    operationalDate = LocalDate.of(2026, 6, 5),
                    status = OccurrenceStatus.PENDING,
                    isShifted = true,
                    originalDate = LocalDate.of(2026, 6, 4),
                ),
            ),
        )

        repository.repairPendingCadences(LocalDate.of(2026, 6, 4))

        val pendingDates = dao.occurrencesForTask(taskId)
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .sorted()
        assertEquals(
            listOf(
                LocalDate.of(2026, 6, 5),
                LocalDate.of(2026, 6, 7),
                LocalDate.of(2026, 6, 9),
                LocalDate.of(2026, 6, 11),
            ),
            pendingDates,
        )
    }

    @Test
    fun repairPendingCadencesLeavesDailyRowsConsecutive() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Supplements", taskType = TaskType.SIMPLE_HABIT),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 6, 4)),
            generateThrough = LocalDate.of(2026, 6, 6),
        )

        val repaired = repository.repairPendingCadences(LocalDate.of(2026, 6, 4))

        val pendingDates = dao.occurrencesForTask(taskId)
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .sorted()
        assertEquals(0, repaired)
        assertEquals(
            listOf(
                LocalDate.of(2026, 6, 4),
                LocalDate.of(2026, 6, 5),
                LocalDate.of(2026, 6, 6),
            ),
            pendingDates,
        )
    }

    @Test
    fun nonPushableTaskIgnoresPushAction() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Supplements", pushable = false),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 21),
        )
        val before = dao.occurrencesForTask(taskId)

        repository.pushOccurrenceForward(before.first().id, LocalDate.of(2026, 5, 20))

        assertEquals(before, dao.occurrencesForTask(taskId))
        assertTrue(dao.logsForTask(taskId).isEmpty())
    }

    @Test
    fun deleteTaskPermanentlyRemovesTaskScheduleSequencesAndLogs() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE, pushable = true),
            rule = rule(ruleType = RuleType.SEQUENCE, startDate = LocalDate.of(2026, 5, 20)),
            sequenceItems = listOf(sequenceItem("Push", 0), sequenceItem("Pull", 1)),
            generateThrough = LocalDate.of(2026, 5, 21),
        )
        val occurrence = dao.occurrencesForTask(taskId).first()
        repository.completeOccurrence(occurrence.id, LocalDate.of(2026, 5, 20), "Done")

        repository.deleteTaskPermanently(taskId)

        assertTrue(dao.taskById(taskId) == null)
        assertTrue(dao.occurrencesForTask(taskId).isEmpty())
        assertTrue(dao.logsForTask(taskId).isEmpty())
        assertTrue(dao.ruleForTask(taskId) == null)
        assertTrue(dao.sequenceForTask(taskId) == null)
    }

    @Test
    fun invalidTaskRuleCombinationsAreRejectedBeforeWriting() = runTest {
        val createError = runCatching {
            repository.createTaskWithRule(
                task = task(name = "Cardio", taskType = TaskType.INTERVAL),
                rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
                generateThrough = LocalDate.of(2026, 5, 20),
            )
        }.exceptionOrNull()

        assertTrue(createError is IllegalArgumentException)
        assertTrue(dao.tasks(includeArchived = true).isEmpty())

        val taskId = repository.createTaskWithRule(
            task = task(name = "Supplements"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 20),
        )

        val editError = runCatching {
            repository.editRuleAndRegenerate(
                task = dao.taskById(taskId)!!.copy(taskType = TaskType.INTERVAL),
                rule = dao.ruleForTask(taskId)!!,
                currentOperationalDate = LocalDate.of(2026, 5, 20),
            )
        }.exceptionOrNull()

        assertTrue(editError is IllegalArgumentException)
        assertEquals(TaskType.SIMPLE_HABIT, dao.taskById(taskId)!!.taskType)
    }

    @Test
    fun sequenceTaskWithoutItemsIsRejectedBeforeWriting() = runTest {
        val error = runCatching {
            repository.createTaskWithRule(
                task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE),
                rule = rule(ruleType = RuleType.SEQUENCE, startDate = LocalDate.of(2026, 5, 20)),
                sequenceItems = emptyList(),
                generateThrough = LocalDate.of(2026, 5, 20),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(dao.tasks(includeArchived = true).isEmpty())
        assertTrue(dao.allSequences().isEmpty())
    }

    @Test
    fun completeAndSkipActionsIgnoreNonPendingOccurrences() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Supplements"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 21),
        )
        val occurrences = dao.occurrencesForTask(taskId).sortedBy { it.operationalDate }
        val completed = occurrences.first()
        val skipped = occurrences.last()
        dao.updateOccurrence(completed.copy(status = OccurrenceStatus.COMPLETED))
        dao.updateOccurrence(skipped.copy(status = OccurrenceStatus.SKIPPED))

        repository.completeOccurrence(completed.id, LocalDate.of(2026, 5, 20), "duplicate complete")
        repository.skipOccurrence(skipped.id, LocalDate.of(2026, 5, 21), "duplicate skip")

        assertEquals(OccurrenceStatus.COMPLETED, dao.occurrenceById(completed.id)!!.status)
        assertEquals(OccurrenceStatus.SKIPPED, dao.occurrenceById(skipped.id)!!.status)
        assertTrue(dao.logsForTask(taskId).isEmpty())
    }

    @Test
    fun sequenceShiftMovesReplacementAndFutureItemsForwardWithoutSameSequenceDuplicateDates() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(
                name = "Workout",
                taskType = TaskType.SEQUENCE_ROUTINE,
                blockedDays = setOf(DayOfWeek.SUNDAY),
            ),
            rule = rule(
                ruleType = RuleType.SEQUENCE,
                startDate = LocalDate.of(2026, 5, 23),
                skipBlockedDaysBehavior = SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY,
            ),
            sequenceItems = listOf(
                sequenceItem("Push", 0),
                sequenceItem("Pull", 1),
                sequenceItem("Legs", 2),
            ),
            generateThrough = LocalDate.of(2026, 5, 28),
        )
        val first = dao.occurrencesForTask(taskId).first { it.operationalDate == LocalDate.of(2026, 5, 23) }

        repository.shiftSequenceForward(first.id, LocalDate.of(2026, 5, 23))

        val afterShift = dao.occurrencesForTask(taskId)
        val pendingDates = afterShift
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
        assertEquals(pendingDates.toSet().size, pendingDates.size)
        assertTrue(afterShift.any { it.id == first.id && it.status == OccurrenceStatus.SHIFTED })
        assertTrue(afterShift.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 25) && it.originalDate == LocalDate.of(2026, 5, 23) })
        assertTrue(afterShift.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 26) && it.originalDate == LocalDate.of(2026, 5, 25) })
        assertEquals(1, dao.logsForTask(taskId).count { it.action == LogAction.SHIFTED_FORWARD })
    }

    @Test
    fun missedSequenceOccurrenceCanStillShiftCurrentAndFutureItemsForward() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE),
            rule = rule(ruleType = RuleType.SEQUENCE, startDate = LocalDate.of(2026, 5, 20)),
            sequenceItems = listOf(sequenceItem("Push", 0), sequenceItem("Pull", 1), sequenceItem("Legs", 2)),
            generateThrough = LocalDate.of(2026, 5, 22),
        )
        val missed = dao.occurrencesForTask(taskId).first { it.operationalDate == LocalDate.of(2026, 5, 20) }
        dao.updateOccurrence(missed.copy(status = OccurrenceStatus.MISSED, note = "Marked missed after rollover"))

        repository.shiftSequenceForward(missed.id, LocalDate.of(2026, 5, 21))

        val afterShift = dao.occurrencesForTask(taskId)
        val shiftedMissed = afterShift.single { it.id == missed.id }
        assertEquals(OccurrenceStatus.MISSED, shiftedMissed.status)
        assertTrue(shiftedMissed.isShifted)
        assertEquals(LocalDate.of(2026, 5, 20), shiftedMissed.originalDate)
        assertTrue(afterShift.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 21) && it.originalDate == LocalDate.of(2026, 5, 20) })
        assertTrue(afterShift.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 22) && it.originalDate == LocalDate.of(2026, 5, 21) })
        assertTrue(afterShift.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 23) && it.originalDate == LocalDate.of(2026, 5, 22) })
        val shiftLog = dao.logsForTask(taskId).single { it.action == LogAction.SHIFTED_FORWARD }
        assertEquals(LocalDate.of(2026, 5, 20), shiftLog.operationalDate)
        val stats = repository.statsForTask(taskId, LocalDate.of(2026, 5, 21))
        assertEquals(1, stats.totalMissed)
        assertEquals(1, stats.totalShifted)
    }

    @Test
    fun sequenceShiftSkipsPreservedHistoryDatesWithoutReplacingLogs() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE),
            rule = rule(ruleType = RuleType.SEQUENCE, startDate = LocalDate.of(2026, 5, 20)),
            sequenceItems = listOf(sequenceItem("Push", 0), sequenceItem("Pull", 1), sequenceItem("Legs", 2)),
            generateThrough = LocalDate.of(2026, 5, 22),
        )
        val occurrences = dao.occurrencesForTask(taskId)
        val missed = occurrences.first { it.operationalDate == LocalDate.of(2026, 5, 20) }
        val completed = occurrences.first { it.operationalDate == LocalDate.of(2026, 5, 21) }
        val future = occurrences.first { it.operationalDate == LocalDate.of(2026, 5, 22) }
        dao.updateOccurrence(missed.copy(status = OccurrenceStatus.MISSED, note = "Missed before shift"))
        dao.updateOccurrence(completed.copy(status = OccurrenceStatus.COMPLETED, note = "Already completed"))
        dao.insertLog(
            CompletionLogEntity(
                taskId = taskId,
                occurrenceId = completed.id,
                action = LogAction.COMPLETED,
                timestamp = now,
                operationalDate = completed.operationalDate,
                note = "Completed before shift",
                createdAt = now,
            ),
        )

        repository.shiftSequenceForward(missed.id, LocalDate.of(2026, 5, 22))

        val afterShift = dao.occurrencesForTask(taskId)
        assertTrue(afterShift.any { it.id == completed.id && it.status == OccurrenceStatus.COMPLETED && it.operationalDate == LocalDate.of(2026, 5, 21) })
        assertTrue(dao.logsForTask(taskId).any { it.occurrenceId == completed.id && it.action == LogAction.COMPLETED })
        assertTrue(afterShift.any { it.status == OccurrenceStatus.PENDING && it.operationalDate == LocalDate.of(2026, 5, 22) && it.originalDate == LocalDate.of(2026, 5, 20) })
        assertEquals(LocalDate.of(2026, 5, 23), afterShift.single { it.id == future.id }.operationalDate)
    }

    @Test
    fun sequenceShiftCanStackWithOtherTasksOnSameDate() = runTest {
        val sequenceTaskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE),
            rule = rule(ruleType = RuleType.SEQUENCE, startDate = LocalDate.of(2026, 5, 20)),
            sequenceItems = listOf(sequenceItem("Push", 0), sequenceItem("Pull", 1)),
            generateThrough = LocalDate.of(2026, 5, 21),
        )
        val otherTaskId = repository.createTaskWithRule(
            task = task(name = "Supplements"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 21)),
            generateThrough = LocalDate.of(2026, 5, 21),
        )
        val firstWorkout = dao.occurrencesForTask(sequenceTaskId).first { it.operationalDate == LocalDate.of(2026, 5, 20) }

        repository.shiftSequenceForward(firstWorkout.id, LocalDate.of(2026, 5, 20))

        val stacked = dao.occurrencesForOperationalDate(LocalDate.of(2026, 5, 21))
        assertTrue(stacked.any { it.taskId == sequenceTaskId && it.status == OccurrenceStatus.PENDING })
        assertTrue(stacked.any { it.taskId == otherTaskId && it.status == OccurrenceStatus.PENDING })
    }

    @Test
    fun sequenceShiftUpdatesGenerationHorizonBeforeNextExtension() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE),
            rule = rule(ruleType = RuleType.SEQUENCE, startDate = LocalDate.of(2026, 5, 20)),
            sequenceItems = listOf(sequenceItem("Push", 0), sequenceItem("Pull", 1), sequenceItem("Legs", 2)),
            generateThrough = LocalDate.of(2026, 5, 22),
        )
        val first = dao.occurrencesForTask(taskId).first { it.operationalDate == LocalDate.of(2026, 5, 20) }

        repository.shiftSequenceForward(first.id, LocalDate.of(2026, 5, 20))
        repository.extendGeneratedOccurrences(
            currentOperationalDate = LocalDate.of(2026, 5, 20),
            daysAhead = 5,
        )

        val occurrences = dao.occurrencesForTask(taskId)
        val uniqueKeys = occurrences.map { it.recurrenceRuleId to it.operationalDate }.toSet()
        assertEquals(uniqueKeys.size, occurrences.size)
        assertEquals(LocalDate.of(2026, 5, 25), dao.ruleForTask(taskId)!!.lastGeneratedDate)
    }

    @Test
    fun shiftingNonSequenceOrCompletedOccurrenceDoesNothing() = runTest {
        val simpleTaskId = repository.createTaskWithRule(
            task = task(name = "Supplements"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 20),
        )
        val sequenceTaskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE),
            rule = rule(ruleType = RuleType.SEQUENCE, startDate = LocalDate.of(2026, 5, 20)),
            sequenceItems = listOf(sequenceItem("Push", 0), sequenceItem("Pull", 1)),
            generateThrough = LocalDate.of(2026, 5, 21),
        )
        val simple = dao.occurrencesForTask(simpleTaskId).single()
        val completedSequence = dao.occurrencesForTask(sequenceTaskId).first { it.operationalDate == LocalDate.of(2026, 5, 20) }
        dao.updateOccurrence(completedSequence.copy(status = OccurrenceStatus.COMPLETED))

        repository.shiftSequenceForward(simple.id, LocalDate.of(2026, 5, 20))
        repository.shiftSequenceForward(completedSequence.id, LocalDate.of(2026, 5, 20))

        assertEquals(listOf(simple), dao.occurrencesForTask(simpleTaskId))
        assertEquals(OccurrenceStatus.COMPLETED, dao.occurrenceById(completedSequence.id)!!.status)
        assertTrue(dao.logsForTask(simpleTaskId).isEmpty())
        assertTrue(dao.logsForTask(sequenceTaskId).isEmpty())
    }

    @Test
    fun shiftingSequenceWithNoValidWeekdayDoesNothing() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE),
            rule = rule(ruleType = RuleType.SEQUENCE, startDate = LocalDate.of(2026, 5, 20)),
            sequenceItems = listOf(sequenceItem("Push", 0), sequenceItem("Pull", 1)),
            generateThrough = LocalDate.of(2026, 5, 21),
        )
        val originalTask = dao.taskById(taskId)!!
        dao.updateTask(originalTask.copy(blockedDays = DayOfWeek.values().toSet()))
        val first = dao.occurrencesForTask(taskId).first { it.operationalDate == LocalDate.of(2026, 5, 20) }
        val before = dao.occurrencesForTask(taskId)

        repository.shiftSequenceForward(first.id, LocalDate.of(2026, 5, 20))

        assertEquals(before, dao.occurrencesForTask(taskId))
        assertTrue(dao.logsForTask(taskId).isEmpty())
    }

    @Test
    fun skippingSequenceOccurrenceDoesNotShiftFutureSchedule() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE),
            rule = rule(ruleType = RuleType.SEQUENCE, startDate = LocalDate.of(2026, 5, 20)),
            sequenceItems = listOf(sequenceItem("Push", 0), sequenceItem("Pull", 1), sequenceItem("Legs", 2)),
            generateThrough = LocalDate.of(2026, 5, 22),
        )
        val before = dao.occurrencesForTask(taskId).sortedBy { it.operationalDate }
        val first = before.first()
        val futureDatesBefore = before.drop(1).map { it.id to it.operationalDate }

        repository.skipOccurrence(first.id, LocalDate.of(2026, 5, 20), "Recovery day")

        val after = dao.occurrencesForTask(taskId)
        assertTrue(after.any { it.id == first.id && it.status == OccurrenceStatus.SKIPPED })
        assertEquals(
            futureDatesBefore,
            after
                .filter { it.id != first.id }
                .sortedBy { it.operationalDate }
                .map { it.id to it.operationalDate },
        )
        assertEquals(1, dao.logsForTask(taskId).count { it.action == LogAction.SKIPPED })
    }

    @Test
    fun editingSequenceCreatesFutureSequenceVersionWithoutBreakingHistoricalItems() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE),
            rule = rule(ruleType = RuleType.SEQUENCE, startDate = LocalDate.of(2026, 5, 20)),
            sequenceItems = listOf(sequenceItem("Push", 0), sequenceItem("Pull", 1)),
            generateThrough = LocalDate.of(2026, 5, 24),
        )
        val historical = dao.occurrencesForTask(taskId).first { it.operationalDate == LocalDate.of(2026, 5, 20) }
        val historicalSequenceItemId = historical.sequenceItemId
        dao.updateOccurrence(historical.copy(status = OccurrenceStatus.COMPLETED))

        repository.editRuleAndRegenerate(
            task = dao.taskById(taskId)!!,
            rule = dao.ruleForTask(taskId)!!,
            sequenceItems = listOf(sequenceItem("Upper", 0), sequenceItem("Lower", 1)),
            currentOperationalDate = LocalDate.of(2026, 5, 20),
            generateThrough = LocalDate.of(2026, 5, 23),
        )

        val allItems = dao.allSequenceItems()
        val latestSequence = dao.sequenceForTask(taskId)!!
        val latestItems = dao.sequenceItems(latestSequence.id).map { it.name }
        val sequenceItemNameById = allItems.associate { it.id to it.name }
        val regeneratedFutureItems = dao.occurrencesForTask(taskId)
            .filter { it.operationalDate.isAfter(LocalDate.of(2026, 5, 20)) }
            .sortedBy { it.operationalDate }
            .map { sequenceItemNameById[it.sequenceItemId] }
        val futureSequenceItemIds = dao.occurrencesForTask(taskId)
            .filter { it.operationalDate.isAfter(LocalDate.of(2026, 5, 20)) }
            .mapNotNull { it.sequenceItemId }
            .toSet()

        assertTrue(allItems.any { it.id == historicalSequenceItemId && it.name == "Push" })
        assertEquals(listOf("Upper", "Lower"), latestItems)
        assertEquals(listOf("Lower", "Upper", "Lower"), regeneratedFutureItems)
        assertTrue(futureSequenceItemIds.all { itemId -> allItems.first { it.id == itemId }.sequenceId == latestSequence.id })
    }

    @Test
    fun extendingSequenceWindowContinuesAfterLastGeneratedSequenceItem() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE),
            rule = rule(ruleType = RuleType.SEQUENCE, startDate = LocalDate.of(2026, 5, 20)),
            sequenceItems = listOf(
                sequenceItem("Push", 0),
                sequenceItem("Pull", 1),
                sequenceItem("Legs", 2),
            ),
            generateThrough = LocalDate.of(2026, 5, 21),
        )

        repository.extendGeneratedOccurrences(
            currentOperationalDate = LocalDate.of(2026, 5, 20),
            daysAhead = 3,
        )

        val sequenceItemNameById = dao.allSequenceItems().associate { it.id to it.name }
        assertEquals(
            listOf("Push", "Pull", "Legs", "Push"),
            dao.occurrencesForTask(taskId)
                .sortedBy { it.operationalDate }
                .map { sequenceItemNameById[it.sequenceItemId] },
        )
        assertEquals(LocalDate.of(2026, 5, 23), dao.ruleForTask(taskId)!!.lastGeneratedDate)
    }

    @Test
    fun swappingSequenceOccurrencesTradesItemsAndNotesWithoutMovingDates() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE),
            rule = rule(
                ruleType = RuleType.SEQUENCE,
                intervalDays = 1,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            sequenceItems = listOf(
                sequenceItem("Full-Body Strength A", 0),
                sequenceItem("Zone 2 Cardio", 1),
                sequenceItem("Upper Hypertrophy A", 2),
            ),
            generateThrough = LocalDate.of(2026, 5, 22),
        )
        val before = dao.occurrencesForTask(taskId).sortedBy { it.operationalDate }
        val first = before[0].copy(note = "Strength note")
        val second = before[1].copy(note = "Cardio note")
        dao.updateOccurrence(first)
        dao.updateOccurrence(second)

        repository.swapSequenceOccurrenceItems(first.id, second.id, LocalDate.of(2026, 5, 20))

        val itemNameById = dao.allSequenceItems().associate { it.id to it.name }
        val after = dao.occurrencesForTask(taskId).sortedBy { it.operationalDate }
        assertEquals(
            listOf(LocalDate.of(2026, 5, 20), LocalDate.of(2026, 5, 21), LocalDate.of(2026, 5, 22)),
            after.map { it.operationalDate },
        )
        assertEquals(
            listOf("Zone 2 Cardio", "Full-Body Strength A", "Upper Hypertrophy A"),
            after.map { itemNameById[it.sequenceItemId] },
        )
        assertEquals(listOf("Cardio note", "Strength note", ""), after.map { it.note })
        assertTrue(
            dao.logsForTask(taskId).any {
                it.action == LogAction.EDITED &&
                    it.occurrenceId == first.id &&
                    it.note == "Swapped sequence item with 2026-05-21"
            },
        )
    }

    @Test
    fun swappingSequenceOccurrenceRejectsPastOrCompletedTarget() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE),
            rule = rule(
                ruleType = RuleType.SEQUENCE,
                intervalDays = 1,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            sequenceItems = listOf(
                sequenceItem("Full-Body Strength A", 0),
                sequenceItem("Zone 2 Cardio", 1),
                sequenceItem("Upper Hypertrophy A", 2),
            ),
            generateThrough = LocalDate.of(2026, 5, 22),
        )
        val before = dao.occurrencesForTask(taskId).sortedBy { it.operationalDate }
        val past = before[0].copy(status = OccurrenceStatus.COMPLETED, note = "Finished strength")
        val current = before[2].copy(note = "Current note")
        dao.updateOccurrence(past)
        dao.updateOccurrence(current)

        repository.swapSequenceOccurrenceItems(current.id, past.id, LocalDate.of(2026, 5, 22))

        val itemNameById = dao.allSequenceItems().associate { it.id to it.name }
        val after = dao.occurrencesForTask(taskId).sortedBy { it.operationalDate }
        assertEquals(
            listOf(LocalDate.of(2026, 5, 20), LocalDate.of(2026, 5, 21), LocalDate.of(2026, 5, 22)),
            after.map { it.operationalDate },
        )
        assertEquals(
            listOf("Full-Body Strength A", "Zone 2 Cardio", "Upper Hypertrophy A"),
            after.map { itemNameById[it.sequenceItemId] },
        )
        assertEquals(
            listOf(OccurrenceStatus.COMPLETED, OccurrenceStatus.PENDING, OccurrenceStatus.PENDING),
            after.map { it.status },
        )
        assertEquals(listOf("Finished strength", "", "Current note"), after.map { it.note })
        assertTrue(dao.logsForTask(taskId).none { it.note == "Swapped sequence item with 2026-05-20" })
    }

    @Test
    fun settingSequenceOccurrencePointCarriesFuturePendingItemsFromSelectedIndex() = runTest {
        val taskId = repository.createTaskWithRule(
            task = task(name = "Workout", taskType = TaskType.SEQUENCE_ROUTINE),
            rule = rule(
                ruleType = RuleType.SEQUENCE,
                intervalDays = 1,
                startDate = LocalDate.of(2026, 5, 20),
            ),
            sequenceItems = listOf(
                sequenceItem("Full-Body Strength A", 0),
                sequenceItem("Zone 2 Cardio", 1),
                sequenceItem("Upper Hypertrophy A", 2),
                sequenceItem("Easy Cardio", 3),
                sequenceItem("Lower Hypertrophy A", 4),
            ),
            generateThrough = LocalDate.of(2026, 5, 23),
        )
        val current = dao.occurrencesForTask(taskId)
            .single { it.operationalDate == LocalDate.of(2026, 5, 20) }

        repository.setSequenceOccurrencePoint(
            occurrenceId = current.id,
            targetSequenceIndex = 3,
            currentOperationalDate = LocalDate.of(2026, 5, 20),
        )

        val itemNameById = dao.allSequenceItems().associate { it.id to it.name }
        val after = dao.occurrencesForTask(taskId).sortedBy { it.operationalDate }
        assertEquals(
            listOf(LocalDate.of(2026, 5, 20), LocalDate.of(2026, 5, 21), LocalDate.of(2026, 5, 22), LocalDate.of(2026, 5, 23)),
            after.map { it.operationalDate },
        )
        assertEquals(
            listOf("Easy Cardio", "Lower Hypertrophy A", "Full-Body Strength A", "Zone 2 Cardio"),
            after.map { itemNameById[it.sequenceItemId] },
        )
        assertTrue(
            dao.logsForTask(taskId).any {
                it.action == LogAction.EDITED &&
                    it.occurrenceId == current.id &&
                    it.note == "Set sequence point to Easy Cardio"
            },
        )
    }

    @Test
    fun extendingScheduleUsesSixtyDayWindowAndSkipsArchivedOrInactiveTasks() = runTest {
        val activeTaskId = repository.createTaskWithRule(
            task = task(name = "Active"),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 20),
        )
        val archivedTaskId = repository.createTaskWithRule(
            task = task(name = "Archived", archived = true, isActive = false),
            rule = rule(ruleType = RuleType.DAILY, startDate = LocalDate.of(2026, 5, 20)),
            generateThrough = LocalDate.of(2026, 5, 20),
        )

        repository.extendGeneratedOccurrences(currentOperationalDate = LocalDate.of(2026, 5, 20))

        assertEquals(61, dao.occurrencesForTask(activeTaskId).size)
        assertEquals(LocalDate.of(2026, 7, 19), dao.ruleForTask(activeTaskId)!!.lastGeneratedDate)
        assertEquals(1, dao.occurrencesForTask(archivedTaskId).size)
        assertEquals(LocalDate.of(2026, 5, 20), dao.ruleForTask(archivedTaskId)!!.lastGeneratedDate)
    }

    private fun task(
        name: String,
        taskType: TaskType = TaskType.SIMPLE_HABIT,
        blockedDays: Set<DayOfWeek> = emptySet(),
        archived: Boolean = false,
        isActive: Boolean = true,
        pushable: Boolean = false,
        noActionBehavior: NoActionBehavior = NoActionBehavior.MARK_MISSED,
    ) = TaskEntity(
        name = name,
        taskType = taskType,
        createdAt = now,
        updatedAt = now,
        blockedDays = blockedDays,
        archived = archived,
        isActive = isActive,
        pushable = pushable,
        noActionBehavior = noActionBehavior,
    )

    private fun rule(
        ruleType: RuleType,
        startDate: LocalDate,
        intervalDays: Int? = null,
        weekdays: Set<DayOfWeek> = emptySet(),
        cycleDefinition: String = "",
        skipBlockedDaysBehavior: SkipBlockedDaysBehavior = SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY,
    ) = RecurrenceRuleEntity(
        taskId = 0,
        ruleType = ruleType,
        intervalDays = intervalDays,
        weekdays = weekdays,
        cycleDefinition = cycleDefinition,
        startDate = startDate,
        skipBlockedDaysBehavior = skipBlockedDaysBehavior,
        createdAt = now,
        updatedAt = now,
    )

    private fun cycleGroup(
        name: String = "Cycle",
        durationDays: Int = 14,
        resetThresholdPercent: Int = 50,
        restartBehavior: CycleRestartBehavior = CycleRestartBehavior.AUTO_RESTART,
        restartTiming: CycleRestartTiming = CycleRestartTiming.TODAY,
        blockedDays: Set<DayOfWeek> = emptySet(),
        currentStartDate: LocalDate = LocalDate.of(2026, 5, 20),
    ) = CycleGroupEntity(
        name = name,
        durationDays = durationDays,
        resetThresholdPercent = resetThresholdPercent,
        restartBehavior = restartBehavior,
        restartTiming = restartTiming,
        blockedDays = blockedDays,
        currentStartDate = currentStartDate,
        createdAt = now,
        updatedAt = now,
    )

    private fun cycleMembership(
        cycleGroupId: Long,
        taskId: Long,
        startOffsetDays: Int = 0,
    ) = CycleTaskMembershipEntity(
        cycleGroupId = cycleGroupId,
        taskId = taskId,
        startOffsetDays = startOffsetDays,
        createdAt = now,
        updatedAt = now,
    )

    private fun sequenceItem(name: String, position: Int) = SequenceItemEntity(
        sequenceId = 0,
        name = name,
        position = position,
    )
}
