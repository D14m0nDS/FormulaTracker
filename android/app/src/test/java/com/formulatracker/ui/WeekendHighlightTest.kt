package com.formulatracker.ui

import com.formulatracker.schedule.RaceWeekend
import com.formulatracker.schedule.ScheduledSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class WeekendHighlightTest {
    private val now = Instant.parse("2026-10-22T12:00:00Z")

    @Test
    fun cardSessionsUseAShortLabelAndTheWeekdayClock() {
        assertEquals("FP1", shortSessionLabel("FP1", "Practice 1"))
        assertEquals("SQ", shortSessionLabel("SQ", "Sprint Qualifying"))
        assertEquals("Sprint", shortSessionLabel("SR", "Sprint"))
        assertEquals("Q", shortSessionLabel("Q", "Qualifying"))
        assertEquals("Race", shortSessionLabel("R", "Race"))
        assertEquals("Warm-up", shortSessionLabel("WU", "Warm-up"))

        val clock = formatSessionStart(
            Instant.parse("2026-10-23T12:30:00Z"),
            ZoneId.of("Europe/Sofia"),
            Locale.UK,
        )
        assertEquals("Fri 15:30", clock)
    }

    @Test
    fun theCardShowsTheFirstAndLastSessionDay() {
        val zone = ZoneId.of("Europe/Sofia")
        assertEquals(
            "23.10-25.10",
            weekendDateRange(
                listOf(
                    Instant.parse("2026-10-25T12:00:00Z"),
                    Instant.parse("2026-10-23T12:30:00Z"),
                ),
                zone,
            ),
        )
        assertEquals(
            "23.10",
            weekendDateRange(listOf(Instant.parse("2026-10-23T12:30:00Z")), zone),
        )
        assertEquals(
            "31.12.2026-01.01.2027",
            weekendDateRange(
                listOf(
                    Instant.parse("2026-12-31T10:00:00Z"),
                    Instant.parse("2026-12-31T22:00:00Z"),
                ),
                zone,
            ),
        )
        assertNull(weekendDateRange(emptyList(), zone))
    }

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
