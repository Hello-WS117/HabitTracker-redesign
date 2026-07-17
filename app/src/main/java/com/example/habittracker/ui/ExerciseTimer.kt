package com.example.habittracker.ui

import kotlin.math.roundToInt

private const val MAX_EXERCISE_TIMER_SECONDS = 24 * 60 * 60

private val setDurationPattern = Regex(
    pattern = """\b\d+(?:\s*-\s*\d+)?\s*sets?\s*(?:x|\u00d7)\s*(\d+(?:\.\d+)?)(?:\s*-\s*\d+(?:\.\d+)?)?\s*-?\s*(seconds?|secs?|sec|s|minutes?|mins?|min|m)\b""",
    option = RegexOption.IGNORE_CASE,
)
private val clockDurationPattern = Regex("""(?<!\d)(\d{1,2}):([0-5]\d)(?!\d)""")
private val durationPattern = Regex(
    pattern = """(?<![\d:])(\d+(?:\.\d+)?)(?:\s*-\s*\d+(?:\.\d+)?)?\s*-?\s*(seconds?|secs?|sec|s|minutes?|mins?|min|m)\b""",
    option = RegexOption.IGNORE_CASE,
)

internal fun exerciseTimerDurationSeconds(prescription: String): Int? {
    val normalized = prescription
        .trim()
        .replace('\u2013', '-')
        .replace('\u2014', '-')
        .replace('\u2212', '-')
    if (normalized.isEmpty()) return null

    setDurationPattern.find(normalized)?.let { match ->
        return durationToSeconds(match.groupValues[1], match.groupValues[2])
    }
    clockDurationPattern.find(normalized)?.let { match ->
        val minutes = match.groupValues[1].toIntOrNull() ?: return@let
        val seconds = match.groupValues[2].toIntOrNull() ?: return@let
        return (minutes * 60 + seconds).takeIf { it in 1..MAX_EXERCISE_TIMER_SECONDS }
    }
    durationPattern.find(normalized)?.let { match ->
        return durationToSeconds(match.groupValues[1], match.groupValues[2])
    }
    return null
}

private fun durationToSeconds(value: String, unit: String): Int? {
    val amount = value.toDoubleOrNull() ?: return null
    val multiplier = when (unit.lowercase()) {
        "m", "min", "mins", "minute", "minutes" -> 60
        else -> 1
    }
    return (amount * multiplier)
        .roundToInt()
        .takeIf { it in 1..MAX_EXERCISE_TIMER_SECONDS }
}

internal fun formatExerciseTimer(seconds: Int): String {
    val normalized = seconds.coerceAtLeast(0)
    val hours = normalized / 3600
    val minutes = (normalized % 3600) / 60
    val remainingSeconds = normalized % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${remainingSeconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${remainingSeconds.toString().padStart(2, '0')}"
    }
}
