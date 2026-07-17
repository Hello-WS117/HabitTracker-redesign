package com.example.habittracker.reminders

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.habittracker.data.settings.AppSettingsSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderSchedulerTest {
    private lateinit var context: Context
    private lateinit var scheduler: ReminderScheduler
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setUp() {
        ShadowAlarmManager.reset()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        context = ApplicationProvider.getApplicationContext()
        scheduler = ReminderScheduler(context)
        shadowAlarmManager = shadowOf(context.getSystemService(AlarmManager::class.java))
    }

    @After
    fun tearDown() {
        ShadowAlarmManager.reset()
    }

    @Test
    fun scheduleCancelsDisabledStaleAlarmsBeforeSchedulingCurrentSettings() {
        val baseDate = LocalDate.now().plusDays(1)
        scheduler.schedule(AppSettingsSnapshot(), baseDate)

        scheduler.schedule(
            AppSettingsSnapshot(
                dailyReviewEnabled = false,
                lateDayReminderEnabled = true,
                taskTimeReminderEnabled = false,
            ),
            baseDate,
        )

        assertEquals(listOf(HabitReminderReceiver.ACTION_LATE_DAY_REVIEW), scheduledActions())
    }

    @Test
    @Suppress("DEPRECATION")
    fun scheduleUsesConfiguredRtcWakeupExactAllowWhileIdleTimes() {
        val baseDate = LocalDate.now().plusDays(1)
            val settings = AppSettingsSnapshot(
                dailyReviewReminderTime = LocalTime.of(7, 15),
                lateDayReminderTime = LocalTime.of(21, 45),
                morningTaskReminderTime = LocalTime.of(6, 30),
                noonTaskReminderTime = LocalTime.of(12, 30),
                eveningTaskReminderTime = LocalTime.of(18, 30),
            )

        val scheduled = scheduler.schedule(settings, baseDate)

        val alarmsByAction = shadowAlarmManager.scheduledAlarms.associateBy { alarm ->
            shadowOf(alarm.operation).savedIntent.action
        }
        assertTrue(scheduled)
        assertEquals(AlarmManager.RTC_WAKEUP, alarmsByAction.getValue(HabitReminderReceiver.ACTION_DAILY_REVIEW).type)
        assertEquals(AlarmManager.RTC_WAKEUP, alarmsByAction.getValue(HabitReminderReceiver.ACTION_LATE_DAY_REVIEW).type)
        assertEquals(AlarmManager.RTC_WAKEUP, alarmsByAction.getValue(HabitReminderReceiver.ACTION_MORNING_TASKS).type)
        assertEquals(AlarmManager.RTC_WAKEUP, alarmsByAction.getValue(HabitReminderReceiver.ACTION_NOON_TASKS).type)
        assertEquals(AlarmManager.RTC_WAKEUP, alarmsByAction.getValue(HabitReminderReceiver.ACTION_EVENING_TASKS).type)
        assertTrue(alarmsByAction.getValue(HabitReminderReceiver.ACTION_DAILY_REVIEW).isAllowWhileIdle)
        assertTrue(alarmsByAction.getValue(HabitReminderReceiver.ACTION_LATE_DAY_REVIEW).isAllowWhileIdle)
        assertTrue(alarmsByAction.getValue(HabitReminderReceiver.ACTION_MORNING_TASKS).isAllowWhileIdle)
        assertTrue(alarmsByAction.getValue(HabitReminderReceiver.ACTION_NOON_TASKS).isAllowWhileIdle)
        assertTrue(alarmsByAction.getValue(HabitReminderReceiver.ACTION_EVENING_TASKS).isAllowWhileIdle)
        assertEquals(
            baseDate.atTime(7, 15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            alarmsByAction.getValue(HabitReminderReceiver.ACTION_DAILY_REVIEW).triggerAtTime,
        )
        assertEquals(
            baseDate.atTime(21, 45).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            alarmsByAction.getValue(HabitReminderReceiver.ACTION_LATE_DAY_REVIEW).triggerAtTime,
        )
        assertEquals(
            baseDate.atTime(6, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            alarmsByAction.getValue(HabitReminderReceiver.ACTION_MORNING_TASKS).triggerAtTime,
        )
        assertEquals(
            baseDate.atTime(12, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            alarmsByAction.getValue(HabitReminderReceiver.ACTION_NOON_TASKS).triggerAtTime,
        )
        assertEquals(
            baseDate.atTime(18, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            alarmsByAction.getValue(HabitReminderReceiver.ACTION_EVENING_TASKS).triggerAtTime,
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun scheduleRollsPastReminderTimesToNextDay() {
        val now = LocalDateTime.of(2026, 5, 20, 12, 0)
        scheduler = ReminderScheduler(context, nowProvider = { now })
            val settings = AppSettingsSnapshot(
                dailyReviewReminderTime = LocalTime.of(8, 0),
                lateDayReminderTime = LocalTime.of(20, 0),
                morningTaskReminderTime = LocalTime.of(7, 0),
                noonTaskReminderTime = LocalTime.of(12, 0),
                eveningTaskReminderTime = LocalTime.of(18, 0),
            )

        val scheduled = scheduler.schedule(settings)

        val alarmsByAction = shadowAlarmManager.scheduledAlarms.associateBy { alarm ->
            shadowOf(alarm.operation).savedIntent.action
        }
        assertTrue(scheduled)
        assertEquals(
            LocalDate.of(2026, 5, 21).atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            alarmsByAction.getValue(HabitReminderReceiver.ACTION_DAILY_REVIEW).triggerAtTime,
        )
        assertEquals(
            LocalDate.of(2026, 5, 20).atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            alarmsByAction.getValue(HabitReminderReceiver.ACTION_LATE_DAY_REVIEW).triggerAtTime,
        )
        assertEquals(
            LocalDate.of(2026, 5, 21).atTime(7, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            alarmsByAction.getValue(HabitReminderReceiver.ACTION_MORNING_TASKS).triggerAtTime,
        )
        assertEquals(
            LocalDate.of(2026, 5, 21).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            alarmsByAction.getValue(HabitReminderReceiver.ACTION_NOON_TASKS).triggerAtTime,
        )
        assertEquals(
            LocalDate.of(2026, 5, 20).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            alarmsByAction.getValue(HabitReminderReceiver.ACTION_EVENING_TASKS).triggerAtTime,
        )
    }

    @Test
    fun scheduleFallsBackToInexactAlarmsWhenExactAlarmPermissionIsUnavailable() {
        val baseDate = LocalDate.now().plusDays(1)
        scheduler.schedule(AppSettingsSnapshot(), baseDate)
        assertEquals(5, shadowAlarmManager.scheduledAlarms.size)

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val scheduled = scheduler.schedule(AppSettingsSnapshot(), baseDate)

        assertTrue(scheduled)
        assertEquals(
            allEnabledReminderActions(),
            scheduledActions(),
        )
    }

    @Test
    fun scheduleClearsAlarmsAndSucceedsWithoutExactPermissionWhenRemindersAreDisabled() {
        val baseDate = LocalDate.now().plusDays(1)
        scheduler.schedule(AppSettingsSnapshot(), baseDate)
        assertEquals(5, shadowAlarmManager.scheduledAlarms.size)

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val scheduled = scheduler.schedule(
            AppSettingsSnapshot(
                dailyReviewEnabled = false,
                lateDayReminderEnabled = false,
                taskTimeReminderEnabled = false,
            ),
            baseDate,
        )

        assertTrue(scheduled)
        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Suppress("DEPRECATION")
    private fun scheduledActions(): List<String> {
        return shadowAlarmManager.scheduledAlarms
            .mapNotNull { alarm -> shadowOf(alarm.operation).savedIntent.action }
            .sorted()
    }

    private fun allEnabledReminderActions(): List<String> {
        return listOf(
            HabitReminderReceiver.ACTION_DAILY_REVIEW,
            HabitReminderReceiver.ACTION_EVENING_TASKS,
            HabitReminderReceiver.ACTION_LATE_DAY_REVIEW,
            HabitReminderReceiver.ACTION_MORNING_TASKS,
            HabitReminderReceiver.ACTION_NOON_TASKS,
        ).sorted()
    }
}
