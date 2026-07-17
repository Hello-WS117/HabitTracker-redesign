package com.example.habittracker.ui

internal enum class SequenceImportMode {
    REPLACE,
    APPEND,
}

internal val sequenceAiFormattingInstructions = """
    Create a sequence routine for my Habit Tracker app.

    Formatting rules:
    - Return only the sequence steps.
    - Put exactly one complete, ready-to-perform sequence step on each line.
    - Keep the items in the exact order they should occur.
    - Repeat an item on multiple lines whenever it should occur multiple times in the routine.
    - For strength or rehab exercises, include concrete sets and reps whenever they apply.
    - For holds, cardio, mobility, or recovery work, include the applicable sets, hold time, duration, distance, or intensity.
    - Put item-specific instructions on the same line as the item.
    - Use a concise format such as: Heavy Straight-Leg Calf Raises (4 sets x 6 reps)
    - If the request asks you to design the routine but does not provide a dosage, choose concrete values instead of leaving placeholders.
    - Do not add numbering, bullets, headings, explanations, dates, separate notes, or code fences.
    - Do not separate items with commas. A comma may only appear when it is part of an item's name.
    - Keep each line concise enough to work as a checklist instruction.
    - Include "Rest Day" only when it should be an actual checklist item.

    Routine request:
    [Replace this line with the routine you want the AI to create.]
""".trimIndent()

private val sequenceListPrefix = Regex(
    pattern = """^\s*(?:(?:[-*+\u2022])\s+|(?:\d{1,3}[.):\-])\s+|(?:\[[ xX]?])\s+)(.+)$""",
)

internal fun parseMultilineSequenceItems(input: String): List<String> {
    return input
        .lineSequence()
        .map(::normalizeSequenceLine)
        .filter { it.isNotEmpty() }
        .toList()
}

internal fun parseSequenceEditorText(input: String): List<String> {
    val rawItems = if ('\n' in input || '\r' in input) {
        input.lineSequence()
    } else {
        input.splitToSequence(',')
    }
    return rawItems
        .map(::normalizeSequenceLine)
        .filter { it.isNotEmpty() }
        .toList()
}

internal fun formatSequenceEditorText(items: List<String>): String {
    return items
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

internal fun mergeSequenceImport(
    existingItems: List<String>,
    importedItems: List<String>,
    mode: SequenceImportMode,
): List<String> {
    return when (mode) {
        SequenceImportMode.REPLACE -> importedItems
        SequenceImportMode.APPEND -> existingItems + importedItems
    }
}

private fun normalizeSequenceLine(line: String): String {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("```")) return ""
    return sequenceListPrefix.matchEntire(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        .orEmpty()
        .ifEmpty { trimmed }
}
