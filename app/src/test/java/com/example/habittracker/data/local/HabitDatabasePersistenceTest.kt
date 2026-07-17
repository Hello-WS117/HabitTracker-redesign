package com.example.habittracker.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.habittracker.data.LogAction
import com.example.habittracker.data.RuleType
import com.example.habittracker.data.SkipBlockedDaysBehavior
import com.example.habittracker.data.TaskType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HabitDatabasePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "habit-persistence-${System.nanoTime()}.db"
    private var database: HabitDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun roomDataPersistsAfterDatabaseReopen() = runTest {
        database = openDatabase()
        database!!.habitDao().insertTask(
            TaskEntity(
                id = 42,
                name = "Persistent task",
                taskType = TaskType.SIMPLE_HABIT,
                createdAt = LocalDateTime.of(2026, 5, 20, 12, 0),
                updatedAt = LocalDateTime.of(2026, 5, 20, 12, 0),
            ),
        )
        database!!.close()

        database = openDatabase()

        assertEquals("Persistent task", database!!.habitDao().taskById(42)!!.name)
    }

    @Test
    fun occurrenceSequenceItemForeignKeyRejectsDanglingReference() = runTest {
        database = openDatabase()
        val dao = database!!.habitDao()
        seedTaskAndRule(dao)

        val error = runCatching {
            dao.insertOccurrences(listOf(occurrence(sequenceItemId = 999)))
        }.exceptionOrNull()

        assertTrue(error is SQLiteConstraintException)
    }

    @Test
    fun completionLogOccurrenceForeignKeyRejectsDanglingReference() = runTest {
        database = openDatabase()
        val dao = database!!.habitDao()
        seedTaskAndRule(dao)

        val error = runCatching {
            dao.insertLog(
                CompletionLogEntity(
                    taskId = 1,
                    occurrenceId = 999,
                    action = LogAction.COMPLETED,
                    timestamp = LocalDateTime.of(2026, 5, 20, 12, 0),
                    operationalDate = LocalDate.of(2026, 5, 20),
                    note = "Dangling",
                    createdAt = LocalDateTime.of(2026, 5, 20, 12, 0),
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is SQLiteConstraintException)
    }

    @Test
    fun duplicateSequenceOccurrenceKeyIsCoalescedByUniqueIndex() = runTest {
        database = openDatabase()
        val dao = database!!.habitDao()
        seedTaskRuleAndSequence(dao)

        dao.insertOccurrences(
            listOf(
                occurrence(id = 1, sequenceItemId = 1),
                occurrence(id = 2, sequenceItemId = 1, note = "Replacement"),
            ),
        )

        val occurrences = dao.allOccurrences()
        assertEquals(1, occurrences.size)
        assertEquals(2, occurrences.single().id)
        assertEquals("Replacement", occurrences.single().note)
    }

    @Test
    fun legacyVersionOneDatabaseMigratesToRelationshipEnforcedSchema() = runTest {
        createLegacyVersionOneDatabase()

        database = openDatabase()
        val dao = database!!.habitDao()

        assertEquals(2, dao.allOccurrences().single().id)
        assertEquals("Replacement", dao.allOccurrences().single().note)
        assertEquals(listOf(2L, null), dao.allLogs().sortedBy { it.id }.map { it.occurrenceId })
    }

    @Test
    fun legacyVersionTwoDatabaseMigratesToRuleDateUniqueSchema() = runTest {
        createLegacyVersionTwoDatabase()

        database = openDatabase()
        val dao = database!!.habitDao()

        val occurrences = dao.allOccurrences()
        assertEquals(1, occurrences.size)
        assertEquals(2, occurrences.single().id)
        assertEquals("Replacement", occurrences.single().note)
        assertEquals(listOf(2L, null), dao.allLogs().sortedBy { it.id }.map { it.occurrenceId })
    }

    private fun openDatabase(): HabitDatabase {
        return Room.databaseBuilder(context, HabitDatabase::class.java, databaseName)
            .addMigrations(HabitDatabase.MIGRATION_1_2)
            .addMigrations(HabitDatabase.MIGRATION_2_3)
            .addMigrations(HabitDatabase.MIGRATION_3_4)
            .addMigrations(HabitDatabase.MIGRATION_4_5)
            .addMigrations(HabitDatabase.MIGRATION_5_6)
            .addMigrations(HabitDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()
    }

    private suspend fun seedTaskAndRule(dao: HabitDao) {
        val now = LocalDateTime.of(2026, 5, 20, 12, 0)
        dao.insertTask(
            TaskEntity(
                id = 1,
                name = "Workout",
                taskType = TaskType.SEQUENCE_ROUTINE,
                createdAt = now,
                updatedAt = now,
            ),
        )
        dao.insertRule(
            RecurrenceRuleEntity(
                id = 1,
                taskId = 1,
                ruleType = RuleType.SEQUENCE,
                cycleDefinition = "Push",
                startDate = LocalDate.of(2026, 5, 20),
                skipBlockedDaysBehavior = SkipBlockedDaysBehavior.MOVE_TO_NEXT_VALID_DAY,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun seedTaskRuleAndSequence(dao: HabitDao) {
        val now = LocalDateTime.of(2026, 5, 20, 12, 0)
        seedTaskAndRule(dao)
        dao.insertSequence(
            WorkoutSequenceEntity(
                id = 1,
                taskId = 1,
                name = "Workout",
                createdAt = now,
                updatedAt = now,
            ),
        )
        dao.insertSequenceItems(
            listOf(
                SequenceItemEntity(
                    id = 1,
                    sequenceId = 1,
                    name = "Push",
                    position = 0,
                ),
            ),
        )
    }

    private fun createLegacyVersionOneDatabase() {
        createLegacyDatabase(
            version = 1,
            includeSequenceItemForeignKey = false,
            useUniqueSequenceDateIndex = false,
            useNullSequenceDuplicates = false,
            includeDanglingSequenceOccurrence = true,
        )
    }

    private fun createLegacyVersionTwoDatabase() {
        createLegacyDatabase(
            version = 2,
            includeSequenceItemForeignKey = true,
            useUniqueSequenceDateIndex = true,
            useNullSequenceDuplicates = true,
            includeDanglingSequenceOccurrence = false,
        )
    }

    private fun createLegacyDatabase(
        version: Int,
        includeSequenceItemForeignKey: Boolean,
        useUniqueSequenceDateIndex: Boolean,
        useNullSequenceDuplicates: Boolean,
        includeDanglingSequenceOccurrence: Boolean,
    ) {
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE `tasks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `taskType` TEXT NOT NULL,
                    `notes` TEXT NOT NULL,
                    `isActive` INTEGER NOT NULL,
                    `archived` INTEGER NOT NULL,
                    `createdAt` TEXT NOT NULL,
                    `updatedAt` TEXT NOT NULL,
                    `defaultReminderEnabled` INTEGER NOT NULL,
                    `calendarVisible` INTEGER NOT NULL,
                    `blockedDays` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE `recurrence_rules` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `taskId` INTEGER NOT NULL,
                    `ruleType` TEXT NOT NULL,
                    `intervalDays` INTEGER,
                    `weekdays` TEXT NOT NULL,
                    `cycleDefinition` TEXT NOT NULL,
                    `startDate` TEXT NOT NULL,
                    `endDate` TEXT,
                    `skipBlockedDaysBehavior` TEXT NOT NULL,
                    `lastGeneratedDate` TEXT,
                    `createdAt` TEXT NOT NULL,
                    `updatedAt` TEXT NOT NULL,
                    FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE `scheduled_occurrences` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `taskId` INTEGER NOT NULL,
                    `recurrenceRuleId` INTEGER NOT NULL,
                    `scheduledDate` TEXT NOT NULL,
                    `operationalDate` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `sequenceItemId` INTEGER,
                    `isShifted` INTEGER NOT NULL,
                    `originalDate` TEXT,
                    `note` TEXT NOT NULL,
                    `createdAt` TEXT NOT NULL,
                    `updatedAt` TEXT NOT NULL,
                    FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`recurrenceRuleId`) REFERENCES `recurrence_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    ${if (includeSequenceItemForeignKey) ", FOREIGN KEY(`sequenceItemId`) REFERENCES `sequence_items`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT" else ""}
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE `completion_logs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `occurrenceId` INTEGER,
                    `taskId` INTEGER NOT NULL,
                    `action` TEXT NOT NULL,
                    `timestamp` TEXT NOT NULL,
                    `operationalDate` TEXT NOT NULL,
                    `note` TEXT NOT NULL,
                    `createdAt` TEXT NOT NULL,
                    FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    ${if (includeSequenceItemForeignKey) ", FOREIGN KEY(`occurrenceId`) REFERENCES `scheduled_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE" else ""}
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE `workout_sequences` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `taskId` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `createdAt` TEXT NOT NULL,
                    `updatedAt` TEXT NOT NULL,
                    FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE `sequence_items` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `sequenceId` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `position` INTEGER NOT NULL,
                    `notes` TEXT NOT NULL,
                    FOREIGN KEY(`sequenceId`) REFERENCES `workout_sequences`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX `index_recurrence_rules_taskId` ON `recurrence_rules` (`taskId`)")
            db.execSQL("CREATE INDEX `index_scheduled_occurrences_taskId` ON `scheduled_occurrences` (`taskId`)")
            db.execSQL("CREATE INDEX `index_scheduled_occurrences_recurrenceRuleId` ON `scheduled_occurrences` (`recurrenceRuleId`)")
            if (includeSequenceItemForeignKey) {
                db.execSQL("CREATE INDEX `index_scheduled_occurrences_sequenceItemId` ON `scheduled_occurrences` (`sequenceItemId`)")
            }
            db.execSQL("CREATE INDEX `index_scheduled_occurrences_operationalDate` ON `scheduled_occurrences` (`operationalDate`)")
            db.execSQL(
                """
                CREATE ${if (useUniqueSequenceDateIndex) "UNIQUE " else ""}INDEX `index_scheduled_occurrences_recurrenceRuleId_operationalDate_sequenceItemId`
                ON `scheduled_occurrences` (`recurrenceRuleId`, `operationalDate`, `sequenceItemId`)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX `index_completion_logs_taskId` ON `completion_logs` (`taskId`)")
            db.execSQL("CREATE INDEX `index_completion_logs_occurrenceId` ON `completion_logs` (`occurrenceId`)")
            db.execSQL("CREATE INDEX `index_completion_logs_operationalDate` ON `completion_logs` (`operationalDate`)")
            db.execSQL("CREATE INDEX `index_workout_sequences_taskId` ON `workout_sequences` (`taskId`)")
            db.execSQL("CREATE INDEX `index_sequence_items_sequenceId` ON `sequence_items` (`sequenceId`)")
            db.execSQL("CREATE UNIQUE INDEX `index_sequence_items_sequenceId_position` ON `sequence_items` (`sequenceId`, `position`)")
            db.execSQL("INSERT INTO `tasks` VALUES (1, 'Workout', 'SEQUENCE_ROUTINE', '', 1, 0, '2026-05-20T12:00:00', '2026-05-20T12:00:00', 1, 1, '')")
            db.execSQL("INSERT INTO `recurrence_rules` VALUES (1, 1, 'SEQUENCE', NULL, '', 'Push', '2026-05-20', NULL, 'MOVE_TO_NEXT_VALID_DAY', '2026-05-20', '2026-05-20T12:00:00', '2026-05-20T12:00:00')")
            db.execSQL("INSERT INTO `workout_sequences` VALUES (1, 1, 'Workout', '2026-05-20T12:00:00', '2026-05-20T12:00:00')")
            db.execSQL("INSERT INTO `sequence_items` VALUES (1, 1, 'Push', 0, '')")
            val duplicateSequenceItemValue = if (useNullSequenceDuplicates) "NULL" else "1"
            db.execSQL("INSERT INTO `scheduled_occurrences` VALUES (1, 1, 1, '2026-05-20', '2026-05-20', 'PENDING', $duplicateSequenceItemValue, 0, NULL, 'Original', '2026-05-20T12:00:00', '2026-05-20T12:00:00')")
            db.execSQL("INSERT INTO `scheduled_occurrences` VALUES (2, 1, 1, '2026-05-20', '2026-05-20', 'PENDING', $duplicateSequenceItemValue, 0, NULL, 'Replacement', '2026-05-20T12:00:00', '2026-05-20T12:00:00')")
            if (includeDanglingSequenceOccurrence) {
                db.execSQL("INSERT INTO `scheduled_occurrences` VALUES (3, 1, 1, '2026-05-21', '2026-05-21', 'PENDING', 999, 0, NULL, 'Dangling', '2026-05-20T12:00:00', '2026-05-20T12:00:00')")
            }
            db.execSQL("INSERT INTO `completion_logs` VALUES (1, 1, 1, 'COMPLETED', '2026-05-20T12:00:00', '2026-05-20', 'Deleted occurrence log', '2026-05-20T12:00:00')")
            db.execSQL("INSERT INTO `completion_logs` VALUES (2, 2, 1, 'COMPLETED', '2026-05-20T12:00:00', '2026-05-20', 'Kept occurrence log', '2026-05-20T12:00:00')")
            if (includeDanglingSequenceOccurrence) {
                db.execSQL("INSERT INTO `completion_logs` VALUES (3, 999, 1, 'COMPLETED', '2026-05-20T12:00:00', '2026-05-20', 'Dangling occurrence log', '2026-05-20T12:00:00')")
            }
            db.execSQL("INSERT INTO `completion_logs` VALUES (4, NULL, 1, 'EDITED', '2026-05-20T12:00:00', '2026-05-20', 'Task edit log', '2026-05-20T12:00:00')")
            db.version = version
        }
    }

    @Test
    fun duplicateNullSequenceOccurrenceKeyIsCoalescedByUniqueIndex() = runTest {
        database = openDatabase()
        val dao = database!!.habitDao()
        seedSimpleTaskAndRule(dao)

        dao.insertOccurrences(
            listOf(
                occurrence(id = 1, taskId = 2, ruleId = 2, sequenceItemId = null, note = "Original"),
                occurrence(id = 2, taskId = 2, ruleId = 2, sequenceItemId = null, note = "Replacement"),
            ),
        )

        val occurrences = dao.allOccurrences()
        assertEquals(1, occurrences.size)
        assertEquals(2, occurrences.single().id)
        assertEquals("Replacement", occurrences.single().note)
    }

    private suspend fun seedSimpleTaskAndRule(dao: HabitDao) {
        val now = LocalDateTime.of(2026, 5, 20, 12, 0)
        dao.insertTask(
            TaskEntity(
                id = 2,
                name = "Supplements",
                taskType = TaskType.SIMPLE_HABIT,
                createdAt = now,
                updatedAt = now,
            ),
        )
        dao.insertRule(
            RecurrenceRuleEntity(
                id = 2,
                taskId = 2,
                ruleType = RuleType.DAILY,
                startDate = LocalDate.of(2026, 5, 20),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun occurrence(
        id: Long = 1,
        taskId: Long = 1,
        ruleId: Long = 1,
        sequenceItemId: Long?,
        note: String = "",
    ): ScheduledOccurrenceEntity {
        val now = LocalDateTime.of(2026, 5, 20, 12, 0)
        return ScheduledOccurrenceEntity(
            id = id,
            taskId = taskId,
            recurrenceRuleId = ruleId,
            scheduledDate = LocalDate.of(2026, 5, 20),
            operationalDate = LocalDate.of(2026, 5, 20),
            sequenceItemId = sequenceItemId,
            note = note,
            createdAt = now,
            updatedAt = now,
        )
    }
}
