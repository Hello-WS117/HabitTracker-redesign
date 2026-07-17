package com.example.habittracker.backup

import com.example.habittracker.data.LogAction
import com.example.habittracker.data.NoActionBehavior
import com.example.habittracker.data.OccurrenceStatus
import com.example.habittracker.data.RuleType
import com.example.habittracker.data.SkipBlockedDaysBehavior
import com.example.habittracker.data.TaskTimeOfDay
import com.example.habittracker.data.TaskType
import com.example.habittracker.data.local.CompletionLogEntity
import com.example.habittracker.data.local.RecurrenceRuleEntity
import com.example.habittracker.data.local.ScheduledOccurrenceEntity
import com.example.habittracker.data.local.SequenceItemEntity
import com.example.habittracker.data.local.TaskEntity
import com.example.habittracker.data.local.WorkoutSequenceEntity
import com.example.habittracker.data.settings.AppSettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class BackupModelsMappingTest {
    private val createdAt = LocalDateTime.of(2026, 5, 20, 9, 15)
    private val updatedAt = LocalDateTime.of(2026, 5, 21, 18, 45)
    private val scheduledDate = LocalDate.of(2026, 5, 22)
    private val operationalDate = LocalDate.of(2026, 5, 23)

    @Test
    fun taskBackupRoundTripPreservesAllFields() {
        val entity = TaskEntity(
            id = 7,
            name = "Hydrate",
            taskType = TaskType.SIMPLE_HABIT,
            notes = "Drink water",
            isActive = false,
            archived = true,
            createdAt = createdAt,
            updatedAt = updatedAt,
            defaultReminderEnabled = false,
            calendarVisible = false,
            blockedDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY),
            timeOfDay = TaskTimeOfDay.MORNING,
            pushable = true,
            noActionBehavior = NoActionBehavior.AUTO_PUSH,
        )

        val backup = entity.toBackup()

        assertEquals(
            TaskBackup(
                id = 7,
                name = "Hydrate",
                taskType = "SIMPLE_HABIT",
                notes = "Drink water",
                isActive = false,
                archived = true,
                createdAt = "2026-05-20T09:15",
                updatedAt = "2026-05-21T18:45",
                defaultReminderEnabled = false,
                calendarVisible = false,
                blockedDays = listOf("MONDAY", "WEDNESDAY"),
                timeOfDay = "MORNING",
                pushable = true,
                noActionBehavior = "AUTO_PUSH",
            ),
            backup,
        )
        assertEquals(entity, backup.toEntity())
    }

    @Test
    fun recurrenceRuleBackupRoundTripPreservesAllFields() {
        val entity = RecurrenceRuleEntity(
            id = 8,
            taskId = 7,
            ruleType = RuleType.EVERY_X_DAYS,
            intervalDays = 3,
            weekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY),
            cycleDefinition = "Push,Pull,Legs",
            startDate = LocalDate.of(2026, 5, 20),
            endDate = LocalDate.of(2026, 6, 30),
            durationDays = 42,
            startsAfterTaskId = 6,
            skipBlockedDaysBehavior = SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY,
            lastGeneratedDate = LocalDate.of(2026, 6, 15),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        val backup = entity.toBackup()

        assertEquals(
            RecurrenceRuleBackup(
                id = 8,
                taskId = 7,
                ruleType = "EVERY_X_DAYS",
                intervalDays = 3,
                weekdays = listOf("FRIDAY", "TUESDAY"),
                cycleDefinition = "Push,Pull,Legs",
                startDate = "2026-05-20",
                endDate = "2026-06-30",
                durationDays = 42,
                startsAfterTaskId = 6,
                skipBlockedDaysBehavior = "MOVE_TO_NEXT_VALID_DAY",
                lastGeneratedDate = "2026-06-15",
                createdAt = "2026-05-20T09:15",
                updatedAt = "2026-05-21T18:45",
            ),
            backup,
        )
        assertEquals(entity, backup.toEntity())
    }

    @Test
    fun scheduledOccurrenceBackupRoundTripPreservesAllFields() {
        val entity = ScheduledOccurrenceEntity(
            id = 9,
            taskId = 7,
            recurrenceRuleId = 8,
            scheduledDate = scheduledDate,
            operationalDate = operationalDate,
            status = OccurrenceStatus.SHIFTED,
            sequenceItemId = 11,
            isShifted = true,
            originalDate = LocalDate.of(2026, 5, 21),
            note = "Shifted for travel",
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        val backup = entity.toBackup()

        assertEquals(
            ScheduledOccurrenceBackup(
                id = 9,
                taskId = 7,
                recurrenceRuleId = 8,
                scheduledDate = "2026-05-22",
                operationalDate = "2026-05-23",
                status = "SHIFTED",
                sequenceItemId = 11,
                isShifted = true,
                originalDate = "2026-05-21",
                note = "Shifted for travel",
                createdAt = "2026-05-20T09:15",
                updatedAt = "2026-05-21T18:45",
            ),
            backup,
        )
        assertEquals(entity, backup.toEntity())
    }

    @Test
    fun completionLogBackupRoundTripPreservesAllFields() {
        val entity = CompletionLogEntity(
            id = 12,
            occurrenceId = 9,
            taskId = 7,
            action = LogAction.SHIFTED_FORWARD,
            timestamp = updatedAt,
            operationalDate = operationalDate,
            note = "Moved to tomorrow",
            createdAt = createdAt,
        )

        val backup = entity.toBackup()

        assertEquals(
            CompletionLogBackup(
                id = 12,
                occurrenceId = 9,
                taskId = 7,
                action = "SHIFTED_FORWARD",
                timestamp = "2026-05-21T18:45",
                operationalDate = "2026-05-23",
                note = "Moved to tomorrow",
                createdAt = "2026-05-20T09:15",
            ),
            backup,
        )
        assertEquals(entity, backup.toEntity())
    }

    @Test
    fun workoutSequenceBackupRoundTripPreservesAllFields() {
        val entity = WorkoutSequenceEntity(
            id = 10,
            taskId = 7,
            name = "Strength split",
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        val backup = entity.toBackup()

        assertEquals(
            WorkoutSequenceBackup(
                id = 10,
                taskId = 7,
                name = "Strength split",
                createdAt = "2026-05-20T09:15",
                updatedAt = "2026-05-21T18:45",
            ),
            backup,
        )
        assertEquals(entity, backup.toEntity())
    }

    @Test
    fun sequenceItemBackupRoundTripPreservesAllFields() {
        val entity = SequenceItemEntity(
            id = 11,
            sequenceId = 10,
            name = "Pull",
            position = 2,
            notes = "Rows and curls",
        )

        val backup = entity.toBackup()

        assertEquals(
            SequenceItemBackup(
                id = 11,
                sequenceId = 10,
                name = "Pull",
                position = 2,
                notes = "Rows and curls",
            ),
            backup,
        )
        assertEquals(entity, backup.toEntity())
    }

    @Test
    fun settingsBackupRoundTripPreservesAllFields() {
        val snapshot = AppSettingsSnapshot(
            dayRolloverTime = LocalTime.of(4, 30),
            dailyReviewReminderTime = LocalTime.of(7, 45),
            lateDayReminderTime = LocalTime.of(21, 15),
            dailyReviewEnabled = false,
            lateDayReminderEnabled = false,
            exactAlarmPermissionPromptShown = true,
            defaultBlockedDays = "SATURDAY,SUNDAY",
            themePreference = "dark",
            backupLastExportedAt = "2026-05-19T10:30",
            autoBackupEnabled = true,
            autoBackupIntervalDays = 3,
            autoBackupFolderUri = "content://tree/backups",
            autoBackupLastRunAt = "2026-05-19T11:30",
        )

        val backup = snapshot.toBackup()

        assertEquals(
            SettingsBackup(
                dayRolloverTime = "04:30",
                dailyReviewReminderTime = "07:45",
                lateDayReminderTime = "21:15",
                dailyReviewEnabled = false,
                lateDayReminderEnabled = false,
                exactAlarmPermissionPromptShown = true,
                defaultBlockedDays = "SATURDAY,SUNDAY",
                themePreference = "dark",
                backupLastExportedAt = "2026-05-19T10:30",
                autoBackupEnabled = true,
                autoBackupIntervalDays = 3,
                autoBackupFolderUri = "content://tree/backups",
                autoBackupLastRunAt = "2026-05-19T11:30",
            ),
            backup,
        )
        assertEquals(snapshot, backup.toSnapshot())
    }
}
