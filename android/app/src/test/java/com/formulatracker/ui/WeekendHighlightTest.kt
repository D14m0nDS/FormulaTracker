package com.formulatracker.ui

import com.formulatracker.schedule.RaceWeekend
import com.formulatracker.schedule.ScheduledSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class WeekendHighlightTest {
    private val now = Instant.parse("2026-10-22T12:00:00Z")

    @Test
    fun anUpcomingWeekendIsMarkedNext() {
        val highlight = weekendHighlight(
            listOf(
                weekend(1, "2026-03-08T05:00:00Z"),
                weekend(2, "2026-10-25T13:00:00Z"),
            ),
            now,
        )

        assertEquals(WeekendHighlight(2, "Next"), highlight)
    }

    @Test
    fun aWeekendAlreadyUnderwayIsMarkedThisWeekend() {
        val highlight = weekendHighlight(
            listOf(
                weekend(1, "2026-10-21T09:00:00Z", "2026-10-23T13:00:00Z"),
                weekend(2, "2026-11-01T13:00:00Z"),
            ),
            now,
        )

        assertEquals(WeekendHighlight(1, "This weekend"), highlight)
    }

    @Test
    fun aFinishedSeasonHasNoMark() {
        val highlight = weekendHighlight(
            listOf(weekend(1, "2026-03-08T05:00:00Z")),
            now,
        )

        assertNull(highlight)
    }

    @Test
    fun aCancelledFutureSessionDoesNotCount() {
        val highlight = weekendHighlight(
            listOf(weekend(1, "2026-11-01T13:00:00Z", cancelled = true)),
            now,
        )

        assertNull(highlight)
    }

    private fun weekend(round: Int, vararg starts: String, cancelled: Boolean = false) = RaceWeekend(
        round = round,
        name = "Race $round",
        circuitName = "Circuit",
        locality = "City",
        country = "Country",
        sessions = starts.map { start ->
            ScheduledSession(
                type = "R",
                name = "Race",
                start = Instant.parse(start),
                timezone = null,
                cancelled = cancelled,
            )
        },
    )
}
