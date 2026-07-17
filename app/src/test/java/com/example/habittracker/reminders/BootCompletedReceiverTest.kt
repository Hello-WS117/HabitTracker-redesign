package com.example.habittracker.reminders

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.habittracker.data.settings.AppSettingsRepository
import com.example.habittracker.data.settings.AppSettingsSnapshot
import kotlinx.coroutines.test.runTest
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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BootCompletedReceiverTest {
    private lateinit var context: Context
    private lateinit var settingsRepository: AppSettingsRepository
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setUp() = runTest {
        ShadowAlarmManager.reset()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        context = ApplicationProvider.getApplicationContext()
        settingsRepository = AppSettingsRepository(context)
        shadowAlarmManager = shadowOf(context.getSystemService(AlarmManager::class.java))
        settingsRepository.restore(AppSettingsSnapshot())
    }

    @After
    fun tearDown() {
        ShadowAlarmManager.reset()
    }

    @Test
    fun bootCompletedSchedulesCurrentReminderSettings() = runTest {
        settingsRepository.restore(AppSettingsSnapshot())

        val scheduled = BootReminderRescheduleHandler.handle(context, Intent.ACTION_BOOT_COMPLETED)

        assertTrue(scheduled)
        assertEquals(
            listOf(
                HabitReminderReceiver.ACTION_DAILY_REVIEW,
                HabitReminderReceiver.ACTION_EVENING_TASKS,
                HabitReminderReceiver.ACTION_LATE_DAY_REVIEW,
                HabitReminderReceiver.ACTION_MORNING_TASKS,
                HabitReminderReceiver.ACTION_NOON_TASKS,
            ),
            scheduledActions(),
        )
    }

    @Test
    fun packageReplacedCancelsStaleAlarmsBeforeReschedulingCurrentSettings() = runTest {
        ReminderScheduler(context).schedule(AppSettingsSnapshot(), LocalDate.now().plusDays(1))
        assertEquals(5, shadowAlarmManager.scheduledAlarms.size)
        settingsRepository.restore(
            AppSettingsSnapshot(
                dailyReviewEnabled = false,
                lateDayReminderEnabled = true,
                taskTimeReminderEnabled = false,
            ),
        )

        val scheduled = BootReminderRescheduleHandler.handle(context, Intent.ACTION_MY_PACKAGE_REPLACED)

        assertTrue(scheduled)
        assertEquals(listOf(HabitReminderReceiver.ACTION_LATE_DAY_REVIEW), scheduledActions())
    }

    @Test
    fun clockChangesCancelStaleAlarmsBeforeReschedulingCurrentSettings() = runTest {
        ReminderScheduler(context).schedule(AppSettingsSnapshot(), LocalDate.now().plusDays(1))
        assertEquals(5, shadowAlarmManager.scheduledAlarms.size)
        settingsRepository.restore(
            AppSettingsSnapshot(
                dailyReviewEnabled = true,
                lateDayReminderEnabled = false,
                taskTimeReminderEnabled = false,
            ),
        )

        val timeChangedScheduled = BootReminderRescheduleHandler.handle(context, Intent.ACTION_TIME_CHANGED)

        assertTrue(timeChangedScheduled)
        assertEquals(listOf(HabitReminderReceiver.ACTION_DAILY_REVIEW), scheduledActions())

        settingsRepository.restore(
            AppSettingsSnapshot(
                dailyReviewEnabled = false,
                lateDayReminderEnabled = true,
                taskTimeReminderEnabled = false,
            ),
        )

        val timeZoneChangedScheduled = BootReminderRescheduleHandler.handle(context, Intent.ACTION_TIMEZONE_CHANGED)

        assertTrue(timeZoneChangedScheduled)
        assertEquals(listOf(HabitReminderReceiver.ACTION_LATE_DAY_REVIEW), scheduledActions())
    }

    @Test
    fun unsupportedBroadcastActionDoesNotTouchAlarms() = runTest {
        settingsRepository.restore(AppSettingsSnapshot())

        val scheduled = BootReminderRescheduleHandler.handle(context, Intent.ACTION_POWER_CONNECTED)

        assertFalse(scheduled)
        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Suppress("DEPRECATION")
    private fun scheduledActions(): List<String> {
        return shadowAlarmManager.scheduledAlarms
            .mapNotNull { alarm -> shadowOf(alarm.operation).savedIntent.action }
            .sorted()
    }
}
