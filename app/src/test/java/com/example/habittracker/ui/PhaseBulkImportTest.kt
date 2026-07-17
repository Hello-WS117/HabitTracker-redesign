package com.example.habittracker.ui

import com.example.habittracker.data.ExerciseRequirement
import com.example.habittracker.data.NoActionBehavior
import com.example.habittracker.data.PhaseAdvanceMode
import com.example.habittracker.data.TaskTimeOfDay
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseBulkImportTest {
    @Test
    fun parserAcceptsCompleteAchillesAiOutputWithWindowsLineEndings() {
        val fixture = checkNotNull(
            javaClass.getResource("/phase-import/achilles-rehab-ai-output.txt"),
        ).readText().replace("\n", "\r\n")

        val result = parsePhaseImport(fixture)

        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertEquals(4, result.phases.size)
        assertEquals(listOf(42, 14, 14, 14), result.phases.map { it.durationDays })
        assertEquals(listOf(7, 3, 3, 3), result.phases.map { it.workoutDays.size })
        assertEquals(42, result.phases.sumOf { phase ->
            phase.workoutDays.sumOf { it.exercises.size }
        })
    }

    @Test
    fun parserRestoresRowsWhenClipboardUsesSoftBreaksInsideFourPhaseParagraphs() {
        val fixture = checkNotNull(
            javaClass.getResource("/phase-import/achilles-rehab-ai-output.txt"),
        ).readText().trim()
        val clipboardText = fixture
            .split(Regex("""\n\s*\n"""))
            .joinToString("\n") { phase -> phase.replace('\n', '\u2028') }

        assertEquals(4, parseMultilineSequenceItems(clipboardText).size)
        val result = parsePhaseImport(clipboardText)

        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertEquals(4, result.phases.size)
        assertEquals(16, result.phases.sumOf { it.workoutDays.size })
        assertEquals(42, result.phases.sumOf { phase ->
            phase.workoutDays.sumOf { it.exercises.size }
        })
    }

    @Test
    fun parserAcceptsExactRoutineInsidePlainTextCodeFence() {
        val fixture = checkNotNull(
            javaClass.getResource("/phase-import/achilles-rehab-ai-output.txt"),
        ).readText().trim()

        val result = parsePhaseImport("```text\n$fixture\n```")

        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertEquals(4, result.phases.size)
        assertEquals(16, result.phases.sumOf { it.workoutDays.size })
        assertEquals(42, result.phases.sumOf { phase ->
            phase.workoutDays.sumOf { it.exercises.size }
        })
    }

    @Test
    fun parserIgnoresTrailingTablePipesOnEveryPhaseHeader() {
        val fixture = checkNotNull(
            javaClass.getResource("/phase-import/achilles-rehab-ai-output.txt"),
        ).readText().lineSequence().joinToString("\n") { line ->
            if (line.startsWith("PHASE |")) "$line |" else line
        }

        val result = parsePhaseImport(fixture)

        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertEquals(4, result.phases.size)
    }

    @Test
    fun parserRecoversPhaseNameContainingAnExtraPipe() {
        val result = parsePhaseImport(
            """
                PHASE | Achilles | Rehab Foundation | minimum 2 weeks | sequence every 1 day | morning | push | manual |
                REVIEW | Is soreness stable?
                DAY 1 | Strength
                EXERCISE | Calf Raise | 4 sets x 8 reps | required | -
                END PHASE
            """.trimIndent(),
        )

        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertEquals("Achilles | Rehab Foundation", result.phases.single().name)
    }

    @Test
    fun parserFindsSemanticHeaderFieldsAndAcceptsWrittenDurations() {
        val result = parsePhaseImport(
            """
                PHASE | Achilles | Rehab Foundation | minimum six weeks | extra formatting | sequence every 1 day | morning | push | manual | trailing text
                REVIEW | Is soreness stable?
                DAY 1 | Strength
                EXERCISE | Calf Raise | 4 sets x 8 reps | required | -
                END PHASE
                PHASE | Pogo Progression | minimum two weeks | sequence every 1 day | morning | push | manual
                REVIEW | Are pogo hops pain free?
                DAY 1 | Pogo Hops
                EXERCISE | Pogo Hops | 3 sets x 20 reps | required | -
                END PHASE
            """.trimIndent(),
        )

        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertEquals(listOf(42, 14), result.phases.map { it.durationDays })
        assertEquals("Achilles | Rehab Foundation", result.phases.first().name)
    }

    @Test
    fun structuredParserNormalizesCommonAiAndClipboardFormatting() {
        val result = parsePhaseImport(
            """
                Here is the routine:
                | **PHASE** | **Foundation** | **minimum 2 weeks** | **sequence every 1 day** | **morning** | **push** | **manual** |
                | --- | --- | --- | --- | --- | --- | --- |
                > **REVIEW** | Has soreness remained stable?
                - **DAY 1** | Strength
                - **EXERCISE** ❘ Calf Raise ❘ 4 sets x 8 reps ❘ required ❘ Lower slowly
                - **DAY 2** | Rest Day
                **END PHASE**
                This routine is ready to paste.
            """.trimIndent(),
        )

        assertTrue(result.issues.toString(), result.issues.isEmpty())
        val phase = result.phases.single()
        assertEquals("Foundation", phase.name)
        assertEquals(14, phase.durationDays)
        assertEquals(2, phase.workoutDays.size)
        assertEquals("Calf Raise", phase.workoutDays.first().exercises.single().name)
    }

    @Test
    fun parserBuildsNumberedWorkoutDaysWithNestedExercisesAndManualReview() {
        val result = parsePhaseImport(
            """
                PHASE | Achilles Foundation | minimum 2 weeks | sequence every 1 day | morning | push | manual
                REVIEW | Has morning soreness remained stable for two weeks?
                DAY 1 | Strength Rehab
                EXERCISE | Straight-Knee Calf Raise | 4 sets x 6-8 reps | required | Lower for 3 seconds
                EXERCISE | Isometric Calf Hold | 5 sets x 45 seconds | conditional | Only when sore or stiff
                DAY 2 | Rest Day
                END PHASE
            """.trimIndent(),
        )

        assertTrue(result.issues.isEmpty())
        val phase = result.phases.single()
        assertTrue(phase.structured)
        assertEquals(14, phase.durationDays)
        assertEquals(PhaseAdvanceMode.MANUAL, phase.advanceMode)
        assertEquals(listOf("Day 1 - Strength Rehab", "Day 2 - Rest Day"), phase.sequenceItems)
        assertEquals(2, phase.workoutDays.size)
        assertEquals("4 sets x 6-8 reps", phase.workoutDays[0].exercises[0].prescription)
        assertEquals(ExerciseRequirement.CONDITIONAL, phase.workoutDays[0].exercises[1].requirement)
        assertEquals("Has morning soreness remained stable for two weeks?", phase.progressionNote)

        val draft = phase.toTaskDraft(LocalDate.of(2026, 7, 15), startsAfterTaskId = 42)
        assertEquals(null, draft.durationDays)
        assertEquals(null, draft.endDate)
        assertEquals(null, draft.startsAfterTaskId)
        assertEquals(2, draft.workoutDays.size)
    }

    @Test
    fun structuredParserRejectsWeekdayTitlesAndManualPhasesWithoutReview() {
        val result = parsePhaseImport(
            """
                PHASE | Rehab | minimum 14 days | sequence every 1 day | morning | push | manual
                DAY 1 | Monday Strength
                EXERCISE | Calf Raise | 4 sets x 8 reps | required | -
                END PHASE
            """.trimIndent(),
        )

        assertTrue(result.phases.isEmpty())
        assertTrue(result.issues.any { it.message.contains("weekday") })
        assertTrue(result.issues.any { it.message.contains("REVIEW") })
    }

    @Test
    fun aiInstructionsRequireNestedDaysPrescriptionsAndManualProgression() {
        assertTrue(phaseAiFormattingInstructions.contains("Never use weekday names"))
        assertTrue(phaseAiFormattingInstructions.contains("normal ASCII | character"))
        assertTrue(phaseAiFormattingInstructions.contains("EXERCISE | Exercise name | Prescription"))
        assertTrue(phaseAiFormattingInstructions.contains("invent a reasonable concrete prescription"))
        assertTrue(phaseAiFormattingInstructions.contains("Later add-on phases must include the full workout"))
        assertTrue(phaseAiFormattingInstructions.contains("manual phase must have one REVIEW row"))
        assertTrue(phaseAiFormattingInstructions.contains("exactly one plain-text fenced code block"))
        assertTrue(phaseAiFormattingInstructions.contains("own physical line"))
        assertTrue(phaseAiFormattingInstructions.contains("PHASE has exactly 7 columns"))
    }

    @Test
    fun parserAcceptsDailyIntervalOnceAndSequencePhases() {
        val result = parsePhaseImport(
            """
                Name | Length | Schedule | Time | No action | Sequence items | Notes
                1. CO2 Tables | 2 weeks | every 2 days | morning | push | - | Complete one table
                Maximum Hold | 1 day | once | noon | push | - | Retest and adjust
                O2 Tables | 14 days | daily | evening | skip | - | -
                Combined | 4 weeks | sequence every 2 days | general | miss | CO2 > O2 > CO2 | Rotate exercises
            """.trimIndent(),
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(4, result.phases.size)
        assertEquals(14, result.phases[0].durationDays)
        assertEquals(PhaseImportSchedule.INTERVAL, result.phases[0].schedule)
        assertEquals(2, result.phases[0].intervalDays)
        assertEquals(TaskTimeOfDay.MORNING, result.phases[0].timeOfDay)
        assertEquals(NoActionBehavior.AUTO_PUSH, result.phases[0].noActionBehavior)
        assertEquals(PhaseImportSchedule.ONCE, result.phases[1].schedule)
        assertEquals(NoActionBehavior.AUTO_SKIP, result.phases[2].noActionBehavior)
        assertEquals(listOf("CO2", "O2", "CO2"), result.phases[3].sequenceItems)
        assertEquals("Rotate exercises", result.phases[3].notes)
    }

    @Test
    fun everyOneDayNormalizesToDailyInsteadOfInterval() {
        val result = parsePhaseImport("Daily practice | 7 days | every 1 day | general | miss")

        assertTrue(result.issues.isEmpty())
        assertEquals(PhaseImportSchedule.DAILY, result.phases.single().schedule)
        assertEquals(1, result.phases.single().intervalDays)
    }

    @Test
    fun parserPreservesSetsRepsAndTimedInstructionsInSequenceItems() {
        val result = parsePhaseImport(
            "Achilles Rehab | 6 weeks | sequence every 1 day | morning | push | " +
                "Heavy Straight-Leg Calf Raises (4 sets x 6 reps) > " +
                "Isometric Calf Holds (5 sets x 45 seconds) > " +
                "Easy Walk (20 minutes; easy pace) > Rest Day | Progress only when soreness is stable",
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(
            listOf(
                "Heavy Straight-Leg Calf Raises (4 sets x 6 reps)",
                "Isometric Calf Holds (5 sets x 45 seconds)",
                "Easy Walk (20 minutes; easy pace)",
                "Rest Day",
            ),
            result.phases.single().sequenceItems,
        )
    }

    @Test
    fun parserReportsLineSpecificFormatProblems() {
        val result = parsePhaseImport(
            """
                Bad duration | several days | daily | morning | push
                Missing items | 14 days | sequence every 1 day | morning | push | -
                Bad once | 2 days | once | morning | skip | -
            """.trimIndent(),
        )

        assertTrue(result.phases.isEmpty())
        assertEquals(listOf(1, 2, 3), result.issues.map { it.lineNumber })
        assertTrue(result.issues[0].message.contains("Length"))
        assertTrue(result.issues[1].message.contains("sequence"))
        assertTrue(result.issues[2].message.contains("1 day"))
    }

    @Test
    fun timelinePlacesEachPhaseImmediatelyAfterThePreviousPhase() {
        val phases = parsePhaseImport(
            """
                Base | 2 weeks | every 2 days | morning | push
                Retest | 1 day | once | morning | push
                Build | 2 weeks | daily | morning | miss
            """.trimIndent(),
        ).phases

        val timeline = buildPhaseImportTimeline(phases, LocalDate.of(2026, 7, 14))

        assertEquals(LocalDate.of(2026, 7, 14), timeline[0].startDate)
        assertEquals(LocalDate.of(2026, 7, 27), timeline[0].endDate)
        assertEquals(LocalDate.of(2026, 7, 28), timeline[1].startDate)
        assertEquals(LocalDate.of(2026, 7, 28), timeline[1].endDate)
        assertEquals(LocalDate.of(2026, 7, 29), timeline[2].startDate)
        assertEquals(LocalDate.of(2026, 8, 11), timeline[2].endDate)
    }

    @Test
    fun phaseMapsToAChainedSequenceTaskDraft() {
        val phase = parsePhaseImport(
            "Strength | 4 weeks | sequence every 2 days | evening | push | Upper > Lower > Cardio | Keep order",
        ).phases.single()

        val draft = phase.toTaskDraft(LocalDate.of(2026, 7, 20), startsAfterTaskId = 42)

        assertEquals(HabitTaskType.Sequence, draft.type)
        assertEquals(28, draft.durationDays)
        assertEquals(LocalDate.of(2026, 8, 16), draft.endDate)
        assertEquals(42, draft.startsAfterTaskId)
        assertEquals(2, draft.sequenceSpacingDays)
        assertEquals("Upper\nLower\nCardio", draft.sequenceText)
        assertEquals(true, draft.pushable)
    }
}
