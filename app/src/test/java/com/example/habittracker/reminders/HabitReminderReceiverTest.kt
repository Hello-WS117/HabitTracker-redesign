package com.example.habittracker.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.habittracker.data.OccurrenceStatus
import com.example.habittracker.data.RuleType
import com.example.habittracker.data.SkipBlockedDaysBehavior
import com.example.habittracker.data.TaskType
import com.example.habittracker.data.local.HabitDatabase
import com.example.habittracker.data.local.RecurrenceRuleEntity
import com.example.habittracker.data.local.ScheduledOccurrenceEntity
import com.example.habittracker.data.local.TaskEntity
import com.example.habittracker.data.scheduling.OperationalDayCalculator
import com.example.habittracker.data.settings.AppSettingsRepository
import com.example.habittracker.data.settings.AppSettingsSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNotificationManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HabitReminderReceiverTest {
    private lateinit var context: Context
    private lateinit var database: HabitDatabase
    private lateinit var settingsRepository: AppSettingsRepository
    private lateinit var notificationManager: NotificationManager

    private val dao get() = database.habitDao()
    private val now = LocalDateTime.of(2026, 5, 20, 12, 0)

    @Before
    fun setUp() = runTest {
        ShadowNotificationManager.reset()
        ShadowAlarmManager.reset()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        context = ApplicationProvider.getApplicationContext()
        database = HabitDatabase.get(context)
        settingsRepository = AppSettingsRepository(context)
        notificationManager = context.getSystemService(NotificationManager::class.java)
        settingsRepository.restore(AppSettingsSnapshot())
        clearDatabase()
    }

    @After
    fun tearDown() = runTest {
        clearDatabase()
        ShadowAlarmManager.reset()
        ShadowNotificationManager.reset()
    }

    @Test
    fun dailyReviewCountsOnlyActiveReminderEligiblePendingTasks() = runTest {
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        seedReminderTasks(operationalDate)

        HabitReminderHandler.handleReminder(context, HabitReminderReceiver.ACTION_DAILY_REVIEW)

        val notification = shadowOf(notificationManager).getNotification(4101)
        val channel = notificationManager.getNotificationChannel(HabitReminderReceiver.CHANNEL_ID)
        assertEquals("Daily review", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("1 task(s) scheduled for today.", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(HabitReminderReceiver.CHANNEL_ID, notification.channelId)
        assertEquals(context.getColor(com.example.habittracker.R.color.notification_color), notification.color)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals("Habit reminders", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(Notification.CATEGORY_REMINDER, notification.category)
        assertEquals(Notification.PRIORITY_HIGH, notification.priority)
        assertTrue(notification.defaults and Notification.DEFAULT_ALL != 0)
        assertTrue(channel.shouldVibrate())
    }

    @Test
    @Suppress("DEPRECATION")
    fun scheduledDailyReviewPendingIntentTargetsReceiverHandler() = runTest {
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        settingsRepository.restore(AppSettingsSnapshot(lateDayReminderEnabled = false))
        seedReminderTasks(operationalDate)
        ReminderScheduler(context).schedule(
            AppSettingsSnapshot(lateDayReminderEnabled = false),
            LocalDate.now().plusDays(1),
        )
        val scheduledDailyReview = shadowOf(alarmManager).scheduledAlarms.single { alarm ->
            shadowOf(alarm.operation).savedIntent.action == HabitReminderReceiver.ACTION_DAILY_REVIEW
        }
        val operation = scheduledDailyReview.operation ?: error("Daily review PendingIntent was not scheduled")
        val pendingIntent = shadowOf(operation)
        val intent = pendingIntent.savedIntent

        assertTrue(pendingIntent.isBroadcast)
        assertEquals(HabitReminderReceiver::class.java.name, intent.component?.className)
        assertEquals(HabitReminderReceiver.ACTION_DAILY_REVIEW, intent.action)
        HabitReminderHandler.handleReminder(context, intent.action)

        val notification = shadowOf(notificationManager).getNotification(4101)
        assertEquals("Daily review", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("1 task(s) scheduled for today.", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test
    @Suppress("DEPRECATION")
    fun scheduledDailyReviewPendingIntentDeliveryPostsNotification() = runTest {
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        settingsRepository.restore(AppSettingsSnapshot(lateDayReminderEnabled = false))
        seedReminderTasks(operationalDate)
        ReminderScheduler(context).schedule(
            AppSettingsSnapshot(lateDayReminderEnabled = false),
            LocalDate.now().plusDays(1),
        )
        val scheduledDailyReview = shadowOf(alarmManager).scheduledAlarms.single { alarm ->
            shadowOf(alarm.operation).savedIntent.action == HabitReminderReceiver.ACTION_DAILY_REVIEW
        }

        val operation = scheduledDailyReview.operation ?: error("Daily review PendingIntent was not scheduled")
        HabitReminderReceiver().onReceive(context, shadowOf(operation).savedIntent)

        val notification = awaitNotification(4101)
        assertEquals("Daily review", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("1 task(s) scheduled for today.", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test
    fun reminderNotificationCopyDoesNotExposeTaskNamesOrNotes() = runTest {
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        dao.restoreTasks(
            listOf(
                task(
                    id = 1,
                    name = "Private supplement protocol",
                    notes = "Personal lab note",
                ),
            ),
        )
        dao.restoreRules(listOf(rule(id = 1, taskId = 1)))
        dao.restoreOccurrences(
            listOf(
                occurrence(
                    id = 1,
                    taskId = 1,
                    ruleId = 1,
                    operationalDate = operationalDate,
                    note = "Sensitive occurrence note",
                ),
            ),
        )

        HabitReminderHandler.handleReminder(context, HabitReminderReceiver.ACTION_DAILY_REVIEW)

        val notification = shadowOf(notificationManager).getNotification(4101)
        val publicVersion = notification.publicVersion ?: error("Reminder notification must provide a public lock-screen version")
        val visiblePayload = listOf(
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        ).joinToString(" ")
        assertEquals("1 task(s) scheduled for today.", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("Habit reminder", publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Open Habit Tracker for details.", publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertFalse(visiblePayload.contains("Private supplement protocol"))
        assertFalse(visiblePayload.contains("Personal lab note"))
        assertFalse(visiblePayload.contains("Sensitive occurrence note"))
    }

    @Test
    fun lateDayReminderPostsUncheckedTasksNotificationWhenEligiblePendingTasksRemain() = runTest {
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        seedReminderTasks(operationalDate)

        HabitReminderHandler.handleReminder(context, HabitReminderReceiver.ACTION_LATE_DAY_REVIEW)

        val notification = shadowOf(notificationManager).getNotification(4102)
        assertEquals("Unchecked tasks", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("1 task(s) still need a decision.", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(HabitReminderReceiver.CHANNEL_ID, notification.channelId)
    }

    @Test
    fun lateDayReminderSuppressesWhenNoEligiblePendingTasksRemain() = runTest {
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        dao.restoreTasks(
            listOf(
                task(id = 1, name = "Disabled reminder", reminderEnabled = false),
                task(id = 2, name = "Archived", archived = true, isActive = false),
                task(id = 3, name = "Completed"),
            ),
        )
        dao.restoreRules(listOf(rule(id = 1, taskId = 1), rule(id = 2, taskId = 2), rule(id = 3, taskId = 3)))
        dao.restoreOccurrences(
            listOf(
                occurrence(id = 1, taskId = 1, ruleId = 1, operationalDate = operationalDate),
                occurrence(id = 2, taskId = 2, ruleId = 2, operationalDate = operationalDate),
                occurrence(id = 3, taskId = 3, ruleId = 3, operationalDate = operationalDate, status = OccurrenceStatus.COMPLETED),
            ),
        )

        HabitReminderHandler.handleReminder(context, HabitReminderReceiver.ACTION_LATE_DAY_REVIEW)

        assertNull(shadowOf(notificationManager).getNotification(4102))
    }

    @Test
    fun pendingReminderCountFiltersTaskEligibilityInDaoQuery() = runTest {
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        seedReminderTasks(operationalDate)
        dao.restoreTasks(listOf(task(id = 5, name = "Tomorrow")))
        dao.restoreRules(listOf(rule(id = 5, taskId = 5)))
        dao.restoreOccurrences(listOf(occurrence(id = 5, taskId = 5, ruleId = 5, operationalDate = operationalDate.plusDays(1))))

        assertEquals(1, dao.pendingReminderOccurrenceCountForDate(operationalDate))
    }

    @Test
    fun pendingReminderCountIncludesOverdueLongTermTasks() = runTest {
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        dao.restoreTasks(
            listOf(
                task(id = 1, name = "Eligible"),
                task(id = 2, name = "Change filters", taskType = TaskType.LONG_TERM),
                task(id = 3, name = "Future filters", taskType = TaskType.LONG_TERM),
            ),
        )
        dao.restoreRules(
            listOf(
                rule(id = 1, taskId = 1),
                rule(
                    id = 2,
                    taskId = 2,
                    ruleType = RuleType.EVERY_X_MONTHS,
                    startDate = operationalDate.minusMonths(6),
                    intervalDays = 6,
                ),
                rule(
                    id = 3,
                    taskId = 3,
                    ruleType = RuleType.EVERY_X_MONTHS,
                    startDate = operationalDate.plusDays(1),
                    intervalDays = 6,
                ),
            ),
        )
        dao.restoreOccurrences(
            listOf(
                occurrence(id = 1, taskId = 1, ruleId = 1, operationalDate = operationalDate),
                occurrence(id = 2, taskId = 2, ruleId = 2, operationalDate = operationalDate.minusDays(7)),
                occurrence(id = 3, taskId = 3, ruleId = 3, operationalDate = operationalDate.plusDays(1)),
                occurrence(id = 4, taskId = 2, ruleId = 2, operationalDate = operationalDate.minusDays(1)),
            ),
        )

        assertEquals(2, dao.pendingReminderOccurrenceCountForDate(operationalDate))
    }

    @Test
    @Config(sdk = [33])
    fun notificationPermissionDeniedSuppressesAndroidThirteenNotification() = runTest {
        shadowOf(context as ContextWrapper).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        seedReminderTasks(operationalDate)

        HabitReminderHandler.handleReminder(context, HabitReminderReceiver.ACTION_DAILY_REVIEW)

        assertNull(shadowOf(notificationManager).getNotification(4101))
        assertNull(notificationManager.getNotificationChannel(HabitReminderReceiver.CHANNEL_ID))
    }

    @Test
    fun dailyReviewSuppressesWhenReminderSettingDisabled() = runTest {
        settingsRepository.restore(AppSettingsSnapshot(dailyReviewEnabled = false))
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        seedReminderTasks(operationalDate)

        HabitReminderHandler.handleReminder(context, HabitReminderReceiver.ACTION_DAILY_REVIEW)

        assertNull(shadowOf(notificationManager).getNotification(4101))
    }

    @Test
    fun lateDayReviewSuppressesWhenReminderSettingDisabled() = runTest {
        settingsRepository.restore(AppSettingsSnapshot(lateDayReminderEnabled = false))
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        seedReminderTasks(operationalDate)

        HabitReminderHandler.handleReminder(context, HabitReminderReceiver.ACTION_LATE_DAY_REVIEW)

        assertNull(shadowOf(notificationManager).getNotification(4102))
    }

    @Test
    fun unsupportedReminderActionDoesNotPostOrReschedule() = runTest {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val operationalDate = OperationalDayCalculator(LocalTime.of(3, 0)).today()
        seedReminderTasks(operationalDate)

        HabitReminderHandler.handleReminder(context, "com.example.habittracker.action.UNKNOWN")

        assertNull(shadowOf(notificationManager).getNotification(4101))
        assertNull(shadowOf(notificationManager).getNotification(4102))
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    private suspend fun seedReminderTasks(operationalDate: LocalDate) {
        dao.restoreTasks(
            listOf(
                task(id = 1, name = "Eligible"),
                task(id = 2, name = "Disabled reminder", reminderEnabled = false),
                task(id = 3, name = "Archived", archived = true, isActive = false),
                task(id = 4, name = "Completed"),
            ),
        )
        dao.restoreRules(
            listOf(
                rule(id = 1, taskId = 1),
                rule(id = 2, taskId = 2),
                rule(id = 3, taskId = 3),
                rule(id = 4, taskId = 4),
            ),
        )
        dao.restoreOccurrences(
            listOf(
                occurrence(id = 1, taskId = 1, ruleId = 1, operationalDate = operationalDate),
                occurrence(id = 2, taskId = 2, ruleId = 2, operationalDate = operationalDate),
                occurrence(id = 3, taskId = 3, ruleId = 3, operationalDate = operationalDate),
                occurrence(id = 4, taskId = 4, ruleId = 4, operationalDate = operationalDate, status = OccurrenceStatus.COMPLETED),
            ),
        )
    }

    private suspend fun clearDatabase() {
        dao.deleteAllLogs()
        dao.deleteAllOccurrences()
        dao.deleteAllSequenceItems()
        dao.deleteAllSequences()
        dao.deleteAllRules()
        dao.deleteAllTasks()
    }

    private fun task(
        id: Long,
        name: String,
        reminderEnabled: Boolean = true,
        archived: Boolean = false,
        isActive: Boolean = true,
        notes: String = "",
        taskType: TaskType = TaskType.SIMPLE_HABIT,
    ) = TaskEntity(
        id = id,
        name = name,
        taskType = taskType,
        notes = notes,
        isActive = isActive,
        archived = archived,
        createdAt = now,
        updatedAt = now,
        defaultReminderEnabled = reminderEnabled,
    )

    private fun rule(
        id: Long,
        taskId: Long,
        ruleType: RuleType = RuleType.DAILY,
        startDate: LocalDate = LocalDate.of(2026, 5, 20),
        intervalDays: Int? = null,
    ) = RecurrenceRuleEntity(
        id = id,
        taskId = taskId,
        ruleType = ruleType,
        intervalDays = intervalDays,
        startDate = startDate,
        skipBlockedDaysBehavior = SkipBlockedDaysBehavior.SKIP_BLOCKED_DAY,
        createdAt = now,
        updatedAt = now,
    )

    private fun occurrence(
        id: Long,
        taskId: Long,
        ruleId: Long,
        operationalDate: LocalDate,
        status: OccurrenceStatus = OccurrenceStatus.PENDING,
        note: String = "",
    ) = ScheduledOccurrenceEntity(
        id = id,
        taskId = taskId,
        recurrenceRuleId = ruleId,
        scheduledDate = operationalDate,
        operationalDate = operationalDate,
        status = status,
        note = note,
        createdAt = now,
        updatedAt = now,
    )

    private fun awaitNotification(id: Int): Notification {
        repeat(100) {
            val notification = shadowOf(notificationManager).getNotification(id)
            if (notification != null) return notification
            Thread.sleep(20)
        }
        throw AssertionError("Notification $id was not posted")
    }
}
