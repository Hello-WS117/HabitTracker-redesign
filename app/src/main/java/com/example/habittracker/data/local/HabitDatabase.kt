package com.example.habittracker.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TaskEntity::class,
        RecurrenceRuleEntity::class,
        ScheduledOccurrenceEntity::class,
        CompletionLogEntity::class,
        WorkoutSequenceEntity::class,
        SequenceItemEntity::class,
        SequenceExerciseEntity::class,
        OccurrenceExerciseCheckEntity::class,
        RoutinePlanEntity::class,
        RoutinePhaseEntity::class,
        CycleGroupEntity::class,
        CycleTaskMembershipEntity::class,
        CycleLogEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(HabitConverters::class)
abstract class HabitDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao

    companion object {
        @Volatile
        private var instance: HabitDatabase? = null

        fun get(context: Context): HabitDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HabitDatabase::class.java,
                    "habit_tracker.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .addMigrations(MIGRATION_4_5)
                    .addMigrations(MIGRATION_5_6)
                    .addMigrations(MIGRATION_6_7)
                    .build()
                    .also { instance = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_scheduled_occurrences_taskId`")
                db.execSQL("DROP INDEX IF EXISTS `index_scheduled_occurrences_recurrenceRuleId`")
                db.execSQL("DROP INDEX IF EXISTS `index_scheduled_occurrences_operationalDate`")
                db.execSQL("DROP INDEX IF EXISTS `index_scheduled_occurrences_recurrenceRuleId_operationalDate_sequenceItemId`")
                db.execSQL("DROP INDEX IF EXISTS `index_completion_logs_taskId`")
                db.execSQL("DROP INDEX IF EXISTS `index_completion_logs_occurrenceId`")
                db.execSQL("DROP INDEX IF EXISTS `index_completion_logs_operationalDate`")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scheduled_occurrences_new` (
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
                        FOREIGN KEY(`recurrenceRuleId`) REFERENCES `recurrence_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`sequenceItemId`) REFERENCES `sequence_items`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `scheduled_occurrences_new`
                    SELECT *
                    FROM `scheduled_occurrences`
                    WHERE (`sequenceItemId` IS NULL OR `sequenceItemId` IN (SELECT `id` FROM `sequence_items`))
                      AND (
                          `sequenceItemId` IS NULL
                          OR `id` IN (
                              SELECT MAX(`id`)
                              FROM `scheduled_occurrences`
                              WHERE `sequenceItemId` IS NOT NULL
                              GROUP BY `recurrenceRuleId`, `operationalDate`, `sequenceItemId`
                          )
                      )
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `scheduled_occurrences`")
                db.execSQL("ALTER TABLE `scheduled_occurrences_new` RENAME TO `scheduled_occurrences`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_occurrences_taskId` ON `scheduled_occurrences` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_occurrences_recurrenceRuleId` ON `scheduled_occurrences` (`recurrenceRuleId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_occurrences_sequenceItemId` ON `scheduled_occurrences` (`sequenceItemId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_occurrences_operationalDate` ON `scheduled_occurrences` (`operationalDate`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scheduled_occurrences_recurrenceRuleId_operationalDate_sequenceItemId` ON `scheduled_occurrences` (`recurrenceRuleId`, `operationalDate`, `sequenceItemId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `completion_logs_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `occurrenceId` INTEGER,
                        `taskId` INTEGER NOT NULL,
                        `action` TEXT NOT NULL,
                        `timestamp` TEXT NOT NULL,
                        `operationalDate` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`occurrenceId`) REFERENCES `scheduled_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `completion_logs_new`
                    SELECT *
                    FROM `completion_logs`
                    WHERE `occurrenceId` IS NULL
                       OR `occurrenceId` IN (SELECT `id` FROM `scheduled_occurrences`)
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `completion_logs`")
                db.execSQL("ALTER TABLE `completion_logs_new` RENAME TO `completion_logs`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_completion_logs_taskId` ON `completion_logs` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_completion_logs_occurrenceId` ON `completion_logs` (`occurrenceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_completion_logs_operationalDate` ON `completion_logs` (`operationalDate`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM `completion_logs`
                    WHERE `occurrenceId` IS NOT NULL
                      AND `occurrenceId` NOT IN (
                          SELECT MAX(`id`)
                          FROM `scheduled_occurrences`
                          GROUP BY `recurrenceRuleId`, `operationalDate`
                      )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    DELETE FROM `scheduled_occurrences`
                    WHERE `id` NOT IN (
                        SELECT MAX(`id`)
                        FROM `scheduled_occurrences`
                        GROUP BY `recurrenceRuleId`, `operationalDate`
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scheduled_occurrences_recurrenceRuleId_operationalDate` ON `scheduled_occurrences` (`recurrenceRuleId`, `operationalDate`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `timeOfDay` TEXT NOT NULL DEFAULT 'GENERAL'")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `pushable` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `tasks` SET `pushable` = 1 WHERE `taskType` = 'SEQUENCE_ROUTINE'")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `noActionBehavior` TEXT NOT NULL DEFAULT 'MARK_MISSED'")
                db.execSQL("ALTER TABLE `recurrence_rules` ADD COLUMN `durationDays` INTEGER")
                db.execSQL("ALTER TABLE `recurrence_rules` ADD COLUMN `startsAfterTaskId` INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cycle_groups` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `durationDays` INTEGER NOT NULL,
                        `resetThresholdPercent` INTEGER NOT NULL,
                        `restartBehavior` TEXT NOT NULL,
                        `restartTiming` TEXT NOT NULL,
                        `blockedDays` TEXT NOT NULL,
                        `currentStartDate` TEXT NOT NULL,
                        `lastRestartedAt` TEXT,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cycle_task_memberships` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `cycleGroupId` INTEGER NOT NULL,
                        `taskId` INTEGER NOT NULL,
                        `startOffsetDays` INTEGER NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        FOREIGN KEY(`cycleGroupId`) REFERENCES `cycle_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cycle_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `cycleGroupId` INTEGER NOT NULL,
                        `timestamp` TEXT NOT NULL,
                        `operationalDate` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        FOREIGN KEY(`cycleGroupId`) REFERENCES `cycle_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cycle_task_memberships_cycleGroupId` ON `cycle_task_memberships` (`cycleGroupId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cycle_task_memberships_taskId` ON `cycle_task_memberships` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cycle_logs_cycleGroupId` ON `cycle_logs` (`cycleGroupId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cycle_logs_operationalDate` ON `cycle_logs` (`operationalDate`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sequence_exercises` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sequenceItemId` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `prescription` TEXT NOT NULL,
                        `instructions` TEXT NOT NULL,
                        `requirement` TEXT NOT NULL,
                        FOREIGN KEY(`sequenceItemId`) REFERENCES `sequence_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sequence_exercises_sequenceItemId` ON `sequence_exercises` (`sequenceItemId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sequence_exercises_sequenceItemId_position` ON `sequence_exercises` (`sequenceItemId`, `position`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `occurrence_exercise_checks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `occurrenceId` INTEGER NOT NULL,
                        `sequenceExerciseId` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        FOREIGN KEY(`occurrenceId`) REFERENCES `scheduled_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`sequenceExerciseId`) REFERENCES `sequence_exercises`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrence_exercise_checks_occurrenceId` ON `occurrence_exercise_checks` (`occurrenceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrence_exercise_checks_sequenceExerciseId` ON `occurrence_exercise_checks` (`sequenceExerciseId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_occurrence_exercise_checks_occurrenceId_sequenceExerciseId` ON `occurrence_exercise_checks` (`occurrenceId`, `sequenceExerciseId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `routine_plans` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `routine_phases` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `routinePlanId` INTEGER NOT NULL,
                        `taskId` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        `advanceMode` TEXT NOT NULL,
                        `minimumDays` INTEGER NOT NULL,
                        `progressionNote` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `activatedDate` TEXT,
                        `advancedAt` TEXT,
                        `lastReviewedDate` TEXT,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        FOREIGN KEY(`routinePlanId`) REFERENCES `routine_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_routine_phases_routinePlanId` ON `routine_phases` (`routinePlanId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_routine_phases_taskId` ON `routine_phases` (`taskId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_routine_phases_routinePlanId_position` ON `routine_phases` (`routinePlanId`, `position`)")
            }
        }
    }
}
