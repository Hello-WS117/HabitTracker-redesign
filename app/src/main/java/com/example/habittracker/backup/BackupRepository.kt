package com.example.habittracker.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.example.habittracker.data.LogAction
import com.example.habittracker.data.local.CompletionLogEntity
import com.example.habittracker.data.local.HabitDatabase
import com.example.habittracker.data.scheduling.OperationalDayCalculator
import com.example.habittracker.data.settings.AppSettingsRepository
import com.example.habittracker.data.settings.AppSettingsSnapshot
import com.example.habittracker.reminders.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class BackupExportReceipt(
    val uri: Uri,
    val byteCount: Int,
)

class PreparedManualBackup internal constructor(
    val finalDisplayName: String,
    val pendingDisplayName: String,
    val byteCount: Int,
    internal val stagedFile: File,
)

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val verificationRetryDelaysMillis: List<Long> = DEFAULT_VERIFICATION_RETRY_DELAYS_MILLIS,
) {
    private val dao = database.habitDao()

    suspend fun exportToUri(uri: Uri): Result<BackupExportReceipt> = withContext(ioDispatcher) {
        try {
            val timestamp = LocalDateTime.now()
            val encodedBytes = encodedBackupBytes()
            writeLocalSafetyCopy(encodedBytes)
            writeAndVerifyBackupBytes(uri, encodedBytes)
            settingsRepository.recordBackupSuccess(timestamp.toString(), encodedBytes.size.toLong(), automatic = false)
            Result.success(BackupExportReceipt(uri, encodedBytes.size))
        } catch (cancellation: CancellationException) {
            deleteDocumentQuietly(uri)
            throw cancellation
        } catch (error: Throwable) {
            deleteDocumentQuietly(uri)
            Result.failure(error)
        }
    }

    suspend fun prepareManualBackup(timestamp: LocalDateTime = LocalDateTime.now()): Result<PreparedManualBackup> =
        withContext(ioDispatcher) {
            try {
                val encodedBytes = encodedBackupBytes()
                writeLocalSafetyCopy(encodedBytes)
                val stagedFile = File(backupSafetyDirectory(), MANUAL_BACKUP_STAGING_FILE_NAME)
                writeAtomicFile(stagedFile, encodedBytes)
                Result.success(
                    PreparedManualBackup(
                        finalDisplayName = manualBackupFileName(timestamp),
                        pendingDisplayName = manualBackupPendingFileName(timestamp),
                        byteCount = encodedBytes.size,
                        stagedFile = stagedFile,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }

    suspend fun discardPreparedManualBackup(prepared: PreparedManualBackup?) = withContext(ioDispatcher) {
        prepared?.stagedFile?.delete()
    }

    suspend fun discardPendingDocument(uri: Uri) = withContext(ioDispatcher) {
        deleteDocumentQuietly(uri)
    }

    suspend fun exportPreparedManualBackup(
        uri: Uri,
        prepared: PreparedManualBackup,
        timestamp: LocalDateTime = LocalDateTime.now(),
    ): Result<BackupExportReceipt> = withContext(ioDispatcher) {
        var cleanupUri: Uri? = uri
        try {
            val encodedBytes = readStagedBackup(prepared)
            writeAndVerifyBackupBytes(uri, encodedBytes)
            val finalizedUri = DocumentsContract.renameDocument(
                context.contentResolver,
                uri,
                prepared.finalDisplayName,
            ) ?: error("Backup provider could not finalize the verified file")
            cleanupUri = finalizedUri
            verifyWrittenBackupWithRetry(finalizedUri, encodedBytes)
            settingsRepository.recordBackupSuccess(timestamp.toString(), encodedBytes.size.toLong(), automatic = false)
            prepared.stagedFile.delete()
            cleanupUri = null
            Result.success(BackupExportReceipt(finalizedUri, encodedBytes.size))
        } catch (cancellation: CancellationException) {
            cleanupUri?.let(::deleteDocumentQuietly)
            throw cancellation
        } catch (error: Throwable) {
            cleanupUri?.let(::deleteDocumentQuietly)
            Result.failure(error)
        }
    }

    suspend fun exportToAutoBackupFolder(
        folderUri: Uri,
        timestamp: LocalDateTime = LocalDateTime.now(),
    ): Result<BackupExportReceipt> = withContext(ioDispatcher) {
        var cleanupUri: Uri? = null
        try {
            val encodedBytes = encodedBackupBytes()
            writeLocalSafetyCopy(encodedBytes)
            val fileName = autoBackupFileName(timestamp)
            val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri),
            )
            val pendingDestination = DocumentsContract.createDocument(
                context.contentResolver,
                treeDocumentUri,
                BackupDocumentContracts.MIME_TYPE,
                autoBackupTemporaryFileName(timestamp),
            ) ?: error("Could not create backup file")
            cleanupUri = pendingDestination
            writeAndVerifyBackupBytes(pendingDestination, encodedBytes)
            val finalizedDestination = DocumentsContract.renameDocument(
                context.contentResolver,
                pendingDestination,
                fileName,
            ) ?: error("Backup provider could not finalize the verified file")
            cleanupUri = finalizedDestination
            verifyWrittenBackupWithRetry(finalizedDestination, encodedBytes)
            val exportedAt = timestamp.toString()
            settingsRepository.recordBackupSuccess(exportedAt, encodedBytes.size.toLong(), automatic = true)
            cleanupUri = null
            Result.success(BackupExportReceipt(finalizedDestination, encodedBytes.size))
        } catch (cancellation: CancellationException) {
            cleanupUri?.let(::deleteDocumentQuietly)
            throw cancellation
        } catch (error: Throwable) {
            cleanupUri?.let(::deleteDocumentQuietly)
            runCatching {
                settingsRepository.recordAutoBackupFailure(
                    timestamp = LocalDateTime.now().toString(),
                    reason = backupFailureReason(error),
                )
            }
            Result.failure(error)
        }
    }

    suspend fun restoreFromUri(uri: Uri): Result<Unit> = withContext(ioDispatcher) {
        try {
            val encoded = readBackupText(uri)
            restoreFromJson(encoded)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
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
        check(encodedBytes.isNotEmpty()) { "Backup payload was empty before writing" }
        check(encodedBytes.size <= maxBackupBytes) { "Backup file is too large" }
        return encodedBytes
    }

    private suspend fun writeAndVerifyBackupBytes(uri: Uri, encodedBytes: ByteArray) {
        writeBackupBytes(uri, encodedBytes)
        verifyWrittenBackupWithRetry(uri, encodedBytes)
    }

    private fun writeBackupBytes(uri: Uri, encodedBytes: ByteArray) {
        val output = context.contentResolver.openOutputStream(uri, BACKUP_WRITE_MODE)
            ?: error("Could not open backup destination")
        output.use {
            output.write(encodedBytes)
            output.flush()
            (output as? FileOutputStream)?.let { fileOutput ->
                runCatching { fileOutput.fd.sync() }
            }
        }
    }

    private suspend fun verifyWrittenBackupWithRetry(uri: Uri, expectedBytes: ByteArray) {
        var lastFailure: Throwable? = null
        val attempts = listOf(0L) + verificationRetryDelaysMillis
        attempts.forEach { delayMillis ->
            if (delayMillis > 0L) delay(delayMillis)
            try {
                verifyWrittenBackup(uri, expectedBytes)
                return
            } catch (error: Throwable) {
                lastFailure = error
            }
        }
        throw lastFailure ?: IllegalStateException("Backup verification failed")
    }

    private fun verifyWrittenBackup(uri: Uri, expectedBytes: ByteArray) {
        val writtenBytes = readBackupBytes(uri)
        check(writtenBytes.isNotEmpty()) { "Backup file was empty after writing" }
        check(writtenBytes.contentEquals(expectedBytes)) { "Backup file did not match written data" }
        readDocumentMetadata(uri)?.let { metadata ->
            metadata.size?.takeIf { it >= 0L }?.let { size ->
                check(size == expectedBytes.size.toLong()) { "Backup provider reported an incorrect file size" }
            }
            check(metadata.isPartial != true) { "Backup provider still reported an incomplete file" }
        }
        val backup = try {
            json.decodeFromString(HabitBackupV1.serializer(), writtenBytes.toString(Charsets.UTF_8))
        } catch (parseError: IllegalArgumentException) {
            throw IllegalStateException("Backup verification failed", parseError)
        } catch (parseError: SerializationException) {
            throw IllegalStateException("Backup verification failed", parseError)
        }
        BackupValidator.validate(backup)?.let { error("Backup verification failed: $it") }
    }

    private fun readDocumentMetadata(uri: Uri): DocumentMetadata? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_FLAGS),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                val flags = if (flagsIndex >= 0 && !cursor.isNull(flagsIndex)) cursor.getInt(flagsIndex) else null
                DocumentMetadata(
                    size = size,
                    isPartial = flags?.let {
                        it and DocumentsContract.Document.FLAG_PARTIAL != 0
                    },
                )
            }
        }.getOrNull()
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

    private fun writeLocalSafetyCopy(encodedBytes: ByteArray) {
        writeAtomicFile(File(backupSafetyDirectory(), LOCAL_SAFETY_BACKUP_FILE_NAME), encodedBytes)
    }

    private fun backupSafetyDirectory(): File {
        return File(context.noBackupFilesDir, BACKUP_SAFETY_DIRECTORY_NAME).also { directory ->
            check(directory.exists() || directory.mkdirs()) { "Could not prepare local backup safety storage" }
        }
    }

    private fun writeAtomicFile(destination: File, encodedBytes: ByteArray) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        runCatching { temporary.delete() }
        FileOutputStream(temporary).use { output ->
            output.write(encodedBytes)
            output.flush()
            output.fd.sync()
        }
        check(temporary.length() == encodedBytes.size.toLong()) { "Local backup safety copy was incomplete" }
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Throwable) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        check(destination.readBytes().contentEquals(encodedBytes)) { "Local backup safety copy failed verification" }
    }

    private fun readStagedBackup(prepared: PreparedManualBackup): ByteArray {
        check(prepared.stagedFile.isFile) { "Prepared backup is no longer available" }
        check(prepared.stagedFile.length() in 1..maxBackupBytes.toLong()) { "Prepared backup has an invalid size" }
        val encodedBytes = prepared.stagedFile.readBytes()
        check(encodedBytes.size == prepared.byteCount) { "Prepared backup size changed before export" }
        val parsed = json.decodeFromString(HabitBackupV1.serializer(), encodedBytes.toString(Charsets.UTF_8))
        BackupValidator.validate(parsed)?.let { error("Prepared backup failed validation: $it") }
        return encodedBytes
    }

    private fun deleteDocumentQuietly(uri: Uri) {
        runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    private data class DocumentMetadata(
        val size: Long?,
        val isPartial: Boolean?,
    )

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
        const val BACKUP_WRITE_MODE = "w"
        const val BACKUP_SAFETY_DIRECTORY_NAME = "backup-safety"
        const val LOCAL_SAFETY_BACKUP_FILE_NAME = "last-verified-backup.json"
        const val MANUAL_BACKUP_STAGING_FILE_NAME = "pending-manual-backup.json"
        val AUTO_BACKUP_FILENAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val DEFAULT_VERIFICATION_RETRY_DELAYS_MILLIS = listOf(250L, 750L, 1_500L, 3_000L, 5_000L, 5_000L, 5_000L)

        fun autoBackupFileName(timestamp: LocalDateTime): String {
            return "personal_scheduler_backup_v1_${timestamp.format(AUTO_BACKUP_FILENAME_FORMATTER)}.json"
        }

        fun autoBackupTemporaryFileName(timestamp: LocalDateTime): String {
            return "personal_scheduler_backup_v1_${timestamp.format(AUTO_BACKUP_FILENAME_FORMATTER)}.json.pending"
        }

        fun backupFailureReason(error: Throwable): String {
            val message = error.message.orEmpty()
            return when {
                error is SecurityException -> "Folder permission expired"
                message.contains("empty", ignoreCase = true) -> "Destination remained empty"
                message.contains("file size", ignoreCase = true) -> "Destination reported the wrong size"
                message.contains("did not match", ignoreCase = true) -> "Destination changed the backup data"
                message.contains("incomplete", ignoreCase = true) -> "Destination did not finish uploading"
                message.contains("finalize", ignoreCase = true) -> "Destination could not finalize the backup"
                message.contains("Could not open", ignoreCase = true) -> "Destination could not be opened"
                else -> "Backup provider write failed"
            }
        }
    }
}
