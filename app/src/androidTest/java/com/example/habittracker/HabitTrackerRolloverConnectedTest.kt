package com.example.habittracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import com.example.habittracker.data.ExerciseRequirement
import com.example.habittracker.data.ExerciseCheckStatus
import com.example.habittracker.data.PhaseAdvanceMode
import com.example.habittracker.data.RoutinePhaseStatus
import com.example.habittracker.ui.HabitTrackerApp
import com.example.habittracker.ui.HabitLogAction
import com.example.habittracker.ui.HabitLogUi
import com.example.habittracker.ui.HabitOccurrenceUi
import com.example.habittracker.ui.HabitRoutinePhaseUi
import com.example.habittracker.ui.HabitStatus
import com.example.habittracker.ui.HabitTaskType
import com.example.habittracker.ui.HabitTaskUi
import com.example.habittracker.ui.HabitTrackerUiStore
import com.example.habittracker.ui.HabitWorkoutDayUi
import com.example.habittracker.ui.HabitWorkoutExerciseUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

class HabitTrackerRolloverConnectedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun completeChecklistItemWithInjectedStore() {
        val taskName = "Injected checklist ${System.currentTimeMillis()}"
        val store = cleanInjectedStore().also { seededStore ->
            val date = seededStore.operationalDate
            seededStore.tasks.add(
                HabitTaskUi(
                    id = 10,
                    name = taskName,
                    type = HabitTaskType.Simple,
                    notes = "",
                    recurrenceSummary = "Daily",
                    startDate = date,
                ),
            )
            seededStore.occurrences.add(
                HabitOccurrenceUi(
                    id = 500,
                    taskId = 10,
                    scheduledDate = date,
                    operationalDate = date,
                    status = HabitStatus.Pending,
                ),
            )
        }

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithTag("nav-today").performClick()
        compose.onNodeWithText(taskName).assertIsDisplayed()
        compose.onNodeWithTag("checklist-card-10").assertIsDisplayed()
        compose.onNodeWithTag("checklist-complete-10").performClick()
        compose.onNodeWithTag("today-completed-section").performClick()
        compose.onNodeWithTag("today-list")
            .performScrollToNode(hasTestTag("checklist-card-10"))
        compose.onNodeWithTag("checklist-complete-10").assertIsNotEnabled()
        compose.onNodeWithTag("checklist-skip-10").assertIsNotEnabled()
    }

    @Test
    fun nestedWorkoutDayShowsFullExercisesAndCompletesFromRequiredChecks() {
        val store = cleanInjectedStore().also { seededStore ->
            val date = seededStore.operationalDate
            val workoutDay = HabitWorkoutDayUi(
                position = 0,
                title = "Day 1 - Strength Rehab",
                exercises = listOf(
                    HabitWorkoutExerciseUi(
                        id = 101,
                        position = 0,
                        name = "Straight-Knee Calf Raise",
                        prescription = "4 sets x 8 reps",
                        instructions = "Lower for 3 seconds",
                    ),
                    HabitWorkoutExerciseUi(
                        id = 102,
                        position = 1,
                        name = "Bent-Knee Calf Raise",
                        prescription = "4 sets x 10 reps",
                    ),
                    HabitWorkoutExerciseUi(
                        id = 103,
                        position = 2,
                        name = "Isometric Calf Hold",
                        prescription = "5 sets x 45 seconds",
                        requirement = ExerciseRequirement.CONDITIONAL,
                    ),
                ),
            )
            seededStore.tasks.add(
                HabitTaskUi(
                    id = 10,
                    name = "Achilles Rehab",
                    type = HabitTaskType.Sequence,
                    notes = "",
                    recurrenceSummary = "Sequence daily",
                    startDate = date,
                    sequenceItems = listOf(workoutDay.title),
                    workoutDays = listOf(workoutDay),
                ),
            )
            seededStore.occurrences.add(
                HabitOccurrenceUi(
                    id = 500,
                    taskId = 10,
                    scheduledDate = date,
                    operationalDate = date,
                    status = HabitStatus.Pending,
                    sequenceItemName = workoutDay.title,
                    sequenceItemPosition = 0,
                ),
            )
        }

        compose.setContent { HabitTrackerApp(store = store) }

        compose.onNodeWithText("Straight-Knee Calf Raise").assertIsDisplayed()
        compose.onNodeWithText("4 sets x 8 reps").assertIsDisplayed()
        compose.onNodeWithText("Isometric Calf Hold").assertIsDisplayed()
        compose.onNodeWithTag("workout-exercise-timer-10-2").assertIsDisplayed().performClick()
        compose.onNodeWithText("0:45").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(store.exerciseTimerFor(500, 103, 45).isRunning)
        }
        compose.onNodeWithTag("workout-exercise-not-needed-10-2").performScrollTo().performClick()
        compose.onNodeWithTag("workout-exercise-check-10-0").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(HabitStatus.Pending, store.occurrences.single().status)
            assertEquals(ExerciseCheckStatus.NOT_NEEDED, store.occurrences.single().exerciseChecks[103])
        }
        compose.onNodeWithTag("workout-exercise-check-10-1").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(HabitStatus.Completed, store.occurrences.single().status)
        }
    }

    @Test
    fun phaseReviewExtendsOneWeekAndChecklistShowsMinimumLength() {
        val store = cleanInjectedStore().also { seededStore ->
            val date = seededStore.operationalDate
            seededStore.tasks.add(
                HabitTaskUi(
                    id = 12,
                    name = "Achilles Foundation",
                    type = HabitTaskType.Sequence,
                    notes = "",
                    recurrenceSummary = "Sequence every day",
                    startDate = date.minusDays(14),
                    sequenceItems = listOf("Day 1 - Strength"),
                ),
            )
            seededStore.occurrences.add(
                HabitOccurrenceUi(
                    id = 512,
                    taskId = 12,
                    scheduledDate = date,
                    operationalDate = date,
                    status = HabitStatus.Pending,
                    sequenceItemName = "Day 1 - Strength",
                    sequenceItemPosition = 0,
                ),
            )
            seededStore.routinePhases.add(
                HabitRoutinePhaseUi(
                    id = 91,
                    routinePlanId = 9,
                    routinePlanName = "Achilles routine",
                    taskId = 12,
                    position = 0,
                    advanceMode = PhaseAdvanceMode.MANUAL,
                    minimumDays = 14,
                    progressionNote = "Has morning soreness remained stable?",
                    status = RoutinePhaseStatus.ACTIVE,
                    activatedDate = date.minusDays(14),
                    lastReviewedDate = null,
                    nextPhaseName = "Pogo progression",
                ),
            )
        }

        compose.setContent { HabitTrackerApp(store = store) }

        assertTagExists("phase-review-card-91")
        compose.onNodeWithTag("phase-review-extend-week-91")
            .assertIsDisplayed()
            .performClick()
        assertTagDoesNotExist("phase-review-card-91")
        compose.runOnIdle {
            assertEquals(store.operationalDate, store.routinePhases.single().lastReviewedDate)
        }
        compose.onNodeWithTag("today-list")
            .performScrollToNode(hasTestTag("checklist-phase-minimum-12"))
        compose.onNodeWithTag("checklist-phase-minimum-12").assertIsDisplayed()
        compose.onNodeWithText("Phase minimum: 2 weeks").assertIsDisplayed()
    }

    @Test
    fun checklistNoteEditAndStatsActionUseStableTags() {
        val taskName = "Injected note ${System.currentTimeMillis()}"
        val store = cleanInjectedStore().also { seededStore ->
            val date = seededStore.operationalDate
            seededStore.tasks.add(
                HabitTaskUi(
                    id = 11,
                    name = taskName,
                    type = HabitTaskType.Simple,
                    notes = "",
                    recurrenceSummary = "Daily",
                    startDate = date,
                ),
            )
            seededStore.occurrences.add(
                HabitOccurrenceUi(
                    id = 510,
                    taskId = 11,
                    scheduledDate = date,
                    operationalDate = date,
                    status = HabitStatus.Pending,
                ),
            )
        }

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithTag("nav-today").performClick()
        compose.onNodeWithTag("today-list")
            .performScrollToNode(hasTestTag("checklist-card-11"))
        compose.onNodeWithTag("checklist-note-toggle-11").performClick()
        compose.onNodeWithTag("checklist-note-field-11").performTextInput("Reduced volume")
        compose.onNodeWithTag("checklist-note-save-11").performClick()
        compose.onNodeWithTag("today-list")
            .performScrollToNode(hasTestTag("checklist-card-11"))
        assertTagDoesNotExist("checklist-note-display-11")
        compose.onNodeWithTag("checklist-notes-11-toggle").performClick()
        compose.onNodeWithTag("checklist-note-display-11").assertIsDisplayed()
        compose.onNodeWithText("Note: Reduced volume").assertIsDisplayed()

        compose.onNodeWithTag("checklist-stats-11").performClick()
        compose.onNodeWithText("Task Detail").assertIsDisplayed()
        assertTagExists("task-detail-header-11")
    }

    @Test
    fun pendingChecklistNotesAndSequenceItemsStartCollapsed() {
        val store = cleanInjectedStore().also { seededStore ->
            val date = seededStore.operationalDate
            seededStore.tasks.add(
                HabitTaskUi(
                    id = 12,
                    name = "Collapsed sequence",
                    type = HabitTaskType.Sequence,
                    notes = "Keep elbows under wrists",
                    recurrenceSummary = "Push / Pull / Legs",
                    startDate = date.minusDays(2),
                    sequenceItems = listOf("Push", "Pull", "Legs"),
                    pushable = true,
                ),
            )
            seededStore.occurrences.addAll(
                listOf(
                    HabitOccurrenceUi(
                        id = 520,
                        taskId = 12,
                        scheduledDate = date.minusDays(2),
                        operationalDate = date.minusDays(2),
                        status = HabitStatus.Completed,
                        sequenceItemName = "Pull",
                        note = "Rows felt strong",
                    ),
                    HabitOccurrenceUi(
                        id = 521,
                        taskId = 12,
                        scheduledDate = date,
                        operationalDate = date,
                        status = HabitStatus.Pending,
                        sequenceItemName = "Pull",
                    ),
                ),
            )
        }

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithTag("today-list")
            .performScrollToNode(hasTestTag("checklist-card-12"))
        assertTagExists("checklist-notes-12-toggle")
        assertTagExists("checklist-sequence-items-12-toggle")
        assertTagDoesNotExist("checklist-notes-12-content")
        assertTagDoesNotExist("checklist-sequence-items-12-content")

        compose.onNodeWithTag("checklist-notes-12-toggle").performClick()
        compose.onNodeWithTag("checklist-task-notes-display-12").assertIsDisplayed()
        compose.onNodeWithTag("checklist-sequence-note-history-12").assertIsDisplayed()

        compose.onNodeWithTag("checklist-sequence-items-12-toggle").performClick()
        compose.onNodeWithTag("checklist-sequence-items-12-content").assertIsDisplayed()
        compose.onNodeWithTag("checklist-sequence-item-12-0").assertIsDisplayed()
        compose.onNodeWithTag("checklist-sequence-item-12-1").assertIsDisplayed()
    }

    @Test
    fun todayQuickAddOneTimeTaskCreatesTodayOnlyTask() {
        val store = cleanInjectedStore()

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithTag("today-quick-add-one-time").performClick()
        compose.onNodeWithTag("today-quick-add-one-time-name").performTextInput("One-off errand")
        compose.onNodeWithTag("today-quick-add-one-time-save").performClick()

        compose.runOnIdle {
            val task = store.tasks.single()
            assertEquals("One-off errand", task.name)
            assertEquals(HabitTaskType.OneTime, task.type)
            assertEquals(store.operationalDate, task.startDate)
            assertEquals(store.operationalDate, task.endDate)
            assertEquals(1, task.durationDays)
            assertEquals(listOf(task.id), store.todayOccurrences().map { it.taskId })
        }
        compose.onNodeWithText("One-off errand").assertIsDisplayed()

        compose.onNodeWithTag("nav-tasks").performClick()
        assertTagDoesNotExist("task-editor-active-tasks-section")
    }

    @Test
    fun settingsRolloverClockPickerOpens() {
        val store = cleanInjectedStore()

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithText("Tue, May 19").assertIsDisplayed()
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-rollover-picker").performClick()
        compose.onNodeWithTag("settings-rollover-clock-picker").assertIsDisplayed()
        compose.onNodeWithTag("settings-rollover-time-cancel").performClick()
    }

    @Test
    fun settingsAcceptanceControlsUseStableTags() {
        val store = cleanInjectedStore()

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("settings-rollover-picker"))
        compose.onNodeWithTag("settings-rollover-picker").assertIsDisplayed()
        assertTagDoesNotExist("settings-rollover-preset-0200")
        assertTagDoesNotExist("settings-rollover-minus")
        assertTagDoesNotExist("settings-rollover-plus")

        compose.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("settings-daily-review-switch"))
        compose.onNodeWithTag("settings-daily-review-switch").assertIsDisplayed()
        compose.onNodeWithTag("settings-review-time-picker").assertIsDisplayed()
        assertTagDoesNotExist("settings-review-time-preset-0800")
        compose.onNodeWithTag("settings-late-reminder-switch").assertIsDisplayed()
        compose.onNodeWithTag("settings-late-time-picker").assertIsDisplayed()
        assertTagDoesNotExist("settings-late-time-preset-2000")

        compose.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("settings-default-blocked-day-sunday"))
        assertTagExists("settings-default-blocked-day-sunday")
        assertTagExists("settings-default-blocked-day-saturday")
        assertTagExists("settings-theme-system")
        assertTagExists("settings-theme-light")
        assertTagExists("settings-theme-dark")

        compose.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("settings-exact-alarm-refresh"))
        compose.onNodeWithTag("settings-exact-alarm-primary").assertIsDisplayed()
        compose.onNodeWithTag("settings-exact-alarm-primary").assertIsEnabled()
        compose.onNodeWithTag("settings-exact-alarm-refresh").assertIsDisplayed()
        compose.onNodeWithTag("settings-reminder-schedule-status").assertIsDisplayed()
        compose.onNodeWithTag("settings-exact-alarm-prompt-status").assertIsDisplayed()
        compose.onNodeWithTag("settings-notifications-primary").assertIsDisplayed()
        compose.onNodeWithTag("settings-notifications-refresh").assertIsDisplayed()

        compose.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("settings-backup-button"))
        compose.onNodeWithTag("settings-backup-button").assertIsDisplayed()
        compose.onNodeWithTag("settings-restore-button").assertIsDisplayed()
        assertTagExists("settings-backup-status")
        assertTagExists("settings-restore-status")
    }

    @Test
    fun taskEditorRecurrenceControlsUseStableTags() {
        val store = cleanInjectedStore()

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithTag("nav-tasks").performClick()
        compose.onNodeWithTag("task-editor-list").assertIsDisplayed()
        compose.onNodeWithTag("task-name-field").assertIsDisplayed()
        assertTagExists("task-notes-field")
        assertTagExists("task-start-date-picker")
        assertTagExists("task-end-date-no-end")
        assertTagExists("task-end-date-use-end")
        assertTagExists("task-type-simple")
        assertTagExists("task-type-interval")
        assertTagExists("task-type-weekday")
        assertTagExists("task-type-sequence")
        assertTagExists("task-recurrence-daily")

        compose.onNodeWithTag("task-type-interval").performClick()
        assertTagExists("task-interval-minus")
        assertTagExists("task-interval-value")
        assertTagExists("task-interval-plus")

        compose.onNodeWithTag("task-type-weekday").performClick()
        assertTagExists("task-weekday-monday")
        assertTagExists("task-weekday-friday")

        compose.onNodeWithTag("task-type-sequence").performClick()
        assertTagExists("task-sequence-items-field")
        assertTagExists("task-blocked-day-sunday")
        assertTagExists("task-blocked-day-saturday")
        assertTagExists("task-editor-reminders-switch")
        assertTagExists("task-editor-calendar-visible-switch")
        assertTagExists("task-editor-active-switch")
        assertTagExists("task-save-button")
        assertTagExists("task-clear-button")
    }

    @Test
    fun sequenceBulkPastePreviewsEditsAndAppliesOrderedItems() {
        val store = cleanInjectedStore()

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithTag("nav-tasks").performClick()
        compose.onNodeWithTag("task-type-sequence").performClick()
        compose.onNodeWithTag("task-sequence-bulk-import")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("sequence-import-paste-field"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("sequence-import-format-guide").performClick()
        compose.onNodeWithTag("sequence-format-guide-note").assertIsDisplayed()
        compose.onNodeWithText("include concrete sets and reps", substring = true, ignoreCase = true)
            .assertExists()
        compose.onNodeWithTag("sequence-format-guide-option-phases").performClick()
        compose.onNodeWithText("Paste AI output into Tasks > Bulk phases.").assertIsDisplayed()
        compose.onNodeWithText("Create a multi-phase, day-numbered routine", substring = true).assertExists()
        compose.onNodeWithText("Put every exercise for that day", substring = true)
            .assertExists()
        compose.onNodeWithTag("sequence-format-guide-copy").performClick()
        compose.onNodeWithText("Copied").assertIsDisplayed()
        compose.onNodeWithTag("sequence-format-guide-back").performClick()
        compose.onNodeWithTag("sequence-import-paste-field").performTextInput(
            "1. Full-Body Strength A (3 sets x 8 reps)\n" +
                "2. Zone 2 Cardio (30 minutes)\n" +
                "3. Easy Cardio (20 minutes)",
        )
        compose.onNodeWithTag("sequence-import-preview-button").performClick()

        compose.onNodeWithText("3 sequence items").assertIsDisplayed()
        compose.onNodeWithTag("sequence-import-move-down-0").performClick()
        compose.onNodeWithTag("sequence-import-preview-name-0")
            .performTextReplacement("Zone 2 Recovery")
        compose.onNodeWithTag("sequence-import-delete-2").performClick()
        compose.onNodeWithTag("sequence-import-apply-button").performClick()

        compose.runOnIdle {
            assertEquals(
                "Zone 2 Recovery\nFull-Body Strength A (3 sets x 8 reps)",
                store.draft.sequenceText,
            )
        }
        assertTagDoesNotExist("sequence-import-dialog")
    }

    @Test
    fun phaseBulkPastePreviewsAndCreatesOneLinkedPlan() {
        val store = cleanInjectedStore()

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithTag("nav-tasks").performClick()
        compose.onNodeWithTag("task-phase-bulk-import")
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("phase-import-dialog").assertIsDisplayed()
        compose.onNodeWithTag("phase-import-format-guide").performClick()
        compose.onNodeWithTag("phase-format-guide-note").assertIsDisplayed()
        compose.onNodeWithText("Paste AI output into this Bulk phases importer.").assertIsDisplayed()
        compose.onNodeWithText("Prescription must include concrete sets, reps", substring = true, ignoreCase = true)
            .assertExists()
        compose.onNodeWithTag("phase-format-guide-copy").performClick()
        compose.onNodeWithText("Copied").assertIsDisplayed()
        compose.onNodeWithTag("phase-format-guide-back").performClick()

        compose.onNodeWithTag("phase-import-paste-field").performTextInput(
            "Broken | two weeks-ish | daily | morning | push",
        )
        compose.onNodeWithTag("phase-import-error-1").assertIsDisplayed()
        compose.onNodeWithTag("phase-import-preview-button").assertIsNotEnabled()
        compose.onNodeWithTag("phase-import-paste-field").performTextReplacement(
            "Here is the routine:\r\n" +
                "| **PHASE** | **Achilles Foundation** | **minimum 14 days** | **sequence every 1 day** | **morning** | **push** | **manual** |\r\n" +
                "| --- | --- | --- | --- | --- | --- | --- |\r\n" +
                "> **REVIEW** | Has morning soreness remained stable for two weeks?\r\n" +
                "- **DAY 1** | Strength Rehab\r\n" +
                "- **EXERCISE** ｜ Straight-Knee Calf Raise ｜ 4 sets x 8 reps ｜ required ｜ Lower for 3 seconds\r\n" +
                "- **EXERCISE** | Isometric Calf Hold | 5 sets x 45 seconds | conditional | Only when sore\r\n" +
                "- **DAY 2** | Rest Day\r\n" +
                "**END PHASE**\r\n" +
                "PHASE | Pogo Progression | minimum 14 days | sequence every 1 day | morning | push | manual |\n" +
                "REVIEW | Were pogo hops pain-free the following morning?\n" +
                "DAY 1 | Strength and Pogo Hops\n" +
                "EXERCISE | Straight-Knee Calf Raise | 4 sets x 8 reps | required | Lower for 3 seconds\n" +
                "EXERCISE | Two-Leg Pogo Hops | 3 sets x 20 reps | required | Stop if pain increases\n" +
                "DAY 2 | Recovery\n" +
                "EXERCISE | Easy Walk | 20 minutes at easy pace | required | -\n" +
                "END PHASE",
        )
        compose.onNodeWithText("2 phases ready").assertIsDisplayed()
        compose.onNodeWithTag("phase-import-preview-button").performClick()

        compose.onNodeWithText("2 linked phases").assertIsDisplayed()
        compose.onNodeWithTag("phase-import-preview-summary-0").assertIsDisplayed()
        compose.onNodeWithTag("phase-import-preview-name-1")
            .performTextReplacement("Pogo Phase")
        compose.onNodeWithTag("phase-import-apply-button").performClick()

        compose.runOnIdle {
            val foundation = store.tasks.single { it.name == "Achilles Foundation" }
            val pogo = store.tasks.single { it.name == "Pogo Phase" }
            assertEquals(null, foundation.startsAfterTaskId)
            assertEquals(null, foundation.durationDays)
            assertEquals(true, foundation.isActive)
            assertEquals(false, pogo.isActive)
            assertEquals(store.operationalDate.plusDays(14), pogo.startDate)
            assertEquals(
                listOf("Day 1 - Strength Rehab", "Day 2 - Rest Day"),
                foundation.sequenceItems,
            )
            assertEquals(2, foundation.workoutDays.size)
            assertEquals("4 sets x 8 reps", foundation.workoutDays.first().exercises.first().prescription)
            assertEquals(ExerciseRequirement.CONDITIONAL, foundation.workoutDays.first().exercises[1].requirement)
            assertEquals(2, store.routinePhases.size)
            assertEquals(PhaseAdvanceMode.MANUAL, store.routinePhases.first().advanceMode)
            assertEquals(RoutinePhaseStatus.ACTIVE, store.routinePhases.first().status)
            assertEquals("Has morning soreness remained stable for two weeks?", store.routinePhases.first().progressionNote)
        }
        assertTagDoesNotExist("phase-import-dialog")
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun exactAttachedAchillesRoutinePastesPreviewsAndCreatesAllPhases() {
        val exactAiOutput = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("achilles-rehab-ai-output.txt")
            .bufferedReader()
            .use { it.readText() }
            .trim()
            .split(Regex("""\n\s*\n"""))
            .joinToString("\n") { phase -> phase.replace('\n', '\u2028') }
        assertEquals(4, exactAiOutput.lineSequence().count())
        val store = cleanInjectedStore()

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithTag("nav-tasks").performClick()
        compose.onNodeWithTag("task-phase-bulk-import").performClick()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("Phased routine", exactAiOutput))
        }
        compose.onNodeWithTag("phase-import-paste-field")
            .performClick()
            .performKeyInput { pressKey(Key.Paste) }

        compose.waitUntil(timeoutMillis = 5_000L) {
            compose.onAllNodesWithText("4 phases ready")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("4 phases ready").assertIsDisplayed()
        compose.onNodeWithTag("phase-import-preview-button")
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithText("4 linked phases").assertIsDisplayed()
        compose.onNodeWithTag("phase-import-apply-button")
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(
                    "Achilles Rehab Foundation",
                    "Pogo Progression",
                    "Single-Leg Pogo Progression",
                    "Jump Progression",
                ),
                store.tasks.map { it.name },
            )
            assertEquals(listOf(42, 14, 14, 14), store.routinePhases.map { it.minimumDays })
            assertEquals(16, store.tasks.sumOf { it.workoutDays.size })
            assertEquals(42, store.tasks.sumOf { task ->
                task.workoutDays.sumOf { it.exercises.size }
            })
        }
        assertTagDoesNotExist("phase-import-dialog")
    }

    @Test
    fun sequenceBulkPasteRejectsStructuredPhaseClipboardText() {
        val phasedText = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("achilles-rehab-ai-output.txt")
            .bufferedReader()
            .use { it.readText() }
            .trim()
            .split(Regex("""\n\s*\n"""))
            .joinToString("\n") { phase -> phase.replace('\n', '\u2028') }
        val store = cleanInjectedStore()

        compose.setContent { HabitTrackerApp(store = store) }

        compose.onNodeWithTag("nav-tasks").performClick()
        compose.onNodeWithTag("task-type-sequence").performScrollTo().performClick()
        compose.onNodeWithTag("task-sequence-bulk-import").performScrollTo().performClick()
        compose.onNodeWithTag("sequence-import-paste-field").performTextReplacement(phasedText)

        compose.onNodeWithText("Phased format: use Bulk phases").assertIsDisplayed()
        compose.onNodeWithTag("sequence-import-preview-button").assertIsNotEnabled()
    }

    private fun assertTagExists(tag: String, useUnmergedTree: Boolean = false) {
        compose.onNodeWithTag(tag, useUnmergedTree = useUnmergedTree).fetchSemanticsNode()
    }

    private fun assertTagDoesNotExist(tag: String) {
        assertTrue(
            "Expected no node with tag $tag",
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun taskListActionsUseStableTags() {
        val store = cleanInjectedStore().also { seededStore ->
            val date = seededStore.operationalDate
            seededStore.tasks.add(
                HabitTaskUi(
                    id = 20,
                    name = "Visible CO2 table",
                    type = HabitTaskType.Interval,
                    notes = "Active row",
                    recurrenceSummary = "Every 2 days for 14 days",
                    startDate = date,
                    endDate = date.plusDays(13),
                    durationDays = 14,
                    intervalDays = 2,
                ),
            )
            seededStore.occurrences.addAll(
                listOf(
                    HabitOccurrenceUi(
                        id = 520,
                        taskId = 20,
                        scheduledDate = date,
                        operationalDate = date,
                        status = HabitStatus.Completed,
                    ),
                    HabitOccurrenceUi(
                        id = 521,
                        taskId = 20,
                        scheduledDate = date.plusDays(2),
                        operationalDate = date.plusDays(2),
                        status = HabitStatus.Pending,
                    ),
                ),
            )
            seededStore.tasks.add(
                HabitTaskUi(
                    id = 21,
                    name = "Archived mobility",
                    type = HabitTaskType.Weekday,
                    notes = "Archived row",
                    recurrenceSummary = "Weekdays",
                    startDate = date,
                    isActive = false,
                    archived = true,
                ),
            )
        }

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithTag("nav-tasks").performClick()
        compose.onNodeWithTag("task-editor-list")
            .performScrollToNode(hasTestTag("task-editor-active-tasks-section"))
        compose.onNodeWithTag("task-editor-active-tasks-section").performClick()
        compose.onNodeWithTag("task-editor-list")
            .performScrollToNode(hasTestTag("task-card-20"))
        assertTagExists("task-card-20")
        assertTagExists("task-edit-20")
        assertTagExists("task-stats-20")
        assertTagExists("task-archive-toggle-20")
        assertTagExists("task-cycle-progress-20")
        compose.onNodeWithText("Cycle progress: 1 of 7 complete, 0 disrupted, 6 left").assertIsDisplayed()

        compose.onNodeWithTag("task-editor-list")
            .performScrollToNode(hasTestTag("task-editor-hidden-tasks-switch"))
        compose.onNodeWithTag("task-editor-hidden-tasks-switch").performClick()
        compose.onNodeWithTag("task-editor-list")
            .performScrollToNode(hasTestTag("task-card-21"))
        assertTagExists("task-card-21")
        assertTagExists("task-edit-21")
        assertTagExists("task-stats-21")
        assertTagExists("task-archive-toggle-21")

        compose.onNodeWithTag("task-edit-21").performClick()
        assertTagExists("task-editor-archived-switch")

        compose.onNodeWithTag("task-editor-list")
            .performScrollToNode(hasTestTag("task-card-20"))
        compose.onNodeWithTag("task-stats-20").performClick()
        compose.onNodeWithText("Task Detail").assertIsDisplayed()
        assertTagExists("task-detail-header-20")
    }

    @Test
    fun phasedRoutineAppearsAsOneExpandableTaskAndArchivesOrRestoresTogether() {
        val store = cleanInjectedStore().also { seededStore ->
            val date = seededStore.operationalDate
            seededStore.tasks.addAll(
                listOf(
                    HabitTaskUi(
                        id = 60,
                        name = "Foundation",
                        type = HabitTaskType.Sequence,
                        notes = "Build calf capacity",
                        recurrenceSummary = "Sequence every day",
                        startDate = date.minusDays(14),
                        isActive = false,
                        sequenceItems = listOf("Day 1 - Strength"),
                    ),
                    HabitTaskUi(
                        id = 61,
                        name = "Pogo progression",
                        type = HabitTaskType.Sequence,
                        notes = "Progress when symptoms remain stable",
                        recurrenceSummary = "Sequence every day",
                        startDate = date,
                        sequenceItems = listOf("Day 1 - Pogo hops"),
                        workoutDays = listOf(
                            HabitWorkoutDayUi(
                                position = 0,
                                title = "Day 1 - Pogo hops",
                                exercises = listOf(
                                    HabitWorkoutExerciseUi(
                                        id = 6101,
                                        position = 0,
                                        name = "Two-leg pogo hops",
                                        prescription = "3 sets x 20 reps",
                                    ),
                                ),
                            ),
                        ),
                    ),
                    HabitTaskUi(
                        id = 62,
                        name = "Jump progression",
                        type = HabitTaskType.Sequence,
                        notes = "Submaximal jumping",
                        recurrenceSummary = "Sequence every day",
                        startDate = date.plusDays(14),
                        isActive = false,
                        sequenceItems = listOf("Day 1 - Jumps"),
                    ),
                ),
            )
            seededStore.routinePhases.addAll(
                listOf(
                    HabitRoutinePhaseUi(
                        id = 701,
                        routinePlanId = 70,
                        routinePlanName = "Achilles return routine",
                        taskId = 60,
                        position = 0,
                        advanceMode = PhaseAdvanceMode.MANUAL,
                        minimumDays = 14,
                        progressionNote = "Morning soreness remained stable",
                        status = RoutinePhaseStatus.COMPLETED,
                        activatedDate = date.minusDays(14),
                        lastReviewedDate = null,
                        nextPhaseName = "Pogo progression",
                    ),
                    HabitRoutinePhaseUi(
                        id = 702,
                        routinePlanId = 70,
                        routinePlanName = "Achilles return routine",
                        taskId = 61,
                        position = 1,
                        advanceMode = PhaseAdvanceMode.MANUAL,
                        minimumDays = 14,
                        progressionNote = "Pogo hops remain pain-free the next morning",
                        status = RoutinePhaseStatus.ACTIVE,
                        activatedDate = date,
                        lastReviewedDate = null,
                        nextPhaseName = "Jump progression",
                    ),
                    HabitRoutinePhaseUi(
                        id = 703,
                        routinePlanId = 70,
                        routinePlanName = "Achilles return routine",
                        taskId = 62,
                        position = 2,
                        advanceMode = PhaseAdvanceMode.MANUAL,
                        minimumDays = 14,
                        progressionNote = "Single-leg hops remain pain-free",
                        status = RoutinePhaseStatus.UPCOMING,
                        activatedDate = null,
                        lastReviewedDate = null,
                    ),
                ),
            )
        }

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithTag("nav-tasks").performClick()
        compose.onNodeWithTag("task-editor-list")
            .performScrollToNode(hasTestTag("task-editor-active-tasks-section"))
        compose.onNodeWithTag("task-editor-active-tasks-section").performClick()
        compose.onNodeWithTag("task-editor-list")
            .performScrollToNode(hasTestTag("routine-plan-card-70"))
        assertTagExists("routine-plan-card-70")
        assertTagDoesNotExist("task-card-60")
        assertTagDoesNotExist("task-card-61")
        assertTagDoesNotExist("task-card-62")

        compose.onNodeWithTag("routine-plan-toggle-70").performClick()
        assertTagExists("routine-plan-content-70")
        assertTagExists("routine-phase-title-701")
        assertTagExists("routine-phase-title-702")
        assertTagExists("routine-phase-title-703")
        compose.onNodeWithTag("task-editor-list")
            .performScrollToNode(hasTestTag("routine-phase-toggle-702"))
        compose.onNodeWithTag("routine-phase-toggle-702").performClick()
        compose.onNodeWithText("Two-leg pogo hops", substring = true).assertExists()
        compose.onNodeWithText("3 sets x 20 reps", substring = true).assertExists()

        compose.onNodeWithTag("task-editor-list")
            .performScrollToNode(hasTestTag("routine-plan-archive-70"))
        compose.onNodeWithTag("routine-plan-archive-70").performClick()
        compose.runOnIdle {
            assertTrue(store.tasks.all { it.archived && !it.isActive })
        }
        assertTagDoesNotExist("task-editor-active-tasks-section")

        compose.onNodeWithTag("task-editor-list")
            .performScrollToNode(hasTestTag("task-editor-hidden-tasks-switch"))
        compose.onNodeWithTag("task-editor-hidden-tasks-switch").performClick()
        compose.onNodeWithTag("task-editor-list")
            .performScrollToNode(hasTestTag("routine-plan-card-70"))
        compose.onNodeWithTag("routine-plan-archive-70").performClick()
        compose.runOnIdle {
            assertTrue(store.tasks.none { it.archived })
            assertEquals(listOf(61), store.tasks.filter { it.isActive }.map { it.id })
        }
    }

    @Test
    fun statsDetailHistoryAndMetricsUseStableTags() {
        val store = cleanInjectedStore().also { seededStore ->
            val date = seededStore.operationalDate
            seededStore.tasks.add(
                HabitTaskUi(
                    id = 30,
                    name = "Sequence detail",
                    type = HabitTaskType.Sequence,
                    notes = "History-ready sequence",
                    recurrenceSummary = "Sequence routine",
                    startDate = date.minusDays(4),
                    sequenceItems = listOf("Push", "Pull", "Legs"),
                ),
            )
            seededStore.occurrences.addAll(
                listOf(
                    HabitOccurrenceUi(
                        id = 700,
                        taskId = 30,
                        scheduledDate = date.minusDays(4),
                        operationalDate = date.minusDays(4),
                        status = HabitStatus.Completed,
                        sequenceItemName = "Push",
                    ),
                    HabitOccurrenceUi(
                        id = 701,
                        taskId = 30,
                        scheduledDate = date.minusDays(3),
                        operationalDate = date.minusDays(3),
                        status = HabitStatus.Skipped,
                        sequenceItemName = "Pull",
                        note = "Travel day",
                    ),
                    HabitOccurrenceUi(
                        id = 702,
                        taskId = 30,
                        scheduledDate = date.minusDays(2),
                        operationalDate = date.minusDays(2),
                        status = HabitStatus.Missed,
                        sequenceItemName = "Legs",
                    ),
                    HabitOccurrenceUi(
                        id = 703,
                        taskId = 30,
                        scheduledDate = date.minusDays(1),
                        operationalDate = date.minusDays(1),
                        status = HabitStatus.Shifted,
                        sequenceItemName = "Push",
                        isShifted = true,
                        originalDate = date.minusDays(2),
                    ),
                ),
            )
            seededStore.logs.add(
                HabitLogUi(
                    id = 800,
                    taskId = 30,
                    occurrenceId = 700,
                    action = HabitLogAction.Completed,
                    timestamp = LocalDateTime.of(2026, 5, 18, 20, 0),
                    operationalDate = date.minusDays(4),
                    note = "Completed Push",
                ),
            )
            seededStore.detailTaskId = 30
        }

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithTag("nav-stats").performClick()
        compose.onNodeWithTag("stats-list").assertIsDisplayed()
        compose.onNodeWithTag("stats-task-filter-30").assertIsDisplayed()
        assertTagExists("task-detail-header-30")
        assertTagExists("task-detail-calendar-30")
        assertTagExists("task-detail-30-current-streak")
        assertTagExists("task-detail-30-longest-streak")
        assertTagExists("task-detail-30-completion")
        assertTagExists("task-detail-30-completed")
        assertTagExists("task-detail-30-skipped")
        assertTagExists("task-detail-30-missed")
        assertTagExists("task-detail-30-shifted")
        assertTagExists("task-detail-30-past-total")

        compose.onNodeWithTag("stats-list")
            .performScrollToNode(hasTestTag("task-detail-history-title-30"))
        assertTagExists("task-detail-history-title-30")
        compose.onNodeWithTag("stats-list")
            .performScrollToNode(hasTestTag("task-detail-history-701"))
        assertTagExists("task-detail-history-701")
        assertTagExists("task-detail-history-701-note-toggle")
        compose.onNodeWithTag("task-detail-history-701-note-toggle").performClick()
        assertTagExists("task-detail-history-701-note-field")
        assertTagExists("task-detail-history-701-note-save")
        assertTagExists("task-detail-history-701-note-cancel")
        compose.onNodeWithTag("stats-list")
            .performScrollToNode(hasTestTag("task-detail-history-702"))
        assertTagExists("task-detail-history-702-shift")
        compose.onNodeWithTag("task-detail-history-702-shift").performClick()
        compose.runOnIdle {
            assertEquals(HabitStatus.Missed, store.occurrences.single { it.id == 702 }.status)
            assertTrue(store.occurrences.single { it.id == 702 }.isShifted)
            assertTrue(
                store.occurrences.any {
                    it.id >= 500 &&
                        it.taskId == 30 &&
                        it.status == HabitStatus.Pending &&
                        it.sequenceItemName == "Legs" &&
                        it.originalDate == store.operationalDate.minusDays(2)
                },
            )
            assertTrue(store.logs.any { it.occurrenceId == 702 && it.action == HabitLogAction.ShiftedForward })
        }

        compose.onNodeWithTag("stats-list")
            .performScrollToNode(hasTestTag("task-detail-log-title-30"))
        assertTagExists("task-detail-log-title-30")
        compose.onNodeWithTag("stats-list")
            .performScrollToNode(hasTestTag("task-detail-log-800"))
        assertTagExists("task-detail-log-800")
    }

    @Test
    fun calendarControlsFiltersAndDetailsUseStableTags() {
        val store = cleanInjectedStore().also { seededStore ->
            val date = seededStore.operationalDate
            seededStore.tasks.addAll(
                listOf(
                    HabitTaskUi(
                        id = 40,
                        name = "Calendar strength",
                        type = HabitTaskType.Simple,
                        notes = "",
                        recurrenceSummary = "Daily",
                        startDate = date.minusDays(1),
                    ),
                    HabitTaskUi(
                        id = 41,
                        name = "Calendar mobility",
                        type = HabitTaskType.Weekday,
                        notes = "",
                        recurrenceSummary = "Weekdays",
                        startDate = date.minusDays(1),
                    ),
                ),
            )
            seededStore.occurrences.addAll(
                listOf(
                    HabitOccurrenceUi(
                        id = 900,
                        taskId = 40,
                        scheduledDate = date,
                        operationalDate = date,
                        status = HabitStatus.Completed,
                        note = "Smooth set",
                    ),
                    HabitOccurrenceUi(
                        id = 901,
                        taskId = 41,
                        scheduledDate = date,
                        operationalDate = date,
                        status = HabitStatus.Skipped,
                        note = "Mobility skipped",
                    ),
                ),
            )
        }

        compose.setContent {
            HabitTrackerApp(store = store)
        }

        compose.onNodeWithTag("nav-calendar").performClick()
        compose.onNodeWithTag("calendar-list").assertIsDisplayed()
        assertTagExists("calendar-month-previous")
        assertTagExists("calendar-month-title")
        assertTagExists("calendar-month-today")
        assertTagExists("calendar-month-next")
        assertTagExists("calendar-filter-list")
        assertTagExists("calendar-filter-all")
        assertTagExists("calendar-filter-40")
        assertTagExists("calendar-filter-41")
        assertTagExists("calendar-grid")
        assertTagExists("calendar-day-${store.operationalDate}")
        assertTagExists("calendar-day-${store.operationalDate}-completed", useUnmergedTree = true)
        assertTagExists("calendar-day-${store.operationalDate}-skipped", useUnmergedTree = true)

        compose.onNodeWithTag("calendar-list")
            .performScrollToNode(hasTestTag("calendar-detail-900"))
        assertTagExists("calendar-selected-date-title")
        assertTagExists("calendar-detail-900")
        assertTagExists("calendar-detail-900-note-toggle")
        compose.onNodeWithTag("calendar-detail-900-note-toggle").performClick()
        assertTagExists("calendar-detail-900-note-field")
        assertTagExists("calendar-detail-900-note-save")
        assertTagExists("calendar-detail-900-note-cancel")

        compose.onNodeWithTag("calendar-list")
            .performScrollToNode(hasTestTag("calendar-filter-41"))
        compose.onNodeWithTag("calendar-filter-41").performClick()
        compose.onNodeWithTag("calendar-list")
            .performScrollToNode(hasTestTag("calendar-detail-901"))
        assertTagExists("calendar-detail-901")
        compose.onNodeWithTag("calendar-detail-901").performClick()
        compose.onNodeWithText("Task Detail").assertIsDisplayed()
        assertTagExists("task-detail-header-41")
    }
}

private fun cleanInjectedStore(): HabitTrackerUiStore {
    return HabitTrackerUiStore(
        nowProvider = { LocalDateTime.of(2026, 5, 20, 2, 0) },
    ).also {
        it.tasks.clear()
        it.occurrences.clear()
        it.logs.clear()
    }
}
