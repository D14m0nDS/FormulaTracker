package com.formulatracker.reminders

import com.formulatracker.schedule.RaceWeekend
import com.formulatracker.schedule.ScheduledSession
import com.formulatracker.schedule.SeasonSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SessionFinishChecksTest {
    private val qualifying = session("Q", "2026-10-24T21:00:00Z")
    private val race = session("R", "2026-10-25T20:00:00Z")

    @Test
    fun theFirstCheckWaitsForTheFinishStatusToBeReadable() {
        val plan = planFinishChecks(
            schedule(qualifying),
            ReminderPreferences(),
            emptySet(),
            now = Instant.parse("2026-10-20T08:00:00Z"),
        )

        // One hour long, then 30 minutes until the free feed can show the finish.
        assertEquals(Instant.parse("2026-10-24T22:30:00Z"), plan.single().triggerAt)
        assertEquals("Q", plan.single().type)
    }

    @Test
    fun aRaceIsCheckedSoonerThanItsListedTwoHoursSuggest() {
        val plan = planFinishChecks(
            schedule(race),
            ReminderPreferences(),
            emptySet(),
            now = Instant.parse("2026-10-20T08:00:00Z"),
        )

        assertEquals(Instant.parse("2026-10-25T22:15:00Z"), plan.single().triggerAt)
    }

    @Test
    fun everySessionTypeHasALength() {
        val start = Instant.parse("2026-10-24T10:00:00Z")
        val now = start.minusSeconds(3600)

        REMINDER_SESSION_TYPES.forEach { type ->
            val next = nextFinishCheck(type.type, start, now)
            assertTrue("${type.type} has no check", next != null && next.isAfter(start))
        }
        assertNull(nextFinishCheck("WARMUP", start, now))
    }

    @Test
    fun anUnansweredCheckRepeatsOnAFixedGrid() {
        val first = Instant.parse("2026-10-24T22:30:00Z")

        assertEquals(
            first.plus(Duration.ofMinutes(10)),
            nextFinishCheck("Q", qualifying.start, first.plusSeconds(1)),
        )
        // Planning again at an odd moment must not shift the grid.
        assertEquals(
            Instant.parse("2026-10-24T23:20:00Z"),
            nextFinishCheck("Q", qualifying.start, Instant.parse("2026-10-24T23:13:41Z")),
        )
        // A check that fires exactly on a grid time is not repeated at the same moment.
        assertEquals(
            Instant.parse("2026-10-24T23:30:00Z"),
            nextFinishCheck("Q", qualifying.start, Instant.parse("2026-10-24T23:20:00Z")),
        )
    }

    @Test
    fun theChecksStopFiveHoursAfterTheExpectedEnd() {
        // Expected end 22:00, so the last grid time before 03:00 is 02:50.
        assertEquals(
            Instant.parse("2026-10-25T02:50:00Z"),
            nextFinishCheck("Q", qualifying.start, Instant.parse("2026-10-25T02:40:00Z")),
        )
        assertNull(nextFinishCheck("Q", qualifying.start, Instant.parse("2026-10-25T02:50:00Z")))
        assertNull(nextFinishCheck("Q", qualifying.start, Instant.parse("2026-10-25T03:00:00Z")))
    }

    @Test
    fun aSessionThatWasAnnouncedIsNotCheckedAgain() {
        val plan = planFinishChecks(
            schedule(qualifying, race),
            ReminderPreferences(),
            setOf(sessionFinishKey(2026, 1, "Q")),
            now = Instant.parse("2026-10-24T23:00:00Z"),
        )

        assertEquals(listOf("R"), plan.map { it.type })
    }

    @Test
    fun cancelledSessionsAndFinishedWindowsAreSkipped() {
        val cancelled = session("FP3", "2026-10-24T17:30:00Z", cancelled = true)
        val plan = planFinishChecks(
            schedule(cancelled, qualifying, race),
            ReminderPreferences(),
            emptySet(),
            now = Instant.parse("2026-10-25T04:00:00Z"),
        )

        assertEquals(listOf("R"), plan.map { it.type })
    }

    @Test
    fun turningTheFinishNoticeOffPlansNothing() {
        val plan = planFinishChecks(
            schedule(qualifying, race),
            ReminderPreferences(sessionFinished = false),
            emptySet(),
            now = Instant.parse("2026-10-20T08:00:00Z"),
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun aSeasonBefore2023IsNotChecked() {
        val old = schedule(qualifying).copy(season = 2022)

        assertTrue(
            planFinishChecks(old, ReminderPreferences(), emptySet(), Instant.parse("2022-10-20T08:00:00Z")).isEmpty(),
        )
    }

    @Test
    fun checksAreOrderedByTimeAndShareNoCodeWithOtherAlarms() {
        val plan = planFinishChecks(
            schedule(race, qualifying),
            ReminderPreferences(),
            emptySet(),
            now = Instant.parse("2026-10-20T08:00:00Z"),
        )
        assertEquals(listOf("Q", "R"), plan.map { it.type })

        val finishCodes = finishCheckRequestCodes()
        val otherCodes = seasonReminderRequestCodes() + raceWeekRequestCodes() +
            TEST_REMINDER_REQUEST_CODE
        assertEquals(finishCodes.size, finishCodes.toSet().size)
        assertTrue(finishCodes.none { it in otherCodes })
        assertNull(finishCheckRequestCode(0, "Q"))
        assertNull(finishCheckRequestCode(MAX_REMINDER_ROUND + 1, "Q"))
        assertNull(finishCheckRequestCode(1, "WARMUP"))
    }

    private fun schedule(vararg sessions: ScheduledSession): SeasonSchedule {
        return SeasonSchedule(
            season = 2026,
            events = listOf(
                RaceWeekend(
                    round = 1,
                    name = "United States Grand Prix",
                    circuitName = "Circuit of the Americas",
                    locality = "Austin",
                    country = "USA",
                    sessions = sessions.toList(),
                ),
            ),
        )
    }

    private fun session(type: String, start: String, cancelled: Boolean = false): ScheduledSession {
        return ScheduledSession(
            type = type,
            name = type,
            start = Instant.parse(start),
            timezone = "America/Chicago",
            cancelled = cancelled,
        )
    }
}
