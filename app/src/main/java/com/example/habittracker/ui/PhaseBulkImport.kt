package com.example.habittracker.ui

import com.example.habittracker.data.ExerciseRequirement
import com.example.habittracker.data.NoActionBehavior
import com.example.habittracker.data.PhaseAdvanceMode
import com.example.habittracker.data.TaskTimeOfDay
import java.time.LocalDate

internal enum class PhaseImportSchedule {
    DAILY,
    INTERVAL,
    SEQUENCE,
    ONCE,
}

internal data class PhaseImportPhase(
    val name: String,
    val durationDays: Int,
    val schedule: PhaseImportSchedule,
    val intervalDays: Int,
    val timeOfDay: TaskTimeOfDay,
    val noActionBehavior: NoActionBehavior,
    val sequenceItems: List<String> = emptyList(),
    val notes: String = "",
    val workoutDays: List<PhaseImportWorkoutDay> = emptyList(),
    val advanceMode: PhaseAdvanceMode = PhaseAdvanceMode.AUTOMATIC,
    val progressionNote: String = "",
    val structured: Boolean = false,
)

internal data class PhaseImportWorkoutDay(
    val dayNumber: Int,
    val title: String,
    val notes: String = "",
    val exercises: List<PhaseImportExercise> = emptyList(),
)

internal data class PhaseImportExercise(
    val name: String,
    val prescription: String,
    val requirement: ExerciseRequirement,
    val instructions: String = "",
)

internal data class PhaseImportIssue(
    val lineNumber: Int,
    val message: String,
)

internal data class PhaseImportParseResult(
    val phases: List<PhaseImportPhase>,
    val issues: List<PhaseImportIssue>,
)

internal data class PhaseImportTimelineRow(
    val phase: PhaseImportPhase,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

internal val phaseAiFormattingInstructions = """
    Create a multi-phase, day-numbered routine for my Habit Tracker app.

    Return exactly one plain-text fenced code block and nothing else. Inside that
    code block, put each record on its own physical line in this exact format:
    PHASE | Phase name | minimum N days | sequence every N days | Time | No action | Advancement
    REVIEW | Question or condition I should evaluate before advancing
    DAY 1 | Workout-day title
    EXERCISE | Exercise name | Prescription | Requirement | Instructions
    EXERCISE | Exercise name | Prescription | Requirement | Instructions
    DAY 2 | Workout-day title
    EXERCISE | Exercise name | Prescription | Requirement | Instructions
    END PHASE

    Formatting rules:
    - Start the response with ```text and end it with ```. Do not put headings, bullets, explanations, or any other text outside the code block.
    - Inside the code block, return only PHASE, REVIEW, DAY, EXERCISE, and END PHASE rows.
    - Every PHASE, REVIEW, DAY, EXERCISE, and END PHASE record must start on a new physical line. Never combine records into a paragraph and never replace line breaks with soft line breaks.
    - Do not wrap a record onto a second line. Shorten Instructions when needed so each record remains one physical line.
    - Use the normal ASCII | character as the separator. Do not format the response as a Markdown table and do not bold row labels.
    - Never use weekday names such as Monday or Tuesday. Define the routine as Day 1, Day 2, Day 3, and so on.
    - Length must be minimum N days or minimum N weeks, such as minimum 14 days or minimum 2 weeks.
    - Schedule must be sequence every N days. Usually use sequence every 1 day.
    - Time must be general, morning, noon, or evening.
    - No action must be push, skip, or miss.
    - Advancement must be manual when progression depends on symptoms, performance, readiness, or a user decision. Otherwise it may be automatic.
    - A manual phase must have one REVIEW row. Phrase it as a concise question or condition I can evaluate after the minimum time has elapsed.
    - Use one DAY row for every distinct day in the repeating workout sequence. DAY numbers must be unique, consecutive, and start at 1.
    - Put every exercise for that day on its own EXERCISE row immediately below the DAY row. This lets me view and check off the full workout one exercise at a time.
    - Prescription must include concrete sets, reps, hold time, duration, distance, or intensity whenever applicable.
    - If the source does not provide a prescription and asks you to design the routine, invent a reasonable concrete prescription instead of leaving a placeholder.
    - Requirement must be required or conditional. Use conditional for work performed only when a symptom or situation applies.
    - Put form cues, tempo, symptom rules, and other exercise-specific detail in Instructions. Use - when none apply.
    - A Rest Day may have no EXERCISE rows.
    - Later add-on phases must include the full workout to perform during that phase. Carry forward base exercises that should continue; do not output only the newly added exercise.
    - Keep phases in execution order. Do not use the | character inside a field.
    - Before answering, silently validate every phase: PHASE has exactly 7 columns, manual phases have exactly one REVIEW row, there is at least one numbered DAY row, every exercise is below a DAY row, and the phase ends with END PHASE on its own line.

    Example:
    PHASE | Achilles Rehab Foundation | minimum 14 days | sequence every 1 day | morning | push | manual
    REVIEW | Has morning soreness remained stable for two weeks?
    DAY 1 | Strength Rehab
    EXERCISE | Straight-Knee Calf Raise | 4 sets x 6-8 reps per leg | required | Use heavy weight; lower for 3 seconds
    EXERCISE | Bent-Knee Calf Raise | 4 sets x 6-8 reps per leg | required | Use heavy weight; lower for 3 seconds
    EXERCISE | Single-Leg Balance | 3 sets x 45 seconds per leg | required | -
    DAY 2 | Recovery
    EXERCISE | Easy Walk or Bike | 20-30 minutes at an easy pace | required | -
    EXERCISE | Isometric Calf Hold | 5 sets x 45 seconds per leg | conditional | Only when the Achilles is sore or stiff
    DAY 3 | Rest Day
    END PHASE

    PHASE | Pogo Progression | minimum 14 days | sequence every 1 day | morning | push | manual
    REVIEW | Were pogo hops pain-free during the session and the following morning?
    DAY 1 | Strength and Pogo Hops
    EXERCISE | Straight-Knee Calf Raise | 4 sets x 6-8 reps per leg | required | Use heavy weight; lower for 3 seconds
    EXERCISE | Two-Leg Pogo Hops | 3 sets x 20 reps | required | Stop if pain increases
    DAY 2 | Recovery
    EXERCISE | Easy Walk or Bike | 20-30 minutes at an easy pace | required | -
    END PHASE

    Routine request:
    [Replace this line with the phased routine you want the AI to create.]
""".trimIndent()

private val phaseListPrefix = Regex(
    pattern = """^\s*(?:(?:[-*+\u2022])\s+|(?:\d{1,3}[.):\-])\s+)(.+)$""",
)
private val durationWithUnitPattern = Regex(
    """^(.+?)\s*-?\s*(d|day|days|w|week|weeks)$""",
    RegexOption.IGNORE_CASE,
)
private val durationPrefixPattern = Regex(
    """^(?:minimum(?:\s+of)?|at\s+least)\s*:?\s*""",
    RegexOption.IGNORE_CASE,
)
private val durationSuffixPattern = Regex("""\s+minimum$""", RegexOption.IGNORE_CASE)
private val intervalPattern = Regex("""^every\s+(\d+)\s+days?$""", RegexOption.IGNORE_CASE)
private val sequenceIntervalPattern = Regex("""^sequence\s+every\s+(\d+)\s+days?$""", RegexOption.IGNORE_CASE)
private val structuredDayLabel = Regex("""^DAY\s+(\d+)$""", RegexOption.IGNORE_CASE)
private val markdownEmphasis = Regex("""^[*_~`]+|[*_~`]+$""")
private val markdownTableDivider = Regex("""^:?-{3,}:?(?:\s*\|\s*:?-{3,}:?)+$""")

internal fun parsePhaseImport(input: String): PhaseImportParseResult {
    val normalizedInput = normalizePhaseImportInput(input)
    return if (normalizedInput.lineSequence().any(::isStructuredPhaseLine)) {
        parseStructuredPhaseImport(normalizedInput)
    } else {
        parseLegacyPhaseImport(normalizedInput)
    }
}

internal fun containsStructuredPhaseRows(input: String): Boolean {
    return normalizePhaseImportInput(input).lineSequence().any(::isStructuredPhaseLine)
}

internal fun normalizePhaseImportInput(input: String): String {
    return input
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u000b', '\n')
        .replace('\u000c', '\n')
        .replace('\u0085', '\n')
        .replace('\u2028', '\n')
        .replace('\u2029', '\n')
        .replace(Regex("""(?i)<br\s*/?>"""), "\n")
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\u200b", "")
        .replace("\u200c", "")
        .replace("\u200d", "")
        .replace("\u2060", "")
}

private fun parseLegacyPhaseImport(input: String): PhaseImportParseResult {
    val phases = mutableListOf<PhaseImportPhase>()
    val issues = mutableListOf<PhaseImportIssue>()

    input.lineSequence().forEachIndexed { index, sourceLine ->
        val lineNumber = index + 1
        val trimmed = sourceLine.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("```") || isPhaseHeader(trimmed)) return@forEachIndexed
        val line = phaseListPrefix.matchEntire(trimmed)?.groupValues?.getOrNull(1)?.trim() ?: trimmed
        val columns = line.split('|').map(String::trim)
        if (columns.size !in 5..7) {
            issues += PhaseImportIssue(lineNumber, "Expected 5 to 7 columns separated by |")
            return@forEachIndexed
        }
        val padded = columns + List(7 - columns.size) { "" }
        val name = padded[0]
        if (name.isBlank()) {
            issues += PhaseImportIssue(lineNumber, "Phase name is required")
            return@forEachIndexed
        }
        val durationDays = parsePhaseDurationDays(padded[1])
        if (durationDays == null) {
            issues += PhaseImportIssue(lineNumber, "Length must look like 14 days or 2 weeks")
            return@forEachIndexed
        }
        val scheduleAndInterval = parsePhaseSchedule(padded[2])
        if (scheduleAndInterval == null) {
            issues += PhaseImportIssue(
                lineNumber,
                "Schedule must be daily, once, every N days, or sequence every N days",
            )
            return@forEachIndexed
        }
        val timeOfDay = parsePhaseTimeOfDay(padded[3])
        if (timeOfDay == null) {
            issues += PhaseImportIssue(lineNumber, "Time must be general, morning, noon, or evening")
            return@forEachIndexed
        }
        val noActionBehavior = parsePhaseNoAction(padded[4])
        if (noActionBehavior == null) {
            issues += PhaseImportIssue(lineNumber, "No action must be push, skip, or miss")
            return@forEachIndexed
        }
        val sequenceItems = parsePhaseSequenceItems(padded[5])
        val schedule = scheduleAndInterval.first
        if (schedule == PhaseImportSchedule.SEQUENCE && sequenceItems.isEmpty()) {
            issues += PhaseImportIssue(lineNumber, "A sequence phase needs items separated by >")
            return@forEachIndexed
        }
        if (schedule != PhaseImportSchedule.SEQUENCE && sequenceItems.isNotEmpty()) {
            issues += PhaseImportIssue(lineNumber, "Only a sequence schedule can include sequence items")
            return@forEachIndexed
        }
        if (schedule == PhaseImportSchedule.ONCE && durationDays != 1) {
            issues += PhaseImportIssue(lineNumber, "A once phase must have a length of 1 day")
            return@forEachIndexed
        }
        if (schedule == PhaseImportSchedule.ONCE && noActionBehavior != NoActionBehavior.AUTO_PUSH) {
            issues += PhaseImportIssue(lineNumber, "A once phase must use push for no action")
            return@forEachIndexed
        }
        phases += PhaseImportPhase(
            name = name,
            durationDays = durationDays,
            schedule = schedule,
            intervalDays = scheduleAndInterval.second,
            timeOfDay = timeOfDay,
            noActionBehavior = noActionBehavior,
            sequenceItems = sequenceItems,
            notes = padded[6].takeUnless(::isEmptyPhaseValue).orEmpty(),
        )
    }

    return PhaseImportParseResult(phases = phases, issues = issues)
}

private data class StructuredPhaseBuilder(
    val lineNumber: Int,
    val name: String,
    val durationDays: Int,
    val intervalDays: Int,
    val timeOfDay: TaskTimeOfDay,
    val noActionBehavior: NoActionBehavior,
    val advanceMode: PhaseAdvanceMode,
    var progressionNote: String = "",
    val workoutDays: MutableList<PhaseImportWorkoutDay> = mutableListOf(),
)

private data class StructuredPhaseHeader(
    val name: String,
    val duration: String,
    val schedule: String,
    val timeOfDay: String,
    val noAction: String,
    val advancement: String,
)

private fun parseStructuredPhaseImport(input: String): PhaseImportParseResult {
    val phases = mutableListOf<PhaseImportPhase>()
    val issues = mutableListOf<PhaseImportIssue>()
    var currentPhase: StructuredPhaseBuilder? = null
    var currentDayIndex: Int? = null

    fun finishCurrentPhase() {
        val builder = currentPhase ?: return
        if (builder.workoutDays.isEmpty()) {
            issues += PhaseImportIssue(builder.lineNumber, "A phase needs at least one numbered DAY")
        }
        val expectedNumbers = (1..builder.workoutDays.size).toList()
        if (builder.workoutDays.map { it.dayNumber } != expectedNumbers) {
            issues += PhaseImportIssue(builder.lineNumber, "DAY numbers must start at 1 and be consecutive")
        }
        if (builder.advanceMode == PhaseAdvanceMode.MANUAL && builder.progressionNote.isBlank()) {
            issues += PhaseImportIssue(builder.lineNumber, "A manual phase needs a REVIEW condition")
        }
        if (builder.workoutDays.isNotEmpty()) {
            val sequenceItems = builder.workoutDays.map { day -> "Day ${day.dayNumber} - ${day.title}" }
            phases += PhaseImportPhase(
                name = builder.name,
                durationDays = builder.durationDays,
                schedule = PhaseImportSchedule.SEQUENCE,
                intervalDays = builder.intervalDays,
                timeOfDay = builder.timeOfDay,
                noActionBehavior = builder.noActionBehavior,
                sequenceItems = sequenceItems,
                notes = builder.progressionNote,
                workoutDays = builder.workoutDays.toList(),
                advanceMode = builder.advanceMode,
                progressionNote = builder.progressionNote,
                structured = true,
            )
        }
        currentPhase = null
        currentDayIndex = null
    }

    input.lineSequence().forEachIndexed { index, sourceLine ->
        val lineNumber = index + 1
        val line = normalizeStructuredLine(sourceLine)
        if (line.isBlank() || line.startsWith("```") || markdownTableDivider.matches(line)) {
            return@forEachIndexed
        }
        val columns = line.split('|').map(::normalizeStructuredColumn)
        val rowType = columns.firstOrNull().orEmpty().removeSuffix(":").trim()

        when {
            rowType.equals("PHASE", ignoreCase = true) -> {
                if (currentPhase != null) {
                    issues += PhaseImportIssue(lineNumber, "Previous phase is missing END PHASE")
                    finishCurrentPhase()
                }
                val header = parseStructuredPhaseHeader(columns)
                if (header == null) {
                    issues += PhaseImportIssue(
                        lineNumber,
                        "PHASE is missing fields: name, length, schedule, time, no action, and advancement",
                    )
                    return@forEachIndexed
                }
                val durationDays = parsePhaseDurationDays(header.duration)
                val schedule = parsePhaseSchedule(header.schedule)
                val timeOfDay = parsePhaseTimeOfDay(header.timeOfDay)
                val noAction = parsePhaseNoAction(header.noAction)
                val advanceMode = parsePhaseAdvanceMode(header.advancement)
                when {
                    header.name.isBlank() -> issues += PhaseImportIssue(lineNumber, "Phase name is required")
                    durationDays == null -> issues += PhaseImportIssue(lineNumber, "Length must look like minimum 14 days or minimum 2 weeks")
                    schedule?.first != PhaseImportSchedule.SEQUENCE -> issues += PhaseImportIssue(lineNumber, "Structured workout phases must use sequence every N days")
                    timeOfDay == null -> issues += PhaseImportIssue(lineNumber, "Time must be general, morning, noon, or evening")
                    noAction == null -> issues += PhaseImportIssue(lineNumber, "No action must be push, skip, or miss")
                    advanceMode == null -> issues += PhaseImportIssue(lineNumber, "Advancement must be manual or automatic")
                    else -> currentPhase = StructuredPhaseBuilder(
                        lineNumber = lineNumber,
                        name = header.name,
                        durationDays = durationDays,
                        intervalDays = schedule.second,
                        timeOfDay = timeOfDay,
                        noActionBehavior = noAction,
                        advanceMode = advanceMode,
                    )
                }
                currentDayIndex = null
            }
            rowType.equals("REVIEW", ignoreCase = true) -> {
                val phase = currentPhase
                if (phase == null) {
                    issues += PhaseImportIssue(lineNumber, "REVIEW must be inside a PHASE block")
                } else if (columns.size != 2 || columns[1].isBlank()) {
                    issues += PhaseImportIssue(lineNumber, "REVIEW needs one progression question or condition")
                } else {
                    phase.progressionNote = columns[1]
                }
            }
            structuredDayLabel.matches(rowType) -> {
                val phase = currentPhase
                val dayNumber = structuredDayLabel.matchEntire(rowType)?.groupValues?.get(1)?.toIntOrNull()
                if (phase == null) {
                    issues += PhaseImportIssue(lineNumber, "DAY must be inside a PHASE block")
                } else if (columns.size !in 2..3 || columns[1].isBlank() || dayNumber == null) {
                    issues += PhaseImportIssue(lineNumber, "DAY must look like DAY 1 | Workout title")
                } else if (containsWeekdayName(columns[1])) {
                    issues += PhaseImportIssue(lineNumber, "Use a numbered day title, not a weekday name")
                } else {
                    phase.workoutDays += PhaseImportWorkoutDay(
                        dayNumber = dayNumber,
                        title = columns[1],
                        notes = columns.getOrNull(2).takeUnless { isEmptyPhaseValue(it.orEmpty()) }.orEmpty(),
                    )
                    currentDayIndex = phase.workoutDays.lastIndex
                }
            }
            rowType.equals("EXERCISE", ignoreCase = true) -> {
                val phase = currentPhase
                val dayIndex = currentDayIndex
                if (phase == null || dayIndex == null) {
                    issues += PhaseImportIssue(lineNumber, "EXERCISE must follow a DAY row")
                } else if (columns.size !in 4..5) {
                    issues += PhaseImportIssue(lineNumber, "EXERCISE needs name, prescription, requirement, and optional instructions")
                } else {
                    val name = columns[1]
                    val prescription = columns[2]
                    val requirement = parseExerciseRequirement(columns[3])
                    when {
                        name.isBlank() -> issues += PhaseImportIssue(lineNumber, "Exercise name is required")
                        isEmptyPhaseValue(prescription) -> issues += PhaseImportIssue(lineNumber, "Exercise prescription is required")
                        requirement == null -> issues += PhaseImportIssue(lineNumber, "Exercise requirement must be required or conditional")
                        else -> {
                            val day = phase.workoutDays[dayIndex]
                            phase.workoutDays[dayIndex] = day.copy(
                                exercises = day.exercises + PhaseImportExercise(
                                    name = name,
                                    prescription = prescription,
                                    requirement = requirement,
                                    instructions = columns.getOrNull(4)
                                        .takeUnless { isEmptyPhaseValue(it.orEmpty()) }
                                        .orEmpty(),
                                ),
                            )
                        }
                    }
                }
            }
            rowType.equals("END PHASE", ignoreCase = true) -> {
                if (columns.size != 1) {
                    issues += PhaseImportIssue(lineNumber, "END PHASE must be on its own line")
                }
                if (currentPhase == null) {
                    issues += PhaseImportIssue(lineNumber, "END PHASE has no matching PHASE")
                } else {
                    finishCurrentPhase()
                }
            }
            currentPhase == null -> Unit
            else -> issues += PhaseImportIssue(
                lineNumber,
                "Expected PHASE, REVIEW, DAY N, EXERCISE, or END PHASE",
            )
        }
    }
    if (currentPhase != null) {
        issues += PhaseImportIssue(currentPhase!!.lineNumber, "Phase is missing END PHASE")
        finishCurrentPhase()
    }
    return PhaseImportParseResult(phases = phases, issues = issues)
}

private fun isStructuredPhaseLine(sourceLine: String): Boolean {
    val line = normalizeStructuredLine(sourceLine)
    val rowType = line.substringBefore('|')
        .let(::normalizeStructuredColumn)
        .removeSuffix(":")
        .trim()
    return rowType.equals("PHASE", ignoreCase = true) && line.contains('|')
}

private fun parseStructuredPhaseHeader(columns: List<String>): StructuredPhaseHeader? {
    val fields = columns.drop(1).filterNot(String::isBlank)
    if (fields.size < 6) return null

    val durationIndex = fields.indices
        .drop(1)
        .firstOrNull { parsePhaseDurationDays(fields[it]) != null }
        ?: return null
    val scheduleIndex = fields.indices
        .firstOrNull { it > durationIndex && parsePhaseSchedule(fields[it]) != null }
        ?: return null
    val timeIndex = fields.indices
        .firstOrNull { it > scheduleIndex && parsePhaseTimeOfDay(fields[it]) != null }
        ?: return null
    val noActionIndex = fields.indices
        .firstOrNull { it > timeIndex && parsePhaseNoAction(fields[it]) != null }
        ?: return null
    val advancementIndex = fields.indices
        .firstOrNull { it > noActionIndex && parsePhaseAdvanceMode(fields[it]) != null }
        ?: return null

    return StructuredPhaseHeader(
        name = fields.take(durationIndex).joinToString(" | ").trim(),
        duration = fields[durationIndex],
        schedule = fields[scheduleIndex],
        timeOfDay = fields[timeIndex],
        noAction = fields[noActionIndex],
        advancement = fields[advancementIndex],
    )
}

private fun normalizeStructuredLine(sourceLine: String): String {
    var line = sourceLine
        .replace('\u00a0', ' ')
        .replace('\uff5c', '|')
        .replace('\u2502', '|')
        .replace('\u2503', '|')
        .replace('\u00a6', '|')
        .replace('\u01c0', '|')
        .replace('\u2223', '|')
        .replace('\u23d0', '|')
        .replace('\u2758', '|')
        .replace('\ufe31', '|')
        .replace('\uffe8', '|')
        .trim()
        .removePrefix("\ufeff")
        .trim()

    while (line.startsWith(">")) {
        line = line.removePrefix(">").trimStart()
    }
    line = phaseListPrefix.matchEntire(line)?.groupValues?.getOrNull(1)?.trim() ?: line
    line = removeMatchingMarkdownWrapper(line)
    return line.trim().trim('|').trim()
}

private fun normalizeStructuredColumn(value: String): String {
    return removeMatchingMarkdownWrapper(value.trim())
        .replace(markdownEmphasis, "")
        .trim()
}

private fun removeMatchingMarkdownWrapper(value: String): String {
    var normalized = value.trim()
    val wrappers = listOf("**", "__", "~~", "`")
    wrappers.forEach { wrapper ->
        if (normalized.length > wrapper.length * 2 &&
            normalized.startsWith(wrapper) &&
            normalized.endsWith(wrapper)
        ) {
            normalized = normalized.substring(wrapper.length, normalized.length - wrapper.length).trim()
        }
    }
    return normalized
}

internal fun buildPhaseImportTimeline(
    phases: List<PhaseImportPhase>,
    firstStartDate: LocalDate,
): List<PhaseImportTimelineRow> {
    var nextStartDate = firstStartDate
    return phases.map { phase ->
        val endDate = nextStartDate.plusDays((phase.durationDays.coerceAtLeast(1) - 1).toLong())
        PhaseImportTimelineRow(
            phase = phase,
            startDate = nextStartDate,
            endDate = endDate,
        ).also {
            nextStartDate = endDate.plusDays(1)
        }
    }
}

internal fun PhaseImportPhase.toTaskDraft(
    startDate: LocalDate,
    startsAfterTaskId: Int?,
): HabitTaskDraft {
    val taskType = when (schedule) {
        PhaseImportSchedule.DAILY -> HabitTaskType.Simple
        PhaseImportSchedule.INTERVAL -> HabitTaskType.Interval
        PhaseImportSchedule.SEQUENCE -> HabitTaskType.Sequence
        PhaseImportSchedule.ONCE -> HabitTaskType.OneTime
    }
    val normalizedDuration = if (schedule == PhaseImportSchedule.ONCE) 1 else durationDays.coerceAtLeast(1)
    val manualStructuredPhase = structured && advanceMode == PhaseAdvanceMode.MANUAL
    return HabitTaskDraft(
        name = name,
        type = taskType,
        notes = notes,
        startDate = startDate,
        endDate = if (manualStructuredPhase) null else startDate.plusDays((normalizedDuration - 1).toLong()),
        durationDays = if (manualStructuredPhase) null else normalizedDuration,
        startsAfterTaskId = if (structured) null else startsAfterTaskId,
        intervalDays = intervalDays.coerceAtLeast(2),
        skipBlockedDaysBehavior = defaultSkipBlockedDaysBehavior(taskType),
        sequenceText = formatSequenceEditorText(sequenceItems),
        workoutDays = workoutDays.mapIndexed { dayIndex, day ->
            HabitWorkoutDayUi(
                position = dayIndex,
                title = "Day ${day.dayNumber} - ${day.title}",
                notes = day.notes,
                exercises = day.exercises.mapIndexed { exerciseIndex, exercise ->
                    HabitWorkoutExerciseUi(
                        id = 0,
                        position = exerciseIndex,
                        name = exercise.name,
                        prescription = exercise.prescription,
                        instructions = exercise.instructions,
                        requirement = exercise.requirement,
                    )
                },
            )
        },
        sequenceSpacingDays = intervalDays.coerceAtLeast(1),
        timeOfDay = timeOfDay,
        pushable = schedule == PhaseImportSchedule.ONCE || noActionBehavior == NoActionBehavior.AUTO_PUSH,
        noActionBehavior = if (schedule == PhaseImportSchedule.ONCE) {
            NoActionBehavior.AUTO_PUSH
        } else {
            noActionBehavior
        },
    )
}

internal fun PhaseImportPhase.scheduleLabel(): String {
    return when (schedule) {
        PhaseImportSchedule.DAILY -> "Daily"
        PhaseImportSchedule.ONCE -> "Once"
        PhaseImportSchedule.INTERVAL -> "Every ${intervalDays.coerceAtLeast(2)} days"
        PhaseImportSchedule.SEQUENCE -> if (intervalDays <= 1) {
            "Sequence daily"
        } else {
            "Sequence every $intervalDays days"
        }
    }
}

private fun parsePhaseDurationDays(value: String): Int? {
    val normalized = value
        .trim()
        .lowercase()
        .replace('\u2013', '-')
        .replace('\u2014', '-')
        .replace('\u2212', '-')
        .replace(durationPrefixPattern, "")
        .replace(durationSuffixPattern, "")
        .replace(Regex("""\s+"""), " ")
        .trim()
    val withUnit = durationWithUnitPattern.matchEntire(normalized)
    val countText = withUnit?.groupValues?.get(1)?.trim() ?: normalized
    val unit = withUnit?.groupValues?.get(2).orEmpty().lowercase()
    val count = parseDurationCount(countText)?.takeIf { it in 1..36_500 } ?: return null
    return when (unit) {
        "w", "week", "weeks" -> count.takeIf { it <= 5_214 }?.times(7)
        else -> count
    }
}

private fun parseDurationCount(value: String): Int? {
    value.toIntOrNull()?.let { return it }
    val normalized = value.trim().lowercase().replace('-', ' ')
    val directNumbers = mapOf(
        "a" to 1,
        "one" to 1,
        "two" to 2,
        "three" to 3,
        "four" to 4,
        "five" to 5,
        "six" to 6,
        "seven" to 7,
        "eight" to 8,
        "nine" to 9,
        "ten" to 10,
        "eleven" to 11,
        "twelve" to 12,
        "thirteen" to 13,
        "fourteen" to 14,
        "fifteen" to 15,
        "sixteen" to 16,
        "seventeen" to 17,
        "eighteen" to 18,
        "nineteen" to 19,
        "twenty" to 20,
        "thirty" to 30,
        "forty" to 40,
        "fifty" to 50,
        "sixty" to 60,
        "seventy" to 70,
        "eighty" to 80,
        "ninety" to 90,
    )
    directNumbers[normalized]?.let { return it }
    val parts = normalized.split(Regex("""\s+"""))
    if (parts.size != 2) return null
    val tens = directNumbers[parts[0]]?.takeIf { it in 20..90 && it % 10 == 0 } ?: return null
    val ones = directNumbers[parts[1]]?.takeIf { it in 1..9 } ?: return null
    return tens + ones
}

private fun parsePhaseSchedule(value: String): Pair<PhaseImportSchedule, Int>? {
    val normalized = value.trim().lowercase().replace(Regex("""\s+"""), " ")
    return when (normalized) {
        "daily", "every day", "every 1 day", "every 1 days" -> PhaseImportSchedule.DAILY to 1
        "once", "one time", "one-time", "1-time" -> PhaseImportSchedule.ONCE to 1
        "sequence", "sequence daily", "sequence every day" -> PhaseImportSchedule.SEQUENCE to 1
        else -> {
            sequenceIntervalPattern.matchEntire(normalized)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it in 1..36_500 }
                ?.let { PhaseImportSchedule.SEQUENCE to it }
                ?: intervalPattern.matchEntire(normalized)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.takeIf { it in 1..36_500 }
                    ?.let { days ->
                        if (days == 1) PhaseImportSchedule.DAILY to 1 else PhaseImportSchedule.INTERVAL to days
                    }
        }
    }
}

private fun parsePhaseTimeOfDay(value: String): TaskTimeOfDay? {
    return when (value.trim().lowercase()) {
        "general", "anytime", "any time" -> TaskTimeOfDay.GENERAL
        "morning" -> TaskTimeOfDay.MORNING
        "noon", "midday" -> TaskTimeOfDay.NOON
        "evening", "night" -> TaskTimeOfDay.EVENING
        else -> null
    }
}

private fun parsePhaseNoAction(value: String): NoActionBehavior? {
    return when (value.trim().lowercase().replace("-", " ")) {
        "push", "auto push" -> NoActionBehavior.AUTO_PUSH
        "skip", "auto skip" -> NoActionBehavior.AUTO_SKIP
        "miss", "missed", "mark missed" -> NoActionBehavior.MARK_MISSED
        else -> null
    }
}

private fun parsePhaseAdvanceMode(value: String): PhaseAdvanceMode? {
    return when (value.trim().lowercase()) {
        "manual", "conditional", "review" -> PhaseAdvanceMode.MANUAL
        "automatic", "auto" -> PhaseAdvanceMode.AUTOMATIC
        else -> null
    }
}

private fun parseExerciseRequirement(value: String): ExerciseRequirement? {
    return when (value.trim().lowercase()) {
        "required" -> ExerciseRequirement.REQUIRED
        "conditional", "optional", "if needed" -> ExerciseRequirement.CONDITIONAL
        else -> null
    }
}

private fun containsWeekdayName(value: String): Boolean {
    return Regex(
        pattern = """\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
        option = RegexOption.IGNORE_CASE,
    ).containsMatchIn(value)
}

private fun parsePhaseSequenceItems(value: String): List<String> {
    if (isEmptyPhaseValue(value)) return emptyList()
    return value.split('>')
        .map(String::trim)
        .filter(String::isNotEmpty)
}

private fun isPhaseHeader(value: String): Boolean {
    val normalized = value.lowercase().replace(" ", "")
    return normalized.startsWith("name|length|schedule|time|noaction") ||
        normalized.startsWith("phasename|length|schedule|time|noaction")
}

private fun isEmptyPhaseValue(value: String): Boolean {
    return value.isBlank() || value.trim().lowercase() in setOf("-", "none", "n/a")
}
