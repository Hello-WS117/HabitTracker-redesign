package com.example.habittracker.ui

import com.example.habittracker.data.LongTermRecurrenceAnchor
import com.example.habittracker.data.CycleRestartBehavior
import com.example.habittracker.data.CycleRestartTiming
import com.example.habittracker.data.ExerciseCheckStatus
import com.example.habittracker.data.ExerciseRequirement
import com.example.habittracker.data.PhaseAdvanceMode
import com.example.habittracker.data.RoutinePhaseStatus
import com.example.habittracker.data.SkipBlockedDaysBehavior
import com.example.habittracker.data.NoActionBehavior
import com.example.habittracker.data.TaskTimeOfDay
import com.example.habittracker.timers.ExerciseTimerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

class HabitTrackerUiStoreTest {
    @Test
    fun defaultDraftStartsOnOperationalDayBeforeRollover() {
        val store = HabitTrackerUiStore(
            nowProvider = { LocalDateTime.of(2026, 5, 20, 1, 30) },
        )

        assertEquals(LocalDate.of(2026, 5, 19), store.operationalDate)
        assertEquals(store.operationalDate, store.draft.startDate)

        store.draft = store.draft.copy(startDate = store.operationalDate.plusDays(2))
        store.clearDraft()

        assertEquals(store.operationalDate, store.draft.startDate)
    }

    @Test
    fun defaultDraftSaveBeforeRolloverCreatesCurrentOperationalDayOccurrence() {
        val store = HabitTrackerUiStore(
            nowProvider = { LocalDateTime.of(2026, 5, 20, 2, 0) },
        ).also {
            it.tasks.clear()
            it.occurrences.clear()
            it.logs.clear()
        }

        store.draft = store.draft.copy(name = "Before rollover")
        store.saveDraft()

        assertEquals(LocalDate.of(2026, 5, 19), store.operationalDate)
        assertEquals(LocalDate.of(2026, 5, 19), store.tasks.single().startDate)
        assertEquals(
            listOf(LocalDate.of(2026, 5, 19)),
            store.todayOccurrences().map { it.operationalDate },
        )
    }

    @Test
    fun quickAddOneTimeTaskCreatesTodayOnlyTaskWithoutReplacingDraft() {
        val store = cleanStore()
        store.settings = store.settings.copy(defaultBlockedDays = setOf(store.operationalDate.dayOfWeek))
        store.draft = store.draft.copy(name = "Longer draft", notes = "Keep editing")

        store.quickAddOneTimeTask("  One-off errand  ")

        val task = store.tasks.single()
        assertEquals("One-off errand", task.name)
        assertEquals(HabitTaskType.OneTime, task.type)
        assertEquals(store.operationalDate, task.startDate)
        assertEquals(store.operationalDate, task.endDate)
        assertEquals(1, task.durationDays)
        assertEquals("Once", task.recurrenceSummary)
        assertEquals(true, task.pushable)
        assertEquals(NoActionBehavior.AUTO_PUSH, task.noActionBehavior)
        assertTrue(task.blockedDays.isEmpty())
        assertEquals("Longer draft", store.draft.name)
        assertEquals("Keep editing", store.draft.notes)
        assertEquals(listOf(task.id), store.todayOccurrences().map { it.taskId })
        assertNull(store.progressForTaskCycleTiming(task))
    }

    @Test
    fun quickAddOneTimeTaskCanBePushedAndAutoPushesByDefault() {
        val store = cleanStore()
        val today = store.operationalDate

        store.quickAddOneTimeTask("Call plumber")
        val task = store.tasks.single()
        val occurrence = store.todayOccurrences().single()

        store.pushOccurrenceForward(occurrence.id)

        val shifted = store.occurrences.single { it.id == occurrence.id }
        val replacement = store.occurrences.single { it.id != occurrence.id }
        assertEquals(true, task.pushable)
        assertEquals(NoActionBehavior.AUTO_PUSH, task.noActionBehavior)
        assertEquals(HabitStatus.Shifted, shifted.status)
        assertEquals(today, shifted.originalDate)
        assertEquals(today.plusDays(1), replacement.operationalDate)
        assertEquals(today.plusDays(1), store.taskById(task.id)!!.endDate)
    }

    @Test
    fun calendarFilterSectionsPlaceLongTermTasksAfterStandardTasks() {
        val standardTask = task(id = 1, name = "Morning")
        val longTermTask = task(id = 2, name = "Filters", type = HabitTaskType.LongTerm)
        val intervalTask = task(id = 3, name = "CO2", type = HabitTaskType.Interval)

        val sections = calendarFilterTaskSections(listOf(longTermTask, standardTask, intervalTask))

        assertEquals(listOf(1, 3), sections.standardTasks.map { it.id })
        assertEquals(listOf(2), sections.longTermTasks.map { it.id })
    }

    @Test
    fun calendarFilterSectionsExcludeOneTimeTasksFromTopScrollingList() {
        val today = LocalDate.of(2026, 5, 20)
        val oneTimeTask = task(
            id = 1,
            name = "One-off errand",
            type = HabitTaskType.Simple,
            startDate = today,
            endDate = today,
            durationDays = 1,
        )
        val pushedOneTimeTask = oneTimeTask.copy(
            id = 2,
            name = "Pushed one-off",
            endDate = today.plusDays(1),
        )
        val dailyTask = task(id = 3, name = "Daily")
        val intervalTask = task(id = 4, name = "CO2", type = HabitTaskType.Interval)
        val longTermTask = task(id = 5, name = "Filters", type = HabitTaskType.LongTerm)

        val sections = calendarFilterTaskSections(
            listOf(oneTimeTask, pushedOneTimeTask, dailyTask, intervalTask, longTermTask),
        )

        assertEquals(listOf(3, 4), sections.standardTasks.map { it.id })
        assertEquals(listOf(5), sections.longTermTasks.map { it.id })
    }

    @Test
    fun saveDraftCreatesTaskWithEditorFieldsStarterOccurrenceAndLog() {
        val store = cleanStore()
        val startDate = LocalDate.of(2026, 5, 20)
        store.draft = HabitTaskDraft(
            name = "  Mobility  ",
            type = HabitTaskType.Weekday,
            notes = "  Hips and shoulders  ",
            startDate = startDate,
            weekdays = emptySet(),
            blockedDays = setOf(DayOfWeek.SUNDAY),
            skipBlockedDaysBehavior = SkipBlockedDaysBehavior.ASK_WHEN_NEEDED,
            reminderEnabled = false,
            calendarVisible = false,
        )

        store.saveDraft()

        val task = store.tasks.single()
        assertEquals(10, task.id)
        assertEquals("Mobility", task.name)
        assertEquals(HabitTaskType.Weekday, task.type)
        assertEquals("Hips and shoulders", task.notes)
        assertEquals("Wed", task.recurrenceSummary)
        assertEquals(startDate, task.startDate)
        assertEquals(setOf(DayOfWeek.WEDNESDAY), task.weekdays)
        assertEquals(setOf(DayOfWeek.SUNDAY), task.blockedDays)
        assertEquals(SkipBlockedDaysBehavior.ASK_WHEN_NEEDED, task.skipBlockedDaysBehavior)
        assertEquals(TaskTimeOfDay.GENERAL, task.timeOfDay)
        assertEquals(false, task.pushable)
        assertEquals(false, task.reminderEnabled)
        assertEquals(false, task.calendarVisible)
        assertEquals(false, task.archived)
        assertEquals(true, task.isActive)
        assertEquals(task.id, store.detailTaskId)
        assertEquals("", store.draft.name)

        val starterOccurrence = store.occurrences.single()
        assertEquals(task.id, starterOccurrence.taskId)
        assertEquals(HabitStatus.Pending, starterOccurrence.status)

        val log = store.logs.single()
        assertEquals(task.id, log.taskId)
        assertEquals(HabitLogAction.Edited, log.action)
        assertEquals("Created task", log.note)
    }

    @Test
    fun saveSequenceDraftUsesStartEndDatesAndFirstSequenceItem() {
        val store = cleanStore()
        val startDate = store.operationalDate.plusDays(3)
        val endDate = startDate.plusDays(7)
        store.draft = HabitTaskDraft(
            name = "  Workout  ",
            type = HabitTaskType.Sequence,
            startDate = startDate,
            endDate = endDate,
            sequenceText = " Push, Pull, Legs ",
            sequenceSpacingDays = 2,
            pushable = true,
        )

        store.saveDraft()

        val task = store.tasks.single()
        assertEquals(HabitTaskType.Sequence, task.type)
        assertEquals(startDate, task.startDate)
        assertEquals(endDate, task.endDate)
        assertEquals(2, task.intervalDays)
        assertEquals(true, task.pushable)
        assertEquals(listOf("Push", "Pull", "Legs"), task.sequenceItems)
        assertEquals("Push / Pull / Legs every 2 days", task.recurrenceSummary)

        val starterOccurrence = store.occurrences.single()
        assertEquals(startDate, starterOccurrence.operationalDate)
        assertEquals("Push", starterOccurrence.sequenceItemName)
    }

    @Test
    fun saveSequenceDraftAcceptsMultilineItemsAndPreservesDuplicatesAndCommas() {
        val store = cleanStore()
        store.draft = HabitTaskDraft(
            name = "Imported workout",
            type = HabitTaskType.Sequence,
            startDate = store.operationalDate,
            sequenceText = "Strength, heavy\nZone 2 Cardio\nZone 2 Cardio",
        )

        store.saveDraft()

        assertEquals(
            listOf("Strength, heavy", "Zone 2 Cardio", "Zone 2 Cardio"),
            store.tasks.single().sequenceItems,
        )
    }

    @Test
    fun durationDraftComputesEndDateAndCanStartAfterAnotherDurationTask() {
        val store = cleanStore()
        val today = store.operationalDate
        store.tasks.add(
            task(
                id = 1,
                name = "CO2 table",
                startDate = today,
                endDate = today.plusDays(13),
                durationDays = 14,
            ),
        )
        store.draft = HabitTaskDraft(
            name = "O2 table",
            type = HabitTaskType.Simple,
            startDate = today,
            durationDays = 14,
            startsAfterTaskId = 1,
        )

        store.saveDraft()

        val task = store.tasks.single { it.name == "O2 table" }
        assertEquals(today.plusDays(14), task.startDate)
        assertEquals(today.plusDays(27), task.endDate)
        assertEquals(14, task.durationDays)
        assertEquals(1, task.startsAfterTaskId)
        assertEquals("Daily for 14 days", task.recurrenceSummary)
    }

    @Test
    fun phaseImportCreatesOneChainedPlanAndLaterPhasesFollowAPush() {
        val store = cleanStore()
        val today = store.operationalDate
        val phases = parsePhaseImport(
            """
                CO2 Tables | 2 weeks | every 2 days | morning | push | - | Base phase
                Maximum Hold | 1 day | once | morning | push | - | Retest
                Combined | 1 week | sequence every 1 day | evening | miss | CO2 > O2 > One Breath | Build phase
            """.trimIndent(),
        ).phases

        store.importPhases(phases, today)

        val co2 = store.tasks.single { it.name == "CO2 Tables" }
        val retest = store.tasks.single { it.name == "Maximum Hold" }
        val combined = store.tasks.single { it.name == "Combined" }
        assertEquals(HabitTaskType.Interval, co2.type)
        assertEquals(today.plusDays(13), co2.endDate)
        assertEquals(HabitTaskType.OneTime, retest.type)
        assertEquals(co2.id, retest.startsAfterTaskId)
        assertEquals(today.plusDays(14), retest.startDate)
        assertEquals(retest.id, combined.startsAfterTaskId)
        assertEquals(today.plusDays(15), combined.startDate)
        assertEquals(listOf("CO2", "O2", "One Breath"), combined.sequenceItems)

        val firstOccurrence = store.occurrences.single { it.taskId == co2.id }
        store.pushOccurrenceForward(firstOccurrence.id)

        assertEquals(today.plusDays(14), store.taskById(co2.id)?.endDate)
        assertEquals(today.plusDays(15), store.taskById(retest.id)?.startDate)
        assertEquals(today.plusDays(16), store.taskById(combined.id)?.startDate)
    }

    @Test
    fun sequenceStepNoteHistoryIsFilteredToTheCurrentStep() {
        val store = cleanStore()
        val today = store.operationalDate
        store.tasks.add(task(id = 1, name = "Workout", type = HabitTaskType.Sequence, sequenceItems = listOf("Push", "Pull")))
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = today.minusDays(4), status = HabitStatus.Completed, sequenceItemName = "Pull", note = "Rows felt strong"),
                occurrence(id = 11, taskId = 1, date = today.minusDays(3), status = HabitStatus.Completed, sequenceItemName = "Push", note = "Shoulder tight"),
                occurrence(id = 12, taskId = 1, date = today.minusDays(2), status = HabitStatus.Completed, sequenceItemName = "Pull", note = "Add reps next time"),
                occurrence(id = 13, taskId = 1, date = today, sequenceItemName = "Pull"),
            ),
        )

        val history = store.sequenceStepNoteHistory(taskId = 1, sequenceItemName = "Pull", beforeOccurrenceId = 13)

        assertEquals(listOf("Add reps next time", "Rows felt strong"), history.map { it.note })
        assertTrue(history.none { it.sequenceItemName == "Push" })
    }

    @Test
    fun archiveTaskTogglesVisibilityAndLogsArchiveAndRestore() {
        val store = cleanStore()
        store.tasks.add(task(id = 1, name = "Supplements"))

        store.archiveTask(taskId = 1, archived = true)

        assertTrue(store.visibleTasks().isEmpty())
        assertEquals(listOf(1), store.visibleTasks(includeArchived = true).map { it.id })
        assertEquals(true, store.taskById(1)!!.archived)
        assertEquals(false, store.taskById(1)!!.isActive)

        store.archiveTask(taskId = 1, archived = false)

        assertEquals(listOf(1), store.visibleTasks().map { it.id })
        assertEquals(false, store.taskById(1)!!.archived)
        assertEquals(true, store.taskById(1)!!.isActive)
        assertEquals(
            listOf("Archived task", "Restored task"),
            store.logs.map { it.note },
        )
    }

    @Test
    fun taskEditorListHidesArchivedTasksUntilToggleIsEnabled() {
        val store = cleanStore()
        store.tasks.addAll(
            listOf(
                task(id = 1, name = "Active"),
                task(id = 2, name = "Archived", archived = true, isActive = false),
                task(id = 3, name = "Inactive", isActive = false),
            ),
        )

        assertEquals(listOf(1), taskEditorVisibleTasks(store, showArchived = false).map { it.id })
        assertEquals(listOf(1, 2, 3), taskEditorVisibleTasks(store, showArchived = true).map { it.id })
        assertEquals("1 active, 2 hidden", taskEditorSummary(activeCount = 1, hiddenCount = 2, showArchived = false))
        assertEquals("3 total", taskEditorSummary(activeCount = 1, hiddenCount = 2, showArchived = true))
    }

    @Test
    fun taskEditorActiveTasksExcludeCurrentAndLegacyOneTimeTasks() {
        val today = LocalDate.of(2026, 5, 20)
        val currentOneTimeTask = task(
            id = 1,
            name = "Clean car",
            type = HabitTaskType.OneTime,
            startDate = today,
            endDate = today,
            durationDays = 1,
        )
        val legacyOneTimeTask = task(
            id = 2,
            name = "Call plumber",
            type = HabitTaskType.Simple,
            startDate = today,
            endDate = today.plusDays(1),
            durationDays = 1,
        )
        val recurringTask = task(id = 3, name = "Morning routine")
        val longTermTask = task(id = 4, name = "Change filters", type = HabitTaskType.LongTerm)

        val activeTasks = taskEditorActiveTasks(
            listOf(currentOneTimeTask, legacyOneTimeTask, recurringTask, longTermTask),
        )

        assertEquals(listOf(3, 4), activeTasks.map { it.id })
    }

    @Test
    fun taskEditorRoutineGroupsCollapseLinkedPhaseTasksIntoOneActiveItem() {
        val tasks = listOf(
            task(id = 1, name = "Foundation", isActive = false),
            task(id = 2, name = "Pogo hops"),
            task(id = 3, name = "Jump progression", isActive = false),
            task(id = 4, name = "Standalone habit"),
        )
        val phases = listOf(
            routinePhase(id = 11, taskId = 1, position = 0, status = RoutinePhaseStatus.COMPLETED),
            routinePhase(id = 12, taskId = 2, position = 1, status = RoutinePhaseStatus.ACTIVE),
            routinePhase(id = 13, taskId = 3, position = 2, status = RoutinePhaseStatus.UPCOMING),
        )

        val groups = taskEditorRoutineGroups(tasks, phases)

        assertEquals(1, groups.size)
        assertEquals("Achilles routine", groups.single().name)
        assertEquals(listOf(1, 2, 3), groups.single().phases.map { it.task.id })
        assertTrue(groups.single().isActive)
        assertFalse(groups.single().isArchived)
        assertEquals(listOf(4), tasks.filterNot { it.id in groups.single().phases.map { phase -> phase.task.id } }.map { it.id })
    }

    @Test
    fun archiveRoutinePlanArchivesAllPhasesAndRestoreReactivatesOnlyCurrentPhase() {
        val store = cleanStore()
        store.tasks.addAll(
            listOf(
                task(id = 1, name = "Foundation", isActive = false),
                task(id = 2, name = "Pogo hops"),
                task(id = 3, name = "Jump progression", isActive = false),
            ),
        )
        store.routinePhases.addAll(
            listOf(
                routinePhase(id = 11, taskId = 1, position = 0, status = RoutinePhaseStatus.COMPLETED),
                routinePhase(
                    id = 12,
                    taskId = 2,
                    position = 1,
                    status = RoutinePhaseStatus.ACTIVE,
                    activatedDate = LocalDate.of(2026, 5, 1),
                ),
                routinePhase(id = 13, taskId = 3, position = 2, status = RoutinePhaseStatus.UPCOMING),
            ),
        )

        assertEquals(listOf(12), store.pendingRoutinePhaseReviews().map { it.id })
        store.archiveRoutinePlan(planId = 50, archived = true)

        assertTrue(store.tasks.all { it.archived && !it.isActive })
        assertTrue(store.pendingRoutinePhaseReviews().isEmpty())
        assertEquals(3, store.logs.count { it.note == "Archived phased routine" })

        store.archiveRoutinePlan(planId = 50, archived = false)

        assertTrue(store.tasks.none { it.archived })
        assertEquals(
            mapOf(1 to false, 2 to true, 3 to false),
            store.tasks.associate { it.id to it.isActive },
        )
        assertEquals(listOf(12), store.pendingRoutinePhaseReviews().map { it.id })
        assertEquals(3, store.logs.count { it.note == "Restored phased routine" })
    }

    @Test
    fun deleteRoutinePlanPermanentlyRemovesEveryPhaseButLeavesStandaloneTasks() {
        val store = cleanStore()
        store.tasks.addAll(
            listOf(
                task(id = 1, name = "Foundation"),
                task(id = 2, name = "Pogo hops", isActive = false),
                task(id = 3, name = "Standalone habit"),
            ),
        )
        store.routinePhases.addAll(
            listOf(
                routinePhase(id = 11, taskId = 1, position = 0, status = RoutinePhaseStatus.ACTIVE),
                routinePhase(id = 12, taskId = 2, position = 1, status = RoutinePhaseStatus.UPCOMING),
            ),
        )
        store.occurrences.add(occurrence(id = 20, taskId = 1, date = store.operationalDate))

        store.deleteRoutinePlanPermanently(50)

        assertEquals(listOf(3), store.tasks.map { it.id })
        assertTrue(store.routinePhases.isEmpty())
        assertTrue(store.occurrences.isEmpty())
    }

    @Test
    fun saveDraftSupportsInactiveTasksWithoutArchiving() {
        val store = cleanStore()
        store.draft = HabitTaskDraft(
            name = "Paused habit",
            isActive = false,
            archived = false,
        )

        store.saveDraft()

        val task = store.tasks.single()
        assertEquals(false, task.isActive)
        assertEquals(false, task.archived)
        assertTrue(store.visibleTasks().isEmpty())
        assertEquals(listOf(task.id), store.visibleTasks(includeArchived = true).map { it.id })
    }

    @Test
    fun changingRolloverTimeUpdatesTodayChecklistBoundary() {
        val store = HabitTrackerUiStore(
            nowProvider = { LocalDateTime.of(2026, 5, 20, 4, 0) },
        ).also {
            it.tasks.clear()
            it.occurrences.clear()
            it.logs.clear()
        }
        store.tasks.add(task(id = 1, name = "Supplements"))
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = LocalDate.of(2026, 5, 19)),
                occurrence(id = 11, taskId = 1, date = LocalDate.of(2026, 5, 20)),
            ),
        )

        assertEquals(LocalDate.of(2026, 5, 20), store.operationalDate)
        assertEquals(listOf(11), store.todayOccurrences().map { it.id })

        store.updateSettings { it.copy(dayRolloverTime = LocalTime.of(5, 0)) }

        assertEquals(LocalDate.of(2026, 5, 19), store.operationalDate)
        assertEquals(listOf(10), store.todayOccurrences().map { it.id })
    }

    @Test
    fun editingTaskReplacesOnlyFuturePendingOccurrencesAndLogsUpdate() {
        val store = cleanStore()
        val today = store.operationalDate
        val task = task(id = 1, name = "Supplements", startDate = today)
        store.tasks.add(task)
        store.occurrences.addAll(
            listOf(
                occurrence(id = 1, taskId = 1, date = today.minusDays(1), status = HabitStatus.Completed),
                occurrence(id = 2, taskId = 1, date = today),
                occurrence(id = 3, taskId = 1, date = today.plusDays(1)),
                occurrence(id = 4, taskId = 1, date = today.plusDays(2)),
                occurrence(id = 5, taskId = 1, date = today.plusDays(3), status = HabitStatus.Skipped),
            ),
        )

        store.editTask(task)
        store.draft = store.draft.copy(
            name = "  Supplements updated  ",
            type = HabitTaskType.Interval,
            notes = "  With breakfast  ",
            intervalDays = 2,
            endDate = today.plusDays(5),
            reminderEnabled = false,
        )
        store.saveDraft()

        val updatedTask = store.taskById(1)!!
        assertEquals("Supplements updated", updatedTask.name)
        assertEquals(HabitTaskType.Interval, updatedTask.type)
        assertEquals("With breakfast", updatedTask.notes)
        assertEquals("Every 2 days", updatedTask.recurrenceSummary)
        assertEquals(2, updatedTask.intervalDays)
        assertEquals(today.plusDays(5), updatedTask.endDate)
        assertEquals(false, updatedTask.reminderEnabled)
        assertTrue(store.occurrences.any { it.id == 1 && it.status == HabitStatus.Completed })
        assertTrue(store.occurrences.any { it.id == 2 && it.status == HabitStatus.Pending })
        assertTrue(store.occurrences.none { it.id == 3 || it.id == 4 })
        assertTrue(store.occurrences.any { it.id == 5 && it.status == HabitStatus.Skipped })
        assertEquals(
            listOf(today.plusDays(2), today.plusDays(4)),
            store.occurrences
                .filter { it.id >= 500 && it.taskId == 1 }
                .sortedBy { it.operationalDate }
                .map { it.operationalDate },
        )
        assertEquals(
            "Updated task and refreshed future pending occurrences",
            store.logs.single().note,
        )
    }

    @Test
    fun metadataOnlyTaskEditPreservesFuturePendingOccurrences() {
        val store = cleanStore()
        val today = store.operationalDate
        val task = task(id = 1, name = "Supplements", startDate = today)
        store.tasks.add(task)
        store.occurrences.addAll(
            listOf(
                occurrence(id = 1, taskId = 1, date = today),
                occurrence(id = 2, taskId = 1, date = today.plusDays(1)),
                occurrence(id = 3, taskId = 1, date = today.plusDays(2)),
            ),
        )

        store.editTask(task)
        store.draft = store.draft.copy(
            name = "Supplements with breakfast",
            notes = "Take after meal",
            reminderEnabled = false,
            calendarVisible = false,
        )
        store.saveDraft()

        val updatedTask = store.taskById(1)!!
        assertEquals("Supplements with breakfast", updatedTask.name)
        assertEquals("Take after meal", updatedTask.notes)
        assertEquals(false, updatedTask.reminderEnabled)
        assertEquals(false, updatedTask.calendarVisible)
        assertEquals(
            listOf(1 to today, 2 to today.plusDays(1), 3 to today.plusDays(2)),
            store.occurrences.sortedBy { it.operationalDate }.map { it.id to it.operationalDate },
        )
        assertTrue(store.occurrences.none { it.id >= 500 })
        assertEquals("Updated task metadata", store.logs.single().note)
    }

    @Test
    fun editingIntervalTaskRegeneratesUsingValidNonBlockedDayCadence() {
        val store = cleanStore()
        val startDate = LocalDate.of(2026, 5, 23)
        val task = task(id = 1, name = "Cardio", startDate = startDate)
        store.tasks.add(task)
        store.occurrences.addAll(
            listOf(
                occurrence(id = 1, taskId = 1, date = store.operationalDate),
                occurrence(id = 2, taskId = 1, date = startDate.plusDays(1)),
            ),
        )

        store.editTask(task)
        store.draft = store.draft.copy(
            type = HabitTaskType.Interval,
            startDate = startDate,
            intervalDays = 2,
            blockedDays = setOf(DayOfWeek.SUNDAY),
            endDate = startDate.plusDays(5),
        )
        store.saveDraft()

        assertEquals(
            listOf(startDate, startDate.plusDays(3), startDate.plusDays(5)),
            store.occurrences
                .filter { it.id >= 500 && it.taskId == 1 }
                .sortedBy { it.operationalDate }
                .map { it.operationalDate },
        )
        assertTrue(store.occurrences.none { it.operationalDate.dayOfWeek == DayOfWeek.SUNDAY })
    }

    @Test
    fun todayChecklistIncludesCalendarHiddenTasksButExcludesArchivedAndInactiveTasks() {
        val store = cleanStore()
        val date = store.operationalDate
        store.tasks.addAll(
            listOf(
                task(id = 1, name = "Alpha"),
                task(id = 2, name = "Calendar hidden", calendarVisible = false),
                task(id = 3, name = "Archived", archived = true, isActive = false),
                task(id = 4, name = "Inactive", isActive = false),
            ),
        )
        store.occurrences.addAll((1..4).map { taskId -> occurrence(id = taskId, taskId = taskId, date = date) })

        assertEquals(listOf(1, 2), store.todayOccurrences().map { it.taskId })
    }

    @Test
    fun longTermTasksShowInSeparateDueListUntilCompleted() {
        val store = cleanStore()
        val date = store.operationalDate
        store.tasks.addAll(
            listOf(
                task(id = 1, name = "Change filters", type = HabitTaskType.LongTerm, intervalDays = 6),
                task(id = 2, name = "Future filters", type = HabitTaskType.LongTerm, intervalDays = 6),
                task(id = 3, name = "Supplements"),
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date.minusDays(8)),
                occurrence(id = 11, taskId = 2, date = date.plusDays(8)),
                occurrence(id = 12, taskId = 3, date = date),
                occurrence(id = 13, taskId = 1, date = date.minusDays(1)),
            ),
        )

        assertEquals(listOf(12), store.todayOccurrences().map { it.id })
        assertEquals(listOf(10), store.longTermDueOccurrences().map { it.id })

        store.skipOccurrence(10)

        assertEquals(HabitStatus.Pending, store.occurrences.single { it.id == 10 }.status)
        assertEquals(listOf(10), store.longTermDueOccurrences().map { it.id })

        store.completeOccurrence(10)

        assertEquals(listOf(10), store.longTermDueOccurrences().map { it.id })
        assertEquals(HabitStatus.Completed, store.occurrences.single { it.id == 10 }.status)

        store.undoOccurrenceDecision(10)

        assertEquals(HabitStatus.Pending, store.occurrences.single { it.id == 10 }.status)
        assertEquals(listOf(10), store.longTermDueOccurrences().map { it.id })
    }

    @Test
    fun completingLongTermTaskDefaultsFutureRecurrenceToCompletionDateAndUndoRestoresDueSchedule() {
        val store = cleanStore()
        val date = store.operationalDate
        val originalDueDate = LocalDate.of(2026, 1, 15)
        val originalNextDueDate = LocalDate.of(2026, 7, 15)
        val completionBasedNextDueDate = LocalDate.of(2026, 11, 20)
        store.tasks.add(
            task(
                id = 1,
                name = "Change filters",
                type = HabitTaskType.LongTerm,
                startDate = originalDueDate,
                intervalDays = 6,
                longTermRecurrenceUnit = LongTermRecurrenceUnit.Months,
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = originalDueDate),
                occurrence(id = 11, taskId = 1, date = originalNextDueDate),
            ),
        )

        store.completeOccurrence(10)

        assertEquals(date, store.tasks.single().startDate)
        assertEquals(originalDueDate, store.occurrences.single { it.id == 10 }.originalDate)
        assertEquals(HabitStatus.Completed, store.occurrences.single { it.id == 10 }.status)
        assertFalse(store.occurrences.any { it.taskId == 1 && it.operationalDate == originalNextDueDate && it.status == HabitStatus.Pending })
        assertTrue(store.occurrences.any { it.taskId == 1 && it.operationalDate == completionBasedNextDueDate && it.status == HabitStatus.Pending })

        store.undoOccurrenceDecision(10)

        assertEquals(originalDueDate, store.tasks.single().startDate)
        assertEquals(HabitStatus.Pending, store.occurrences.single { it.id == 10 }.status)
        assertEquals(null, store.occurrences.single { it.id == 10 }.originalDate)
        assertTrue(store.occurrences.any { it.taskId == 1 && it.operationalDate == originalNextDueDate && it.status == HabitStatus.Pending })
        assertFalse(store.occurrences.any { it.taskId == 1 && it.operationalDate == completionBasedNextDueDate && it.status == HabitStatus.Pending })
    }

    @Test
    fun dueDateAnchoredLongTermTaskKeepsFutureDueDateAfterCompletion() {
        val store = cleanStore()
        val originalDueDate = LocalDate.of(2026, 1, 15)
        val originalNextDueDate = LocalDate.of(2026, 7, 15)
        store.tasks.add(
            task(
                id = 1,
                name = "Change filters",
                type = HabitTaskType.LongTerm,
                startDate = originalDueDate,
                intervalDays = 6,
                longTermRecurrenceUnit = LongTermRecurrenceUnit.Months,
                longTermRecurrenceAnchor = LongTermRecurrenceAnchor.DUE_DATE,
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = originalDueDate),
                occurrence(id = 11, taskId = 1, date = originalNextDueDate),
            ),
        )

        store.completeOccurrence(10)

        assertEquals(originalDueDate, store.tasks.single().startDate)
        assertEquals(null, store.occurrences.single { it.id == 10 }.originalDate)
        assertTrue(store.occurrences.any { it.taskId == 1 && it.operationalDate == originalNextDueDate && it.status == HabitStatus.Pending })
    }

    @Test
    fun savingLongTermTaskUsesMonthlyRepeatAndDisablesPushBehavior() {
        val store = cleanStore()
        val date = store.operationalDate
        store.draft = store.draft.copy(
            name = "Change filters",
            type = HabitTaskType.LongTerm,
            startDate = date,
            intervalDays = 6,
            longTermRecurrenceUnit = LongTermRecurrenceUnit.Months,
            pushable = true,
            noActionBehavior = NoActionBehavior.AUTO_PUSH,
            durationDays = 14,
            startsAfterTaskId = 99,
        )

        store.saveDraft()

        val task = store.tasks.single()
        assertEquals(HabitTaskType.LongTerm, task.type)
        assertEquals("Every 6 months", task.recurrenceSummary)
        assertEquals(6, task.intervalDays)
        assertEquals(LongTermRecurrenceUnit.Months, task.longTermRecurrenceUnit)
        assertEquals(LongTermRecurrenceAnchor.COMPLETION_DATE, task.longTermRecurrenceAnchor)
        assertEquals(false, task.pushable)
        assertEquals(NoActionBehavior.MARK_MISSED, task.noActionBehavior)
        assertEquals(null, task.durationDays)
        assertEquals(null, task.startsAfterTaskId)
        assertEquals(listOf(task.id), store.longTermDueOccurrences().map { it.taskId })
        assertTrue(store.todayOccurrences().isEmpty())
    }

    @Test
    fun savingLongTermTaskCanUseYearsForRecurrence() {
        val store = cleanStore()
        val date = store.operationalDate
        store.draft = store.draft.copy(
            name = "Renew passport",
            type = HabitTaskType.LongTerm,
            startDate = date,
            intervalDays = 2,
            longTermRecurrenceUnit = LongTermRecurrenceUnit.Years,
        )

        store.saveDraft()

        val task = store.tasks.single()
        assertEquals(HabitTaskType.LongTerm, task.type)
        assertEquals("Every 2 years", task.recurrenceSummary)
        assertEquals(2, task.intervalDays)
        assertEquals(LongTermRecurrenceUnit.Years, task.longTermRecurrenceUnit)
        assertTrue(store.todayOccurrences().isEmpty())
        assertEquals(listOf(task.id), store.longTermDueOccurrences().map { it.taskId })
    }

    @Test
    fun todayChecklistSortsByTimeOfDayWithinStatus() {
        val store = cleanStore()
        val date = store.operationalDate
        store.tasks.addAll(
            listOf(
                task(id = 1, name = "General", timeOfDay = TaskTimeOfDay.GENERAL),
                task(id = 2, name = "Evening", timeOfDay = TaskTimeOfDay.EVENING),
                task(id = 3, name = "Morning", timeOfDay = TaskTimeOfDay.MORNING),
                task(id = 4, name = "Noon", timeOfDay = TaskTimeOfDay.NOON),
            ),
        )
        store.occurrences.addAll((1..4).map { taskId -> occurrence(id = taskId, taskId = taskId, date = date) })

        assertEquals(listOf(3, 4, 2, 1), store.todayOccurrences().map { it.taskId })
    }

    @Test
    fun cycleProgressCountsCompletedDisruptedAndLeftSlotsForCurrentCycleGroup() {
        val store = cleanStore()
        store.cycleGroups.add(
            HabitCycleUi(
                id = 7,
                name = "Breath tables",
                durationDays = 14,
                resetThresholdPercent = 50,
                restartBehavior = CycleRestartBehavior.AUTO_RESTART,
                restartTiming = CycleRestartTiming.TODAY,
                blockedDays = emptySet(),
                currentStartDate = LocalDate.of(2026, 5, 20),
            ),
        )
        store.tasks.addAll(
            listOf(
                task(id = 1, name = "CO2", cycleGroupId = 7, cycleGroupName = "Breath tables"),
                task(id = 2, name = "O2", cycleGroupId = 7, cycleGroupName = "Breath tables"),
                task(id = 3, name = "Other"),
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 1, taskId = 1, date = LocalDate.of(2026, 5, 20), status = HabitStatus.Completed),
                occurrence(id = 2, taskId = 1, date = LocalDate.of(2026, 5, 22), status = HabitStatus.Skipped),
                occurrence(id = 3, taskId = 2, date = LocalDate.of(2026, 5, 21), status = HabitStatus.Completed),
                occurrence(id = 4, taskId = 2, date = LocalDate.of(2026, 5, 23), status = HabitStatus.Pending),
                occurrence(id = 5, taskId = 1, date = LocalDate.of(2026, 6, 10), status = HabitStatus.Pending),
                occurrence(id = 6, taskId = 3, date = LocalDate.of(2026, 5, 20), status = HabitStatus.Completed),
            ),
        )

        assertEquals(
            HabitCycleProgressUi(completed = 2, disrupted = 1, remaining = 25, expected = 28),
            store.cycleProgressForTask(store.taskById(1)!!),
        )
    }

    @Test
    fun cycleTimingProgressCountsExpectedSlotsEvenWhenFuturePendingRowsAreMissing() {
        val store = cleanStore()
        val start = LocalDate.of(2026, 5, 20)
        store.tasks.add(
            task(
                id = 1,
                name = "CO2 table",
                type = HabitTaskType.Interval,
                startDate = start,
                endDate = start.plusDays(13),
                durationDays = 14,
                intervalDays = 2,
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 1, taskId = 1, date = start, status = HabitStatus.Completed),
                occurrence(id = 2, taskId = 1, date = start.plusDays(2), status = HabitStatus.Pending),
                occurrence(id = 3, taskId = 1, date = start.plusDays(4), status = HabitStatus.Missed),
                occurrence(id = 4, taskId = 1, date = start.plusDays(20), status = HabitStatus.Pending),
            ),
        )

        assertEquals(
            HabitCycleProgressUi(completed = 1, disrupted = 1, remaining = 5, expected = 7),
            store.progressForTaskCycleTiming(store.taskById(1)!!),
        )
    }

    @Test
    fun saveDraftSupportsTaskLevelAutoRestartForCycleTiming() {
        val store = cleanStore()
        val start = LocalDate.of(2026, 5, 20)
        store.draft = HabitTaskDraft(
            name = "CO2 table",
            type = HabitTaskType.Interval,
            startDate = start,
            endDate = start.plusDays(13),
            durationDays = 14,
            intervalDays = 2,
            cycleRestartBehavior = CycleRestartBehavior.AUTO_RESTART,
            cycleResetThresholdPercent = 50,
            cycleRestartTiming = CycleRestartTiming.TOMORROW,
        )

        store.saveDraft()

        val task = store.tasks.single()
        assertEquals("CO2 table auto restart", task.cycleGroupName)
        assertEquals(14, task.cycleDurationDays)
        assertEquals(CycleRestartBehavior.AUTO_RESTART, task.cycleRestartBehavior)
        assertEquals(CycleRestartTiming.TOMORROW, task.cycleRestartTiming)
        val internalRestartCycle = store.cycleGroups.single()
        assertEquals(task.cycleGroupId, internalRestartCycle.id)
        assertEquals("CO2 table auto restart", internalRestartCycle.name)
        assertEquals(14, internalRestartCycle.durationDays)
    }

    @Test
    fun saveDraftAutoRestartsDisruptedCycleTimingTask() {
        val store = cleanStore(now = LocalDateTime.of(2026, 6, 16, 12, 0))
        val start = LocalDate.of(2026, 5, 24)
        val task = task(
            id = 1,
            name = "CO2 tables",
            type = HabitTaskType.Interval,
            startDate = start,
            endDate = LocalDate.of(2026, 6, 20),
            durationDays = 14,
            intervalDays = 2,
            pushable = true,
            noActionBehavior = NoActionBehavior.AUTO_PUSH,
        )
        store.tasks.add(task)
        store.occurrences.addAll(
            (0L..12L step 2).mapIndexed { index, offset ->
                occurrence(
                    id = index + 1,
                    taskId = 1,
                    date = start.plusDays(offset),
                    status = HabitStatus.Skipped,
                    isShifted = true,
                    originalDate = start.plusDays(offset),
                    note = "Pushed forward",
                )
            },
        )
        store.draft = HabitTaskDraft.fromTask(task).copy(
            cycleRestartBehavior = CycleRestartBehavior.AUTO_RESTART,
            cycleResetThresholdPercent = 50,
            cycleRestartTiming = CycleRestartTiming.TODAY,
        )

        store.saveDraft()

        val restartedTask = store.taskById(1)!!
        val pendingDates = store.occurrences
            .filter { it.taskId == 1 && it.status == HabitStatus.Pending }
            .map { it.operationalDate }
            .sorted()
        assertEquals(LocalDate.of(2026, 6, 16), restartedTask.startDate)
        assertEquals(LocalDate.of(2026, 6, 29), restartedTask.endDate)
        assertEquals(HabitCycleProgressUi(completed = 0, remaining = 7), store.progressForTaskCycleTiming(restartedTask))
        assertEquals(
            listOf(
                LocalDate.of(2026, 6, 16),
                LocalDate.of(2026, 6, 18),
                LocalDate.of(2026, 6, 20),
                LocalDate.of(2026, 6, 22),
                LocalDate.of(2026, 6, 24),
                LocalDate.of(2026, 6, 26),
                LocalDate.of(2026, 6, 28),
            ),
            pendingDates,
        )
    }

    @Test
    fun calendarMonthNavigationKeepsSelectedDateInsideVisibleMonth() {
        val store = cleanStore()
        store.selectCalendarDate(LocalDate.of(2026, 5, 31))

        store.moveCalendarMonth(1)

        assertEquals(YearMonth.of(2026, 6), store.calendarMonth)
        assertEquals(LocalDate.of(2026, 6, 30), store.selectedCalendarDate)

        store.moveCalendarMonth(-2)

        assertEquals(YearMonth.of(2026, 4), store.calendarMonth)
        assertEquals(LocalDate.of(2026, 4, 30), store.selectedCalendarDate)

        store.showCalendarToday()

        assertEquals(store.operationalDate, store.selectedCalendarDate)
        assertEquals(YearMonth.from(store.operationalDate), store.calendarMonth)
    }

    @Test
    fun calendarTaskFilterHidesOtherTasksButAllowsExplicitHistoryInspection() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.addAll(
            listOf(
                task(id = 1, name = "Alpha"),
                task(id = 2, name = "Beta"),
                task(id = 3, name = "Hidden", calendarVisible = false),
                task(id = 4, name = "Archived", archived = true, isActive = false),
                task(id = 5, name = "Inactive", isActive = false),
            ),
        )
        store.occurrences.addAll((1..5).map { taskId -> occurrence(id = taskId, taskId = taskId, date = date) })

        assertEquals(listOf(1, 2), store.occurrencesForDate(date).map { it.taskId })
        assertEquals(listOf(2), store.occurrencesForDate(date, taskFilterId = 2).map { it.taskId })
        assertEquals(listOf(3), store.occurrencesForDate(date, taskFilterId = 3).map { it.taskId })
        assertEquals(listOf(4), store.occurrencesForDate(date, taskFilterId = 4).map { it.taskId })
        assertEquals(listOf(5), store.occurrencesForDate(date, taskFilterId = 5).map { it.taskId })
    }

    @Test
    fun updateOccurrenceNoteTrimsNoteAndAddsHistoryLog() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(task(id = 1, name = "Supplements"))
        store.occurrences.add(occurrence(id = 10, taskId = 1, date = date))

        store.updateOccurrenceNote(10, "  Taken after breakfast  ")

        assertEquals("Taken after breakfast", store.occurrences.single { it.id == 10 }.note)
        val log = store.logs.single()
        assertEquals(1, log.taskId)
        assertEquals(10, log.occurrenceId)
        assertEquals(HabitLogAction.Edited, log.action)
        assertEquals("Updated occurrence note", log.note)
    }

    @Test
    fun completeAndSkipPendingOccurrencesUpdateStatusAndLogs() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(task(id = 1, name = "Supplements"))
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date),
                occurrence(id = 11, taskId = 1, date = date.plusDays(1)),
            ),
        )

        store.completeOccurrence(10)
        store.skipOccurrence(11)

        assertEquals(HabitStatus.Completed, store.occurrences.single { it.id == 10 }.status)
        assertEquals(HabitStatus.Skipped, store.occurrences.single { it.id == 11 }.status)
        assertEquals("Marked complete", store.occurrences.single { it.id == 10 }.note)
        assertEquals("Skipped intentionally", store.occurrences.single { it.id == 11 }.note)
        assertEquals(
            listOf(HabitLogAction.Completed, HabitLogAction.Skipped),
            store.logs.map { it.action },
        )
        assertEquals(listOf(10, 11), store.logs.map { it.occurrenceId })
        assertEquals(listOf(date, date.plusDays(1)), store.logs.map { it.operationalDate })
    }

    @Test
    fun completeAndSkipPreserveExistingOccurrenceNotes() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(task(id = 1, name = "Supplements"))
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date, note = "Taken after breakfast"),
                occurrence(id = 11, taskId = 1, date = date.plusDays(1), note = "Travel day"),
            ),
        )

        store.completeOccurrence(10)
        store.skipOccurrence(11)

        assertEquals("Taken after breakfast", store.occurrences.single { it.id == 10 }.note)
        assertEquals("Travel day", store.occurrences.single { it.id == 11 }.note)
        assertEquals(listOf("Marked complete", "Skipped intentionally"), store.logs.map { it.note })
    }

    @Test
    fun completeAndSkipActionsIgnoreNonPendingOccurrences() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(task(id = 1, name = "Supplements"))
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date, status = HabitStatus.Completed),
                occurrence(id = 11, taskId = 1, date = date.plusDays(1), status = HabitStatus.Skipped),
            ),
        )

        store.completeOccurrence(10)
        store.skipOccurrence(11)

        assertEquals(HabitStatus.Completed, store.occurrences.single { it.id == 10 }.status)
        assertEquals(HabitStatus.Skipped, store.occurrences.single { it.id == 11 }.status)
        assertTrue(store.logs.isEmpty())
    }

    @Test
    fun shiftActionIgnoresNonSequenceCompletedAndNoValidWeekday() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.addAll(
            listOf(
                task(id = 1, name = "Supplements"),
                task(id = 2, name = "Completed sequence", type = HabitTaskType.Sequence, sequenceItems = listOf("Push", "Pull")),
                task(
                    id = 3,
                    name = "Blocked sequence",
                    type = HabitTaskType.Sequence,
                    blockedDays = DayOfWeek.values().toSet(),
                    sequenceItems = listOf("Push", "Pull"),
                ),
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date),
                occurrence(id = 11, taskId = 2, date = date, status = HabitStatus.Completed),
                occurrence(id = 12, taskId = 3, date = date),
            ),
        )

        store.shiftOccurrenceForward(10)
        store.shiftOccurrenceForward(11)
        store.shiftOccurrenceForward(12)

        assertEquals(HabitStatus.Pending, store.occurrences.single { it.id == 10 }.status)
        assertEquals(HabitStatus.Completed, store.occurrences.single { it.id == 11 }.status)
        assertEquals(HabitStatus.Pending, store.occurrences.single { it.id == 12 }.status)
        assertEquals(3, store.occurrences.size)
        assertTrue(store.logs.isEmpty())
    }

    @Test
    fun shiftSequenceForwardMovesCurrentAndFuturePendingItems() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 23)
        store.tasks.add(
            task(
                id = 1,
                name = "Workout",
                type = HabitTaskType.Sequence,
                blockedDays = setOf(DayOfWeek.SUNDAY),
                sequenceItems = listOf("Pull", "Legs"),
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date, sequenceItemName = "Pull"),
                occurrence(id = 11, taskId = 1, date = date.plusDays(2), sequenceItemName = "Legs"),
            ),
        )

        store.shiftOccurrenceForward(10)

        val shiftedCurrent = store.occurrences.single { it.id == 10 }
        val replacement = store.occurrences.single { it.id >= 500 }
        val shiftedFuture = store.occurrences.single { it.id == 11 }
        assertEquals(HabitStatus.Shifted, shiftedCurrent.status)
        assertEquals(date, shiftedCurrent.originalDate)
        assertEquals(date.plusDays(2), replacement.operationalDate)
        assertEquals("Pull", replacement.sequenceItemName)
        assertEquals(date.plusDays(3), shiftedFuture.operationalDate)
        assertEquals(date.plusDays(2), shiftedFuture.originalDate)
        assertEquals(HabitLogAction.ShiftedForward, store.logs.single().action)
    }

    @Test
    fun pushableTaskPushMovesCurrentAndFuturePendingItems() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(
            task(
                id = 1,
                name = "Cardio",
                type = HabitTaskType.Interval,
                startDate = date,
                endDate = date.plusDays(13),
                durationDays = 14,
                intervalDays = 2,
                pushable = true,
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date),
                occurrence(id = 11, taskId = 1, date = date.plusDays(2)),
            ),
        )

        store.pushOccurrenceForward(10)

        val shiftedCurrent = store.occurrences.single { it.id == 10 }
        val replacement = store.occurrences.single { it.id >= 500 }
        val shiftedFuture = store.occurrences.single { it.id == 11 }
        assertEquals(HabitStatus.Shifted, shiftedCurrent.status)
        assertEquals(date, shiftedCurrent.originalDate)
        assertEquals(date.plusDays(1), replacement.operationalDate)
        assertEquals(date.plusDays(3), shiftedFuture.operationalDate)
        assertEquals(date.plusDays(2), shiftedFuture.originalDate)
        assertEquals(date.plusDays(14), store.taskById(1)!!.endDate)
        assertEquals(HabitLogAction.ShiftedForward, store.logs.single().action)
        assertEquals("Pushed to May 21", store.logs.single().note)
    }

    @Test
    fun pushedFutureCadenceDoesNotInflateCycleDisruptionProgress() {
        val store = cleanStore()
        val date = store.operationalDate
        store.cycleGroups.add(
            HabitCycleUi(
                id = 7,
                name = "CO2 tables auto restart",
                durationDays = 14,
                resetThresholdPercent = 50,
                restartBehavior = CycleRestartBehavior.AUTO_RESTART,
                restartTiming = CycleRestartTiming.TODAY,
                blockedDays = emptySet(),
                currentStartDate = date,
            ),
        )
        store.tasks.add(
            task(
                id = 1,
                name = "CO2 tables",
                type = HabitTaskType.Interval,
                startDate = date,
                endDate = date.plusDays(13),
                durationDays = 14,
                intervalDays = 2,
                pushable = true,
                cycleGroupId = 7,
                cycleGroupName = "CO2 tables auto restart",
            ),
        )
        store.occurrences.addAll(
            (0L..12L step 2).mapIndexed { index, offset ->
                occurrence(id = index + 1, taskId = 1, date = date.plusDays(offset))
            },
        )

        store.pushOccurrenceForward(1)

        assertEquals(
            HabitCycleProgressUi(completed = 0, disrupted = 1, remaining = 6, expected = 7),
            store.progressForTaskCycleTiming(store.taskById(1)!!),
        )
    }

    @Test
    fun completePushableOccurrenceYesterdayPullsCurrentAndFuturePendingItemsBack() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(
            task(
                id = 1,
                name = "Cardio",
                type = HabitTaskType.Interval,
                startDate = date,
                endDate = date.plusDays(13),
                durationDays = 14,
                intervalDays = 2,
                pushable = true,
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date),
                occurrence(id = 11, taskId = 1, date = date.plusDays(2)),
                occurrence(id = 12, taskId = 1, date = date.plusDays(4)),
            ),
        )

        assertEquals(true, store.canCompleteOccurrenceYesterday(10))
        store.completeOccurrenceYesterday(10)

        val completedYesterday = store.occurrences.single { it.id == 10 }
        val shiftedFirstFuture = store.occurrences.single { it.id == 11 }
        val shiftedSecondFuture = store.occurrences.single { it.id == 12 }
        assertEquals(HabitStatus.Completed, completedYesterday.status)
        assertEquals(date.minusDays(1), completedYesterday.operationalDate)
        assertEquals(date, completedYesterday.originalDate)
        assertEquals("Completed yesterday", completedYesterday.note)
        assertEquals(date.plusDays(1), shiftedFirstFuture.operationalDate)
        assertEquals(date.plusDays(2), shiftedFirstFuture.originalDate)
        assertEquals(date.plusDays(3), shiftedSecondFuture.operationalDate)
        assertEquals(date.plusDays(4), shiftedSecondFuture.originalDate)
        assertEquals(date.plusDays(12), store.taskById(1)!!.endDate)
        assertEquals(HabitLogAction.Completed, store.logs.single().action)
        assertEquals(date.minusDays(1), store.logs.single().operationalDate)
        assertEquals("Completed yesterday; schedule shifted from May 20", store.logs.single().note)
    }

    @Test
    fun completeYesterdayIsAvailableForFirstEveryTwoDayOccurrenceAndMovesStartDateBack() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(
            task(
                id = 1,
                name = "Cardio",
                type = HabitTaskType.Interval,
                startDate = date,
                endDate = date.plusDays(13),
                durationDays = 14,
                intervalDays = 2,
                pushable = true,
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date),
                occurrence(id = 11, taskId = 1, date = date.plusDays(2)),
            ),
        )

        assertEquals(true, store.canCompleteOccurrenceYesterday(10))
        store.completeOccurrenceYesterday(10)

        val completedYesterday = store.occurrences.single { it.id == 10 }
        val shiftedFuture = store.occurrences.single { it.id == 11 }
        val task = store.taskById(1)!!
        assertEquals(HabitStatus.Completed, completedYesterday.status)
        assertEquals(date.minusDays(1), completedYesterday.operationalDate)
        assertEquals(date, completedYesterday.originalDate)
        assertEquals(date.plusDays(1), shiftedFuture.operationalDate)
        assertEquals(date.plusDays(2), shiftedFuture.originalDate)
        assertEquals(date.minusDays(1), task.startDate)
        assertEquals(date.plusDays(12), task.endDate)
        assertEquals("Every 2 days for 14 days", task.recurrenceSummary)
    }

    @Test
    fun yesterdayCompletionCandidatesExcludeEveryTwoDayTaskDueTomorrow() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(
            task(
                id = 1,
                name = "Cardio",
                type = HabitTaskType.Interval,
                startDate = date.plusDays(1),
                intervalDays = 2,
                pushable = true,
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date.plusDays(1)),
                occurrence(id = 11, taskId = 1, date = date.plusDays(3)),
            ),
        )

        assertTrue(store.yesterdayCompletionCandidates().isEmpty())
        assertEquals(false, store.canCompleteOccurrenceYesterday(10))
        store.completeOccurrenceYesterday(10)

        assertEquals(HabitStatus.Pending, store.occurrences.single { it.id == 10 }.status)
        assertEquals(date.plusDays(1), store.occurrences.single { it.id == 10 }.operationalDate)
        assertEquals(date.plusDays(3), store.occurrences.single { it.id == 11 }.operationalDate)
        assertEquals(date.plusDays(1), store.taskById(1)!!.startDate)
    }

    @Test
    fun completeYesterdayWorksWhenYesterdayHasShiftedPlaceholder() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(
            task(
                id = 1,
                name = "Cardio",
                type = HabitTaskType.Interval,
                startDate = date.minusDays(5),
                intervalDays = 2,
                pushable = true,
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(
                    id = 9,
                    taskId = 1,
                    date = date.minusDays(1),
                    status = HabitStatus.Shifted,
                    isShifted = true,
                    originalDate = date.minusDays(2),
                ),
                occurrence(id = 10, taskId = 1, date = date),
                occurrence(id = 11, taskId = 1, date = date.plusDays(2)),
            ),
        )

        assertEquals(true, store.canCompleteOccurrenceYesterday(10))
        store.completeOccurrenceYesterday(10)

        val completedYesterday = store.occurrences.single { it.id == 9 }
        val shiftedCurrent = store.occurrences.single { it.id == 10 }
        val shiftedFuture = store.occurrences.single { it.id == 11 }
        assertEquals(HabitStatus.Completed, completedYesterday.status)
        assertEquals(date.minusDays(1), completedYesterday.operationalDate)
        assertEquals(date.plusDays(1), shiftedCurrent.operationalDate)
        assertEquals(date, shiftedCurrent.originalDate)
        assertEquals(date.plusDays(3), shiftedFuture.operationalDate)
        assertEquals(date.plusDays(2), shiftedFuture.originalDate)
        assertEquals(9, store.logs.single().occurrenceId)
    }

    @Test
    fun undoCompletedYesterdayRestoresPulledSchedule() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(
            task(
                id = 1,
                name = "Cardio",
                type = HabitTaskType.Interval,
                startDate = date,
                endDate = date.plusDays(13),
                durationDays = 14,
                intervalDays = 2,
                pushable = true,
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date),
                occurrence(id = 11, taskId = 1, date = date.plusDays(2)),
            ),
        )
        store.completeOccurrenceYesterday(10)

        store.undoOccurrenceDecision(10)

        val restoredCurrent = store.occurrences.single { it.id == 10 }
        val restoredFuture = store.occurrences.single { it.id == 11 }
        assertEquals(HabitStatus.Pending, restoredCurrent.status)
        assertEquals(date, restoredCurrent.operationalDate)
        assertEquals(null, restoredCurrent.originalDate)
        assertEquals(date.plusDays(2), restoredFuture.operationalDate)
        assertEquals(null, restoredFuture.originalDate)
        assertEquals(date, store.taskById(1)!!.startDate)
        assertEquals(date.plusDays(13), store.taskById(1)!!.endDate)
        assertTrue(store.logs.any { it.note == "Undid completed-yesterday shift" })
    }

    @Test
    fun undoDecisionRestoresCompletedAndPushedChecklistRows() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.addAll(
            listOf(
                task(id = 1, name = "Supplements"),
                task(id = 2, name = "Cardio", type = HabitTaskType.Interval, intervalDays = 2, pushable = true),
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date),
                occurrence(id = 20, taskId = 2, date = date),
                occurrence(id = 21, taskId = 2, date = date.plusDays(2)),
            ),
        )
        store.completeOccurrence(10)
        store.pushOccurrenceForward(20)

        store.undoOccurrenceDecision(10)
        store.undoOccurrenceDecision(20)

        assertEquals(HabitStatus.Pending, store.occurrences.single { it.id == 10 }.status)
        assertEquals(HabitStatus.Pending, store.occurrences.single { it.id == 20 }.status)
        assertTrue(store.occurrences.none { it.id >= 500 && it.taskId == 2 })
        assertEquals(date.plusDays(2), store.occurrences.single { it.id == 21 }.operationalDate)
        assertTrue(store.logs.any { it.note == "Reset checklist decision" })
        assertTrue(store.logs.any { it.note == "Undid push" })
    }

    @Test
    fun nonPushableTaskPushDoesNothingSoChecklistCanUseSkip() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(task(id = 1, name = "Supplements", pushable = false))
        store.occurrences.add(occurrence(id = 10, taskId = 1, date = date))

        store.pushOccurrenceForward(10)

        assertEquals(HabitStatus.Pending, store.occurrences.single().status)
        assertEquals(1, store.occurrences.size)
        assertTrue(store.logs.isEmpty())
    }

    @Test
    fun shiftSequenceForwardAllowsMissedCurrentItem() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(
            task(
                id = 1,
                name = "Workout",
                type = HabitTaskType.Sequence,
                sequenceItems = listOf("Push", "Pull"),
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date, status = HabitStatus.Missed, sequenceItemName = "Push"),
                occurrence(id = 11, taskId = 1, date = date.plusDays(1), sequenceItemName = "Pull"),
            ),
        )

        store.shiftOccurrenceForward(10)

        val shiftedMissed = store.occurrences.single { it.id == 10 }
        assertEquals(HabitStatus.Missed, shiftedMissed.status)
        assertTrue(shiftedMissed.isShifted)
        assertEquals(date, shiftedMissed.originalDate)
        assertTrue(store.occurrences.any { it.id >= 500 && it.operationalDate == date.plusDays(1) && it.sequenceItemName == "Push" })
        assertEquals(date.plusDays(2), store.occurrences.single { it.id == 11 }.operationalDate)
        assertEquals(HabitLogAction.ShiftedForward, store.logs.single().action)
    }

    @Test
    fun shiftSequenceForwardDoesNotReplacePreservedHistoryDates() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(
            task(
                id = 1,
                name = "Workout",
                type = HabitTaskType.Sequence,
                sequenceItems = listOf("Push", "Pull", "Legs"),
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = date, status = HabitStatus.Missed, sequenceItemName = "Push"),
                occurrence(id = 11, taskId = 1, date = date.plusDays(1), status = HabitStatus.Completed, sequenceItemName = "Pull"),
                occurrence(id = 12, taskId = 1, date = date.plusDays(2), sequenceItemName = "Legs"),
            ),
        )
        store.logs.add(
            HabitLogUi(
                id = 1,
                taskId = 1,
                occurrenceId = 11,
                action = HabitLogAction.Completed,
                timestamp = LocalDateTime.of(2026, 5, 21, 8, 0),
                operationalDate = date.plusDays(1),
                note = "Completed before shift",
            ),
        )

        store.shiftOccurrenceForward(10)

        assertEquals(HabitStatus.Completed, store.occurrences.single { it.id == 11 }.status)
        assertEquals(date.plusDays(1), store.occurrences.single { it.id == 11 }.operationalDate)
        assertTrue(store.logs.any { it.occurrenceId == 11 && it.action == HabitLogAction.Completed })
        assertTrue(store.occurrences.any { it.id >= 500 && it.operationalDate == date.plusDays(2) && it.originalDate == date })
        assertEquals(date.plusDays(3), store.occurrences.single { it.id == 12 }.operationalDate)
    }

    @Test
    fun swapSequenceOccurrenceItemsTradesEligiblePendingSequenceLabelsAndNotes() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(
            task(
                id = 1,
                name = "Workout",
                type = HabitTaskType.Sequence,
                sequenceItems = listOf("Full-Body Strength A", "Zone 2 Cardio", "Upper Hypertrophy A"),
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(
                    id = 10,
                    taskId = 1,
                    date = date,
                    sequenceItemName = "Full-Body Strength A",
                    note = "Strength note",
                ),
                occurrence(
                    id = 11,
                    taskId = 1,
                    date = date.plusDays(1),
                    sequenceItemName = "Zone 2 Cardio",
                    note = "Cardio note",
                ),
                occurrence(
                    id = 12,
                    taskId = 1,
                    date = date.plusDays(2),
                    status = HabitStatus.Completed,
                    sequenceItemName = "Upper Hypertrophy A",
                ),
            ),
        )

        assertEquals(listOf(11), store.sequenceSwapCandidates(10).map { it.id })
        store.swapSequenceOccurrenceItems(10, 11)

        val today = store.occurrences.single { it.id == 10 }
        val tomorrow = store.occurrences.single { it.id == 11 }
        assertEquals(date, today.operationalDate)
        assertEquals("Zone 2 Cardio", today.sequenceItemName)
        assertEquals("Cardio note", today.note)
        assertEquals(date.plusDays(1), tomorrow.operationalDate)
        assertEquals("Full-Body Strength A", tomorrow.sequenceItemName)
        assertEquals("Strength note", tomorrow.note)
        assertEquals("Upper Hypertrophy A", store.occurrences.single { it.id == 12 }.sequenceItemName)
        assertTrue(store.logs.any { it.note == "Swapped sequence item with May 21" })
    }

    @Test
    fun sequenceSwapExcludesPastAndCompletedItems() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(
            task(
                id = 1,
                name = "Workout",
                type = HabitTaskType.Sequence,
                startDate = date.minusDays(3),
                sequenceItems = listOf("Push", "Pull", "Legs"),
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(
                    id = 8,
                    taskId = 1,
                    date = date.minusDays(4),
                    status = HabitStatus.Completed,
                    sequenceItemName = "Push",
                    note = "Before task start",
                ),
                occurrence(
                    id = 9,
                    taskId = 1,
                    date = date.minusDays(3),
                    status = HabitStatus.Completed,
                    sequenceItemName = "Push",
                    note = "Start note",
                ),
                occurrence(
                    id = 10,
                    taskId = 1,
                    date = date.minusDays(1),
                    status = HabitStatus.Skipped,
                    sequenceItemName = "Pull",
                    note = "Past pull",
                ),
                occurrence(
                    id = 11,
                    taskId = 1,
                    date = date,
                    sequenceItemName = "Legs",
                    note = "Current legs",
                ),
                occurrence(
                    id = 12,
                    taskId = 1,
                    date = date.plusDays(1),
                    sequenceItemName = "Push",
                ),
                occurrence(
                    id = 13,
                    taskId = 1,
                    date = date.plusDays(2),
                    status = HabitStatus.Completed,
                    sequenceItemName = "Pull",
                ),
            ),
        )

        assertEquals(listOf(12), store.sequenceSwapCandidates(11).map { it.id })
        store.swapSequenceOccurrenceItems(11, 10)

        val current = store.occurrences.single { it.id == 11 }
        val past = store.occurrences.single { it.id == 10 }
        assertEquals("Legs", current.sequenceItemName)
        assertEquals("Current legs", current.note)
        assertEquals("Pull", past.sequenceItemName)
        assertEquals("Past pull", past.note)
        assertEquals(HabitStatus.Skipped, past.status)
        assertTrue(store.logs.none { it.note == "Swapped sequence item with May 19" })
    }

    @Test
    fun currentSequenceItemUsesPositionWhenLabelsRepeat() {
        val occurrence = occurrence(
            id = 10,
            taskId = 1,
            date = LocalDate.of(2026, 5, 20),
            sequenceItemName = "Zone 2 Cardio",
            sequenceItemPosition = 3,
        )
        val sequenceItems = listOf(
            "Full-Body Strength A",
            "Zone 2 Cardio",
            "Upper Hypertrophy A",
            "Zone 2 Cardio",
        )

        assertEquals(
            listOf(false, false, false, true),
            sequenceItems.mapIndexed { index, item -> isCurrentSequenceItem(index, item, occurrence) },
        )
    }

    @Test
    fun setSequenceOccurrencePointCarriesFuturePendingSequenceFromSelectedPoint() {
        val store = cleanStore()
        val date = LocalDate.of(2026, 5, 20)
        store.tasks.add(
            task(
                id = 1,
                name = "Workout",
                type = HabitTaskType.Sequence,
                sequenceItems = listOf(
                    "Full-Body Strength A",
                    "Zone 2 Cardio",
                    "Upper Hypertrophy A",
                    "Easy Cardio",
                    "Lower Hypertrophy A",
                ),
            ),
        )
        store.occurrences.addAll(
            listOf(
                occurrence(
                    id = 10,
                    taskId = 1,
                    date = date,
                    sequenceItemName = "Zone 2 Cardio",
                    sequenceItemPosition = 1,
                ),
                occurrence(
                    id = 11,
                    taskId = 1,
                    date = date.plusDays(1),
                    sequenceItemName = "Upper Hypertrophy A",
                    sequenceItemPosition = 2,
                ),
                occurrence(
                    id = 12,
                    taskId = 1,
                    date = date.plusDays(2),
                    sequenceItemName = "Easy Cardio",
                    sequenceItemPosition = 3,
                ),
                occurrence(
                    id = 13,
                    taskId = 1,
                    date = date.plusDays(3),
                    sequenceItemName = "Lower Hypertrophy A",
                    sequenceItemPosition = 4,
                ),
            ),
        )

        store.setSequenceOccurrencePoint(10, 3)

        val after = store.occurrences.sortedBy { it.operationalDate }
        assertEquals(
            listOf("Easy Cardio", "Lower Hypertrophy A", "Full-Body Strength A", "Zone 2 Cardio"),
            after.map { it.sequenceItemName },
        )
        assertEquals(listOf(3, 4, 0, 1), after.map { it.sequenceItemPosition })
        assertEquals(
            listOf(date, date.plusDays(1), date.plusDays(2), date.plusDays(3)),
            after.map { it.operationalDate },
        )
        assertTrue(store.logs.any { it.note == "Set sequence point to Easy Cardio" })
    }

    @Test
    fun statsForTaskCountsShiftedRowsAndExcludesCurrentPending() {
        val store = cleanStore()
        val today = store.operationalDate
        store.tasks.add(task(id = 1, name = "Workout", type = HabitTaskType.Sequence))
        store.occurrences.addAll(
            listOf(
                occurrence(id = 1, taskId = 1, date = today.minusDays(5), status = HabitStatus.Completed),
                occurrence(id = 2, taskId = 1, date = today.minusDays(4), status = HabitStatus.Skipped),
                occurrence(id = 3, taskId = 1, date = today.minusDays(3), status = HabitStatus.Missed),
                occurrence(id = 4, taskId = 1, date = today.minusDays(2), status = HabitStatus.Shifted, isShifted = true),
                occurrence(id = 5, taskId = 1, date = today.minusDays(1), status = HabitStatus.Missed, isShifted = true),
                occurrence(id = 6, taskId = 1, date = today, status = HabitStatus.Pending),
            ),
        )

        val stats = store.statsFor(1)

        assertEquals(1, stats.completed)
        assertEquals(1, stats.skipped)
        assertEquals(2, stats.missed)
        assertEquals(2, stats.shifted)
        assertEquals(20, stats.completionPercentage)
        assertEquals(20, stats.skipRate)
        assertEquals(40, stats.missRate)
        assertEquals(5, stats.pastTotal)
    }

    @Test
    fun deleteTaskPermanentlyRemovesTaskOccurrencesLogsAndSelection() {
        val store = cleanStore()
        store.tasks.addAll(listOf(task(id = 1, name = "Delete me"), task(id = 2, name = "Keep me")))
        store.occurrences.addAll(
            listOf(
                occurrence(id = 10, taskId = 1, date = store.operationalDate),
                occurrence(id = 11, taskId = 2, date = store.operationalDate),
            ),
        )
        store.logs.add(
            HabitLogUi(
                id = 1,
                taskId = 1,
                occurrenceId = 10,
                action = HabitLogAction.Completed,
                timestamp = LocalDateTime.of(2026, 5, 20, 8, 0),
                operationalDate = store.operationalDate,
                note = "Done",
            ),
        )
        store.detailTaskId = 1
        store.calendarTaskFilterId = 1

        store.deleteTaskPermanently(1)

        assertEquals(listOf(2), store.tasks.map { it.id })
        assertEquals(listOf(11), store.occurrences.map { it.id })
        assertTrue(store.logs.isEmpty())
        assertEquals(2, store.detailTaskId)
        assertEquals(null, store.calendarTaskFilterId)
    }

    @Test
    fun taskDetailHistoryExcludesFuturePendingScheduleRows() {
        val store = cleanStore()
        val today = store.operationalDate
        store.tasks.add(task(id = 1, name = "Workout", type = HabitTaskType.Sequence))
        store.occurrences.addAll(
            listOf(
                occurrence(id = 1, taskId = 1, date = today.minusDays(2), status = HabitStatus.Completed, sequenceItemName = "Push"),
                occurrence(id = 2, taskId = 1, date = today.minusDays(1), status = HabitStatus.Pending, sequenceItemName = "Pull"),
                occurrence(id = 3, taskId = 1, date = today, status = HabitStatus.Completed, sequenceItemName = "Legs"),
                occurrence(id = 4, taskId = 1, date = today, status = HabitStatus.Pending, sequenceItemName = "Cardio"),
                occurrence(id = 5, taskId = 1, date = today.plusDays(1), status = HabitStatus.Pending, sequenceItemName = "HIIT"),
            ),
        )

        assertEquals(setOf(1, 2, 3, 4, 5), store.occurrencesForTask(1).map { it.id }.toSet())
        assertEquals(listOf(3, 2, 1), store.historyOccurrencesForTask(1).map { it.id })
    }

    @Test
    fun workoutExerciseChecksCompleteAndReopenTheParentOccurrence() {
        val store = cleanStore()
        val workoutDay = HabitWorkoutDayUi(
            position = 0,
            title = "Day 1 - Strength",
            exercises = listOf(
                HabitWorkoutExerciseUi(
                    id = 101,
                    position = 0,
                    name = "Calf raise",
                    prescription = "4 sets x 8 reps",
                    requirement = ExerciseRequirement.REQUIRED,
                ),
                HabitWorkoutExerciseUi(
                    id = 102,
                    position = 1,
                    name = "Isometric hold",
                    prescription = "5 sets x 45 seconds",
                    requirement = ExerciseRequirement.CONDITIONAL,
                ),
            ),
        )
        store.tasks.add(
            task(
                id = 1,
                name = "Rehab",
                type = HabitTaskType.Sequence,
                sequenceItems = listOf(workoutDay.title),
            ).copy(workoutDays = listOf(workoutDay)),
        )
        store.occurrences.add(
            occurrence(
                id = 1,
                taskId = 1,
                date = store.operationalDate,
                sequenceItemName = workoutDay.title,
                sequenceItemPosition = 0,
            ),
        )

        store.setExerciseCheckStatus(1, 102, ExerciseCheckStatus.NOT_NEEDED)
        assertEquals(HabitStatus.Pending, store.occurrences.single().status)
        store.setExerciseCheckStatus(1, 101, ExerciseCheckStatus.COMPLETED)
        assertEquals(HabitStatus.Completed, store.occurrences.single().status)
        store.setExerciseCheckStatus(1, 101, ExerciseCheckStatus.PENDING)
        assertEquals(HabitStatus.Pending, store.occurrences.single().status)
        assertEquals(ExerciseCheckStatus.NOT_NEEDED, store.occurrences.single().exerciseChecks[102])
    }

    @Test
    fun manualPhaseReviewAppearsAfterMinimumDaysAndReturnsOneWeekAfterExtension() {
        val store = cleanStore(now = LocalDateTime.of(2026, 6, 3, 12, 0))
        store.tasks.add(task(id = 1, name = "Foundation"))
        store.routinePhases.add(
            HabitRoutinePhaseUi(
                id = 1,
                routinePlanId = 1,
                routinePlanName = "Achilles",
                taskId = 1,
                position = 0,
                advanceMode = PhaseAdvanceMode.MANUAL,
                minimumDays = 14,
                progressionNote = "Has soreness remained stable?",
                status = RoutinePhaseStatus.ACTIVE,
                activatedDate = LocalDate.of(2026, 5, 20),
                lastReviewedDate = null,
                nextPhaseName = "Pogo",
            ),
        )

        assertEquals(listOf(1), store.pendingRoutinePhaseReviews().map { it.id })
        store.extendRoutinePhaseOneWeek(1)
        assertTrue(store.pendingRoutinePhaseReviews().isEmpty())

        val nextDayStore = cleanStore(now = LocalDateTime.of(2026, 6, 4, 12, 0))
        nextDayStore.tasks.add(task(id = 1, name = "Foundation"))
        nextDayStore.routinePhases.add(store.routinePhases.single())
        assertTrue(nextDayStore.pendingRoutinePhaseReviews().isEmpty())

        val nextReviewStore = cleanStore(now = LocalDateTime.of(2026, 6, 10, 12, 0))
        nextReviewStore.tasks.add(task(id = 1, name = "Foundation"))
        nextReviewStore.routinePhases.add(store.routinePhases.single())
        assertEquals(listOf(1), nextReviewStore.pendingRoutinePhaseReviews().map { it.id })
    }

    @Test
    fun phaseMinimumLengthUsesWeeksOnlyForWholeWeeks() {
        assertEquals("2 weeks", phaseMinimumLengthLabel(14))
        assertEquals("6 weeks", phaseMinimumLengthLabel(42))
        assertEquals("10 days", phaseMinimumLengthLabel(10))
    }

    @Test
    fun exerciseTimerStartsPausesCompletesAndRestartsFromPrescriptionDuration() {
        var timerNow = 1_000L
        val startedTimers = mutableListOf<Pair<String, Long>>()
        val canceledTimers = mutableListOf<Pair<Int, Int>>()
        val controller = object : ExerciseTimerController {
            override fun startTimer(
                occurrenceId: Int,
                exerciseId: Int,
                exerciseName: String,
                endsAtEpochMillis: Long,
            ) {
                startedTimers += exerciseName to endsAtEpochMillis
            }

            override fun cancelTimer(occurrenceId: Int, exerciseId: Int) {
                canceledTimers += occurrenceId to exerciseId
            }
        }
        val store = HabitTrackerUiStore(
            timerNowProvider = { timerNow },
            exerciseTimerController = controller,
        )

        assertEquals(45, store.exerciseTimerFor(10, 101, 45).remainingSeconds)
        assertFalse(store.exerciseTimerFor(10, 101, 45).isRunning)

        store.toggleExerciseTimer(10, 101, 45, "Single-leg balance")
        assertTrue(store.exerciseTimerFor(10, 101, 45).isRunning)
        assertEquals(listOf("Single-leg balance" to 46_000L), startedTimers)

        timerNow += 10_500L
        assertEquals(0, store.tickExerciseTimers())
        assertEquals(35, store.exerciseTimerFor(10, 101, 45).remainingSeconds)

        store.toggleExerciseTimer(10, 101, 45)
        assertFalse(store.exerciseTimerFor(10, 101, 45).isRunning)
        assertEquals(listOf(10 to 101), canceledTimers)
        timerNow += 5_000L
        assertEquals(0, store.tickExerciseTimers())
        assertEquals(35, store.exerciseTimerFor(10, 101, 45).remainingSeconds)

        store.toggleExerciseTimer(10, 101, 45)
        timerNow += 35_000L
        assertEquals(1, store.tickExerciseTimers())
        assertTrue(store.exerciseTimerFor(10, 101, 45).isComplete)

        store.toggleExerciseTimer(10, 101, 45)
        val restarted = store.exerciseTimerFor(10, 101, 45)
        assertTrue(restarted.isRunning)
        assertEquals(45, restarted.remainingSeconds)
        assertEquals(3, startedTimers.size)
    }

    private fun cleanStore(now: LocalDateTime = LocalDateTime.of(2026, 5, 20, 12, 0)): HabitTrackerUiStore {
        return HabitTrackerUiStore(
            nowProvider = { now },
        ).also {
            it.tasks.clear()
            it.occurrences.clear()
            it.logs.clear()
        }
    }

    private fun task(
        id: Int,
        name: String,
        type: HabitTaskType = HabitTaskType.Simple,
        notes: String = "",
        startDate: LocalDate = LocalDate.of(2026, 5, 20),
        endDate: LocalDate? = null,
        durationDays: Int? = null,
        startsAfterTaskId: Int? = null,
        calendarVisible: Boolean = true,
        archived: Boolean = false,
        isActive: Boolean = true,
        blockedDays: Set<DayOfWeek> = emptySet(),
        sequenceItems: List<String> = emptyList(),
        intervalDays: Int? = null,
        longTermRecurrenceUnit: LongTermRecurrenceUnit = LongTermRecurrenceUnit.Months,
        longTermRecurrenceAnchor: LongTermRecurrenceAnchor = LongTermRecurrenceAnchor.COMPLETION_DATE,
        timeOfDay: TaskTimeOfDay = TaskTimeOfDay.GENERAL,
        pushable: Boolean = false,
        noActionBehavior: NoActionBehavior = NoActionBehavior.MARK_MISSED,
        cycleGroupId: Int? = null,
        cycleGroupName: String = "",
    ) = HabitTaskUi(
        id = id,
        name = name,
        type = type,
        notes = notes,
        recurrenceSummary = "Daily",
        startDate = startDate,
        endDate = endDate,
        durationDays = durationDays,
        startsAfterTaskId = startsAfterTaskId,
        intervalDays = intervalDays,
        longTermRecurrenceUnit = longTermRecurrenceUnit,
        longTermRecurrenceAnchor = longTermRecurrenceAnchor,
        blockedDays = blockedDays,
        sequenceItems = sequenceItems,
        timeOfDay = timeOfDay,
        pushable = pushable,
        noActionBehavior = noActionBehavior,
        calendarVisible = calendarVisible,
        archived = archived,
        isActive = isActive,
        cycleGroupId = cycleGroupId,
        cycleGroupName = cycleGroupName,
    )

    private fun routinePhase(
        id: Int,
        taskId: Int,
        position: Int,
        status: RoutinePhaseStatus,
        activatedDate: LocalDate? = null,
    ) = HabitRoutinePhaseUi(
        id = id,
        routinePlanId = 50,
        routinePlanName = "Achilles routine",
        taskId = taskId,
        position = position,
        advanceMode = PhaseAdvanceMode.MANUAL,
        minimumDays = 14,
        progressionNote = "Symptoms remain stable",
        status = status,
        activatedDate = activatedDate,
        lastReviewedDate = null,
        nextPhaseName = null,
    )

    private fun occurrence(
        id: Int,
        taskId: Int,
        date: LocalDate,
        status: HabitStatus = HabitStatus.Pending,
        isShifted: Boolean = false,
        originalDate: LocalDate? = null,
        sequenceItemName: String? = null,
        sequenceItemPosition: Int? = null,
        note: String = "",
    ) = HabitOccurrenceUi(
        id = id,
        taskId = taskId,
        scheduledDate = date,
        operationalDate = date,
        status = status,
        isShifted = isShifted,
        originalDate = originalDate,
        sequenceItemName = sequenceItemName,
        sequenceItemPosition = sequenceItemPosition,
        note = note,
    )
}
