package com.example.habittracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

class CalendarMarkerTest {
    @Test
    fun statusMarkersHaveDistinctLabelsColorsAndDisplayOrder() {
        val date = LocalDate.of(2026, 5, 20)
        val markers = listOf(
            markerFor(HabitStatus.Pending, date),
            markerFor(HabitStatus.Shifted, date),
            markerFor(HabitStatus.Missed, date),
            markerFor(HabitStatus.Skipped, date),
            markerFor(HabitStatus.Completed, date),
        )

        assertEquals(
            listOf("Pending", "Pushed", "Missed", "Skipped", "Completed"),
            markers.sortedBy { it.displayOrder }.map { it.label },
        )
        assertEquals(markers.size, markers.map { it.color }.toSet().size)
        assertEquals(markers.size, markers.map { it.displayOrder }.toSet().size)
    }

    @Test
    fun futurePendingOccurrenceUsesUpcomingMarkerDistinctFromPending() {
        val today = LocalDate.of(2026, 5, 20)
        val pending = markerFor(HabitStatus.Pending, today, currentOperationalDate = today)
        val upcoming = markerFor(HabitStatus.Pending, today.plusDays(1), currentOperationalDate = today)

        assertEquals("Pending", pending.label)
        assertEquals("Upcoming", upcoming.label)
        assertNotEquals(pending.color, upcoming.color)
        assertEquals(0, upcoming.displayOrder)
        assertEquals(1, pending.displayOrder)
    }

    @Test
    fun occurrenceDetailSummaryIncludesSequenceItemWithoutNote() {
        val date = LocalDate.of(2026, 5, 20)

        val summary = HabitOccurrenceUi(
            id = 1,
            taskId = 1,
            scheduledDate = date,
            operationalDate = date,
            status = HabitStatus.Pending,
            sequenceItemName = "Pull",
            note = "Reduced volume",
        ).detailSummaryText()

        assertEquals("May 20 - Pull", summary)
    }

    @Test
    fun occurrenceDetailSummaryFallsBackToDateOnly() {
        val date = LocalDate.of(2026, 5, 20)

        val summary = HabitOccurrenceUi(
            id = 1,
            taskId = 1,
            scheduledDate = date,
            operationalDate = date,
            status = HabitStatus.Pending,
        ).detailSummaryText()

        assertEquals("May 20", summary)
    }

    @Test
    fun calendarDayTagsUseStableIsoDateAndMarkerLabel() {
        val date = LocalDate.of(2026, 5, 20)
        val marker = markerFor(HabitStatus.Completed, date)

        assertEquals("calendar-day-2026-05-20", date.calendarDayTestTag())
        assertEquals("calendar-day-2026-05-20-completed", marker.calendarMarkerTestTag(date))
    }

    @Test
    fun calendarDayContentDescriptionIncludesDateStateCountAndMarkers() {
        val date = LocalDate.of(2026, 5, 20)
        val markers = listOf(
            markerFor(HabitStatus.Pending, date),
            markerFor(HabitStatus.Completed, date),
        ).sortedBy { it.displayOrder }

        val description = calendarDayContentDescription(
            date = date,
            markers = markers,
            itemCount = 3,
            isSelected = true,
            isToday = true,
        )

        assertEquals("Wed, May 20, today, selected, 3 items: Pending, Completed", description)
    }

    @Test
    fun emptyCalendarDayContentDescriptionCallsOutNoScheduledItems() {
        val date = LocalDate.of(2026, 5, 20)

        val description = calendarDayContentDescription(
            date = date,
            markers = emptyList(),
            itemCount = 0,
            isSelected = false,
            isToday = false,
        )

        assertEquals("Wed, May 20, 0 items: no scheduled items", description)
    }

    private fun markerFor(
        status: HabitStatus,
        date: LocalDate,
        currentOperationalDate: LocalDate = date,
    ): CalendarMarker {
        return HabitOccurrenceUi(
            id = 1,
            taskId = 1,
            scheduledDate = date,
            operationalDate = date,
            status = status,
        ).calendarMarker(date, currentOperationalDate)
    }
}
