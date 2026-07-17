package com.example.habittracker

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.habittracker.timers.ExerciseTimerService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExerciseTimerLockScreenConnectedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun foregroundTimerContinuesAndFinishesWithScreenLockedWhileMediaPlays() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val audioManager = context.getSystemService(AudioManager::class.java)
        val mediaAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val mediaFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(mediaAttributes)
            .setOnAudioFocusChangeListener { }
            .build()
        val mediaPlayer = requireNotNull(
            MediaPlayer.create(
                context,
                R.raw.exercise_timer_complete,
                mediaAttributes,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            ),
        ).apply {
            isLooping = true
        }

        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        assertTrue(
            audioManager.requestAudioFocus(mediaFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
        )
        mediaPlayer.start()
        context.startForegroundService(
            ExerciseTimerService.startIntent(
                context = context,
                occurrenceId = 991,
                exerciseId = 992,
                exerciseName = "Lock-screen timer test",
                endsAtEpochMillis = System.currentTimeMillis() + TIMER_DURATION_MILLIS,
            ),
        )
        waitUntil { notificationManager.hasExerciseTimerNotification() }

        try {
            shell("input keyevent KEYCODE_SLEEP")
            waitUntil { !powerManager.isInteractive }
            Thread.sleep(1_000L)

            assertFalse(powerManager.isInteractive)
            assertTrue(mediaPlayer.isPlaying)
            assertTrue(notificationManager.hasExerciseTimerNotification())
            waitUntil(timeoutMillis = 8_000L) {
                !notificationManager.hasExerciseTimerNotification()
            }
            assertFalse(powerManager.isInteractive)
            assertTrue(mediaPlayer.isPlaying)
        } finally {
            mediaPlayer.stop()
            mediaPlayer.release()
            audioManager.abandonAudioFocusRequest(mediaFocusRequest)
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("input keyevent KEYCODE_MENU")
            waitUntil { powerManager.isInteractive && !keyguardManager.isKeyguardLocked }
            instrumentation.waitForIdleSync()
            Thread.sleep(750L)
        }
    }

    private fun NotificationManager.hasExerciseTimerNotification(): Boolean {
        return activeNotifications.any { it.id == ExerciseTimerService.NOTIFICATION_ID }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .close()
    }

    private fun waitUntil(timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L)
        }
        assertTrue("Condition was not met within $timeoutMillis ms", condition())
    }

    private companion object {
        const val TIMER_DURATION_MILLIS = 7_000L
    }
}
