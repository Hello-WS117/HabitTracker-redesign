package com.example.habittracker.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.habittracker.data.CycleRestartBehavior
import com.example.habittracker.data.CycleRestartTiming
import com.example.habittracker.data.OccurrenceStatus
import com.example.habittracker.data.RuleType
import com.example.habittracker.data.SkipBlockedDaysBehavior
import com.example.habittracker.data.TaskType
import com.example.habittracker.data.local.CycleGroupEntity
import com.example.habittracker.data.local.CycleTaskMembershipEntity
import com.example.habittracker.data.local.HabitDatabase
import com.example.habittracker.data.local.RecurrenceRuleEntity
import com.example.habittracker.data.local.ScheduledOccurrenceEntity
import com.example.habittracker.data.local.TaskEntity
import com.example.habittracker.data.scheduling.OperationalDayCalculator
import com.example.habittracker.data.settings.AppSettingsRepository
import com.example.habittracker.data.settings.AppSettingsSnapshot
import androidx.work.ListenableWorker
import androidx.work.ExistingPeriodicWorkPolicy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScheduleMaintenanceHandlerTest {
    private lateinit var context: Context
    private lateinit var database: HabitDatabase
    private lateinit var settingsRepository: AppSettingsRepository

    private val dao get() = database.habitDao()

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = HabitDatabase.get(context)
        settingsRepository = AppSettingsRepository(context)
        settingsRepository.restore(AppSettingsSnapshot(dayRolloverTime = LocalTime.of(3, 0)))
        clearDatabase()
    }

    @After
    fun tearDown() = runTest {
        clearDatabase()
    }

    @Test
    fun maintenanceMarksOverduePendingMissedAndExtendsSchedule() = runTest {
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        val now = LocalDateTime.now()
        dao.restoreTasks(
            listOf(
                TaskEntity(
                    id = 1,
                    name = "Daily task",
                    taskType = TaskType.SIMPLE_HABIT,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        dao.restoreRules(
            listOf(
                RecurrenceRuleEntity(
                    id = 1,
                    taskId = 1,
                    ruleType = RuleType.DAILY,
                    startDate = operationalDate.minusDays(2),
                    skipBlockedDaysBehavior = SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY,
                    lastGeneratedDate = operationalDate.minusDays(1),
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        dao.restoreOccurrences(
            listOf(
                ScheduledOccurrenceEntity(
                    id = 1,
                    taskId = 1,
                    recurrenceRuleId = 1,
                    scheduledDate = operationalDate.minusDays(1),
                    operationalDate = operationalDate.minusDays(1),
                    status = OccurrenceStatus.PENDING,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )

        val result = ScheduleMaintenanceHandler.run(context)

        val occurrences = dao.occurrencesForTask(1)
        assertEquals(1, result.missedMarked)
        assertEquals(OccurrenceStatus.MISSED, dao.occurrenceById(1)!!.status)
        assertTrue(occurrences.any { it.operationalDate == operationalDate && it.status == OccurrenceStatus.PENDING })
        assertEquals(operationalDate.plusDays(60), dao.ruleById(1)!!.lastGeneratedDate)
    }

    @Test
    fun maintenanceRepairsStaleIntervalPendingCadence() = runTest {
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        val now = LocalDateTime.now()
        dao.restoreTasks(
            listOf(
                TaskEntity(
                    id = 2,
                    name = "Red Light Scalp",
                    taskType = TaskType.INTERVAL,
                    pushable = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        dao.restoreRules(
            listOf(
                RecurrenceRuleEntity(
                    id = 2,
                    taskId = 2,
                    ruleType = RuleType.EVERY_X_DAYS,
                    intervalDays = 2,
                    startDate = operationalDate,
                    skipBlockedDaysBehavior = SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY,
                    lastGeneratedDate = operationalDate.plusDays(4),
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        dao.restoreOccurrences(
            listOf(
                ScheduledOccurrenceEntity(
                    id = 20,
                    taskId = 2,
                    recurrenceRuleId = 2,
                    scheduledDate = operationalDate,
                    operationalDate = operationalDate,
                    status = OccurrenceStatus.PENDING,
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    id = 21,
                    taskId = 2,
                    recurrenceRuleId = 2,
                    scheduledDate = operationalDate.plusDays(1),
                    operationalDate = operationalDate.plusDays(1),
                    status = OccurrenceStatus.PENDING,
                    isShifted = true,
                    originalDate = operationalDate,
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    id = 22,
                    taskId = 2,
                    recurrenceRuleId = 2,
                    scheduledDate = operationalDate.plusDays(2),
                    operationalDate = operationalDate.plusDays(2),
                    status = OccurrenceStatus.PENDING,
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    id = 23,
                    taskId = 2,
                    recurrenceRuleId = 2,
                    scheduledDate = operationalDate.plusDays(3),
                    operationalDate = operationalDate.plusDays(3),
                    status = OccurrenceStatus.PENDING,
                    isShifted = true,
                    originalDate = operationalDate.minusDays(2),
                    createdAt = now,
                    updatedAt = now,
                ),
                ScheduledOccurrenceEntity(
                    id = 24,
                    taskId = 2,
                    recurrenceRuleId = 2,
                    scheduledDate = operationalDate.plusDays(4),
                    operationalDate = operationalDate.plusDays(4),
                    status = OccurrenceStatus.PENDING,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )

        val result = ScheduleMaintenanceHandler.run(context)

        val firstPendingDates = dao.occurrencesForTask(2)
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .sorted()
            .take(4)
        assertEquals(2, result.cadenceRepairs)
        assertEquals(
            listOf(
                operationalDate,
                operationalDate.plusDays(2),
                operationalDate.plusDays(4),
                operationalDate.plusDays(6),
            ),
            firstPendingDates,
        )
    }

    @Test
    fun maintenanceRestartsEndedAutoRestartCycleTimingTask() = runTest {
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        val startDate = operationalDate.minusDays(14)
        val now = LocalDateTime.now()
        dao.restoreTasks(
            listOf(
                TaskEntity(
                    id = 3,
                    name = "CO2 tables",
                    taskType = TaskType.INTERVAL,
                    pushable = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        dao.restoreRules(
            listOf(
                RecurrenceRuleEntity(
                    id = 3,
                    taskId = 3,
                    ruleType = RuleType.EVERY_X_DAYS,
                    intervalDays = 2,
                    startDate = startDate,
                    endDate = operationalDate.minusDays(1),
                    durationDays = 14,
                    skipBlockedDaysBehavior = SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY,
                    lastGeneratedDate = operationalDate.minusDays(1),
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        dao.restoreCycleGroups(
            listOf(
                CycleGroupEntity(
                    id = 3,
                    name = "CO2 tables auto restart",
                    durationDays = 14,
                    resetThresholdPercent = 50,
                    restartBehavior = CycleRestartBehavior.AUTO_RESTART,
                    restartTiming = CycleRestartTiming.TODAY,
                    blockedDays = emptySet(),
                    currentStartDate = startDate,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        dao.restoreCycleMemberships(
            listOf(
                CycleTaskMembershipEntity(
                    id = 3,
                    cycleGroupId = 3,
                    taskId = 3,
                    startOffsetDays = 0,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )

        val result = ScheduleMaintenanceHandler.run(context)

        val pendingDates = dao.occurrencesForTask(3)
            .filter { it.status == OccurrenceStatus.PENDING }
            .map { it.operationalDate }
            .sorted()
        assertEquals(1, result.cycleRestarts)
        assertEquals(operationalDate, pendingDates.first())
        assertEquals(operationalDate, dao.ruleById(3)!!.startDate)
        assertEquals(operationalDate.plusDays(13), dao.ruleById(3)!!.endDate)
    }

    @Test
    fun maintenanceDelayRunsShortlyAfterNextRollover() {
        assertEquals(
            Duration.ofMinutes(65).toMillis(),
            MaintenanceScheduler.delayUntilNextRolloverMaintenance(
                rolloverTime = LocalTime.of(3, 0),
                now = LocalDateTime.of(2026, 5, 20, 2, 0),
            ),
        )
        assertEquals(
            Duration.ofHours(23).plusMinutes(59).toMillis(),
            MaintenanceScheduler.delayUntilNextRolloverMaintenance(
                rolloverTime = LocalTime.of(3, 0),
                now = LocalDateTime.of(2026, 5, 20, 3, 6),
            ),
        )
    }

    @Test
    fun maintenanceRequestUsesUniquePeriodicUpdateContract() {
        val request = MaintenanceScheduler.buildRequest(
            rolloverTime = LocalTime.of(3, 0),
            now = LocalDateTime.of(2026, 5, 20, 2, 0),
        )
        val workSpec = request.workSpec

        assertEquals("habit_schedule_maintenance", MaintenanceScheduler.WORK_NAME)
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, MaintenanceScheduler.EXISTING_WORK_POLICY)
        assertEquals(ScheduleMaintenanceWorker::class.java.name, workSpec.workerClassName)
        assertEquals(Duration.ofHours(6).toMillis(), workSpec.intervalDuration)
        assertEquals(Duration.ofMinutes(65).toMillis(), workSpec.initialDelay)
        assertTrue(workSpec.isPeriodic)
    }

    @Test
    fun autoBackupRequestUsesConfiguredIntervalAndLastRunDelay() {
        val request = AutoBackupScheduler.buildRequest(
            settings = AppSettingsSnapshot(
                autoBackupEnabled = true,
                autoBackupIntervalDays = 3,
                autoBackupFolderUri = "content://tree/backups",
                autoBackupLastRunAt = "2026-05-20T08:00:00",
            ),
            now = LocalDateTime.of(2026, 5, 21, 8, 0),
        )
        val workSpec = request.workSpec

        assertEquals("habit_auto_backup", AutoBackupScheduler.WORK_NAME)
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, AutoBackupScheduler.EXISTING_WORK_POLICY)
        assertEquals(AutoBackupWorker::class.java.name, workSpec.workerClassName)
        assertEquals(Duration.ofDays(3).toMillis(), workSpec.intervalDuration)
        assertEquals(Duration.ofDays(2).toMillis(), workSpec.initialDelay)
        assertTrue(workSpec.isPeriodic)
    }

    @Test
    fun autoBackupDelayIsImmediateWhenNoPreviousRunExists() {
        val delay = AutoBackupScheduler.delayUntilNextAutoBackup(
            settings = AppSettingsSnapshot(
                autoBackupEnabled = true,
                autoBackupIntervalDays = 7,
                autoBackupFolderUri = "content://tree/backups",
            ),
            now = LocalDateTime.of(2026, 5, 21, 8, 0),
        )

        assertEquals(0, delay)
    }

    @Test
    fun workerRunnerReportsSuccessWhenMaintenanceCompletes() = runTest {
        val result = ScheduleMaintenanceRunner.run { }

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun workerRunnerRetriesWhenMaintenanceFails() = runTest {
        val result = ScheduleMaintenanceRunner.run {
            error("database temporarily unavailable")
        }

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    private suspend fun clearDatabase() {
        dao.deleteAllLogs()
        dao.deleteAllCycleLogs()
        dao.deleteAllOccurrences()
        dao.deleteAllSequenceItems()
        dao.deleteAllSequences()
        dao.deleteAllCycleMemberships()
        dao.deleteAllCycleGroups()
        dao.deleteAllRules()
        dao.deleteAllTasks()
    }
}
