package com.example.habittracker.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

data class AppSettingsSnapshot(
    val dayRolloverTime: LocalTime = LocalTime.of(3, 0),
    val dailyReviewReminderTime: LocalTime = LocalTime.of(8, 0),
    val lateDayReminderTime: LocalTime = LocalTime.of(20, 0),
    val morningTaskReminderTime: LocalTime = LocalTime.of(8, 0),
    val noonTaskReminderTime: LocalTime = LocalTime.of(12, 0),
    val eveningTaskReminderTime: LocalTime = LocalTime.of(18, 0),
    val dailyReviewEnabled: Boolean = true,
    val lateDayReminderEnabled: Boolean = true,
    val taskTimeReminderEnabled: Boolean = true,
    val exactAlarmPermissionPromptShown: Boolean = false,
    val defaultBlockedDays: String = "",
    val themePreference: String = "system",
    val backupLastExportedAt: String = "",
    val autoBackupEnabled: Boolean = false,
    val autoBackupIntervalDays: Int = 7,
    val autoBackupFolderUri: String = "",
    val autoBackupLastRunAt: String = "",
)

class AppSettingsRepository(private val context: Context) {
    val settings: Flow<AppSettingsSnapshot> = context.settingsDataStore.data.map { prefs ->
            AppSettingsSnapshot(
                dayRolloverTime = prefs[DAY_ROLLOVER_TIME]?.let(LocalTime::parse) ?: LocalTime.of(3, 0),
                dailyReviewReminderTime = prefs[DAILY_REVIEW_TIME]?.let(LocalTime::parse) ?: LocalTime.of(8, 0),
                lateDayReminderTime = prefs[LATE_DAY_TIME]?.let(LocalTime::parse) ?: LocalTime.of(20, 0),
                morningTaskReminderTime = prefs[MORNING_TASK_TIME]?.let(LocalTime::parse) ?: LocalTime.of(8, 0),
                noonTaskReminderTime = prefs[NOON_TASK_TIME]?.let(LocalTime::parse) ?: LocalTime.of(12, 0),
                eveningTaskReminderTime = prefs[EVENING_TASK_TIME]?.let(LocalTime::parse) ?: LocalTime.of(18, 0),
                dailyReviewEnabled = prefs[DAILY_REVIEW_ENABLED] ?: true,
                lateDayReminderEnabled = prefs[LATE_DAY_ENABLED] ?: true,
                taskTimeReminderEnabled = prefs[TASK_TIME_ENABLED] ?: true,
                exactAlarmPermissionPromptShown = prefs[EXACT_ALARM_PROMPT_SHOWN] ?: false,
                defaultBlockedDays = prefs[DEFAULT_BLOCKED_DAYS] ?: "",
                themePreference = prefs[THEME_PREFERENCE] ?: "system",
            backupLastExportedAt = prefs[BACKUP_LAST_EXPORTED_AT] ?: "",
            autoBackupEnabled = prefs[AUTO_BACKUP_ENABLED] ?: false,
            autoBackupIntervalDays = (prefs[AUTO_BACKUP_INTERVAL_DAYS] ?: 7).coerceAtLeast(1),
            autoBackupFolderUri = prefs[AUTO_BACKUP_FOLDER_URI] ?: "",
            autoBackupLastRunAt = prefs[AUTO_BACKUP_LAST_RUN_AT] ?: "",
        )
    }

    suspend fun updateDayRolloverTime(time: LocalTime) {
        context.settingsDataStore.edit { it[DAY_ROLLOVER_TIME] = time.toString() }
    }

    suspend fun updateReminderTimes(dailyReview: LocalTime, lateDay: LocalTime) {
        context.settingsDataStore.edit {
            it[DAILY_REVIEW_TIME] = dailyReview.toString()
            it[LATE_DAY_TIME] = lateDay.toString()
        }
    }

    suspend fun updateTaskReminderTimes(morning: LocalTime, noon: LocalTime, evening: LocalTime) {
        context.settingsDataStore.edit {
            it[MORNING_TASK_TIME] = morning.toString()
            it[NOON_TASK_TIME] = noon.toString()
            it[EVENING_TASK_TIME] = evening.toString()
        }
    }

    suspend fun updateReminderEnabled(dailyReview: Boolean, lateDay: Boolean, taskTime: Boolean) {
        context.settingsDataStore.edit {
            it[DAILY_REVIEW_ENABLED] = dailyReview
            it[LATE_DAY_ENABLED] = lateDay
            it[TASK_TIME_ENABLED] = taskTime
        }
    }

    suspend fun setExactAlarmPermissionPromptShown(shown: Boolean) {
        context.settingsDataStore.edit { it[EXACT_ALARM_PROMPT_SHOWN] = shown }
    }

    suspend fun updateDefaultBlockedDays(days: String) {
        context.settingsDataStore.edit { it[DEFAULT_BLOCKED_DAYS] = days }
    }

    suspend fun updateThemePreference(theme: String) {
        context.settingsDataStore.edit { it[THEME_PREFERENCE] = theme }
    }

    suspend fun setBackupLastExportedAt(timestamp: String) {
        context.settingsDataStore.edit { it[BACKUP_LAST_EXPORTED_AT] = timestamp }
    }

    suspend fun updateAutoBackup(enabled: Boolean, intervalDays: Int, folderUri: String) {
        context.settingsDataStore.edit {
            it[AUTO_BACKUP_ENABLED] = enabled
            it[AUTO_BACKUP_INTERVAL_DAYS] = intervalDays.coerceAtLeast(1)
            it[AUTO_BACKUP_FOLDER_URI] = folderUri
        }
    }

    suspend fun setAutoBackupLastRunAt(timestamp: String) {
        context.settingsDataStore.edit { it[AUTO_BACKUP_LAST_RUN_AT] = timestamp }
    }

    suspend fun restore(snapshot: AppSettingsSnapshot) {
        context.settingsDataStore.edit {
            it[DAY_ROLLOVER_TIME] = snapshot.dayRolloverTime.toString()
            it[DAILY_REVIEW_TIME] = snapshot.dailyReviewReminderTime.toString()
            it[LATE_DAY_TIME] = snapshot.lateDayReminderTime.toString()
            it[MORNING_TASK_TIME] = snapshot.morningTaskReminderTime.toString()
            it[NOON_TASK_TIME] = snapshot.noonTaskReminderTime.toString()
            it[EVENING_TASK_TIME] = snapshot.eveningTaskReminderTime.toString()
            it[DAILY_REVIEW_ENABLED] = snapshot.dailyReviewEnabled
            it[LATE_DAY_ENABLED] = snapshot.lateDayReminderEnabled
            it[TASK_TIME_ENABLED] = snapshot.taskTimeReminderEnabled
            it[EXACT_ALARM_PROMPT_SHOWN] = snapshot.exactAlarmPermissionPromptShown
            it[DEFAULT_BLOCKED_DAYS] = snapshot.defaultBlockedDays
            it[THEME_PREFERENCE] = snapshot.themePreference
            it[BACKUP_LAST_EXPORTED_AT] = snapshot.backupLastExportedAt
            it[AUTO_BACKUP_ENABLED] = snapshot.autoBackupEnabled
            it[AUTO_BACKUP_INTERVAL_DAYS] = snapshot.autoBackupIntervalDays.coerceAtLeast(1)
            it[AUTO_BACKUP_FOLDER_URI] = snapshot.autoBackupFolderUri
            it[AUTO_BACKUP_LAST_RUN_AT] = snapshot.autoBackupLastRunAt
        }
    }

    private companion object {
        val DAY_ROLLOVER_TIME = stringPreferencesKey("dayRolloverTime")
        val DAILY_REVIEW_TIME = stringPreferencesKey("dailyReviewReminderTime")
        val LATE_DAY_TIME = stringPreferencesKey("lateDayReminderTime")
        val MORNING_TASK_TIME = stringPreferencesKey("morningTaskReminderTime")
        val NOON_TASK_TIME = stringPreferencesKey("noonTaskReminderTime")
        val EVENING_TASK_TIME = stringPreferencesKey("eveningTaskReminderTime")
        val DAILY_REVIEW_ENABLED = booleanPreferencesKey("dailyReviewEnabled")
        val LATE_DAY_ENABLED = booleanPreferencesKey("lateDayReminderEnabled")
        val TASK_TIME_ENABLED = booleanPreferencesKey("taskTimeReminderEnabled")
        val EXACT_ALARM_PROMPT_SHOWN = booleanPreferencesKey("exactAlarmPermissionPromptShown")
        val DEFAULT_BLOCKED_DAYS = stringPreferencesKey("defaultBlockedDays")
        val THEME_PREFERENCE = stringPreferencesKey("themePreference")
        val BACKUP_LAST_EXPORTED_AT = stringPreferencesKey("backupLastExportedAt")
        val AUTO_BACKUP_ENABLED = booleanPreferencesKey("autoBackupEnabled")
        val AUTO_BACKUP_INTERVAL_DAYS = intPreferencesKey("autoBackupIntervalDays")
        val AUTO_BACKUP_FOLDER_URI = stringPreferencesKey("autoBackupFolderUri")
        val AUTO_BACKUP_LAST_RUN_AT = stringPreferencesKey("autoBackupLastRunAt")
    }
}
