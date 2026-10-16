package com.formulatracker.reminders

import com.formulatracker.favorites.FavoriteSelection
import com.formulatracker.schedule.RaceWeekend
import com.formulatracker.schedule.ScheduledSession
import com.formulatracker.schedule.SeasonSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderPlanTest {
    private val now = Instant.parse("2026-03-06T00:00:00Z")

    @Test
    fun futureSessionsGetBothReminders() {
        val schedule = schedule(
            session("Q", "Qualifying", "2026-03-07T05:00:00Z"),
            session("R", "Race", "2026-03-08T04:00:00Z"),
        )

        val planned = planReminders(schedule, ReminderPreferences(raceWeek = false), now)

        assertEquals(
            listOf(
                "Qualifying is scheduled to start in 60 minutes",
                "Qualifying is scheduled to start",
                "Race is scheduled to start in 60 minutes",
                "Race is scheduled to start",
            ),
            planned.map { it.text },
        )
        assertEquals(Instant.parse("2026-03-07T04:00:00Z"), planned[0].triggerAt)
        assertEquals(Instant.parse("2026-03-07T05:00:00Z"), planned[1].triggerAt)
        assertTrue(planned.all { it.title == "Australian Grand Prix" })
    }

    @Test
    fun aCustomLeadReplacesTheDefault() {
        val schedule = schedule(session("Q", "Qualifying", "2026-03-06T00:30:00Z"))
        val preferences = ReminderPreferences(
            atStart = false,
            raceWeek = false,
            leadMinutes = 15,
        )

        val planned = planReminders(schedule, preferences, now)

        assertEquals("Qualifying is scheduled to start in 15 minutes", planned.single().text)
        assertEquals(Instant.parse("2026-03-06T00:15:00Z"), planned.single().triggerAt)
    }

    @Test
    fun aFavoriteIsNamedOnTheScheduledReminder() {
        val schedule = schedule(session("Q", "Qualifying", "2026-03-07T05:00:00Z"))
        val favorite = FavoriteSelection(driverCode = "NOR", driverName = "Norris", team = "McLaren")

        val planned = planReminders(
            schedule,
            ReminderPreferences(beforeStart = false, raceWeek = false),
            now,
            favorite = favorite,
        )

        assertEquals(
            "Qualifying is scheduled to start. Favorite: Norris, McLaren.",
            planned.single().text,
        )
    }

    @Test
    fun leadReminderIsDroppedOnceThatMomentHasPassed() {
        val schedule = schedule(
            session("Q", "Qualifying", "2026-03-06T00:30:00Z"),
        )

        val planned = planReminders(schedule, ReminderPreferences(), now)

        assertEquals(listOf("Qualifying is scheduled to start"), planned.map { it.text })
        assertEquals(Instant.parse("2026-03-06T00:30:00Z"), planned.single().triggerAt)
    }

    @Test
    fun disabledTypeAndCancelledSessionAreSkipped() {
        val preferences = ReminderPreferences(enabledTypes = setOf("R"))
        val schedule = schedule(
            session("Q", "Qualifying", "2026-03-07T05:00:00Z"),
            session("R", "Race", "2026-03-08T04:00:00Z", cancelled = true),
            session("FP1", "Practice 1", "2026-03-06T01:30:00Z"),
        )

        val planned = planReminders(schedule, preferences, now)

        assertTrue(planned.isEmpty())
    }

    @Test
    fun timingsThatAreOffProduceNothing() {
        val schedule = schedule(session("R", "Race", "2026-03-08T04:00:00Z"))
        val preferences = ReminderPreferences(beforeStart = false, atStart = false, raceWeek = false)

        assertTrue(planReminders(schedule, preferences, now).isEmpty())
    }

    @Test
    fun pastSessionIsSkipped() {
        val schedule = schedule(session("FP1", "Practice 1", "2026-03-05T01:30:00Z"))

        assertTrue(planReminders(schedule, ReminderPreferences(), now).isEmpty())
    }

    @Test
    fun blankSessionNameUsesTheTypeLabel() {
        val schedule = schedule(session("SQ", "", "2026-03-07T05:00:00Z"))

        val planned = planReminders(schedule, ReminderPreferences(beforeStart = false), now)

        assertEquals("Sprint qualifying is scheduled to start", planned.single().text)
    }

    @Test
    fun browsingAnotherSeasonDoesNotReplaceCurrentSeasonReminders() {
        assertTrue(scheduleRemindersFor(loadedSeason = 2026, currentYear = 2026))
        assertFalse(scheduleRemindersFor(loadedSeason = 2011, currentYear = 2026))
    }

    @Test
    fun requestCodesStayUniqueAndClearOfTheTestReminder() {
        val sessions = REMINDER_SESSION_TYPES.map {
            session(it.type, it.label, "2026-03-07T05:00:00Z")
        }
        val planned = planReminders(schedule(*sessions.toTypedArray()), ReminderPreferences(raceWeek = false), now)
        val codes = seasonReminderRequestCodes()

        assertEquals(14, planned.size)
        assertEquals(planned.size, planned.map { it.requestCode }.distinct().size)
        assertTrue(planned.all { it.requestCode in codes })
        assertFalse(codes.contains(TEST_REMINDER_REQUEST_CODE))
        assertEquals(codes.size, codes.distinct().size)
        assertTrue(raceWeekRequestCodes().none { it in codes || it == TEST_REMINDER_REQUEST_CODE })
    }

    @Test
    fun raceWeekIsMondayAtNineInThePhoneZone() {
        val zone = ZoneId.of("America/New_York")
        val race = ZonedDateTime.of(2026, 11, 8, 14, 0, 0, 0, zone)
        val schedule = schedule(session("R", "Race", race.toInstant().toString()))
        val preferences = ReminderPreferences(beforeStart = false, atStart = false)

        val planned = planReminders(schedule, preferences, Instant.parse("2026-10-01T00:00:00Z"), zone)

        assertEquals("Race week is scheduled to begin", planned.single().text)
        assertEquals(ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, zone).toInstant(), planned.single().triggerAt)
        assertEquals(raceWeekRequestCode(1), planned.single().requestCode)
    }

    @Test
    fun raceWeekUsesTheOffsetInForceOnThatMonday() {
        val zone = ZoneId.of("America/New_York")
        val race = ZonedDateTime.of(2026, 3, 15, 14, 0, 0, 0, zone)
        val schedule = schedule(session("R", "Race", race.toInstant().toString()))

        val planned = planReminders(
            schedule,
            ReminderPreferences(beforeStart = false, atStart = false),
            Instant.parse("2026-03-01T00:00:00Z"),
            zone,
        )

        assertEquals(Instant.parse("2026-03-09T13:00:00Z"), planned.single().triggerAt)
    }

    @Test
    fun raceWeekInParisUsesCentralEuropeanSummerTime() {
        val zone = ZoneId.of("Europe/Paris")
        val race = ZonedDateTime.of(2026, 4, 5, 15, 0, 0, 0, zone)
        val schedule = schedule(session("R", "Race", race.toInstant().toString()))

        val planned = planReminders(
            schedule,
            ReminderPreferences(beforeStart = false, atStart = false),
            Instant.parse("2026-03-01T00:00:00Z"),
            zone,
        )

        assertEquals(Instant.parse("2026-03-30T07:00:00Z"), planned.single().triggerAt)
    }

    @Test
    fun raceWeekIsSkippedOnceMondayMorningHasPassed() {
        val zone = ZoneId.of("Europe/Sofia")
        val schedule = schedule(session("R", "Race", "2026-03-08T13:00:00Z"))

        val planned = planReminders(
            schedule,
            ReminderPreferences(beforeStart = false, atStart = false),
            now,
            zone,
        )

        assertTrue(planned.isEmpty())
    }

    @Test
    fun cancelledRaceHasNoRaceWeekReminder() {
        val zone = ZoneId.of("Europe/Sofia")
        val schedule = schedule(session("R", "Race", "2026-11-08T12:00:00Z", cancelled = true))

        val planned = planReminders(schedule, ReminderPreferences(), Instant.parse("2026-10-01T00:00:00Z"), zone)

        assertTrue(planned.none { it.text == "Race week is scheduled to begin" })
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
            timezone = "Australia/Melbourne",
            cancelled = cancelled,
        )
    }
}
