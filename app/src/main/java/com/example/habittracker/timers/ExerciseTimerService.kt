package com.example.habittracker.timers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.example.habittracker.MainActivity
import com.example.habittracker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ExerciseTimerService : Service() {
    private data class TimerKey(val occurrenceId: Int, val exerciseId: Int)

    private data class ActiveTimer(
        val key: TimerKey,
        val exerciseName: String,
        val endsAtEpochMillis: Long,
        val job: Job,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val timers = mutableMapOf<TimerKey, ActiveTimer>()
    private val alertPlayers = mutableSetOf<ExerciseTimerAlertPlayer>()
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTimer(intent)
            ACTION_CANCEL -> cancelTimer(intent)
            else -> stopIfIdle()
        }
        return if (timers.isNotEmpty() || alertPlayers.isNotEmpty()) {
            START_REDELIVER_INTENT
        } else {
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timers.values.forEach { it.job.cancel() }
        timers.clear()
        alertPlayers.toList().forEach { it.release() }
        alertPlayers.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startTimer(intent: Intent) {
        val key = intent.timerKey() ?: return
        val endsAtEpochMillis = intent.getLongExtra(EXTRA_ENDS_AT_EPOCH_MILLIS, 0L)
        if (endsAtEpochMillis <= 0L) return
        val exerciseName = intent.getStringExtra(EXTRA_EXERCISE_NAME)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Exercise timer"

        timers.remove(key)?.job?.cancel()
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            delay((endsAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L))
            finishTimer(key)
        }
        timers[key] = ActiveTimer(
            key = key,
            exerciseName = exerciseName,
            endsAtEpochMillis = endsAtEpochMillis,
            job = job,
        )
        showRunningNotification()
        job.start()
    }

    private fun cancelTimer(intent: Intent) {
        val key = intent.timerKey() ?: return
        timers.remove(key)?.job?.cancel()
        if (timers.isEmpty()) {
            stopIfIdle()
        } else {
            showRunningNotification()
        }
    }

    private fun finishTimer(key: TimerKey) {
        val completed = timers.remove(key) ?: return
        showForegroundNotification(buildCompletedNotification(completed.exerciseName))

        lateinit var alertPlayer: ExerciseTimerAlertPlayer
        alertPlayer = ExerciseTimerAlertPlayer(this) {
            alertPlayers.remove(alertPlayer)
            if (timers.isEmpty()) {
                stopIfIdle()
            } else {
                showRunningNotification()
            }
        }
        alertPlayers += alertPlayer
        alertPlayer.play()
    }

    private fun showRunningNotification() {
        val nextTimer = timers.values.minByOrNull { it.endsAtEpochMillis } ?: return
        showForegroundNotification(buildRunningNotification(nextTimer))
    }

    private fun showForegroundNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildRunningNotification(timer: ActiveTimer): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            cancelIntent(this, timer.key.occurrenceId, timer.key.exerciseId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = baseNotificationBuilder()
            .setContentTitle("Exercise timer")
            .setContentText("Timer running")
            .build()
        return baseNotificationBuilder()
            .setContentTitle(timer.exerciseName)
            .setContentText("Exercise timer running")
            .setWhen(timer.endsAtEpochMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setPublicVersion(publicVersion)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    stopIntent,
                ).build(),
            )
            .build()
    }

    private fun buildCompletedNotification(exerciseName: String): Notification {
        val publicVersion = baseNotificationBuilder()
            .setContentTitle("Exercise timer complete")
            .setContentText("Open Habit Tracker for details")
            .build()
        return baseNotificationBuilder()
            .setContentTitle("Timer complete")
            .setContentText(exerciseName)
            .setOngoing(false)
            .setPublicVersion(publicVersion)
            .build()
    }

    private fun baseNotificationBuilder(): Notification.Builder {
        val openAppIntent = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(getColor(R.color.notification_color))
            .setContentIntent(openAppIntent)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
    }

    private fun createNotificationChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Exercise timers",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows active workout countdown timers"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun stopIfIdle() {
        if (timers.isNotEmpty() || alertPlayers.isNotEmpty()) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun Intent.timerKey(): TimerKey? {
        if (!hasExtra(EXTRA_OCCURRENCE_ID) || !hasExtra(EXTRA_EXERCISE_ID)) return null
        return TimerKey(
            occurrenceId = getIntExtra(EXTRA_OCCURRENCE_ID, 0),
            exerciseId = getIntExtra(EXTRA_EXERCISE_ID, 0),
        )
    }

    companion object {
        internal const val ACTION_START = "com.example.habittracker.action.START_EXERCISE_TIMER"
        internal const val ACTION_CANCEL = "com.example.habittracker.action.CANCEL_EXERCISE_TIMER"
        internal const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        internal const val EXTRA_EXERCISE_ID = "exercise_id"
        internal const val EXTRA_EXERCISE_NAME = "exercise_name"
        internal const val EXTRA_ENDS_AT_EPOCH_MILLIS = "ends_at_epoch_millis"
        internal const val CHANNEL_ID = "exercise_timer_active"
        internal const val NOTIFICATION_ID = 5301
        private const val OPEN_APP_REQUEST_CODE = 5302

        internal fun startIntent(
            context: Context,
            occurrenceId: Int,
            exerciseId: Int,
            exerciseName: String,
            endsAtEpochMillis: Long,
        ): Intent {
            return timerIntent(context, ACTION_START, occurrenceId, exerciseId)
                .putExtra(EXTRA_EXERCISE_NAME, exerciseName)
                .putExtra(EXTRA_ENDS_AT_EPOCH_MILLIS, endsAtEpochMillis)
        }

        internal fun cancelIntent(
            context: Context,
            occurrenceId: Int,
            exerciseId: Int,
        ): Intent = timerIntent(context, ACTION_CANCEL, occurrenceId, exerciseId)

        private fun timerIntent(
            context: Context,
            action: String,
            occurrenceId: Int,
            exerciseId: Int,
        ): Intent {
            return Intent(context, ExerciseTimerService::class.java)
                .setAction(action)
                .setData(
                    Uri.Builder()
                        .scheme("habittracker")
                        .authority("exercise-timer")
                        .appendPath(occurrenceId.toString())
                        .appendPath(exerciseId.toString())
                        .build(),
                )
                .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
                .putExtra(EXTRA_EXERCISE_ID, exerciseId)
        }
    }
}

private class ExerciseTimerAlertPlayer(
    context: Context,
    private val onFinished: () -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioAttributes = exerciseTimerAudioAttributes()
    private val focusRequest = exerciseTimerAudioFocusRequest(audioAttributes)
    private val player = MediaPlayer.create(
        context,
        R.raw.exercise_timer_complete,
        audioAttributes,
        AudioManager.AUDIO_SESSION_ID_GENERATE,
    )
    private var fallbackTone: ToneGenerator? = null
    private var released = false

    fun play() {
        audioManager.requestAudioFocus(focusRequest)
        if (player == null) {
            fallbackTone = ToneGenerator(AudioManager.STREAM_ALARM, 100).also {
                it.startTone(ToneGenerator.TONE_PROP_BEEP, FALLBACK_TONE_DURATION_MILLIS)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                ::release,
                FALLBACK_TONE_DURATION_MILLIS.toLong() + 100L,
            )
            return
        }
        player.setOnCompletionListener { release() }
        player.setOnErrorListener { _, _, _ ->
            release()
            true
        }
        player.start()
    }

    fun release() {
        if (released) return
        released = true
        runCatching { player?.stop() }
        player?.release()
        fallbackTone?.stopTone()
        fallbackTone?.release()
        audioManager.abandonAudioFocusRequest(focusRequest)
        onFinished()
    }

    private companion object {
        const val FALLBACK_TONE_DURATION_MILLIS = 600
    }
}

internal fun exerciseTimerAudioAttributes(): AudioAttributes {
    return AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}

internal fun exerciseTimerAudioFocusRequest(audioAttributes: AudioAttributes): AudioFocusRequest {
    return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener { }
        .build()
}
