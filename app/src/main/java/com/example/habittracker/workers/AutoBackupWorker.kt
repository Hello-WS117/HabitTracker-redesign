package com.example.habittracker.workers

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.habittracker.backup.BackupRepository
import com.example.habittracker.data.settings.AppSettingsRepository
import com.example.habittracker.data.settings.AppSettingsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class AutoBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return AutoBackupRunner.run {
            AutoBackupHandler.run(applicationContext)
        }
    }
}

internal object AutoBackupRunner {
    suspend fun run(backup: suspend () -> Unit): ListenableWorker.Result {
        return try {
            backup()
            ListenableWorker.Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ListenableWorker.Result.retry()
        }
    }
}

object AutoBackupHandler {
    suspend fun run(context: Context) {
        val appContext = context.applicationContext
        val settings = AppSettingsRepository(appContext).settings.first()
        if (!settings.autoBackupEnabled || settings.autoBackupFolderUri.isBlank()) return
        BackupRepository(appContext)
            .exportToAutoBackupFolder(Uri.parse(settings.autoBackupFolderUri))
            .getOrThrow()
    }
}

object AutoBackupScheduler {
    internal const val WORK_NAME = "habit_auto_backup"
    internal val EXISTING_WORK_POLICY = ExistingPeriodicWorkPolicy.UPDATE

    fun configure(context: Context, settings: AppSettingsSnapshot) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (
            !settings.autoBackupEnabled ||
            settings.autoBackupFolderUri.isBlank() ||
            !hasPersistedWritePermission(context, settings.autoBackupFolderUri)
        ) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            EXISTING_WORK_POLICY,
            buildRequest(settings),
        )
    }

    internal fun buildRequest(
        settings: AppSettingsSnapshot,
        now: LocalDateTime = LocalDateTime.now(),
    ): PeriodicWorkRequest {
        val intervalDays = settings.autoBackupIntervalDays.coerceAtLeast(1)
        return PeriodicWorkRequestBuilder<AutoBackupWorker>(intervalDays.toLong(), TimeUnit.DAYS)
            .setInitialDelay(delayUntilNextAutoBackup(settings, now), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.MINUTES)
            .build()
    }

    internal fun delayUntilNextAutoBackup(
        settings: AppSettingsSnapshot,
        now: LocalDateTime = LocalDateTime.now(),
    ): Long {
        val lastRun = settings.autoBackupLastRunAt
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            ?: return 0L
        val nextRun = lastRun.plusDays(settings.autoBackupIntervalDays.coerceAtLeast(1).toLong())
        return Duration.between(now, nextRun).toMillis().coerceAtLeast(0)
    }

    private fun hasPersistedWritePermission(context: Context, folderUri: String): Boolean {
        val uri = runCatching { Uri.parse(folderUri) }.getOrNull() ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
    }
}
