package com.example.habittracker.timers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExerciseTimerServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val controller: ServiceController<ExerciseTimerService> =
        Robolectric.buildService(ExerciseTimerService::class.java).create()
    private val service = controller.get()

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun startRunsAsMediaPlaybackForegroundServiceWithLockScreenCountdown() {
        val endAt = System.currentTimeMillis() + 45_000L

        service.onStartCommand(
            ExerciseTimerService.startIntent(context, 10, 101, "Single-leg balance", endAt),
            0,
            1,
        )

        val shadowService = shadowOf(service)
        val notification = requireNotNull(shadowService.lastForegroundNotification)
        assertEquals(ExerciseTimerService.NOTIFICATION_ID, shadowService.lastForegroundNotificationId)
        assertEquals(Notification.CATEGORY_STOPWATCH, notification.category)
        assertEquals("Single-leg balance", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(endAt, notification.`when`)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(notification.publicVersion)
        assertEquals(
            "Exercise timer",
            notification.publicVersion.extras.getString(Notification.EXTRA_TITLE),
        )

        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(ExerciseTimerService.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
    }

    @Test
    fun cancelStopsTheForegroundServiceWhenNoTimersRemain() {
        service.onStartCommand(
            ExerciseTimerService.startIntent(
                context,
                occurrenceId = 10,
                exerciseId = 101,
                exerciseName = "Balance",
                endsAtEpochMillis = System.currentTimeMillis() + 45_000L,
            ),
            0,
            1,
        )

        service.onStartCommand(
            ExerciseTimerService.cancelIntent(context, 10, 101),
            0,
            2,
        )

        val shadowService = shadowOf(service)
        assertTrue(shadowService.isForegroundStopped)
        assertTrue(shadowService.isStoppedBySelf)
        assertTrue(shadowService.notificationShouldRemoved)
    }

    @Test
    fun startAndCancelIntentsArePrivateAndUniquelyIdentifyTheExercise() {
        val start = ExerciseTimerService.startIntent(context, 10, 101, "Balance", 50_000L)
        val cancel = ExerciseTimerService.cancelIntent(context, 10, 101)

        assertEquals(ExerciseTimerService::class.java.name, start.component?.className)
        assertEquals(ExerciseTimerService.ACTION_START, start.action)
        assertEquals(ExerciseTimerService.ACTION_CANCEL, cancel.action)
        assertEquals("habittracker://exercise-timer/10/101", start.dataString)
        assertEquals(start.data, cancel.data)
        assertFalse(start.filterEquals(ExerciseTimerService.startIntent(context, 10, 102, "Balance", 50_000L)))
    }

    @Test
    fun completionAudioUsesAlarmRoutingAndDucksOtherMedia() {
        val attributes = exerciseTimerAudioAttributes()
        val focusRequest = exerciseTimerAudioFocusRequest(attributes)

        assertEquals(AudioAttributes.USAGE_ALARM, attributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, attributes.contentType)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, focusRequest.focusGain)
    }
}
