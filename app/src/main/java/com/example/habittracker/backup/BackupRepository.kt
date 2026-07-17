package com.example.habittracker.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.example.habittracker.data.LogAction
import com.example.habittracker.data.local.CompletionLogEntity
import com.example.habittracker.data.local.HabitDatabase
import com.example.habittracker.data.scheduling.OperationalDayCalculator
import com.example.habittracker.data.settings.AppSettingsRepository
import com.example.habittracker.data.settings.AppSettingsSnapshot
import com.example.habittracker.reminders.ReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class BackupRepository(
    private val context: Context,
    private val database: HabitDatabase = HabitDatabase.get(context),
    private val settingsRepository: AppSettingsRepository = AppSettingsRepository(context),
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    },
    private val maxBackupBytes: Int = MAX_BACKUP_BYTES,
) {
    private val dao = database.habitDao()

    suspend fun exportToUri(uri: Uri): Result<Unit> {
        return runCatching {
            val encodedBytes = encodedBackupBytes()
            writeAndVerifyBackupBytes(uri, encodedBytes)
            settingsRepository.setBackupLastExportedAt(LocalDateTime.now().toString())
        }.onFailure {
            deleteDocumentQuietly(uri)
        }
    }

    suspend fun exportToAutoBackupFolder(folderUri: Uri, timestamp: LocalDateTime = LocalDateTime.now()): Result<Uri> {
        return runCatching {
            val encodedBytes = encodedBackupBytes()
            val fileName = autoBackupFileName(timestamp)
            val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri),
            )
            val temporaryDestination = DocumentsContract.createDocument(
                context.contentResolver,
                treeDocumentUri,
                BackupDocumentContracts.MIME_TYPE,
                autoBackupTemporaryFileName(timestamp),
            ) ?: error("Could not create backup file")
            var cleanupUri: Uri? = temporaryDestination
            val destination = try {
                writeAndVerifyBackupBytes(temporaryDestination, encodedBytes)
                val renamedDestination = runCatching {
                    DocumentsContract.renameDocument(context.contentResolver, temporaryDestination, fileName)
                }.getOrNull()
                if (renamedDestination != null) {
                    cleanupUri = renamedDestination
                    verifyWrittenBackup(renamedDestination, encodedBytes)
                    renamedDestination
                } else {
                    deleteDocumentQuietly(temporaryDestination)
                    cleanupUri = null
                    val directDestination = DocumentsContract.createDocument(
                        context.contentResolver,
                        treeDocumentUri,
                        BackupDocumentContracts.MIME_TYPE,
                        fileName,
                    ) ?: error("Could not create backup file")
                    cleanupUri = directDestination
                    writeAndVerifyBackupBytes(directDestination, encodedBytes)
                    directDestination
                }
            } catch (error: Throwable) {
                cleanupUri?.let { deleteDocumentQuietly(it) }
                throw error
            }
            cleanupUri = null
            val exportedAt = timestamp.toString()
            settingsRepository.setBackupLastExportedAt(exportedAt)
            settingsRepository.setAutoBackupLastRunAt(exportedAt)
            destination
        }
    }

    suspend fun restoreFromUri(uri: Uri): Result<Unit> {
        return runCatching {
            val encoded = readBackupText(uri)
            restoreFromJson(encoded).getOrThrow()
        }
    }

    suspend fun createBackup(): HabitBackupV1 {
        val settings = settingsRepository.settings.first()
        val backup = database.withTransaction {
            val cycleGroups = dao.allCycleGroups()
            val cycleGroupById = cycleGroups.associateBy { it.id }
            val cycleMembershipByTaskId = dao.allCycleMemberships().associateBy { it.taskId }
            HabitBackupV1(
                exportedAt = LocalDateTime.now().toString(),
                tasks = dao.tasks(includeArchived = true).map { it.toBackup() },
                recurrenceRules = dao.allRules().map { rule ->
                    val group = cycleMembershipByTaskId[rule.taskId]
                        ?.let { membership -> cycleGroupById[membership.cycleGroupId] }
                    rule.toBackup(group)
                },
                scheduledOccurrences = dao.allOccurrences().map { it.toBackup() },
                completionLogs = dao.allLogs().map { it.toBackup() },
                workoutSequences = dao.allSequences().map { it.toBackup() },
                sequenceItems = dao.allSequenceItems().map { it.toBackup() },
                sequenceExercises = dao.allSequenceExercises().map { it.toBackup() },
                occurrenceExerciseChecks = dao.allOccurrenceExerciseChecks().map { it.toBackup() },
                routinePlans = dao.allRoutinePlans().map { it.toBackup() },
                routinePhases = dao.allRoutinePhases().map { it.toBackup() },
                cycleGroups = emptyList(),
                cycleTaskMemberships = emptyList(),
                cycleLogs = emptyList(),
                appSettings = settings.toBackup(),
            )
        }
        BackupValidator.validate(backup)?.let { error("Local data cannot be exported: $it") }
        return backup
    }

    private suspend fun encodedBackupBytes(): ByteArray {
        val backup = createBackup()
        val encodedBytes = json.encodeToString(HabitBackupV1.serializer(), backup)
            .toByteArray(Charsets.UTF_8)
        check(encodedBytes.size <= maxBackupBytes) { "Backup file is too large" }
        return encodedBytes
    }

    private fun writeAndVerifyBackupBytes(uri: Uri, encodedBytes: ByteArray) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(encodedBytes)
            stream.flush()
        } ?: error("Could not open backup destination")
        verifyWrittenBackupWithRetry(uri, encodedBytes)
    }

    private fun verifyWrittenBackupWithRetry(uri: Uri, expectedBytes: ByteArray) {
        var lastFailure: Throwable? = null
        repeat(5) { attempt ->
            try {
                verifyWrittenBackup(uri, expectedBytes)
                return
            } catch (error: Throwable) {
                lastFailure = error
                if (attempt < 4) {
                    Thread.sleep(300L)
                }
            }
        }
        throw lastFailure ?: IllegalStateException("Backup verification failed")
    }

    private fun verifyWrittenBackup(uri: Uri, expectedBytes: ByteArray) {
        val writtenBytes = readBackupBytes(uri)
        check(writtenBytes.isNotEmpty()) { "Backup file was empty after writing" }
        check(writtenBytes.contentEquals(expectedBytes)) { "Backup file did not match written data" }
        val backup = try {
            json.decodeFromString(HabitBackupV1.serializer(), writtenBytes.toString(Charsets.UTF_8))
        } catch (parseError: IllegalArgumentException) {
            throw IllegalStateException("Backup verification failed", parseError)
        } catch (parseError: SerializationException) {
            throw IllegalStateException("Backup verification failed", parseError)
        }
        BackupValidator.validate(backup)?.let { error("Backup verification failed: $it") }
    }

    suspend fun restoreFromJson(encoded: String): Result<Unit> {
        val element = try {
            json.parseToJsonElement(encoded)
        } catch (error: IllegalArgumentException) {
            return Result.failure(error)
        } catch (error: SerializationException) {
            return Result.failure(error)
        }
        if (element !is JsonObject || "schemaVersion" !in element) {
            return Result.failure(IllegalArgumentException("Backup is missing schema version"))
        }
        val parsed = try {
            json.decodeFromJsonElement(HabitBackupV1.serializer(), element)
        } catch (error: IllegalArgumentException) {
            return Result.failure(error)
        } catch (error: SerializationException) {
            return Result.failure(error)
        }

        val validation = BackupValidator.validate(parsed)
        if (validation != null) return Result.failure(IllegalArgumentException(validation))

        val converted = runCatching {
            val taskNameById = parsed.tasks.associate { it.id to it.name }
            val derivedCycleGroups = if (parsed.cycleGroups.isEmpty()) {
                parsed.recurrenceRules.mapNotNull { rule ->
                    rule.toAutoRestartCycleGroup(taskNameById[rule.taskId].orEmpty())
                }
            } else {
                emptyList()
            }
            val derivedCycleMemberships = if (parsed.cycleTaskMemberships.isEmpty()) {
                parsed.recurrenceRules.mapNotNull { rule ->
                    derivedCycleGroups.firstOrNull { it.id == rule.id }
                        ?.let { group -> rule.toAutoRestartCycleMembership(group.id) }
                }
            } else {
                emptyList()
            }
            ConvertedBackup(
                settings = parsed.appSettings.toSnapshot(),
                tasks = parsed.tasks.map { it.toEntity() },
                rules = parsed.recurrenceRules.map { it.toEntity() },
                occurrences = parsed.scheduledOccurrences.map { it.toEntity() },
                logs = parsed.completionLogs.map { it.toEntity() },
                sequences = parsed.workoutSequences.map { it.toEntity() },
                sequenceItems = parsed.sequenceItems.map { it.toEntity() },
                sequenceExercises = parsed.sequenceExercises.map { it.toEntity() },
                occurrenceExerciseChecks = parsed.occurrenceExerciseChecks.map { it.toEntity() },
                routinePlans = parsed.routinePlans.map { it.toEntity() },
                routinePhases = parsed.routinePhases.map { it.toEntity() },
                cycleGroups = parsed.cycleGroups.map { it.toEntity() } + derivedCycleGroups,
                cycleMemberships = parsed.cycleTaskMemberships.map { it.toEntity() } + derivedCycleMemberships,
                cycleLogs = parsed.cycleLogs.map { it.toEntity() },
            )
        }.getOrElse { return Result.failure(it) }

        val previous = currentConvertedBackup()
        return try {
            restoreConvertedBackup(converted)
            settingsRepository.restore(converted.settings)
            ReminderScheduler(context).schedule(converted.settings)
            insertRestoreAuditLog(converted)
            Result.success(Unit)
        } catch (error: Throwable) {
            runCatching {
                restoreConvertedBackup(previous)
                settingsRepository.restore(previous.settings)
                ReminderScheduler(context).schedule(previous.settings)
            }
            Result.failure(error)
        }
    }

    private suspend fun insertRestoreAuditLog(converted: ConvertedBackup) {
        val taskId = converted.tasks.minOfOrNull { it.id } ?: return
        val now = LocalDateTime.now()
        dao.insertLog(
            CompletionLogEntity(
                taskId = taskId,
                occurrenceId = null,
                action = LogAction.RESTORED_FROM_BACKUP,
                timestamp = now,
                operationalDate = OperationalDayCalculator(converted.settings.dayRolloverTime).today(),
                note = "Restored from backup",
                createdAt = now,
            ),
        )
    }

    private fun readBackupText(uri: Uri): String {
        return readBackupBytes(uri).toString(Charsets.UTF_8)
    }

    private fun readBackupBytes(uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri) ?: error("Could not open backup source")
        return stream.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = it.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBackupBytes) error("Backup file is too large")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun deleteDocumentQuietly(uri: Uri) {
        runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    private suspend fun currentConvertedBackup(): ConvertedBackup {
        return ConvertedBackup(
            settings = settingsRepository.settings.first(),
            tasks = dao.tasks(includeArchived = true),
            rules = dao.allRules(),
            occurrences = dao.allOccurrences(),
            logs = dao.allLogs(),
            sequences = dao.allSequences(),
            sequenceItems = dao.allSequenceItems(),
            sequenceExercises = dao.allSequenceExercises(),
            occurrenceExerciseChecks = dao.allOccurrenceExerciseChecks(),
            routinePlans = dao.allRoutinePlans(),
            routinePhases = dao.allRoutinePhases(),
            cycleGroups = dao.allCycleGroups(),
            cycleMemberships = dao.allCycleMemberships(),
            cycleLogs = dao.allCycleLogs(),
        )
    }

    private suspend fun restoreConvertedBackup(converted: ConvertedBackup) {
        database.withTransaction {
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
            dao.restoreTasks(converted.tasks)
            dao.restoreRules(converted.rules)
            dao.restoreCycleGroups(converted.cycleGroups)
            dao.restoreCycleMemberships(converted.cycleMemberships)
            dao.restoreRoutinePlans(converted.routinePlans)
            dao.restoreRoutinePhases(converted.routinePhases)
            dao.restoreSequences(converted.sequences)
            dao.restoreSequenceItems(converted.sequenceItems)
            dao.restoreSequenceExercises(converted.sequenceExercises)
            dao.restoreOccurrences(converted.occurrences)
            dao.restoreOccurrenceExerciseChecks(converted.occurrenceExerciseChecks)
            dao.restoreLogs(converted.logs)
            dao.restoreCycleLogs(converted.cycleLogs)
        }
    }

    private data class ConvertedBackup(
        val settings: AppSettingsSnapshot,
        val tasks: List<com.example.habittracker.data.local.TaskEntity>,
        val rules: List<com.example.habittracker.data.local.RecurrenceRuleEntity>,
        val occurrences: List<com.example.habittracker.data.local.ScheduledOccurrenceEntity>,
        val logs: List<com.example.habittracker.data.local.CompletionLogEntity>,
        val sequences: List<com.example.habittracker.data.local.WorkoutSequenceEntity>,
        val sequenceItems: List<com.example.habittracker.data.local.SequenceItemEntity>,
        val sequenceExercises: List<com.example.habittracker.data.local.SequenceExerciseEntity>,
        val occurrenceExerciseChecks: List<com.example.habittracker.data.local.OccurrenceExerciseCheckEntity>,
        val routinePlans: List<com.example.habittracker.data.local.RoutinePlanEntity>,
        val routinePhases: List<com.example.habittracker.data.local.RoutinePhaseEntity>,
        val cycleGroups: List<com.example.habittracker.data.local.CycleGroupEntity>,
        val cycleMemberships: List<com.example.habittracker.data.local.CycleTaskMembershipEntity>,
        val cycleLogs: List<com.example.habittracker.data.local.CycleLogEntity>,
    )

    private companion object {
        const val MAX_BACKUP_BYTES = 10 * 1024 * 1024
        val AUTO_BACKUP_FILENAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun autoBackupFileName(timestamp: LocalDateTime): String {
            return "personal_scheduler_backup_v1_${timestamp.format(AUTO_BACKUP_FILENAME_FORMATTER)}.json"
        }

        fun autoBackupTemporaryFileName(timestamp: LocalDateTime): String {
            return "personal_scheduler_backup_v1_${timestamp.format(AUTO_BACKUP_FILENAME_FORMATTER)}.json.tmp"
        }
    }
}
