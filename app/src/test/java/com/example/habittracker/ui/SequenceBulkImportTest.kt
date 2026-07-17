package com.example.habittracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SequenceBulkImportTest {
    @Test
    fun multilineImportCleansListMarkersAndBlankLines() {
        val input = """
            ```text
            1. Full-Body Strength A
            2) Zone 2 Cardio

            - Upper Hypertrophy A
            * Easy Cardio
            • Lower Hypertrophy A
            ```
        """.trimIndent()

        assertEquals(
            listOf(
                "Full-Body Strength A",
                "Zone 2 Cardio",
                "Upper Hypertrophy A",
                "Easy Cardio",
                "Lower Hypertrophy A",
            ),
            parseMultilineSequenceItems(input),
        )
    }

    @Test
    fun multilineImportPreservesOrderDuplicatesAndCommasInsideNames() {
        assertEquals(
            listOf("Strength, heavy", "Zone 2 Cardio", "Zone 2 Cardio"),
            parseMultilineSequenceItems(
                """
                    Strength, heavy
                    Zone 2 Cardio
                    Zone 2 Cardio
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun multilineImportPreservesCompleteExercisePrescriptions() {
        assertEquals(
            listOf(
                "Heavy Straight-Leg Calf Raises (4 sets x 6 reps)",
                "Isometric Calf Holds (5 sets x 45 seconds)",
                "Easy Walk (20 minutes; conversational pace)",
            ),
            parseMultilineSequenceItems(
                """
                    Heavy Straight-Leg Calf Raises (4 sets x 6 reps)
                    Isometric Calf Holds (5 sets x 45 seconds)
                    Easy Walk (20 minutes; conversational pace)
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun editorParserAcceptsLegacyCommaSeparatedSequences() {
        assertEquals(
            listOf("Push", "Pull", "Legs"),
            parseSequenceEditorText(" Push, Pull, Legs "),
        )
    }

    @Test
    fun importCanReplaceOrAppendWithoutDeduplicating() {
        val existing = listOf("Push", "Pull")
        val imported = listOf("Legs", "Push")

        assertEquals(imported, mergeSequenceImport(existing, imported, SequenceImportMode.REPLACE))
        assertEquals(
            listOf("Push", "Pull", "Legs", "Push"),
            mergeSequenceImport(existing, imported, SequenceImportMode.APPEND),
        )
    }
}
