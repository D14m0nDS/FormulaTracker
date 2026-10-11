package com.formulatracker.reminders

import com.formulatracker.schedule.RaceWeekend
import com.formulatracker.schedule.ScheduledSession
import com.formulatracker.schedule.SeasonSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class ScheduleChangesTest {
    private val now = Instant.parse("2026-03-06T00:00:00Z")
    private val zone = ZoneId.of("UTC")
    private val locale = Locale.US

    @Test
    fun firstSaveAndAnUnchangedCalendarStayQuiet() {
        val current = schedule(session("Q", "Qualifying", "2026-03-07T05:00:00Z"))

        assertTrue(scheduleChanges(null, current, now, zone, locale).isEmpty())
        assertTrue(scheduleChanges(current, current, now, zone, locale).isEmpty())
    }

    @Test
    fun aDifferentSeasonIsNotAChange() {
        val previous = schedule(session("R", "Race", "2026-03-08T04:00:00Z")).copy(season = 2025)
        val current = schedule(session("R", "Race", "2026-03-08T04:00:00Z"))

        assertTrue(scheduleChanges(previous, current, now, zone, locale).isEmpty())
    }

    @Test
    fun aNewOrRemovedSessionIsNotAChange() {
        val qualifying = session("Q", "Qualifying", "2026-03-07T05:00:00Z")
        val race = session("R", "Race", "2026-03-08T04:00:00Z")

        assertTrue(scheduleChanges(schedule(qualifying), schedule(qualifying, race), now, zone, locale).isEmpty())
        assertTrue(scheduleChanges(schedule(qualifying, race), schedule(qualifying), now, zone, locale).isEmpty())
    }

    @Test
    fun aFutureStartChangeIsReportedOnce() {
        val previous = schedule(session("Q", "Qualifying", "2026-03-07T05:00:00Z"))
        val current = schedule(session("Q", "Qualifying", "2026-03-07T06:00:00Z"))

        val change = scheduleChanges(previous, current, now, zone, locale).single()

        assertEquals("Australian Grand Prix", change.title)
        assertEquals(
            "Qualifying has a new scheduled start of ${format("2026-03-07T06:00:00Z")}",
            change.text,
        )
        assertEquals(scheduleChangeNotificationId(1, "Q"), change.notificationId)
        assertTrue(change.notificationId > SCHEDULE_CHANGE_NOTIFICATION_BASE)
    }

    @Test
    fun cancellationAndRestorationAreReported() {
        val upcoming = session("R", "Race", "2026-03-08T04:00:00Z")
        val cancelled = upcoming.copy(cancelled = true)

        val cancelledChange = scheduleChanges(schedule(upcoming), schedule(cancelled), now, zone, locale).single()
        val restoredChange = scheduleChanges(schedule(cancelled), schedule(upcoming), now, zone, locale).single()

        assertEquals("Race is now cancelled", cancelledChange.text)
        assertEquals(
            "Race is scheduled again for ${format("2026-03-08T04:00:00Z")}",
            restoredChange.text,
        )
    }

    @Test
    fun aFailedRefreshKeepsTheSavedCalendar() {
        val saved = schedule(session("R", "Race", "2026-03-08T04:00:00Z"))

        val plan = planScheduleRefresh(saved, fetched = null, now, zone, locale)

        assertTrue(plan.changes.isEmpty())
        assertEquals(null, plan.schedule)
    }

    @Test
    fun theFirstRefreshIsStoredQuietly() {
        val fetched = schedule(session("R", "Race", "2026-03-08T04:00:00Z"))

        val plan = planScheduleRefresh(previous = null, fetched = fetched, now, zone, locale)

        assertTrue(plan.changes.isEmpty())
        assertEquals(fetched, plan.schedule)
    }

    @Test
    fun aPastSessionChangeStaysQuiet() {
        val previous = schedule(session("FP1", "Practice 1", "2026-03-01T01:30:00Z"))
        val moved = schedule(session("FP1", "Practice 1", "2026-03-01T02:00:00Z"))
        val cancelled = schedule(session("FP1", "Practice 1", "2026-03-01T01:30:00Z", cancelled = true))

        assertTrue(scheduleChanges(previous, moved, now, zone, locale).isEmpty())
        assertTrue(scheduleChanges(previous, cancelled, now, zone, locale).isEmpty())
    }

    private fun format(start: String): String {
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
        return formatter.format(Instant.parse(start).atZone(zone))
    }

    private fun schedule(vararg sessions: ScheduledSession): SeasonSchedule {
        return SeasonSchedule(
            season = 2026,
            events = listOf(
                RaceWeekend(
                    round = 1,
                    name = "Australian Grand Prix",
                    circuitName = "Albert Park",
                    locality = "Melbourne",
                    country = "Australia",
                    sessions = sessions.toList(),
                ),
            ),
        )
    }

    private fun session(
        type: String,
        name: String,
        start: String,
        cancelled: Boolean = false,
    ): ScheduledSession {
        return ScheduledSession(
            type = type,
            name = name,
            start = Instant.parse(start),
            timezone = "UTC",
            cancelled = cancelled,
        )
    }
}
