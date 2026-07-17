package com.example.habittracker.timers

import android.content.Context

interface ExerciseTimerController {
    fun startTimer(
        occurrenceId: Int,
        exerciseId: Int,
        exerciseName: String,
        endsAtEpochMillis: Long,
    )

    fun cancelTimer(occurrenceId: Int, exerciseId: Int)
}

class ExerciseTimerServiceController(context: Context) : ExerciseTimerController {
    private val appContext = context.applicationContext

    override fun startTimer(
        occurrenceId: Int,
        exerciseId: Int,
        exerciseName: String,
        endsAtEpochMillis: Long,
    ) {
        appContext.startForegroundService(
            ExerciseTimerService.startIntent(
                context = appContext,
                occurrenceId = occurrenceId,
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                endsAtEpochMillis = endsAtEpochMillis,
            ),
        )
    }

    override fun cancelTimer(occurrenceId: Int, exerciseId: Int) {
        appContext.startService(
            ExerciseTimerService.cancelIntent(
                context = appContext,
                occurrenceId = occurrenceId,
                exerciseId = exerciseId,
            ),
        )
    }
}
