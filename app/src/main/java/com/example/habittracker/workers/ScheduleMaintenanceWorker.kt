package com.example.habittracker.workers

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.example.habittracker.data.HabitRepository
import com.example.habittracker.data.local.HabitDatabase
import com.example.habittracker.data.scheduling.OperationalDayCalculator
import com.example.habittracker.data.settings.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class ScheduleMaintenanceWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return ScheduleMaintenanceRunner.run {
            ScheduleMaintenanceHandler.run(applicationContext)
        }
    }
}

internal object ScheduleMaintenanceRunner {
    suspend fun run(maintenance: suspend () -> Unit): ListenableWorker.Result {
        return try {
            maintenance()
            ListenableWorker.Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ListenableWorker.Result.retry()
        }
    }
}

data class ScheduleMaintenanceResult(
    val missedMarked: Int,
    val cadenceRepairs: Int = 0,
    val cycleRestarts: Int = 0,
)

object ScheduleMaintenanceHandler {
    suspend fun run(context: Context): ScheduleMaintenanceResult {
        val appContext = context.applicationContext
        val settings = AppSettingsRepository(appContext).settings.first()
        val operationalDate = OperationalDayCalculator(settings.dayRolloverTime).today()
        val repository = HabitRepository(HabitDatabase.get(appContext))
        val missedMarked = repository.markOverduePendingMissed(operationalDate)
        repository.extendGeneratedOccurrences(operationalDate)
        val cadenceRepairs = repository.repairPendingCadences(operationalDate)
        val cycleRestarts = repository.restartEndedCycles(operationalDate)
        return ScheduleMaintenanceResult(missedMarked, cadenceRepairs, cycleRestarts)
    }
}

object MaintenanceScheduler {
    internal const val WORK_NAME = "habit_schedule_maintenance"
    internal val EXISTING_WORK_POLICY = ExistingPeriodicWorkPolicy.UPDATE
    private const val MAINTENANCE_INTERVAL_HOURS = 6L

    fun enqueue(context: Context, rolloverTime: LocalTime = LocalTime.of(3, 0)) {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            EXISTING_WORK_POLICY,
            buildRequest(rolloverTime),
        )
    }

    internal fun buildRequest(
        rolloverTime: LocalTime = LocalTime.of(3, 0),
        now: LocalDateTime = LocalDateTime.now(),
    ): PeriodicWorkRequest {
        return PeriodicWorkRequestBuilder<ScheduleMaintenanceWorker>(MAINTENANCE_INTERVAL_HOURS, TimeUnit.HOURS)
            .setInitialDelay(delayUntilNextRolloverMaintenance(rolloverTime, now), TimeUnit.MILLISECONDS)
            .build()
    }

    internal fun delayUntilNextRolloverMaintenance(
        rolloverTime: LocalTime,
        now: LocalDateTime = LocalDateTime.now(),
    ): Long {
        val maintenanceToday = now.toLocalDate().atTime(rolloverTime).plusMinutes(5)
        val nextMaintenance = if (maintenanceToday.isAfter(now)) {
            maintenanceToday
        } else {
            maintenanceToday.plusDays(1)
        }
        return Duration.between(now, nextMaintenance).toMillis().coerceAtLeast(0)
    }
}
