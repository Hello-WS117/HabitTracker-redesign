package com.example.habittracker.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.habittracker.data.settings.AppSettingsSnapshot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderScheduler(
    private val context: Context,
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now() },
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    }

    fun schedule(settings: AppSettingsSnapshot, baseDate: LocalDate? = null): Boolean {
        cancelAll()
        if (!settings.dailyReviewEnabled && !settings.lateDayReminderEnabled && !settings.taskTimeReminderEnabled) return true
        val exact = canScheduleExactAlarms()
        val scheduleBaseDate = baseDate ?: nowProvider().toLocalDate()
        if (settings.dailyReviewEnabled) {
            scheduleAlarm(
                requestCode = DAILY_REVIEW_REQUEST,
                action = HabitReminderReceiver.ACTION_DAILY_REVIEW,
                triggerAt = nextTrigger(scheduleBaseDate, settings.dailyReviewReminderTime),
                exact = exact,
            )
        }
        if (settings.lateDayReminderEnabled) {
            scheduleAlarm(
                requestCode = LATE_DAY_REQUEST,
                action = HabitReminderReceiver.ACTION_LATE_DAY_REVIEW,
                triggerAt = nextTrigger(scheduleBaseDate, settings.lateDayReminderTime),
                exact = exact,
            )
        }
        if (settings.taskTimeReminderEnabled) {
            scheduleAlarm(
                requestCode = MORNING_TASK_REQUEST,
                action = HabitReminderReceiver.ACTION_MORNING_TASKS,
                triggerAt = nextTrigger(scheduleBaseDate, settings.morningTaskReminderTime),
                exact = exact,
            )
            scheduleAlarm(
                requestCode = NOON_TASK_REQUEST,
                action = HabitReminderReceiver.ACTION_NOON_TASKS,
                triggerAt = nextTrigger(scheduleBaseDate, settings.noonTaskReminderTime),
                exact = exact,
            )
            scheduleAlarm(
                requestCode = EVENING_TASK_REQUEST,
                action = HabitReminderReceiver.ACTION_EVENING_TASKS,
                triggerAt = nextTrigger(scheduleBaseDate, settings.eveningTaskReminderTime),
                exact = exact,
            )
        }
        return true
    }

    fun cancelAll() {
        alarmManager.cancel(pendingIntent(DAILY_REVIEW_REQUEST, HabitReminderReceiver.ACTION_DAILY_REVIEW))
        alarmManager.cancel(pendingIntent(LATE_DAY_REQUEST, HabitReminderReceiver.ACTION_LATE_DAY_REVIEW))
        alarmManager.cancel(pendingIntent(MORNING_TASK_REQUEST, HabitReminderReceiver.ACTION_MORNING_TASKS))
        alarmManager.cancel(pendingIntent(NOON_TASK_REQUEST, HabitReminderReceiver.ACTION_NOON_TASKS))
        alarmManager.cancel(pendingIntent(EVENING_TASK_REQUEST, HabitReminderReceiver.ACTION_EVENING_TASKS))
    }

    private fun scheduleAlarm(requestCode: Int, action: String, triggerAt: LocalDateTime, exact: Boolean) {
        val millis = triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = pendingIntent(requestCode, action)
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        }
    }

    private fun pendingIntent(requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, HabitReminderReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun nextTrigger(baseDate: LocalDate, time: java.time.LocalTime): LocalDateTime {
        val candidate = baseDate.atTime(time)
        return if (candidate.isAfter(nowProvider())) candidate else candidate.plusDays(1)
    }

    private companion object {
        const val DAILY_REVIEW_REQUEST = 3101
        const val LATE_DAY_REQUEST = 3102
        const val MORNING_TASK_REQUEST = 3103
        const val NOON_TASK_REQUEST = 3104
        const val EVENING_TASK_REQUEST = 3105
    }
}
