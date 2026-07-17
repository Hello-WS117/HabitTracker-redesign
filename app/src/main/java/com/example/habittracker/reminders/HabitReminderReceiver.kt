package com.example.habittracker.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.habittracker.MainActivity
import com.example.habittracker.R
import com.example.habittracker.data.TaskTimeOfDay
import com.example.habittracker.data.local.HabitDatabase
import com.example.habittracker.data.scheduling.OperationalDayCalculator
import com.example.habittracker.data.settings.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HabitReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                HabitReminderHandler.handleReminder(context.applicationContext, intent.action)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_DAILY_REVIEW = "com.example.habittracker.action.DAILY_REVIEW"
        const val ACTION_LATE_DAY_REVIEW = "com.example.habittracker.action.LATE_DAY_REVIEW"
        const val ACTION_MORNING_TASKS = "com.example.habittracker.action.MORNING_TASKS"
        const val ACTION_NOON_TASKS = "com.example.habittracker.action.NOON_TASKS"
        const val ACTION_EVENING_TASKS = "com.example.habittracker.action.EVENING_TASKS"
        const val CHANNEL_ID = "habit_reminders_alerts"
    }
}

internal object HabitReminderHandler {
    suspend fun handleReminder(context: Context, action: String?) {
        if (!isSupportedAction(action)) return
        val settings = AppSettingsRepository(context).settings.first()
        val operationalDate = OperationalDayCalculator(settings.dayRolloverTime).today()
        val dao = HabitDatabase.get(context).habitDao()
        val pendingCount = dao.pendingReminderOccurrenceCountForDate(operationalDate)

        when (action) {
            HabitReminderReceiver.ACTION_DAILY_REVIEW -> if (settings.dailyReviewEnabled) {
                showNotification(
                    context = context,
                    id = 4101,
                    title = "Daily review",
                    body = if (pendingCount == 0) "No pending tasks for today." else "$pendingCount task(s) scheduled for today.",
                )
            }
            HabitReminderReceiver.ACTION_LATE_DAY_REVIEW -> if (settings.lateDayReminderEnabled && pendingCount > 0) {
                showNotification(
                    context = context,
                    id = 4102,
                    title = "Unchecked tasks",
                    body = "$pendingCount task(s) still need a decision.",
                )
            }
            HabitReminderReceiver.ACTION_MORNING_TASKS -> showTaskTimeNotificationIfNeeded(
                context = context,
                enabled = settings.taskTimeReminderEnabled,
                pendingCount = dao.pendingReminderOccurrenceCountForDateAndTimeOfDay(operationalDate, TaskTimeOfDay.MORNING),
                id = 4103,
                title = "Morning tasks",
                bodyLabel = "morning",
            )
            HabitReminderReceiver.ACTION_NOON_TASKS -> showTaskTimeNotificationIfNeeded(
                context = context,
                enabled = settings.taskTimeReminderEnabled,
                pendingCount = dao.pendingReminderOccurrenceCountForDateAndTimeOfDay(operationalDate, TaskTimeOfDay.NOON),
                id = 4104,
                title = "Noon tasks",
                bodyLabel = "noon",
            )
            HabitReminderReceiver.ACTION_EVENING_TASKS -> showTaskTimeNotificationIfNeeded(
                context = context,
                enabled = settings.taskTimeReminderEnabled,
                pendingCount = dao.pendingReminderOccurrenceCountForDateAndTimeOfDay(operationalDate, TaskTimeOfDay.EVENING),
                id = 4105,
                title = "Evening tasks",
                bodyLabel = "evening",
            )
        }

        ReminderScheduler(context).schedule(settings)
    }

    private fun isSupportedAction(action: String?): Boolean {
        return action == HabitReminderReceiver.ACTION_DAILY_REVIEW ||
            action == HabitReminderReceiver.ACTION_LATE_DAY_REVIEW ||
            action == HabitReminderReceiver.ACTION_MORNING_TASKS ||
            action == HabitReminderReceiver.ACTION_NOON_TASKS ||
            action == HabitReminderReceiver.ACTION_EVENING_TASKS
    }

    private fun showTaskTimeNotificationIfNeeded(
        context: Context,
        enabled: Boolean,
        pendingCount: Int,
        id: Int,
        title: String,
        bodyLabel: String,
    ) {
        if (!enabled || pendingCount <= 0) return
        showNotification(
            context = context,
            id = id,
            title = title,
            body = "$pendingCount $bodyLabel task(s) still need a decision.",
        )
    }

    private fun showNotification(context: Context, id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(HabitReminderReceiver.CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(HabitReminderReceiver.CHANNEL_ID, "Habit reminders", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        enableVibration(true)
                    },
            )
        }
        val openAppIntent = PendingIntent.getActivity(
            context,
            4100,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val publicNotification = Notification.Builder(context, HabitReminderReceiver.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.notification_color))
            .setContentTitle("Habit reminder")
            .setContentText("Open Habit Tracker for details.")
            .setContentIntent(openAppIntent)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        val notification = Notification.Builder(context, HabitReminderReceiver.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.notification_color))
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .build()
        manager.notify(id, notification)
    }
}
