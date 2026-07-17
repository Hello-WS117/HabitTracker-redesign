package com.example.habittracker.backup

import android.content.Context
import android.app.AlarmManager
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.habittracker.data.LogAction
import com.example.habittracker.data.OccurrenceStatus
import com.example.habittracker.data.RuleType
import com.example.habittracker.data.SkipBlockedDaysBehavior
import com.example.habittracker.data.TaskType
import com.example.habittracker.data.local.CompletionLogEntity
import com.example.habittracker.data.local.HabitDatabase
import com.example.habittracker.data.local.RecurrenceRuleEntity
import com.example.habittracker.data.local.ScheduledOccurrenceEntity
import com.example.habittracker.data.local.SequenceItemEntity
import com.example.habittracker.data.local.TaskEntity
import com.example.habittracker.data.local.WorkoutSequenceEntity
import com.example.habittracker.data.settings.AppSettingsRepository
import com.example.habittracker.data.settings.AppSettingsSnapshot
import com.example.habittracker.reminders.HabitReminderReceiver
import com.example.habittracker.reminders.ReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRepositoryTest {
    private lateinit var database: HabitDatabase
    private lateinit var settingsRepository: AppSettingsRepository
    private lateinit var repository: BackupRepository

    private val dao get() = database.habitDao()
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private val now = LocalDateTime.of(2026, 5, 20, 12, 0)

    @Before
    fun setUp() {
        ShadowAlarmManager.reset()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.noBackupFilesDir, "backup-safety").deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, HabitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsRepository = AppSettingsRepository(context)
        repository = BackupRepository(
            context = context,
            database = database,
            settingsRepository = settingsRepository,
            json = json,
            verificationRetryDelaysMillis = emptyList(),
        )
    }

    @After
    fun tearDown() {
        database.close()
        ShadowAlarmManager.reset()
    }

    @Test
    fun invalidRestoreLeavesExistingDatabaseAndSettingsUnchanged() = runTest {
        settingsRepository.restore(AppSettingsSnapshot(themePreference = "dark"))
        seedExistingDatabase()
        val invalidBackup = validBackup().copy(
            recurrenceRules = listOf(validRule(taskId = 999)),
        )

        val result = repository.restoreFromJson(json.encodeToString(invalidBackup))

        assertTrue(result.isFailure)
        assertEquals(listOf("Existing task"), dao.tasks(includeArchived = true).map { it.name })
        assertEquals(listOf(10L), dao.allRules().map { it.id })
        assertEquals(listOf(10L), dao.allOccurrences().map { it.id })
        assertEquals("dark", settingsRepository.settings.first().themePreference)
    }

    @Test
    fun invalidJsonRestoreLeavesExistingDatabaseAndSettingsUnchanged() = runTest {
        settingsRepository.restore(AppSettingsSnapshot(themePreference = "dark"))
        seedExistingDatabase()

        val result = repository.restoreFromJson("{not valid json")

        assertTrue(result.isFailure)
        assertEquals(listOf("Existing task"), dao.tasks(includeArchived = true).map { it.name })
        assertEquals(listOf(10L), dao.allRules().map { it.id })
        assertEquals(listOf(10L), dao.allOccurrences().map { it.id })
        assertEquals("dark", settingsRepository.settings.first().themePreference)
    }

    @Test
    fun unsupportedSchemaRestoreLeavesExistingDatabaseAndSettingsUnchanged() = runTest {
        settingsRepository.restore(AppSettingsSnapshot(themePreference = "dark"))
        seedExistingDatabase()
        val unsupportedBackup = validBackup().copy(schemaVersion = 99)

        val result = repository.restoreFromJson(json.encodeToString(unsupportedBackup))

        assertTrue(result.isFailure)
        assertEquals("Unsupported backup schema 99", result.exceptionOrNull()?.message)
        assertEquals(listOf("Existing task"), dao.tasks(includeArchived = true).map { it.name })
        assertEquals(listOf(10L), dao.allRules().map { it.id })
        assertEquals(listOf(10L), dao.allOccurrences().map { it.id })
        assertEquals("dark", settingsRepository.settings.first().themePreference)
    }

    @Test
    fun restoreWithDanglingSequenceItemReferenceFailsBeforeOverwrite() = runTest {
        settingsRepository.restore(AppSettingsSnapshot(themePreference = "dark"))
        seedExistingDatabase()
        val backup = validBackup().copy(
            scheduledOccurrences = listOf(validOccurrence(sequenceItemId = 999)),
        )

        val result = repository.restoreFromJson(json.encodeToString(backup))

        assertTrue(result.isFailure)
        assertEquals("Backup contains an occurrence with an unknown sequence item", result.exceptionOrNull()?.message)
        assertEquals(listOf("Existing task"), dao.tasks(includeArchived = true).map { it.name })
        assertEquals(listOf(10L), dao.allOccurrences().map { it.id })
        assertEquals(listOf(10L), dao.allLogs().map { it.id })
        assertEquals("dark", settingsRepository.settings.first().themePreference)
    }

    @Test
    fun restoreWithDanglingLogOccurrenceReferenceFailsBeforeOverwrite() = runTest {
        settingsRepository.restore(AppSettingsSnapshot(themePreference = "dark"))
        seedExistingDatabase()
        val backup = validBackup().copy(
            completionLogs = listOf(validLog(occurrenceId = 999)),
        )

        val result = repository.restoreFromJson(json.encodeToString(backup))

        assertTrue(result.isFailure)
        assertEquals("Backup contains a log without an occurrence", result.exceptionOrNull()?.message)
        assertEquals(listOf("Existing task"), dao.tasks(includeArchived = true).map { it.name })
        assertEquals(listOf(10L), dao.allOccurrences().map { it.id })
        assertEquals(listOf(10L), dao.allLogs().map { it.id })
        assertEquals("dark", settingsRepository.settings.first().themePreference)
    }

    @Test
    fun validRestoreReplacesDatabaseAndRestoresSettings() = runTest {
        settingsRepository.restore(AppSettingsSnapshot(themePreference = "dark"))
        seedExistingDatabase()
        val backup = validBackup(
            settings = settingsBackup(
                dailyReviewEnabled = false,
                themePreference = "light",
            ),
        )

        val result = repository.restoreFromJson(json.encodeToString(backup))

        assertTrue(result.isSuccess)
        assertEquals(listOf(1L), dao.tasks(includeArchived = true).map { it.id })
        assertEquals(listOf("Workout"), dao.tasks(includeArchived = true).map { it.name })
        assertFalse(dao.tasks(includeArchived = true).any { it.id == 10L })
        assertEquals(listOf(1L), dao.allRules().map { it.id })
        assertEquals(listOf(1L), dao.allOccurrences().map { it.id })
        assertEquals(
            listOf(LogAction.COMPLETED, LogAction.RESTORED_FROM_BACKUP),
            dao.allLogs().sortedBy { it.id }.map { it.action },
        )
        assertEquals("Restored from backup", dao.allLogs().single { it.action == LogAction.RESTORED_FROM_BACKUP }.note)
        assertEquals(listOf(1L), dao.allSequences().map { it.id })
        assertEquals(listOf("Push"), dao.allSequenceItems().map { it.name })
        val restoredSettings = settingsRepository.settings.first()
        assertFalse(restoredSettings.dailyReviewEnabled)
        assertEquals("light", restoredSettings.themePreference)
        assertEquals(
            listOf(
                HabitReminderReceiver.ACTION_EVENING_TASKS,
                HabitReminderReceiver.ACTION_LATE_DAY_REVIEW,
                HabitReminderReceiver.ACTION_MORNING_TASKS,
                HabitReminderReceiver.ACTION_NOON_TASKS,
            ).sorted(),
            scheduledReminderActions(),
        )
    }

    @Test
    fun restorePreservesNestedExerciseChecksAndRoutinePhaseState() = runTest {
        val backup = validBackup().copy(
            sequenceExercises = listOf(
                SequenceExerciseBackup(
                    id = 1,
                    sequenceItemId = 1,
                    position = 0,
                    name = "Calf raise",
                    prescription = "4 sets x 8 reps",
                    instructions = "Lower for 3 seconds",
                    requirement = "REQUIRED",
                ),
            ),
            occurrenceExerciseChecks = listOf(
                OccurrenceExerciseCheckBackup(
                    id = 1,
                    occurrenceId = 1,
                    sequenceExerciseId = 1,
                    status = "COMPLETED",
                    updatedAt = now.toString(),
                ),
            ),
            routinePlans = listOf(
                RoutinePlanBackup(
                    id = 1,
                    name = "Achilles routine",
                    createdAt = now.toString(),
                    updatedAt = now.toString(),
                ),
            ),
            routinePhases = listOf(
                RoutinePhaseBackup(
                    id = 1,
                    routinePlanId = 1,
                    taskId = 1,
                    position = 0,
                    advanceMode = "MANUAL",
                    minimumDays = 14,
                    progressionNote = "Has soreness remained stable?",
                    status = "ACTIVE",
                    activatedDate = "2026-05-20",
                    advancedAt = null,
                    lastReviewedDate = "2026-06-03",
                    createdAt = now.toString(),
                    updatedAt = now.toString(),
                ),
            ),
        )

        val result = repository.restoreFromJson(json.encodeToString(backup))

        assertTrue(result.isSuccess)
        assertEquals("4 sets x 8 reps", dao.allSequenceExercises().single().prescription)
        assertEquals("COMPLETED", dao.allOccurrenceExerciseChecks().single().status.name)
        assertEquals("Achilles routine", dao.allRoutinePlans().single().name)
        assertEquals("Has soreness remained stable?", dao.allRoutinePhases().single().progressionNote)

        val exported = repository.createBackup()
        assertEquals("Calf raise", exported.sequenceExercises.single().name)
        assertEquals("COMPLETED", exported.occurrenceExerciseChecks.single().status)
        assertEquals("MANUAL", exported.routinePhases.single().advanceMode)
    }

    @Test
    fun validRestoreIntoEmptyDatabasePopulatesTasksHistoryAndSettings() = runTest {
        settingsRepository.restore(AppSettingsSnapshot(themePreference = "dark"))
        val backup = validBackup(
            settings = settingsBackup(
                dailyReviewEnabled = false,
                themePreference = "light",
            ),
        )

        val result = repository.restoreFromJson(json.encodeToString(backup))

        assertTrue(result.isSuccess)
        assertEquals(listOf("Workout"), dao.tasks(includeArchived = true).map { it.name })
        assertEquals(listOf(1L), dao.allRules().map { it.id })
        assertEquals(listOf(1L), dao.allOccurrences().map { it.id })
        assertEquals(
            listOf(LogAction.COMPLETED, LogAction.RESTORED_FROM_BACKUP),
            dao.allLogs().sortedBy { it.id }.map { it.action },
        )
        assertEquals("Restored from backup", dao.allLogs().single { it.action == LogAction.RESTORED_FROM_BACKUP }.note)
        assertEquals(listOf(1L), dao.allSequences().map { it.id })
        assertEquals(listOf("Push"), dao.allSequenceItems().map { it.name })
        val restoredSettings = settingsRepository.settings.first()
        assertFalse(restoredSettings.dailyReviewEnabled)
        assertEquals("light", restoredSettings.themePreference)
        assertEquals(
            listOf(
                HabitReminderReceiver.ACTION_EVENING_TASKS,
                HabitReminderReceiver.ACTION_LATE_DAY_REVIEW,
                HabitReminderReceiver.ACTION_MORNING_TASKS,
                HabitReminderReceiver.ACTION_NOON_TASKS,
            ).sorted(),
            scheduledReminderActions(),
        )
    }

    @Test
    fun restoreWithMissingExactAlarmPermissionSchedulesFallbackAlarmsWithoutFailingRestore() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        settingsRepository.restore(AppSettingsSnapshot(themePreference = "dark"))
        seedExistingDatabase()
        ReminderScheduler(context).schedule(AppSettingsSnapshot(), LocalDate.now().plusDays(1))
        assertEquals(
            listOf(
                HabitReminderReceiver.ACTION_DAILY_REVIEW,
                HabitReminderReceiver.ACTION_EVENING_TASKS,
                HabitReminderReceiver.ACTION_LATE_DAY_REVIEW,
                HabitReminderReceiver.ACTION_MORNING_TASKS,
                HabitReminderReceiver.ACTION_NOON_TASKS,
            ),
            scheduledReminderActions(),
        )
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val backup = validBackup(
            settings = settingsBackup(
                dailyReviewEnabled = true,
                themePreference = "light",
            ),
        )

        val result = repository.restoreFromJson(json.encodeToString(backup))

        assertTrue(result.isSuccess)
        assertEquals(listOf("Workout"), dao.tasks(includeArchived = true).map { it.name })
        assertEquals(
            listOf(LogAction.COMPLETED, LogAction.RESTORED_FROM_BACKUP),
            dao.allLogs().sortedBy { it.id }.map { it.action },
        )
        val restoredSettings = settingsRepository.settings.first()
        assertTrue(restoredSettings.dailyReviewEnabled)
        assertTrue(restoredSettings.lateDayReminderEnabled)
        assertEquals("light", restoredSettings.themePreference)
        assertEquals(
            listOf(
                HabitReminderReceiver.ACTION_DAILY_REVIEW,
                HabitReminderReceiver.ACTION_EVENING_TASKS,
                HabitReminderReceiver.ACTION_LATE_DAY_REVIEW,
                HabitReminderReceiver.ACTION_MORNING_TASKS,
                HabitReminderReceiver.ACTION_NOON_TASKS,
            ),
            scheduledReminderActions(),
        )
    }

    @Test
    fun restoredRoomDataPersistsAfterDatabaseReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "restore_reopen_${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        var firstDatabase: HabitDatabase? = null
        var reopenedDatabase: HabitDatabase? = null
        try {
            firstDatabase = Room.databaseBuilder(context, HabitDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            val firstSettingsRepository = AppSettingsRepository(context)
            val firstRepository = BackupRepository(context, firstDatabase, firstSettingsRepository, json)

            val result = firstRepository.restoreFromJson(json.encodeToString(validBackup()))

            assertTrue(result.isSuccess)
            firstDatabase.close()
            firstDatabase = null

            reopenedDatabase = Room.databaseBuilder(context, HabitDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            val reopenedDao = reopenedDatabase.habitDao()
            assertEquals(listOf("Workout"), reopenedDao.tasks(includeArchived = true).map { it.name })
            assertEquals(listOf("Push"), reopenedDao.allSequenceItems().map { it.name })
            assertEquals(listOf(OccurrenceStatus.COMPLETED), reopenedDao.allOccurrences().map { it.status })
            assertEquals(
                listOf(LogAction.COMPLETED, LogAction.RESTORED_FROM_BACKUP),
                reopenedDao.allLogs().sortedBy { it.id }.map { it.action },
            )
        } finally {
            firstDatabase?.close()
            reopenedDatabase?.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun restoreWithoutSchemaVersionFailsBeforeOverwrite() = runTest {
        settingsRepository.restore(AppSettingsSnapshot(themePreference = "dark"))
        seedExistingDatabase()
        val missingSchemaJson = json.encodeToString(validBackup())
            .lineSequence()
            .filterNot { it.contains("\"schemaVersion\"") }
            .joinToString("\n")

        val result = repository.restoreFromJson(missingSchemaJson)

        assertTrue(result.isFailure)
        assertEquals("Backup is missing schema version", result.exceptionOrNull()?.message)
        assertEquals(listOf("Existing task"), dao.tasks(includeArchived = true).map { it.name })
        assertEquals("dark", settingsRepository.settings.first().themePreference)
    }

    @Test
    fun createBackupExportsFullRoomSnapshotAndSettings() = runTest {
        settingsRepository.restore(
            AppSettingsSnapshot(
                dayRolloverTime = LocalTime.of(4, 30),
                dailyReviewEnabled = false,
                defaultBlockedDays = "SATURDAY",
                themePreference = "dark",
                backupLastExportedAt = "2026-05-19T10:00:00",
            ),
        )
        seedExistingDatabase()

        val backup = repository.createBackup()

        assertEquals(listOf(10L), backup.tasks.map { it.id })
        assertEquals(listOf(true), backup.tasks.map { it.archived })
        assertEquals(listOf(10L), backup.recurrenceRules.map { it.id })
        assertEquals(listOf(10L), backup.scheduledOccurrences.map { it.id })
        assertEquals(listOf(10L), backup.completionLogs.map { it.id })
        assertEquals(listOf(10L), backup.workoutSequences.map { it.id })
        assertEquals(listOf("Existing item"), backup.sequenceItems.map { it.name })
        assertEquals("04:30", backup.appSettings.dayRolloverTime)
        assertFalse(backup.appSettings.dailyReviewEnabled)
        assertEquals("SATURDAY", backup.appSettings.defaultBlockedDays)
        assertEquals("dark", backup.appSettings.themePreference)
        LocalDateTime.parse(backup.exportedAt)
    }

    @Test
    fun createBackupAllowsHistoricalStatusLogThatDiffersFromCurrentOccurrenceStatus() = runTest {
        seedExistingDatabase()
        dao.updateOccurrence(dao.occurrenceById(10)!!.copy(status = OccurrenceStatus.PENDING))

        val backup = repository.createBackup()

        assertEquals(listOf("PENDING"), backup.scheduledOccurrences.map { it.status })
        assertEquals(listOf("COMPLETED"), backup.completionLogs.map { it.action })
    }

    @Test
    fun createBackupRejectsInvalidLocalSnapshotBeforeExport() = runTest {
        dao.restoreTasks(listOf(taskEntity(id = 20, name = "Invalid", taskType = TaskType.SIMPLE_HABIT)))
        dao.restoreRules(listOf(ruleEntity(id = 20, taskId = 20, ruleType = RuleType.SEQUENCE)))

        val error = runCatching { repository.createBackup() }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("Local data cannot be exported"))
        assertTrue(error.message!!.contains("recurrence rule"))
    }

    @Test
    fun exportToUriWritesBackupAndUpdatesExportTimestamp() = runTest {
        settingsRepository.restore(
            AppSettingsSnapshot(
                defaultBlockedDays = "SATURDAY",
                themePreference = "dark",
                backupLastExportedAt = "",
            ),
        )
        seedExistingDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.example.habittracker.backup/export.json")
        val output = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        registerInputStreamFromOutput(context, uri, output)

        val result = repository.exportToUri(uri)

        assertTrue(result.isSuccess)
        val receipt = result.getOrThrow()
        val encodedBackup = output.toString(Charsets.UTF_8.name())
        val backup = json.decodeFromString(HabitBackupV1.serializer(), encodedBackup)
        assertEquals(uri, receipt.uri)
        assertEquals(output.size(), receipt.byteCount)
        assertEquals(listOf("Existing task"), backup.tasks.map { it.name })
        assertEquals("SATURDAY", backup.appSettings.defaultBlockedDays)
        assertEquals("dark", backup.appSettings.themePreference)
        val settings = settingsRepository.settings.first()
        assertTrue(settings.backupLastExportedAt.isNotBlank())
        assertEquals(output.size().toLong(), settings.backupLastVerifiedBytes)
        val safetyCopy = File(context.noBackupFilesDir, "backup-safety/last-verified-backup.json")
        assertEquals(output.size().toLong(), safetyCopy.length())
        assertEquals(encodedBackup, safetyCopy.readText())
    }

    @Test
    fun exportToUriRejectsEmptyWrittenDocumentAndKeepsPreviousTimestamp() = runTest {
        val previousExportedAt = "2026-05-19T12:00:00"
        settingsRepository.restore(
            AppSettingsSnapshot(
                backupLastExportedAt = previousExportedAt,
                backupLastVerifiedBytes = 123_456L,
            ),
        )
        seedExistingDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.example.habittracker.backup/empty-after-write.json")
        val output = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(0)))

        val result = repository.exportToUri(uri)

        assertTrue(result.isFailure)
        assertEquals("Backup file was empty after writing", result.exceptionOrNull()?.message)
        assertTrue(output.size() > 0)
        val settings = settingsRepository.settings.first()
        assertEquals(previousExportedAt, settings.backupLastExportedAt)
        assertEquals(123_456L, settings.backupLastVerifiedBytes)
    }

    @Test
    fun exportToUriWaitsForProviderReadbackInsteadOfAcceptingInitialEmptyState() = runTest {
        seedExistingDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.example.habittracker.backup/delayed-commit.json")
        val output = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            object : InputStream() {
                private var firstRead = true
                private var delegate: ByteArrayInputStream? = null

                override fun read(): Int {
                    if (firstRead) {
                        firstRead = false
                        return -1
                    }
                    return stream().read()
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (firstRead) {
                        firstRead = false
                        return -1
                    }
                    return stream().read(buffer, offset, length)
                }

                private fun stream(): ByteArrayInputStream {
                    return delegate ?: ByteArrayInputStream(output.toByteArray()).also { delegate = it }
                }
            },
        )
        val retryingRepository = BackupRepository(
            context = context,
            database = database,
            settingsRepository = settingsRepository,
            json = json,
            verificationRetryDelaysMillis = listOf(0L),
        )

        val result = retryingRepository.exportToUri(uri)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().byteCount > 0)
    }

    @Test
    fun prepareManualBackupCreatesVerifiedLocalStageBeforeOpeningProvider() = runTest {
        seedExistingDatabase()

        val prepared = repository.prepareManualBackup(now).getOrThrow()

        assertEquals("personal_scheduler_backup_v1_20260520-120000.json", prepared.finalDisplayName)
        assertTrue(prepared.byteCount > 0)
        assertTrue(prepared.stagedFile.isFile)
        assertEquals(prepared.byteCount.toLong(), prepared.stagedFile.length())
        val parsed = json.decodeFromString(HabitBackupV1.serializer(), prepared.stagedFile.readText())
        assertEquals(listOf("Existing task"), parsed.tasks.map { it.name })

        repository.discardPreparedManualBackup(prepared)
        assertFalse(prepared.stagedFile.exists())
    }

    @Test
    fun preparedManualBackupWritesFinalDocumentWithoutRequiringProviderRename() = runTest {
        seedExistingDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.example.habittracker.backup/manual-final.json")
        val output = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        registerInputStreamFromOutput(context, uri, output)
        val gateway = RecordingBackupDocumentGateway(
            supportsRename = true,
            renameFailure = UnsupportedOperationException("Provider does not implement rename"),
        )
        val providerCompatibleRepository = repositoryWithGateway(context, gateway)
        val prepared = providerCompatibleRepository.prepareManualBackup(now).getOrThrow()

        val result = providerCompatibleRepository.exportPreparedManualBackup(uri, prepared, now)

        assertTrue(result.isSuccess)
        assertEquals(uri, result.getOrThrow().uri)
        assertTrue(output.size() > 0)
        assertEquals(0, gateway.renameCalls)
        assertTrue(gateway.deletedUris.isEmpty())
        assertEquals(output.size().toLong(), settingsRepository.settings.first().backupLastVerifiedBytes)
    }

    @Test
    fun autoBackupCopiesVerifiedPendingDataWhenProviderDoesNotSupportRename() = runTest {
        seedExistingDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pendingUri = Uri.parse("content://com.example.habittracker.backup/auto.pending")
        val finalUri = Uri.parse("content://com.example.habittracker.backup/auto.json")
        val pendingOutput = ByteArrayOutputStream()
        val finalOutput = ByteArrayOutputStream()
        registerWritableDocument(context, pendingUri, pendingOutput)
        registerWritableDocument(context, finalUri, finalOutput)
        val gateway = RecordingBackupDocumentGateway(
            createResults = mutableListOf(pendingUri, finalUri),
            supportsRename = false,
        )
        val providerCompatibleRepository = repositoryWithGateway(context, gateway)
        val folderUri = Uri.parse("content://com.example.habittracker.backup/tree/backups")

        val result = providerCompatibleRepository.exportToAutoBackupFolder(folderUri, now)

        assertTrue(result.isSuccess)
        assertEquals(finalUri, result.getOrThrow().uri)
        assertTrue(pendingOutput.size() > 0)
        assertTrue(pendingOutput.toByteArray().contentEquals(finalOutput.toByteArray()))
        assertEquals(
            listOf(
                "personal_scheduler_backup_v1_20260520-120000.json.pending",
                "personal_scheduler_backup_v1_20260520-120000.json",
            ),
            gateway.createdDisplayNames,
        )
        assertEquals(0, gateway.renameCalls)
        assertTrue(pendingUri in gateway.deletedUris)
        assertFalse(finalUri in gateway.deletedUris)
        val settings = settingsRepository.settings.first()
        assertTrue(settings.autoBackupLastRunAt.isNotBlank())
        assertEquals(finalOutput.size().toLong(), settings.backupLastVerifiedBytes)
    }

    @Test
    fun autoBackupFallsBackToVerifiedCopyWhenAdvertisedRenameThrows() = runTest {
        seedExistingDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pendingUri = Uri.parse("content://com.example.habittracker.backup/throwing-rename.pending")
        val finalUri = Uri.parse("content://com.example.habittracker.backup/throwing-rename.json")
        val pendingOutput = ByteArrayOutputStream()
        val finalOutput = ByteArrayOutputStream()
        registerWritableDocument(context, pendingUri, pendingOutput)
        registerWritableDocument(context, finalUri, finalOutput)
        val gateway = RecordingBackupDocumentGateway(
            createResults = mutableListOf(pendingUri, finalUri),
            supportsRename = true,
            renameFailure = UnsupportedOperationException("Cloud provider rename failed"),
        )
        val providerCompatibleRepository = repositoryWithGateway(context, gateway)
        val folderUri = Uri.parse("content://com.example.habittracker.backup/tree/backups")

        val result = providerCompatibleRepository.exportToAutoBackupFolder(folderUri, now)

        assertTrue(result.isSuccess)
        assertEquals(finalUri, result.getOrThrow().uri)
        assertEquals(1, gateway.renameCalls)
        assertTrue(pendingOutput.toByteArray().contentEquals(finalOutput.toByteArray()))
        assertTrue(pendingUri in gateway.deletedUris)
        assertFalse(finalUri in gateway.deletedUris)
    }

    @Test
    fun autoBackupUsesAtomicRenameWhenProviderSupportsIt() = runTest {
        seedExistingDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pendingUri = Uri.parse("content://com.example.habittracker.backup/atomic.pending")
        val renamedUri = Uri.parse("content://com.example.habittracker.backup/atomic.json")
        val pendingOutput = ByteArrayOutputStream()
        registerWritableDocument(context, pendingUri, pendingOutput)
        registerInputStreamFromOutput(context, renamedUri, pendingOutput)
        val gateway = RecordingBackupDocumentGateway(
            createResults = mutableListOf(pendingUri),
            supportsRename = true,
            renameResult = renamedUri,
        )
        val providerCompatibleRepository = repositoryWithGateway(context, gateway)
        val folderUri = Uri.parse("content://com.example.habittracker.backup/tree/backups")

        val result = providerCompatibleRepository.exportToAutoBackupFolder(folderUri, now)

        assertTrue(result.isSuccess)
        assertEquals(renamedUri, result.getOrThrow().uri)
        assertEquals(1, gateway.renameCalls)
        assertEquals(
            listOf("personal_scheduler_backup_v1_20260520-120000.json.pending"),
            gateway.createdDisplayNames,
        )
        assertTrue(gateway.deletedUris.isEmpty())
    }

    @Test
    fun autoBackupDeletesPendingAndFinalDocumentsWhenFallbackCannotBeVerified() = runTest {
        val previousExportedAt = "2026-05-19T12:00:00"
        settingsRepository.restore(
            AppSettingsSnapshot(
                backupLastExportedAt = previousExportedAt,
                backupLastVerifiedBytes = 123_456L,
            ),
        )
        seedExistingDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pendingUri = Uri.parse("content://com.example.habittracker.backup/failing.pending")
        val finalUri = Uri.parse("content://com.example.habittracker.backup/failing.json")
        val pendingOutput = ByteArrayOutputStream()
        val finalOutput = ByteArrayOutputStream()
        registerWritableDocument(context, pendingUri, pendingOutput)
        shadowOf(context.contentResolver).registerOutputStream(finalUri, finalOutput)
        shadowOf(context.contentResolver).registerInputStream(finalUri, ByteArrayInputStream(ByteArray(0)))
        val gateway = RecordingBackupDocumentGateway(
            createResults = mutableListOf(pendingUri, finalUri),
            supportsRename = false,
        )
        val providerCompatibleRepository = repositoryWithGateway(context, gateway)
        val folderUri = Uri.parse("content://com.example.habittracker.backup/tree/backups")

        val result = providerCompatibleRepository.exportToAutoBackupFolder(folderUri, now)

        assertTrue(result.isFailure)
        assertEquals("Backup file was empty after writing", result.exceptionOrNull()?.message)
        assertTrue(pendingUri in gateway.deletedUris)
        assertTrue(finalUri in gateway.deletedUris)
        val settings = settingsRepository.settings.first()
        assertEquals(previousExportedAt, settings.backupLastExportedAt)
        assertEquals(123_456L, settings.backupLastVerifiedBytes)
        assertEquals("Destination remained empty", settings.autoBackupLastFailureReason)
    }

    @Test
    fun failedExportLeavesPreviousExportTimestampUnchanged() = runTest {
        val previousExportedAt = "2026-05-19T12:00:00"
        settingsRepository.restore(AppSettingsSnapshot(backupLastExportedAt = previousExportedAt))
        seedExistingDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.example.habittracker.backup/export-fails.json")
        shadowOf(context.contentResolver).registerOutputStream(
            uri,
            object : OutputStream() {
                override fun write(b: Int) {
                    throw IOException("Simulated backup write failure")
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    throw IOException("Simulated backup write failure")
                }
            },
        )

        val result = repository.exportToUri(uri)

        assertTrue(result.isFailure)
        assertEquals(previousExportedAt, settingsRepository.settings.first().backupLastExportedAt)
    }

    @Test
    fun exportToUriRejectsOversizedBackupBeforeWritingOrUpdatingTimestamp() = runTest {
        val previousExportedAt = "2026-05-19T12:00:00"
        settingsRepository.restore(AppSettingsSnapshot(backupLastExportedAt = previousExportedAt))
        seedExistingDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val boundedRepository = BackupRepository(
            context = context,
            database = database,
            settingsRepository = settingsRepository,
            json = json,
            maxBackupBytes = 128,
        )
        val uri = Uri.parse("content://com.example.habittracker.backup/oversized-export.json")
        val output = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, output)

        val result = boundedRepository.exportToUri(uri)

        assertTrue(result.isFailure)
        assertEquals("Backup file is too large", result.exceptionOrNull()?.message)
        assertEquals(0, output.size())
        assertEquals(previousExportedAt, settingsRepository.settings.first().backupLastExportedAt)
    }

    @Test
    fun restoreFromUriReadsSelectedDocumentAndRestoresBackup() = runTest {
        settingsRepository.restore(AppSettingsSnapshot(themePreference = "dark"))
        seedExistingDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.example.habittracker.backup/restore.json")
        val encodedBackup = json.encodeToString(
            validBackup(
                settings = settingsBackup(themePreference = "light"),
            ),
        )
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream(encodedBackup.toByteArray(Charsets.UTF_8)),
        )

        val result = repository.restoreFromUri(uri)

        assertTrue(result.isSuccess)
        assertEquals(listOf("Workout"), dao.tasks(includeArchived = true).map { it.name })
        assertEquals(listOf("Push"), dao.allSequenceItems().map { it.name })
        assertEquals("light", settingsRepository.settings.first().themePreference)
    }

    @Test
    fun restoreFromUriRejectsOversizedBackupBeforeOverwrite() = runTest {
        settingsRepository.restore(AppSettingsSnapshot(themePreference = "dark"))
        seedExistingDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.example.habittracker.backup/oversized.json")
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream(ByteArray(10 * 1024 * 1024 + 1)),
        )

        val result = repository.restoreFromUri(uri)

        assertTrue(result.isFailure)
        assertEquals("Backup file is too large", result.exceptionOrNull()?.message)
        assertEquals(listOf("Existing task"), dao.tasks(includeArchived = true).map { it.name })
        assertEquals("dark", settingsRepository.settings.first().themePreference)
    }

    private suspend fun seedExistingDatabase() {
        dao.restoreTasks(
            listOf(
                taskEntity(
                    id = 10,
                    name = "Existing task",
                    archived = true,
                ),
            ),
        )
        dao.restoreRules(listOf(ruleEntity(id = 10, taskId = 10)))
        dao.restoreSequences(listOf(sequenceEntity(id = 10, taskId = 10)))
        dao.restoreSequenceItems(listOf(sequenceItemEntity(id = 10, sequenceId = 10)))
        dao.restoreOccurrences(listOf(occurrenceEntity(id = 10, taskId = 10, ruleId = 10, sequenceItemId = 10)))
        dao.restoreLogs(listOf(logEntity(id = 10, taskId = 10, occurrenceId = 10)))
    }

    private fun registerInputStreamFromOutput(
        context: Context,
        uri: Uri,
        output: ByteArrayOutputStream,
    ) {
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            object : InputStream() {
                private var delegate: ByteArrayInputStream? = null

                private fun stream(): ByteArrayInputStream {
                    return delegate ?: ByteArrayInputStream(output.toByteArray()).also { delegate = it }
                }

                override fun read(): Int {
                    return stream().read()
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    return stream().read(buffer, offset, length)
                }
            },
        )
    }

    private fun registerWritableDocument(
        context: Context,
        uri: Uri,
        output: ByteArrayOutputStream,
    ) {
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        registerInputStreamFromOutput(context, uri, output)
    }

    private fun repositoryWithGateway(
        context: Context,
        gateway: BackupDocumentGateway,
    ): BackupRepository {
        return BackupRepository(
            context = context,
            database = database,
            settingsRepository = settingsRepository,
            json = json,
            verificationRetryDelaysMillis = emptyList(),
            documentGateway = gateway,
        )
    }

    private class RecordingBackupDocumentGateway(
        private val createResults: MutableList<Uri> = mutableListOf(),
        private val supportsRename: Boolean,
        private val renameResult: Uri? = null,
        private val renameFailure: Throwable? = null,
    ) : BackupDocumentGateway {
        val createdDisplayNames = mutableListOf<String>()
        val deletedUris = mutableListOf<Uri>()
        var renameCalls: Int = 0
            private set

        override fun createDocument(parentUri: Uri, mimeType: String, displayName: String): Uri? {
            createdDisplayNames += displayName
            return createResults.removeFirstOrNull()
        }

        override fun renameDocument(documentUri: Uri, displayName: String): Uri? {
            renameCalls += 1
            renameFailure?.let { throw it }
            return renameResult
        }

        override fun deleteDocument(documentUri: Uri): Boolean {
            deletedUris += documentUri
            return true
        }

        override fun metadata(documentUri: Uri): BackupDocumentMetadata {
            return BackupDocumentMetadata(
                size = null,
                isPartial = false,
                supportsRename = supportsRename,
            )
        }
    }

    private fun validBackup(
        settings: SettingsBackup = settingsBackup(),
    ): HabitBackupV1 {
        return HabitBackupV1(
            exportedAt = "2026-05-20T12:00:00",
            tasks = listOf(validTask()),
            recurrenceRules = listOf(validRule()),
            scheduledOccurrences = listOf(validOccurrence()),
            completionLogs = listOf(validLog()),
            workoutSequences = listOf(validSequence()),
            sequenceItems = listOf(validSequenceItem()),
            appSettings = settings,
        )
    }

    private fun validTask(
        id: Long = 1,
        taskType: String = "SEQUENCE_ROUTINE",
    ) = TaskBackup(
        id = id,
        name = "Workout",
        taskType = taskType,
        notes = "Notes",
        isActive = true,
        archived = false,
        createdAt = "2026-05-20T12:00:00",
        updatedAt = "2026-05-20T12:00:00",
        defaultReminderEnabled = true,
        calendarVisible = true,
        blockedDays = listOf("SUNDAY"),
    )

    private fun validRule(
        id: Long = 1,
        taskId: Long = 1,
    ) = RecurrenceRuleBackup(
        id = id,
        taskId = taskId,
        ruleType = "SEQUENCE",
        intervalDays = null,
        weekdays = emptyList(),
        cycleDefinition = "Push,Pull,Legs",
        startDate = "2026-05-20",
        endDate = null,
        skipBlockedDaysBehavior = "MOVE_TO_NEXT_VALID_DAY",
        lastGeneratedDate = "2026-07-19",
        createdAt = "2026-05-20T12:00:00",
        updatedAt = "2026-05-20T12:00:00",
    )

    private fun validOccurrence(
        id: Long = 1,
        taskId: Long = 1,
        recurrenceRuleId: Long = 1,
        sequenceItemId: Long? = 1,
    ) = ScheduledOccurrenceBackup(
        id = id,
        taskId = taskId,
        recurrenceRuleId = recurrenceRuleId,
        scheduledDate = "2026-05-20",
        operationalDate = "2026-05-20",
        status = "COMPLETED",
        sequenceItemId = sequenceItemId,
        isShifted = false,
        originalDate = null,
        note = "",
        createdAt = "2026-05-20T12:00:00",
        updatedAt = "2026-05-20T12:00:00",
    )

    private fun validLog(
        id: Long = 1,
        taskId: Long = 1,
        occurrenceId: Long? = 1,
    ) = CompletionLogBackup(
        id = id,
        occurrenceId = occurrenceId,
        taskId = taskId,
        action = "COMPLETED",
        timestamp = "2026-05-20T12:00:00",
        operationalDate = "2026-05-20",
        note = "Done",
        createdAt = "2026-05-20T12:00:00",
    )

    private fun validSequence(
        id: Long = 1,
        taskId: Long = 1,
    ) = WorkoutSequenceBackup(
        id = id,
        taskId = taskId,
        name = "Workout",
        createdAt = "2026-05-20T12:00:00",
        updatedAt = "2026-05-20T12:00:00",
    )

    private fun validSequenceItem(
        id: Long = 1,
        sequenceId: Long = 1,
    ) = SequenceItemBackup(
        id = id,
        sequenceId = sequenceId,
        name = "Push",
        position = 0,
        notes = "",
    )

    private fun settingsBackup(
        dailyReviewEnabled: Boolean = true,
        themePreference: String = "system",
    ) = SettingsBackup(
        dayRolloverTime = "03:00",
        dailyReviewReminderTime = "08:00",
        lateDayReminderTime = "20:00",
        dailyReviewEnabled = dailyReviewEnabled,
        lateDayReminderEnabled = true,
        exactAlarmPermissionPromptShown = false,
        defaultBlockedDays = "",
        themePreference = themePreference,
        backupLastExportedAt = "",
    )

    @Suppress("DEPRECATION")
    private fun scheduledReminderActions(): List<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return shadowOf(alarmManager).scheduledAlarms
            .mapNotNull { alarm -> shadowOf(alarm.operation).savedIntent.action }
            .sorted()
    }

    private fun taskEntity(
        id: Long,
        name: String,
        taskType: TaskType = TaskType.SEQUENCE_ROUTINE,
        archived: Boolean = false,
    ) = TaskEntity(
        id = id,
        name = name,
        taskType = taskType,
        notes = "Existing notes",
        isActive = true,
        archived = archived,
        createdAt = now,
        updatedAt = now,
        defaultReminderEnabled = true,
        calendarVisible = true,
        blockedDays = setOf(DayOfWeek.SUNDAY),
    )

    private fun ruleEntity(
        id: Long,
        taskId: Long,
        ruleType: RuleType = RuleType.SEQUENCE,
    ) = RecurrenceRuleEntity(
        id = id,
        taskId = taskId,
        ruleType = ruleType,
        cycleDefinition = "Existing item",
        startDate = LocalDate.of(2026, 5, 20),
        skipBlockedDaysBehavior = SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY,
        lastGeneratedDate = LocalDate.of(2026, 5, 20),
        createdAt = now,
        updatedAt = now,
    )

    private fun occurrenceEntity(
        id: Long,
        taskId: Long,
        ruleId: Long,
        sequenceItemId: Long,
    ) = ScheduledOccurrenceEntity(
        id = id,
        taskId = taskId,
        recurrenceRuleId = ruleId,
        scheduledDate = LocalDate.of(2026, 5, 20),
        operationalDate = LocalDate.of(2026, 5, 20),
        status = OccurrenceStatus.COMPLETED,
        sequenceItemId = sequenceItemId,
        createdAt = now,
        updatedAt = now,
    )

    private fun logEntity(
        id: Long,
        taskId: Long,
        occurrenceId: Long,
    ) = CompletionLogEntity(
        id = id,
        occurrenceId = occurrenceId,
        taskId = taskId,
        action = LogAction.COMPLETED,
        timestamp = now,
        operationalDate = LocalDate.of(2026, 5, 20),
        note = "Existing log",
        createdAt = now,
    )

    private fun sequenceEntity(
        id: Long,
        taskId: Long,
    ) = WorkoutSequenceEntity(
        id = id,
        taskId = taskId,
        name = "Existing sequence",
        createdAt = now,
        updatedAt = now,
    )

    private fun sequenceItemEntity(
        id: Long,
        sequenceId: Long,
    ) = SequenceItemEntity(
        id = id,
        sequenceId = sequenceId,
        name = "Existing item",
        position = 0,
        notes = "Existing item notes",
    )
}
