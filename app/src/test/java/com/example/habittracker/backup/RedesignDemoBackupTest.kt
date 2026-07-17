package com.example.habittracker.backup

import com.example.habittracker.data.TaskType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedesignDemoBackupTest {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Test
    fun redesignDemoBackupMatchesCurrentRestoreContract() {
        val encoded = checkNotNull(
            javaClass.getResource("/habittracker-redesign-demo-v1.json"),
        ).readText()
        val backup = json.decodeFromString(HabitBackupV1.serializer(), encoded)

        assertNull(BackupValidator.validate(backup))
        assertEquals(TaskType.entries.toSet(), backup.tasks.map { TaskType.valueOf(it.taskType) }.toSet())
        assertTrue(backup.scheduledOccurrences.any { it.note.isNotBlank() })
        assertTrue(backup.sequenceExercises.isNotEmpty())
        assertTrue(backup.occurrenceExerciseChecks.isNotEmpty())
        assertTrue(backup.routinePhases.size >= 2)
        assertTrue(backup.recurrenceRules.any { it.autoRestartBehavior == "AUTO_RESTART" })
        assertTrue(backup.tasks.any { it.archived })
    }
}
