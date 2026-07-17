package com.example.habittracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExerciseTimerTest {
    @Test
    fun timerUsesPerSetHoldDuration() {
        assertEquals(45, exerciseTimerDurationSeconds("3 sets x 45 seconds per leg"))
        assertEquals(90, exerciseTimerDurationSeconds("4 sets x 1.5 minutes"))
    }

    @Test
    fun timerUsesMinimumForDurationRange() {
        assertEquals(120, exerciseTimerDurationSeconds("2-3 minutes"))
        assertEquals(1_200, exerciseTimerDurationSeconds("20-30 minutes easy pace"))
        assertEquals(1_200, exerciseTimerDurationSeconds("20\u201330 minutes easy pace"))
    }

    @Test
    fun timerAcceptsClockAndHyphenatedDurations() {
        assertEquals(45, exerciseTimerDurationSeconds("Hold 0:45"))
        assertEquals(30, exerciseTimerDurationSeconds("30-second hold"))
    }

    @Test
    fun repOnlyPrescriptionDoesNotCreateTimer() {
        assertNull(exerciseTimerDurationSeconds("4 sets x 6-8 reps per leg"))
    }

    @Test
    fun timerFormatsCompactCountdown() {
        assertEquals("0:45", formatExerciseTimer(45))
        assertEquals("20:00", formatExerciseTimer(1_200))
        assertEquals("1:01:01", formatExerciseTimer(3_661))
    }
}
