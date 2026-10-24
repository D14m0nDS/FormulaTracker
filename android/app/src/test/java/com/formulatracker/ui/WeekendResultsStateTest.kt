package com.formulatracker.ui

import com.formulatracker.results.PracticeResults
import com.formulatracker.results.PracticeRow
import com.formulatracker.results.QualifyingResults
import com.formulatracker.results.RaceResults
import com.formulatracker.results.SessionResults
import com.formulatracker.schedule.ScheduledSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WeekendResultsStateTest {
    private val sprintWeekend = listOf(
        session("FP1", "2026-10-23T16:30:00Z"),
        session("SQ", "2026-10-23T20:30:00Z"),
        session("SR", "2026-10-24T16:00:00Z"),
        session("Q", "2026-10-24T20:00:00Z"),
        session("R", "2026-10-25T19:00:00Z"),
    )

    @Test
    fun aFinishedWeekendOpensOnTheRace() {
        val now = Instant.parse("2026-10-26T09:00:00Z")

        assertEquals("R", defaultResultType(sprintWeekend, now))
    }

    @Test
    fun aWeekendInProgressOpensOnTheLatestSessionThatHasStarted() {
        val now = Instant.parse("2026-10-24T18:00:00Z")

        assertEquals("SR", defaultResultType(sprintWeekend, now))
    }

    @Test
    fun aSessionStartingRightNowCounts() {
        val now = Instant.parse("2026-10-24T20:00:00Z")

        assertEquals("Q", defaultResultType(sprintWeekend, now))
    }

    @Test
    fun aWeekendThatHasNotStartedOpensOnItsFirstSession() {
        val now = Instant.parse("2026-10-20T09:00:00Z")

        assertEquals("FP1", defaultResultType(sprintWeekend, now))
    }

    @Test
    fun aCancelledSessionIsSkippedWhenAnEarlierOneHasStarted() {
        val sessions = listOf(
            session("FP1", "2026-10-23T16:30:00Z"),
            session("Q", "2026-10-24T20:00:00Z", cancelled = true),
        )
        val now = Instant.parse("2026-10-25T09:00:00Z")

        assertEquals("FP1", defaultResultType(sessions, now))
    }

    @Test
    fun noSessionsGivesNoDefault() {
        assertNull(defaultResultType(emptyList(), Instant.parse("2026-10-25T09:00:00Z")))
    }

    @Test
    fun resultsWithoutRowsAreTreatedAsNotPublishedYet() {
        val practice = SessionResults.Practice(PracticeResults("FP3", "Practice 3", emptyList()))
        val qualifying = SessionResults.Qualifying(QualifyingResults("Q", "Qualifying", emptyList(), emptyList()))
        val race = SessionResults.Race(RaceResults("R", "Race", emptyList()))

        assertFalse(practice.hasRows())
        assertFalse(qualifying.hasRows())
        assertFalse(race.hasRows())
    }

    @Test
    fun resultsWithAtLeastOneRowAreShown() {
        val practice = SessionResults.Practice(
            PracticeResults("FP3", "Practice 3", listOf(PracticeRow("1", "NOR", "McLaren", "1:32.100", 18))),
        )

        assertTrue(practice.hasRows())
    }

    private fun session(type: String, start: String, cancelled: Boolean = false) = ScheduledSession(
        type = type,
        name = type,
        start = Instant.parse(start),
        timezone = null,
        cancelled = cancelled,
    )
}
