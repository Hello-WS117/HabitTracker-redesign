package com.example.habittracker.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.habittracker.backup.BackupRepository
import com.example.habittracker.backup.BackupValidator
import com.example.habittracker.data.HabitRepository
import com.example.habittracker.data.OccurrenceStatus
import com.example.habittracker.data.RuleType
import com.example.habittracker.data.SkipBlockedDaysBehavior
import com.example.habittracker.data.TaskType
import com.example.habittracker.data.local.HabitDatabase
import com.example.habittracker.data.local.RecurrenceRuleEntity
import com.example.habittracker.data.local.ScheduledOccurrenceEntity
import com.example.habittracker.data.local.TaskEntity
import com.example.habittracker.data.settings.AppSettingsRepository
import com.example.habittracker.data.settings.AppSettingsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HabitTrackerUiStorePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = HabitDatabase.get(context)
    private val dao = database.habitDao()
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val now = LocalDateTime.of(2026, 5, 20, 12, 0)

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(mainDispatcher)
        ShadowAlarmManager.reset()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        clearDatabase()
        AppSettingsRepository(context).restore(AppSettingsSnapshot())
    }

    @After
    fun tearDown() = runTest {
        clearDatabase()
        ShadowAlarmManager.reset()
        Dispatchers.resetMain()
    }

    @Test
    fun contextBackedStorePersistsNewTaskAndReloadsGeneratedWindow() = runTest {
        val firstStore = HabitTrackerUiStore(
            appContext = context,
            scope = this,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
        )
        firstStore.reloadFromDatabase()
        val startDate = firstStore.operationalDate
        firstStore.draft = HabitTaskDraft(
            name = "Hydrate",
            type = HabitTaskType.Simple,
            startDate = startDate,
            skipBlockedDaysBehavior = SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY,
            reminderEnabled = false,
        )

        firstStore.saveDraft()?.join()

        val persistedTask = dao.tasks(includeArchived = true).single()
        val persistedOccurrences = dao.occurrencesForTask(persistedTask.id)
        val persistedRule = dao.ruleForTask(persistedTask.id)!!
        assertEquals("Hydrate", persistedTask.name)
        assertEquals(61, persistedOccurrences.size)
        assertEquals(startDate, persistedOccurrences.minOf { it.operationalDate })
        assertEquals(startDate.plusDays(60), persistedOccurrences.maxOf { it.operationalDate })
        assertEquals(SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY, persistedRule.skipBlockedDaysBehavior)
        assertEquals(startDate.plusDays(60), persistedRule.lastGeneratedDate)
        assertTrue(persistedOccurrences.all { it.status == OccurrenceStatus.PENDING })

        val secondStore = HabitTrackerUiStore(
            appContext = context,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
        )
        secondStore.reloadFromDatabase()

        assertEquals("Hydrate", secondStore.tasks.single().name)
        assertEquals(SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY, secondStore.tasks.single().skipBlockedDaysBehavior)
        assertEquals(61, secondStore.occurrencesForTask(persistedTask.id.toInt()).size)
        assertEquals(listOf(startDate), secondStore.todayOccurrences().map { it.operationalDate })
    }

    @Test
    fun contextBackedStorePersistsAllImportedPhasesAndTheirDependencies() = runTest {
        val firstStore = HabitTrackerUiStore(
            appContext = context,
            scope = this,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
        )
        firstStore.reloadFromDatabase()
        val startDate = LocalDate.of(2026, 8, 1)
        val phases = parsePhaseImport(
            """
                Base | 2 weeks | every 2 days | morning | push | - | CO2 work
                Retest | 1 day | once | noon | push | - | Test maximum
                Build | 2 weeks | sequence every 1 day | evening | miss | O2 > CO2 | Rotate
            """.trimIndent(),
        ).phases

        firstStore.importPhases(phases, startDate)?.join()

        val persistedTasks = dao.tasks(includeArchived = true).associateBy { it.name }
        val base = requireNotNull(persistedTasks["Base"])
        val retest = requireNotNull(persistedTasks["Retest"])
        val build = requireNotNull(persistedTasks["Build"])
        assertEquals(base.id, dao.ruleForTask(retest.id)?.startsAfterTaskId)
        assertEquals(retest.id, dao.ruleForTask(build.id)?.startsAfterTaskId)
        assertEquals(TaskType.QUICK_ONE_TIME, retest.taskType)
        assertEquals(TaskType.SEQUENCE_ROUTINE, build.taskType)

        val secondStore = HabitTrackerUiStore(
            appContext = context,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
        )
        secondStore.reloadFromDatabase()
        val loadedBase = secondStore.tasks.single { it.name == "Base" }
        val loadedRetest = secondStore.tasks.single { it.name == "Retest" }
        val loadedBuild = secondStore.tasks.single { it.name == "Build" }
        assertEquals(loadedBase.id, loadedRetest.startsAfterTaskId)
        assertEquals(loadedRetest.id, loadedBuild.startsAfterTaskId)
        assertEquals(startDate.plusDays(14), loadedRetest.startDate)
        assertEquals(startDate.plusDays(15), loadedBuild.startDate)
        assertEquals(listOf("O2", "CO2"), loadedBuild.sequenceItems)
    }

    @Test
    fun achillesImportCanAdvanceAndCreateACompleteBackup() = runTest {
        val source = requireNotNull(
            javaClass.getResource("/phase-import/achilles-rehab-ai-output.txt"),
        ).readText()
        val parsed = parsePhaseImport(source)
        assertTrue(parsed.issues.isEmpty())
        val startDate = LocalDate.of(2026, 8, 1)
        val store = HabitTrackerUiStore(
            appContext = context,
            scope = this,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
        )
        store.reloadFromDatabase()

        store.importPhases(parsed.phases, startDate)?.join()
        val firstPhase = dao.allRoutinePhases().minBy { it.position }
        val advanceDate = startDate.plusDays(firstPhase.minimumDays.toLong())
        assertTrue(HabitRepository(database).advanceRoutinePhase(firstPhase.id, advanceDate))

        val backup = BackupRepository(context = context, database = database).createBackup()
        assertNull(BackupValidator.validate(backup))
        assertEquals(4, backup.routinePhases.size)
        assertTrue(backup.sequenceExercises.isNotEmpty())
        val firstRule = backup.recurrenceRules.single { it.taskId == firstPhase.taskId }
        val firstEndDate = LocalDate.parse(requireNotNull(firstRule.endDate))
        assertTrue(
            backup.scheduledOccurrences.none {
                it.taskId == firstPhase.taskId &&
                    it.status == OccurrenceStatus.PENDING.name &&
                    LocalDate.parse(it.operationalDate).isAfter(firstEndDate)
            },
        )
    }

    @Test
    fun contextBackedStorePersistsExactAlarmPromptShown() = runTest {
        val settingsRepository = AppSettingsRepository(context)
        val store = HabitTrackerUiStore(
            appContext = context,
            scope = this,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
        )
        advanceUntilIdle()
        assertEquals(false, store.settings.exactAlarmPermissionPromptShown)

        store.markExactAlarmPromptShown()?.join()
        store.reloadSettings()

        assertTrue(store.settings.exactAlarmPermissionPromptShown)
        assertTrue(settingsRepository.settings.first().exactAlarmPermissionPromptShown)
    }

    @Test
    fun contextBackedStoreShowsExactAlarmSchedulingStatusForDeniedAndGrantedPermission() = runTest {
        val store = HabitTrackerUiStore(
            appContext = context,
            scope = this,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
        )
        advanceUntilIdle()

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        store.updateSettings { it.copy(dailyReviewEnabled = true, lateReminderEnabled = true) }?.join()

        assertEquals("Reminders scheduled; exact alarm permission improves timing", store.reminderScheduleStatus)

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        store.rescheduleReminders()?.join()

        assertEquals("Exact reminders scheduled", store.reminderScheduleStatus)
    }

    @Test
    fun contextBackedStoreDoesNotRequireExactAlarmPermissionWhenRemindersAreDisabled() = runTest {
        val store = HabitTrackerUiStore(
            appContext = context,
            scope = this,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
        )
        store.startupJob?.join()

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        store.updateSettings {
            it.copy(dailyReviewEnabled = false, lateReminderEnabled = false, taskTimeReminderEnabled = false)
        }?.join()

        assertEquals("Reminders disabled", store.reminderScheduleStatus)
    }

    @Test
    fun contextBackedStoreLoadsPersistedRolloverBeforeStartupMaintenance() = runTest {
        AppSettingsRepository(context).restore(
            AppSettingsSnapshot(dayRolloverTime = LocalTime.of(5, 0)),
        )
        val createdAt = LocalDateTime.of(2026, 5, 19, 12, 0)
        dao.restoreTasks(
            listOf(
                TaskEntity(
                    id = 1,
                    name = "Hydrate",
                    taskType = TaskType.SIMPLE_HABIT,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            ),
        )
        dao.restoreRules(
            listOf(
                RecurrenceRuleEntity(
                    id = 1,
                    taskId = 1,
                    ruleType = RuleType.DAILY,
                    startDate = LocalDate.of(2026, 5, 19),
                    lastGeneratedDate = LocalDate.of(2026, 5, 20),
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            ),
        )
        dao.restoreOccurrences(
            listOf(
                ScheduledOccurrenceEntity(
                    id = 1,
                    taskId = 1,
                    recurrenceRuleId = 1,
                    scheduledDate = LocalDate.of(2026, 5, 19),
                    operationalDate = LocalDate.of(2026, 5, 19),
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
                ScheduledOccurrenceEntity(
                    id = 2,
                    taskId = 1,
                    recurrenceRuleId = 1,
                    scheduledDate = LocalDate.of(2026, 5, 20),
                    operationalDate = LocalDate.of(2026, 5, 20),
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            ),
        )

        val store = HabitTrackerUiStore(
            appContext = context,
            scope = this,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
            nowProvider = { LocalDateTime.of(2026, 5, 20, 4, 0) },
        )
        store.startupJob?.join()

        assertEquals(LocalDate.of(2026, 5, 19), store.operationalDate)
        assertEquals(OccurrenceStatus.PENDING, dao.occurrenceById(1)!!.status)
        assertEquals(HabitStatus.Pending, store.todayOccurrences().single { it.id == 1 }.status)
        assertTrue(dao.logsForTask(1).isEmpty())
    }

    @Test
    fun contextBackedStoreEnqueuesMaintenanceWithPersistedAndUpdatedRollover() = runTest {
        AppSettingsRepository(context).restore(
            AppSettingsSnapshot(dayRolloverTime = LocalTime.of(4, 30)),
        )
        val enqueuedRolloverTimes = mutableListOf<LocalTime>()
        val store = HabitTrackerUiStore(
            appContext = context,
            scope = this,
            enqueueMaintenance = { _, rolloverTime -> enqueuedRolloverTimes += rolloverTime },
            configureAutoBackup = { _, _ -> },
            nowProvider = { now },
        )
        store.startupJob?.join()

        store.updateSettings { it.copy(dayRolloverTime = LocalTime.of(5, 15)) }?.join()

        assertEquals(
            listOf(LocalTime.of(4, 30), LocalTime.of(5, 15)),
            enqueuedRolloverTimes,
        )
        assertEquals(LocalTime.of(5, 15), AppSettingsRepository(context).settings.first().dayRolloverTime)
    }

    @Test
    fun contextBackedStoreReloadAfterRestoreUsesRestoredRolloverBeforeMaintenance() = runTest {
        val store = HabitTrackerUiStore(
            appContext = context,
            scope = this,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
            nowProvider = { LocalDateTime.of(2026, 5, 20, 4, 0) },
        )
        store.startupJob?.join()
        AppSettingsRepository(context).restore(
            AppSettingsSnapshot(dayRolloverTime = LocalTime.of(5, 0)),
        )
        val createdAt = LocalDateTime.of(2026, 5, 19, 12, 0)
        dao.restoreTasks(
            listOf(
                TaskEntity(
                    id = 1,
                    name = "Hydrate",
                    taskType = TaskType.SIMPLE_HABIT,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            ),
        )
        dao.restoreRules(
            listOf(
                RecurrenceRuleEntity(
                    id = 1,
                    taskId = 1,
                    ruleType = RuleType.DAILY,
                    startDate = LocalDate.of(2026, 5, 19),
                    lastGeneratedDate = LocalDate.of(2026, 5, 20),
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            ),
        )
        dao.restoreOccurrences(
            listOf(
                ScheduledOccurrenceEntity(
                    id = 1,
                    taskId = 1,
                    recurrenceRuleId = 1,
                    scheduledDate = LocalDate.of(2026, 5, 19),
                    operationalDate = LocalDate.of(2026, 5, 19),
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
                ScheduledOccurrenceEntity(
                    id = 2,
                    taskId = 1,
                    recurrenceRuleId = 1,
                    scheduledDate = LocalDate.of(2026, 5, 20),
                    operationalDate = LocalDate.of(2026, 5, 20),
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            ),
        )

        store.reloadAfterRestore()

        assertEquals(LocalDate.of(2026, 5, 19), store.operationalDate)
        assertEquals(OccurrenceStatus.PENDING, dao.occurrenceById(1)!!.status)
        assertEquals(HabitStatus.Pending, store.todayOccurrences().single { it.id == 1 }.status)
        assertTrue(dao.logsForTask(1).isEmpty())
    }

    @Test
    fun contextBackedStorePersistsCompleteAndSkipActionsAfterReload() = runTest {
        val firstStore = HabitTrackerUiStore(
            appContext = context,
            scope = this,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
            nowProvider = { now },
        )
        advanceUntilIdle()
        firstStore.draft = HabitTaskDraft(
            name = "Hydrate",
            type = HabitTaskType.Simple,
            startDate = LocalDate.of(2026, 5, 20),
        )
        firstStore.saveDraft()?.join()
        firstStore.reloadFromDatabase()
        val taskId = dao.tasks(includeArchived = true).single().id.toInt()
        val todayOccurrence = firstStore.occurrencesForTask(taskId).single {
            it.operationalDate == LocalDate.of(2026, 5, 20)
        }
        val tomorrowOccurrence = firstStore.occurrencesForTask(taskId).single {
            it.operationalDate == LocalDate.of(2026, 5, 21)
        }

        firstStore.completeOccurrence(todayOccurrence.id)?.join()
        firstStore.skipOccurrence(tomorrowOccurrence.id)?.join()

        val secondStore = HabitTrackerUiStore(
            appContext = context,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
            nowProvider = { now },
        )
        secondStore.reloadFromDatabase()
        val reloadedByDate = secondStore.occurrencesForTask(taskId).associateBy { it.operationalDate }
        val logs = secondStore.recentLogsForTask(taskId)

        assertEquals(HabitStatus.Completed, reloadedByDate.getValue(LocalDate.of(2026, 5, 20)).status)
        assertEquals("Marked complete", reloadedByDate.getValue(LocalDate.of(2026, 5, 20)).note)
        assertEquals(HabitStatus.Skipped, reloadedByDate.getValue(LocalDate.of(2026, 5, 21)).status)
        assertEquals("Skipped intentionally", reloadedByDate.getValue(LocalDate.of(2026, 5, 21)).note)
        assertTrue(logs.any { it.action == HabitLogAction.Completed && it.note == "Marked complete" })
        assertTrue(logs.any { it.action == HabitLogAction.Skipped && it.note == "Skipped intentionally" })
    }

    @Test
    fun contextBackedStorePersistsSequenceShiftAfterReload() = runTest {
        val firstStore = HabitTrackerUiStore(
            appContext = context,
            scope = this,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
            nowProvider = { now },
        )
        advanceUntilIdle()
        firstStore.draft = HabitTaskDraft(
            name = "Workout",
            type = HabitTaskType.Sequence,
            startDate = LocalDate.of(2026, 5, 20),
            sequenceText = "Push, Pull",
        )
        firstStore.saveDraft()?.join()
        firstStore.reloadFromDatabase()
        val taskId = dao.tasks(includeArchived = true).single().id.toInt()
        val firstOccurrence = firstStore.occurrencesForTask(taskId).single {
            it.operationalDate == LocalDate.of(2026, 5, 20)
        }

        firstStore.shiftOccurrenceForward(firstOccurrence.id)?.join()

        val secondStore = HabitTrackerUiStore(
            appContext = context,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
            nowProvider = { now },
        )
        secondStore.reloadFromDatabase()
        val shiftedOriginal = secondStore.occurrencesForTask(taskId).single {
            it.id == firstOccurrence.id
        }
        val replacement = secondStore.occurrencesForTask(taskId).single {
            it.originalDate == LocalDate.of(2026, 5, 20) && it.status == HabitStatus.Pending
        }
        val logs = secondStore.recentLogsForTask(taskId)

        assertEquals(HabitStatus.Shifted, shiftedOriginal.status)
        assertEquals(LocalDate.of(2026, 5, 20), shiftedOriginal.originalDate)
        assertEquals("Pushed forward", shiftedOriginal.note)
        assertEquals(LocalDate.of(2026, 5, 21), replacement.operationalDate)
        assertEquals("Push", replacement.sequenceItemName)
        assertTrue(replacement.isShifted)
        assertTrue(logs.any { it.action == HabitLogAction.ShiftedForward })
    }

    @Test
    fun contextBackedArchivePersistsWithoutDeletingHistoryOrStats() = runTest {
        val firstStore = HabitTrackerUiStore(
            appContext = context,
            scope = this,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
            nowProvider = { now },
        )
        advanceUntilIdle()
        firstStore.draft = HabitTaskDraft(
            name = "Mobility",
            type = HabitTaskType.Simple,
            startDate = LocalDate.of(2026, 5, 19),
        )
        firstStore.saveDraft()?.join()
        firstStore.reloadFromDatabase()
        val taskId = dao.tasks(includeArchived = true).single().id
        val completedOccurrence = dao.occurrencesForTask(taskId).single {
            it.operationalDate == LocalDate.of(2026, 5, 19)
        }
        dao.updateOccurrence(completedOccurrence.copy(status = OccurrenceStatus.COMPLETED))

        firstStore.archiveTask(taskId.toInt(), archived = true)?.join()

        val secondStore = HabitTrackerUiStore(
            appContext = context,
            enqueueMaintenance = { _, _ -> },
            configureAutoBackup = { _, _ -> },
            nowProvider = { now },
        )
        secondStore.reloadFromDatabase()

        val reloadedTask = secondStore.visibleTasks(includeArchived = true).single()
        val stats = secondStore.statsFor(taskId.toInt())
        assertTrue(secondStore.visibleTasks().isEmpty())
        assertEquals("Mobility", reloadedTask.name)
        assertTrue(reloadedTask.archived)
        assertEquals(false, reloadedTask.isActive)
        assertTrue(secondStore.occurrencesForTask(taskId.toInt()).isNotEmpty())
        assertEquals(1, stats.completed)
        assertEquals(100, stats.completionPercentage)
        assertTrue(secondStore.recentLogsForTask(taskId.toInt()).any { it.note == "Archived task" })
    }

    private suspend fun clearDatabase() {
        dao.deleteAllCycleLogs()
        dao.deleteAllLogs()
        dao.deleteAllOccurrenceExerciseChecks()
        dao.deleteAllOccurrences()
        dao.deleteAllSequenceExercises()
        dao.deleteAllSequenceItems()
        dao.deleteAllSequences()
        dao.deleteAllRoutinePhases()
        dao.deleteAllRoutinePlans()
        dao.deleteAllCycleMemberships()
        dao.deleteAllCycleGroups()
        dao.deleteAllRules()
        dao.deleteAllTasks()
    }
}
