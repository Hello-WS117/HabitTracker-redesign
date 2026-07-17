package com.example.habittracker.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupValidatorTest {
    @Test
    fun validBackupPassesValidation() {
        assertNull(BackupValidator.validate(validBackup()))
    }

    @Test
    fun validLongTermBackupPassesValidation() {
        val backup = validBackup().copy(
            tasks = listOf(validTask(taskType = "LONG_TERM")),
            recurrenceRules = listOf(validRule(ruleType = "EVERY_X_MONTHS", intervalDays = 6)),
            scheduledOccurrences = listOf(validOccurrence(sequenceItemId = null)),
            completionLogs = emptyList(),
            workoutSequences = emptyList(),
            sequenceItems = emptyList(),
        )

        assertNull(BackupValidator.validate(backup))
    }

    @Test
    fun validLongTermBackupAllowsDayWeekAndYearRules() {
        listOf("EVERY_X_DAYS", "EVERY_X_WEEKS", "EVERY_X_YEARS").forEach { ruleType ->
            val backup = validBackup().copy(
                tasks = listOf(validTask(taskType = "LONG_TERM")),
                recurrenceRules = listOf(validRule(ruleType = ruleType, intervalDays = 1)),
                scheduledOccurrences = listOf(validOccurrence(sequenceItemId = null)),
                completionLogs = emptyList(),
                workoutSequences = emptyList(),
                sequenceItems = emptyList(),
            )

            assertNull("Expected $ruleType to be valid for long-term tasks", BackupValidator.validate(backup))
        }
    }

    @Test
    fun unsupportedSchemaFailsValidation() {
        val backup = validBackup().copy(schemaVersion = 99)

        assertEquals("Unsupported backup schema 99", BackupValidator.validate(backup))
    }

    @Test
    fun duplicateIdsFailValidation() {
        assertEquals(
            "Backup contains duplicate task IDs",
            BackupValidator.validate(validBackup().copy(tasks = listOf(validTask(id = 1), validTask(id = 1)))),
        )
        assertEquals(
            "Backup contains duplicate recurrence rule IDs",
            BackupValidator.validate(validBackup().copy(recurrenceRules = listOf(validRule(id = 1), validRule(id = 1)))),
        )
        assertEquals(
            "Backup contains duplicate occurrence IDs",
            BackupValidator.validate(
                validBackup().copy(scheduledOccurrences = listOf(validOccurrence(id = 1), validOccurrence(id = 1))),
            ),
        )
        assertEquals(
            "Backup contains duplicate log IDs",
            BackupValidator.validate(validBackup().copy(completionLogs = listOf(validLog(id = 1), validLog(id = 1)))),
        )
        assertEquals(
            "Backup contains duplicate sequence IDs",
            BackupValidator.validate(
                validBackup().copy(workoutSequences = listOf(validSequence(id = 1), validSequence(id = 1))),
            ),
        )
        assertEquals(
            "Backup contains duplicate sequence item IDs",
            BackupValidator.validate(
                validBackup().copy(sequenceItems = listOf(validSequenceItem(id = 1), validSequenceItem(id = 1))),
            ),
        )
    }

    @Test
    fun duplicateScheduledOccurrenceKeysFailValidation() {
        val backup = validBackup().copy(
            scheduledOccurrences = listOf(
                validOccurrence(id = 1),
                validOccurrence(id = 2),
            ),
        )

        assertEquals("Backup contains duplicate scheduled occurrences", BackupValidator.validate(backup))
    }

    @Test
    fun duplicateRuleDateOccurrencesFailValidationEvenWithDifferentSequenceItems() {
        val backup = validBackup().copy(
            sequenceItems = listOf(
                validSequenceItem(id = 1, position = 0),
                validSequenceItem(id = 2, position = 1),
            ),
            scheduledOccurrences = listOf(
                validOccurrence(id = 1, sequenceItemId = 1),
                validOccurrence(id = 2, sequenceItemId = 2),
            ),
            completionLogs = emptyList(),
        )

        assertEquals("Backup contains duplicate scheduled occurrences", BackupValidator.validate(backup))
    }

    @Test
    fun nonPositiveIdsFailValidation() {
        assertEquals(
            "Backup contains an invalid recurrence rule ID",
            BackupValidator.validate(validBackup().copy(recurrenceRules = listOf(validRule(id = 0)))),
        )
        assertEquals(
            "Backup contains an invalid occurrence ID",
            BackupValidator.validate(validBackup().copy(scheduledOccurrences = listOf(validOccurrence(id = 0)))),
        )
        assertEquals(
            "Backup contains an invalid log ID",
            BackupValidator.validate(validBackup().copy(completionLogs = listOf(validLog(id = 0)))),
        )
        assertEquals(
            "Backup contains an invalid sequence ID",
            BackupValidator.validate(validBackup().copy(workoutSequences = listOf(validSequence(id = 0)))),
        )
        assertEquals(
            "Backup contains an invalid sequence item",
            BackupValidator.validate(validBackup().copy(sequenceItems = listOf(validSequenceItem(id = 0)))),
        )
        assertEquals(
            "Backup contains an invalid sequence item",
            BackupValidator.validate(validBackup().copy(sequenceItems = listOf(validSequenceItem(position = -1)))),
        )
    }

    @Test
    fun occurrenceWithMissingRuleFailsValidation() {
        val backup = validBackup().copy(
            scheduledOccurrences = listOf(validOccurrence(recurrenceRuleId = 999)),
        )

        assertEquals("Backup contains an occurrence without a task or rule", BackupValidator.validate(backup))
    }

    @Test
    fun recurrenceRuleWithMissingTaskFailsValidation() {
        val backup = validBackup().copy(
            recurrenceRules = listOf(validRule(taskId = 999)),
        )

        assertEquals("Backup contains a recurrence rule without a task", BackupValidator.validate(backup))
    }

    @Test
    fun sequenceWithMissingTaskFailsValidation() {
        val backup = validBackup().copy(
            workoutSequences = listOf(validSequence(taskId = 999)),
        )

        assertEquals("Backup contains a sequence without a task", BackupValidator.validate(backup))
    }

    @Test
    fun sequenceItemWithMissingSequenceFailsValidation() {
        val backup = validBackup().copy(
            sequenceItems = listOf(validSequenceItem(sequenceId = 999)),
        )

        assertEquals("Backup contains a sequence item without a sequence", BackupValidator.validate(backup))
    }

    @Test
    fun occurrenceWithUnknownSequenceItemFailsValidation() {
        val backup = validBackup().copy(
            scheduledOccurrences = listOf(validOccurrence(sequenceItemId = 999)),
        )

        assertEquals("Backup contains an occurrence with an unknown sequence item", BackupValidator.validate(backup))
    }

    @Test
    fun logWithMissingOccurrenceFailsValidation() {
        val backup = validBackup().copy(
            completionLogs = listOf(validLog(occurrenceId = 999)),
        )

        assertEquals("Backup contains a log without an occurrence", BackupValidator.validate(backup))
    }

    @Test
    fun weekdayRuleWithoutWeekdaysFailsValidation() {
        val backup = validBackup().copy(
            recurrenceRules = listOf(validRule(ruleType = "WEEKDAYS", weekdays = emptyList())),
        )

        assertEquals("Backup contains a weekday rule without weekdays", BackupValidator.validate(backup))
    }

    @Test
    fun intervalRuleWithoutIntervalFailsValidation() {
        val backup = validBackup().copy(
            recurrenceRules = listOf(validRule(ruleType = "EVERY_X_DAYS", intervalDays = null)),
        )

        assertEquals("Backup contains an interval rule without an interval", BackupValidator.validate(backup))
    }

    @Test
    fun longTermRuleWithoutIntervalFailsValidation() {
        val backup = validBackup().copy(
            tasks = listOf(validTask(taskType = "LONG_TERM")),
            recurrenceRules = listOf(validRule(ruleType = "EVERY_X_MONTHS", intervalDays = null)),
            scheduledOccurrences = listOf(validOccurrence(sequenceItemId = null)),
            completionLogs = emptyList(),
            workoutSequences = emptyList(),
            sequenceItems = emptyList(),
        )

        assertEquals("Backup contains a long-term rule without an interval", BackupValidator.validate(backup))
    }

    @Test
    fun invalidEnumFailsBeforeRestoreConversion() {
        val backup = validBackup().copy(
            tasks = listOf(validTask(taskType = "NETWORK_SYNC")),
        )

        assertEquals("Backup contains invalid enum, date, or timestamp data", BackupValidator.validate(backup))
    }

    @Test
    fun invalidSettingsValuesFailBeforeRestoreConversion() {
        val invalidBlockedDay = validBackup().let { backup ->
            backup.copy(appSettings = backup.appSettings.copy(defaultBlockedDays = "SOMEDAY"))
        }
        val invalidTheme = validBackup().let { backup ->
            backup.copy(appSettings = backup.appSettings.copy(themePreference = "sepia"))
        }
        val invalidBackupTimestamp = validBackup().let { backup ->
            backup.copy(appSettings = backup.appSettings.copy(backupLastExportedAt = "May 20, 2026"))
        }

        assertEquals("Backup contains invalid enum, date, or timestamp data", BackupValidator.validate(invalidBlockedDay))
        assertEquals("Backup contains invalid enum, date, or timestamp data", BackupValidator.validate(invalidTheme))
        assertEquals("Backup contains invalid enum, date, or timestamp data", BackupValidator.validate(invalidBackupTimestamp))
    }

    @Test
    fun duplicateSequencePositionsFailValidation() {
        val backup = validBackup().copy(
            sequenceItems = listOf(
                validSequenceItem(id = 1, position = 0),
                validSequenceItem(id = 2, position = 0),
            ),
        )

        assertEquals("Backup contains duplicate sequence positions", BackupValidator.validate(backup))
    }

    @Test
    fun occurrenceWithRuleFromAnotherTaskFailsValidation() {
        val backup = validBackup().copy(
            tasks = listOf(validTask(id = 1), validTask(id = 2)),
            recurrenceRules = listOf(validRule(id = 1, taskId = 2)),
            workoutSequences = listOf(validSequence(taskId = 1)),
            scheduledOccurrences = listOf(validOccurrence(taskId = 1, recurrenceRuleId = 1)),
        )

        assertEquals("Backup contains an occurrence with a rule from another task", BackupValidator.validate(backup))
    }

    @Test
    fun occurrenceWithSequenceItemFromAnotherTaskFailsValidation() {
        val backup = validBackup().copy(
            tasks = listOf(validTask(id = 1), validTask(id = 2)),
            workoutSequences = listOf(
                validSequence(id = 1, taskId = 1),
                validSequence(id = 2, taskId = 2),
            ),
            sequenceItems = listOf(
                validSequenceItem(id = 1, sequenceId = 1),
                validSequenceItem(id = 2, sequenceId = 2),
            ),
            scheduledOccurrences = listOf(validOccurrence(taskId = 1, sequenceItemId = 2)),
        )

        assertEquals("Backup contains an occurrence with a sequence item from another task", BackupValidator.validate(backup))
    }

    @Test
    fun recurrenceRuleThatDoesNotMatchTaskTypeFailsValidation() {
        val backup = validBackup().copy(
            tasks = listOf(validTask(taskType = "SIMPLE_HABIT")),
            recurrenceRules = listOf(validRule()),
        )

        assertEquals("Backup contains a recurrence rule that does not match its task type", BackupValidator.validate(backup))
    }

    @Test
    fun unusedSequenceAttachedToNonSequenceTaskFailsValidation() {
        val backup = validBackup().copy(
            tasks = listOf(validTask(taskType = "SIMPLE_HABIT")),
            recurrenceRules = listOf(validRule(ruleType = "DAILY", intervalDays = null)),
            workoutSequences = listOf(validSequence()),
            sequenceItems = emptyList(),
            scheduledOccurrences = listOf(validOccurrence(sequenceItemId = null)),
        )

        assertEquals("Backup contains an unused sequence for a non-sequence task", BackupValidator.validate(backup))
    }

    @Test
    fun legacySequenceHistoryForNonSequenceTaskPassesValidation() {
        val backup = validBackup().copy(
            tasks = listOf(validTask(taskType = "SIMPLE_HABIT")),
            recurrenceRules = listOf(validRule(ruleType = "DAILY", intervalDays = null)),
            scheduledOccurrences = listOf(validOccurrence(sequenceItemId = 1)),
            completionLogs = listOf(validLog()),
            workoutSequences = listOf(validSequence()),
            sequenceItems = listOf(validSequenceItem()),
        )

        assertEquals(null, BackupValidator.validate(backup))
    }

    @Test
    fun sequenceTaskWithoutSequenceFailsValidation() {
        val backup = validBackup().copy(
            workoutSequences = emptyList(),
            sequenceItems = emptyList(),
            scheduledOccurrences = emptyList(),
            completionLogs = emptyList(),
        )

        assertEquals("Backup contains a sequence task without a sequence", BackupValidator.validate(backup))
    }

    @Test
    fun sequenceWithoutItemsFailsValidation() {
        val backup = validBackup().copy(
            sequenceItems = emptyList(),
            scheduledOccurrences = emptyList(),
            completionLogs = emptyList(),
        )

        assertEquals("Backup contains a sequence without items", BackupValidator.validate(backup))
    }

    @Test
    fun sequenceOccurrenceWithoutItemFailsValidation() {
        val backup = validBackup().copy(
            scheduledOccurrences = listOf(validOccurrence(sequenceItemId = null)),
            completionLogs = emptyList(),
        )

        assertEquals("Backup contains a sequence occurrence without an item", BackupValidator.validate(backup))
    }

    @Test
    fun logWithOccurrenceFromAnotherTaskFailsValidation() {
        val backup = validBackup().copy(
            tasks = listOf(validTask(id = 1), validTask(id = 2, taskType = "SIMPLE_HABIT")),
            completionLogs = listOf(validLog(taskId = 2, occurrenceId = 1)),
        )

        assertEquals("Backup contains a log with an occurrence from another task", BackupValidator.validate(backup))
    }

    @Test
    fun statusChangingLogWithoutOccurrenceFailsValidation() {
        val backup = validBackup().copy(
            completionLogs = listOf(validLog(occurrenceId = null)),
        )

        assertEquals("Backup contains a status log without an occurrence", BackupValidator.validate(backup))
    }

    @Test
    fun historicalStatusChangingLogWithDifferentCurrentOccurrenceStatusPassesValidation() {
        val backup = validBackup().copy(
            scheduledOccurrences = listOf(validOccurrence(status = "SKIPPED")),
            completionLogs = listOf(validLog(action = "COMPLETED")),
        )

        assertEquals(null, BackupValidator.validate(backup))
    }

    @Test
    fun olderMissedLogFollowedByShiftedLogPassesValidation() {
        val backup = validBackup().copy(
            scheduledOccurrences = listOf(validOccurrence(status = "SHIFTED")),
            completionLogs = listOf(
                validLog(id = 1, action = "MARKED_MISSED").copy(timestamp = "2026-05-20T03:05:00"),
                validLog(id = 2, action = "SHIFTED_FORWARD").copy(timestamp = "2026-05-20T09:00:00"),
            ),
        )

        assertEquals(null, BackupValidator.validate(backup))
    }

    @Test
    fun missedShiftedOccurrenceWithLatestShiftLogPassesValidation() {
        val backup = validBackup().copy(
            scheduledOccurrences = listOf(
                validOccurrence(status = "MISSED").copy(
                    isShifted = true,
                    originalDate = "2026-05-20",
                ),
            ),
            completionLogs = listOf(
                validLog(id = 1, action = "MARKED_MISSED").copy(timestamp = "2026-05-20T03:05:00"),
                validLog(id = 2, action = "SHIFTED_FORWARD").copy(timestamp = "2026-05-20T09:00:00"),
            ),
        )

        assertEquals(null, BackupValidator.validate(backup))
    }

    @Test
    fun occurrenceAfterLastGeneratedDateRemainsRestorable() {
        val backup = validBackup().copy(
            recurrenceRules = listOf(validRule(startDate = "2026-05-01", lastGeneratedDate = "2026-05-19")),
            scheduledOccurrences = listOf(validOccurrence(scheduledDate = "2026-05-20")),
        )

        assertEquals(null, BackupValidator.validate(backup))
    }

    @Test
    fun pendingOccurrenceBeforeRuleStartDateRemainsRestorable() {
        val backup = validBackup().copy(
            scheduledOccurrences = listOf(validOccurrence(scheduledDate = "2026-05-19", status = "PENDING")),
        )

        assertEquals(null, BackupValidator.validate(backup))
    }

    @Test
    fun pendingOccurrenceAfterRuleEndDateRemainsRestorable() {
        val backup = validBackup().copy(
            recurrenceRules = listOf(validRule(endDate = "2026-05-21", lastGeneratedDate = "2026-05-21")),
            scheduledOccurrences = listOf(validOccurrence(scheduledDate = "2026-05-22", status = "PENDING")),
        )

        assertEquals(null, BackupValidator.validate(backup))
    }

    @Test
    fun historicalOccurrencesOutsideRestartedRuleWindowPassValidation() {
        val backup = validBackup().copy(
            recurrenceRules = listOf(validRule(startDate = "2026-07-08", lastGeneratedDate = "2026-07-21")),
            scheduledOccurrences = listOf(
                validOccurrence(
                    id = 1,
                    scheduledDate = "2026-06-24",
                    operationalDate = "2026-06-24",
                    status = "COMPLETED",
                ),
                validOccurrence(
                    id = 2,
                    scheduledDate = "2026-07-08",
                    operationalDate = "2026-07-08",
                    status = "PENDING",
                ),
            ),
            completionLogs = listOf(validLog(occurrenceId = 1)),
        )

        assertEquals(null, BackupValidator.validate(backup))
    }

    @Test
    fun lastGeneratedDateBeforeRuleStartDateRemainsRestorable() {
        val backup = validBackup().copy(
            recurrenceRules = listOf(validRule(lastGeneratedDate = "2026-05-19")),
            scheduledOccurrences = emptyList(),
            completionLogs = emptyList(),
        )

        assertEquals(null, BackupValidator.validate(backup))
    }

    private fun validBackup(): HabitBackupV1 {
        return HabitBackupV1(
            exportedAt = "2026-05-20T12:00:00",
            tasks = listOf(validTask()),
            recurrenceRules = listOf(validRule()),
            scheduledOccurrences = listOf(validOccurrence()),
            completionLogs = listOf(validLog()),
            workoutSequences = listOf(validSequence()),
            sequenceItems = listOf(validSequenceItem()),
            appSettings = SettingsBackup(
                dayRolloverTime = "03:00",
                dailyReviewReminderTime = "08:00",
                lateDayReminderTime = "20:00",
                dailyReviewEnabled = true,
                lateDayReminderEnabled = true,
                exactAlarmPermissionPromptShown = false,
                defaultBlockedDays = "",
                themePreference = "system",
                backupLastExportedAt = "",
            ),
        )
    }

    private fun validTask(
        id: Long = 1,
        taskType: String = "SEQUENCE_ROUTINE",
    ) = TaskBackup(
        id = id,
        name = "Workout",
        taskType = taskType,
        notes = "Notes",
        isActive = true,
        archived = false,
        createdAt = "2026-05-20T12:00:00",
        updatedAt = "2026-05-20T12:00:00",
        defaultReminderEnabled = true,
        calendarVisible = true,
        blockedDays = listOf("SUNDAY"),
    )

    private fun validRule(
        id: Long = 1,
        taskId: Long = 1,
        ruleType: String = "SEQUENCE",
        intervalDays: Int? = null,
        weekdays: List<String> = emptyList(),
        startDate: String = "2026-05-20",
        endDate: String? = null,
        lastGeneratedDate: String? = "2026-07-19",
    ) = RecurrenceRuleBackup(
        id = id,
        taskId = taskId,
        ruleType = ruleType,
        intervalDays = intervalDays,
        weekdays = weekdays,
        cycleDefinition = "Push,Pull,Legs",
        startDate = startDate,
        endDate = endDate,
        skipBlockedDaysBehavior = "MOVE_TO_NEXT_VALID_DAY",
        lastGeneratedDate = lastGeneratedDate,
        createdAt = "2026-05-20T12:00:00",
        updatedAt = "2026-05-20T12:00:00",
    )

    private fun validOccurrence(
        id: Long = 1,
        taskId: Long = 1,
        recurrenceRuleId: Long = 1,
        sequenceItemId: Long? = 1,
        scheduledDate: String = "2026-05-20",
        operationalDate: String = scheduledDate,
        status: String = "COMPLETED",
    ) = ScheduledOccurrenceBackup(
        id = id,
        taskId = taskId,
        recurrenceRuleId = recurrenceRuleId,
        scheduledDate = scheduledDate,
        operationalDate = operationalDate,
        status = status,
        sequenceItemId = sequenceItemId,
        isShifted = false,
        originalDate = null,
        note = "",
        createdAt = "2026-05-20T12:00:00",
        updatedAt = "2026-05-20T12:00:00",
    )

    private fun validLog(
        id: Long = 1,
        taskId: Long = 1,
        occurrenceId: Long? = 1,
        action: String = "COMPLETED",
    ) = CompletionLogBackup(
        id = id,
        occurrenceId = occurrenceId,
        taskId = taskId,
        action = action,
        timestamp = "2026-05-20T12:00:00",
        operationalDate = "2026-05-20",
        note = "Done",
        createdAt = "2026-05-20T12:00:00",
    )

    private fun validSequence(
        id: Long = 1,
        taskId: Long = 1,
    ) = WorkoutSequenceBackup(
        id = id,
        taskId = taskId,
        name = "Workout",
        createdAt = "2026-05-20T12:00:00",
        updatedAt = "2026-05-20T12:00:00",
    )

    private fun validSequenceItem(
        id: Long = 1,
        sequenceId: Long = 1,
        position: Int = 0,
    ) = SequenceItemBackup(
        id = id,
        sequenceId = sequenceId,
        name = "Push",
        position = position,
        notes = "",
    )
}
