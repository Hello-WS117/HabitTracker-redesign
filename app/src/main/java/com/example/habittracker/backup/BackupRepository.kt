package com.example.habittracker.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.room.withTransaction
import com.example.habittracker.data.HabitRepository
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
    val byteCount: Int,
    internal val stagedFile: File,
)

class BackupRepository internal constructor(
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
    private val modeSwitchVerificationRetryDelaysMillis: List<Long> = MODE_SWITCH_VERIFICATION_RETRY_DELAYS_MILLIS,
    private val documentGateway: BackupDocumentGateway = AndroidBackupDocumentGateway(context.contentResolver),
    private val streamGateway: BackupStreamGateway = AndroidBackupStreamGateway(context.contentResolver),
) {
    private val dao = database.habitDao()

    suspend fun exportToUri(uri: Uri): Result<BackupExportReceipt> = withContext(ioDispatcher) {
        var destinationVerified = false
        try {
            val timestamp = LocalDateTime.now()
            val encodedBytes = encodedBackupBytes()
            writeLocalSafetyCopy(encodedBytes)
            writeAndVerifyBackupBytes(uri, encodedBytes)
            destinationVerified = true
            recordBackupSuccessSafely(timestamp, encodedBytes.size, automatic = false)
            Result.success(BackupExportReceipt(uri, encodedBytes.size))
        } catch (cancellation: CancellationException) {
            if (!destinationVerified) deleteDocumentQuietly(uri)
            throw cancellation
        } catch (error: Throwable) {
            if (!destinationVerified) deleteDocumentQuietly(uri)
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
                        byteCount = encodedBytes.size,
                        stagedFile = stagedFile,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Result.failure(error.asBackupOperation(BackupFailureStage.PREPARE, "Could not prepare manual backup"))
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
        var destinationVerified = false
        try {
            val encodedBytes = readStagedBackup(prepared)
            writeAndVerifyBackupBytes(uri, encodedBytes)
            destinationVerified = true
            recordBackupSuccessSafely(timestamp, encodedBytes.size, automatic = false)
            prepared.stagedFile.delete()
            Result.success(BackupExportReceipt(uri, encodedBytes.size))
        } catch (cancellation: CancellationException) {
            if (!destinationVerified) deleteDocumentQuietly(uri)
            throw cancellation
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Manual backup failed for provider ${uri.authority.orEmpty()}", error)
            if (!destinationVerified) deleteDocumentQuietly(uri)
            Result.failure(error)
        }
    }

    suspend fun exportToAutoBackupFolder(
        folderUri: Uri,
        timestamp: LocalDateTime = LocalDateTime.now(),
    ): Result<BackupExportReceipt> = withContext(ioDispatcher) {
        try {
            val encodedBytes = encodedBackupBytes()
            writeLocalSafetyCopy(encodedBytes)
            val fileName = autoBackupFileName(timestamp)
            val treeDocumentUri = try {
                DocumentsContract.buildDocumentUriUsingTree(
                    folderUri,
                    DocumentsContract.getTreeDocumentId(folderUri),
                )
            } catch (error: Throwable) {
                throw error.asBackupOperation(
                    stage = BackupFailureStage.CREATE_FILE,
                    message = "Auto backup folder could not be opened",
                )
            }
            val finalizedDestination = writeVerifiedAutoBackup(
                treeDocumentUri = treeDocumentUri,
                finalDisplayName = fileName,
                pendingDisplayName = autoBackupTemporaryFileName(timestamp),
                encodedBytes = encodedBytes,
            )
            recordBackupSuccessSafely(timestamp, encodedBytes.size, automatic = true)
            Result.success(BackupExportReceipt(finalizedDestination, encodedBytes.size))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Automatic backup failed for provider ${folderUri.authority.orEmpty()}", error)
            runCatching {
                settingsRepository.recordAutoBackupFailure(
                    timestamp = LocalDateTime.now().toString(),
                    reason = backupFailureReason(error),
                )
            }
            Result.failure(error)
        }
    }

    private suspend fun writeVerifiedAutoBackup(
        treeDocumentUri: Uri,
        finalDisplayName: String,
        pendingDisplayName: String,
        encodedBytes: ByteArray,
    ): Uri {
        val pendingDestination = createBackupDocument(treeDocumentUri, pendingDisplayName)
        try {
            writeAndVerifyBackupBytes(pendingDestination, encodedBytes)
            val renamedDestination = renameVerifiedDocumentIfSupported(pendingDestination, finalDisplayName)
            if (renamedDestination != null) {
                try {
                    verifyWrittenBackupWithRetry(renamedDestination, encodedBytes)
                    return renamedDestination
                } catch (error: Throwable) {
                    deleteDocumentQuietly(renamedDestination)
                    Log.w(LOG_TAG, "Provider rename verification failed; using verified-copy fallback", error)
                }
            }

            val finalDestination = writeVerifiedFinalDocument(treeDocumentUri, finalDisplayName, encodedBytes)
            deleteDocumentQuietly(pendingDestination)
            return finalDestination
        } catch (cancellation: CancellationException) {
            deleteDocumentQuietly(pendingDestination)
            throw cancellation
        } catch (error: Throwable) {
            deleteDocumentQuietly(pendingDestination)
            throw error
        }
    }

    private fun renameVerifiedDocumentIfSupported(documentUri: Uri, finalDisplayName: String): Uri? {
        if (documentGateway.metadata(documentUri)?.supportsRename != true) return null
        return runCatching {
            documentGateway.renameDocument(documentUri, finalDisplayName)
        }.onFailure { error ->
            Log.w(LOG_TAG, "Provider rename failed; using verified-copy fallback", error)
        }.getOrNull()
    }

    private suspend fun writeVerifiedFinalDocument(
        treeDocumentUri: Uri,
        finalDisplayName: String,
        encodedBytes: ByteArray,
    ): Uri {
        val finalDestination = createBackupDocument(treeDocumentUri, finalDisplayName)
        try {
            writeAndVerifyBackupBytes(finalDestination, encodedBytes)
            return finalDestination
        } catch (cancellation: CancellationException) {
            deleteDocumentQuietly(finalDestination)
            throw cancellation
        } catch (error: Throwable) {
            deleteDocumentQuietly(finalDestination)
            throw error
        }
    }

    private fun createBackupDocument(parentUri: Uri, displayName: String): Uri {
        return try {
            documentGateway.createDocument(parentUri, BackupDocumentContracts.MIME_TYPE, displayName)
                ?: throw BackupOperationException(
                    stage = BackupFailureStage.CREATE_FILE,
                    message = "Backup destination could not create the file",
                )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            throw error.asBackupOperation(
                stage = BackupFailureStage.CREATE_FILE,
                message = "Backup destination could not create the file",
            )
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
        HabitRepository(database).repairDerivedDataConsistency()
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
        return try {
            val backup = createBackup()
            val encodedBytes = json.encodeToString(HabitBackupV1.serializer(), backup)
                .toByteArray(Charsets.UTF_8)
            check(encodedBytes.isNotEmpty()) { "Backup payload was empty before writing" }
            check(encodedBytes.size <= maxBackupBytes) { "Backup file is too large" }
            encodedBytes
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            throw error.asBackupOperation(BackupFailureStage.PREPARE, "Could not prepare backup data")
        }
    }

    private suspend fun writeAndVerifyBackupBytes(uri: Uri, encodedBytes: ByteArray) {
        var openedDestination = false
        var lastWriteFailure: Throwable? = null
        var lastVerificationFailure: Throwable? = null
        BACKUP_WRITE_MODES.forEachIndexed { index, mode ->
            try {
                writeBackupBytes(uri, encodedBytes, mode)
                openedDestination = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: BackupModeWriteException) {
                openedDestination = openedDestination || error.destinationOpened
                lastWriteFailure = error.cause ?: error
                Log.w(LOG_TAG, "Backup write mode $mode failed for provider ${uri.authority.orEmpty()}", error)
                return@forEachIndexed
            }

            val retryDelays = if (index == BACKUP_WRITE_MODES.lastIndex) {
                verificationRetryDelaysMillis
            } else {
                modeSwitchVerificationRetryDelaysMillis
            }
            try {
                verifyWrittenBackupWithRetry(uri, encodedBytes, retryDelays)
                Log.d(LOG_TAG, "Backup provider ${uri.authority.orEmpty()} verified write mode $mode")
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lastVerificationFailure = error
                Log.w(
                    LOG_TAG,
                    "Backup mode $mode wrote but did not verify for provider ${uri.authority.orEmpty()}; trying fallback",
                    error,
                )
            }
        }

        lastVerificationFailure?.let { throw it }
        val stage = if (openedDestination) {
            BackupFailureStage.WRITE_DESTINATION
        } else {
            BackupFailureStage.OPEN_DESTINATION
        }
        throw BackupOperationException(
            stage = stage,
            message = "Backup destination rejected all supported write modes: ${BACKUP_WRITE_MODES.joinToString()}",
            cause = lastWriteFailure,
        )
    }

    private fun writeBackupBytes(uri: Uri, encodedBytes: ByteArray, mode: String) {
        val output = try {
            streamGateway.openOutputStream(uri, mode)
                ?: throw IllegalStateException("Could not open backup destination with mode $mode")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            throw BackupModeWriteException(destinationOpened = false, cause = error)
        }
        try {
            output.use {
                output.write(encodedBytes)
                output.flush()
                (output as? FileOutputStream)?.let { fileOutput ->
                    runCatching { fileOutput.fd.sync() }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            throw BackupModeWriteException(destinationOpened = true, cause = error)
        }
    }

    private suspend fun verifyWrittenBackupWithRetry(
        uri: Uri,
        expectedBytes: ByteArray,
        retryDelaysMillis: List<Long> = verificationRetryDelaysMillis,
    ) {
        var lastFailure: Throwable? = null
        val attempts = listOf(0L) + retryDelaysMillis
        attempts.forEach { delayMillis ->
            if (delayMillis > 0L) delay(delayMillis)
            try {
                verifyWrittenBackup(uri, expectedBytes)
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lastFailure = error
            }
        }
        if ((lastFailure as? BackupOperationException)?.stage == BackupFailureStage.PROVIDER_SYNC) {
            verifyWrittenBackup(uri, expectedBytes, requireSettledMetadata = false)
            Log.w(LOG_TAG, "Accepted exact backup readback after provider metadata remained stale")
            return
        }
        throw lastFailure ?: IllegalStateException("Backup verification failed")
    }

    private fun verifyWrittenBackup(
        uri: Uri,
        expectedBytes: ByteArray,
        requireSettledMetadata: Boolean = true,
    ) {
        val writtenBytes = try {
            readBackupBytes(uri)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            throw BackupOperationException(
                stage = BackupFailureStage.READ_BACK,
                message = "Backup destination could not be read back",
                cause = error,
            )
        }
        if (writtenBytes.isEmpty()) {
            throw BackupOperationException(
                stage = BackupFailureStage.VERIFY_CONTENT,
                message = "Backup file was empty after writing",
            )
        }
        if (!writtenBytes.contentEquals(expectedBytes)) {
            throw BackupOperationException(
                stage = BackupFailureStage.VERIFY_CONTENT,
                message = "Backup file did not match written data",
            )
        }
        if (requireSettledMetadata) {
            documentGateway.metadata(uri)?.let { metadata ->
                val wrongSize = metadata.size?.takeIf { it >= 0L } != null &&
                    metadata.size != expectedBytes.size.toLong()
                if (wrongSize || metadata.isPartial == true) {
                    throw BackupOperationException(
                        stage = BackupFailureStage.PROVIDER_SYNC,
                        message = if (wrongSize) {
                            "Backup provider reported an incorrect file size"
                        } else {
                            "Backup provider still reported an incomplete file"
                        },
                    )
                }
            }
        }
        val backup = try {
            json.decodeFromString(HabitBackupV1.serializer(), writtenBytes.toString(Charsets.UTF_8))
        } catch (parseError: IllegalArgumentException) {
            throw BackupOperationException(
                stage = BackupFailureStage.VERIFY_CONTENT,
                message = "Backup verification failed",
                cause = parseError,
            )
        } catch (parseError: SerializationException) {
            throw BackupOperationException(
                stage = BackupFailureStage.VERIFY_CONTENT,
                message = "Backup verification failed",
                cause = parseError,
            )
        }
        BackupValidator.validate(backup)?.let { validationError ->
            throw BackupOperationException(
                stage = BackupFailureStage.VERIFY_CONTENT,
                message = "Backup verification failed: $validationError",
            )
        }
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
            HabitRepository(database).repairDerivedDataConsistency()
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
        val stream = streamGateway.openInputStream(uri) ?: error("Could not open backup source")
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
        try {
            writeAtomicFile(File(backupSafetyDirectory(), LOCAL_SAFETY_BACKUP_FILE_NAME), encodedBytes)
        } catch (error: Throwable) {
            throw error.asBackupOperation(BackupFailureStage.PREPARE, "Local safety backup could not be written")
        }
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
        return try {
            check(prepared.stagedFile.isFile) { "Prepared backup is no longer available" }
            check(prepared.stagedFile.length() in 1..maxBackupBytes.toLong()) { "Prepared backup has an invalid size" }
            val encodedBytes = prepared.stagedFile.readBytes()
            check(encodedBytes.size == prepared.byteCount) { "Prepared backup size changed before export" }
            val parsed = json.decodeFromString(HabitBackupV1.serializer(), encodedBytes.toString(Charsets.UTF_8))
            BackupValidator.validate(parsed)?.let { error("Prepared backup failed validation: $it") }
            encodedBytes
        } catch (error: Throwable) {
            throw error.asBackupOperation(BackupFailureStage.PREPARE, "Prepared backup was unavailable or invalid")
        }
    }

    private suspend fun recordBackupSuccessSafely(
        timestamp: LocalDateTime,
        byteCount: Int,
        automatic: Boolean,
    ) {
        try {
            settingsRepository.recordBackupSuccess(timestamp.toString(), byteCount.toLong(), automatic)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Backup was verified, but success metadata could not be recorded", error)
        }
    }

    private fun deleteDocumentQuietly(uri: Uri) {
        runCatching {
            documentGateway.deleteDocument(uri)
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
        const val LOG_TAG = "HabitBackup"
        const val MAX_BACKUP_BYTES = 10 * 1024 * 1024
        val BACKUP_WRITE_MODES = listOf("wt", "rwt", "w")
        const val BACKUP_SAFETY_DIRECTORY_NAME = "backup-safety"
        const val LOCAL_SAFETY_BACKUP_FILE_NAME = "last-verified-backup.json"
        const val MANUAL_BACKUP_STAGING_FILE_NAME = "pending-manual-backup.json"
        val AUTO_BACKUP_FILENAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val DEFAULT_VERIFICATION_RETRY_DELAYS_MILLIS =
            listOf(250L, 750L, 1_500L, 3_000L, 5_000L, 10_000L, 15_000L, 20_000L)
        val MODE_SWITCH_VERIFICATION_RETRY_DELAYS_MILLIS = listOf(250L, 750L, 1_500L, 3_000L)

        fun autoBackupFileName(timestamp: LocalDateTime): String {
            return "personal_scheduler_backup_v1_${timestamp.format(AUTO_BACKUP_FILENAME_FORMATTER)}.json"
        }

        fun autoBackupTemporaryFileName(timestamp: LocalDateTime): String {
            return "personal_scheduler_backup_v1_${timestamp.format(AUTO_BACKUP_FILENAME_FORMATTER)}.pending.json"
        }

        fun backupFailureReason(error: Throwable): String {
            return backupFailureStoredReason(error)
        }
    }
}

private class BackupModeWriteException(
    val destinationOpened: Boolean,
    cause: Throwable,
) : IllegalStateException("Backup stream mode failed", cause)

private fun Throwable.asBackupOperation(
    stage: BackupFailureStage,
    message: String,
): BackupOperationException {
    return this as? BackupOperationException ?: BackupOperationException(stage, this.message ?: message, this)
}
