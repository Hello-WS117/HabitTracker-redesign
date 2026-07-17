package com.example.habittracker.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppSettingsRepositoryTest {
    private lateinit var repository: AppSettingsRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository = AppSettingsRepository(context)
        repository.restore(AppSettingsSnapshot())
    }

    @Test
    fun updateMethodsPersistSettingsSnapshot() = runTest {
        repository.updateDayRolloverTime(LocalTime.of(4, 30))
        repository.updateReminderTimes(
            dailyReview = LocalTime.of(7, 15),
            lateDay = LocalTime.of(21, 45),
        )
        repository.updateTaskReminderTimes(
            morning = LocalTime.of(8, 5),
            noon = LocalTime.of(12, 10),
            evening = LocalTime.of(18, 15),
        )
        repository.updateReminderEnabled(dailyReview = false, lateDay = true, taskTime = false)
        repository.setExactAlarmPermissionPromptShown(true)
        repository.updateDefaultBlockedDays("SATURDAY,SUNDAY")
        repository.updateThemePreference("dark")
        repository.setBackupLastExportedAt("2026-05-20T12:00:00")
        repository.updateAutoBackup(
            enabled = true,
            intervalDays = 3,
            folderUri = "content://tree/backups",
        )
        repository.setAutoBackupLastRunAt("2026-05-20T13:00:00")

        val settings = repository.settings.first()

        assertEquals(LocalTime.of(4, 30), settings.dayRolloverTime)
        assertEquals(LocalTime.of(7, 15), settings.dailyReviewReminderTime)
        assertEquals(LocalTime.of(21, 45), settings.lateDayReminderTime)
        assertEquals(LocalTime.of(8, 5), settings.morningTaskReminderTime)
        assertEquals(LocalTime.of(12, 10), settings.noonTaskReminderTime)
        assertEquals(LocalTime.of(18, 15), settings.eveningTaskReminderTime)
        assertFalse(settings.dailyReviewEnabled)
        assertTrue(settings.lateDayReminderEnabled)
        assertFalse(settings.taskTimeReminderEnabled)
        assertTrue(settings.exactAlarmPermissionPromptShown)
        assertEquals("SATURDAY,SUNDAY", settings.defaultBlockedDays)
        assertEquals("dark", settings.themePreference)
        assertEquals("2026-05-20T12:00:00", settings.backupLastExportedAt)
        assertTrue(settings.autoBackupEnabled)
        assertEquals(3, settings.autoBackupIntervalDays)
        assertEquals("content://tree/backups", settings.autoBackupFolderUri)
        assertEquals("2026-05-20T13:00:00", settings.autoBackupLastRunAt)
    }

    @Test
    fun restoreReplacesEverySettingValue() = runTest {
        val restored = AppSettingsSnapshot(
            dayRolloverTime = LocalTime.of(5, 0),
            dailyReviewReminderTime = LocalTime.of(6, 30),
            lateDayReminderTime = LocalTime.of(22, 0),
            morningTaskReminderTime = LocalTime.of(7, 0),
            noonTaskReminderTime = LocalTime.of(13, 0),
            eveningTaskReminderTime = LocalTime.of(19, 30),
            dailyReviewEnabled = false,
            lateDayReminderEnabled = false,
            taskTimeReminderEnabled = false,
            exactAlarmPermissionPromptShown = true,
            defaultBlockedDays = "SUNDAY",
            themePreference = "light",
            backupLastExportedAt = "2026-05-19T08:00:00",
            autoBackupEnabled = true,
            autoBackupIntervalDays = 14,
            autoBackupFolderUri = "content://tree/restored",
            autoBackupLastRunAt = "2026-05-19T09:00:00",
        )

        repository.restore(restored)

        assertEquals(restored, repository.settings.first())
    }
}
