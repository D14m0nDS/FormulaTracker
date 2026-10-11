package com.formulatracker.reminders

import com.formulatracker.schedule.RaceWeekend
import com.formulatracker.schedule.ScheduledSession
import com.formulatracker.schedule.SeasonSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ReminderPlanTest {
    private val now = Instant.parse("2026-03-06T00:00:00Z")

    @Test
    fun futureSessionsGetBothReminders() {
        val schedule = schedule(
            session("Q", "Qualifying", "2026-03-07T05:00:00Z"),
            session("R", "Race", "2026-03-08T04:00:00Z"),
        )

        val planned = planReminders(schedule, ReminderPreferences(), now)

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
        val preferences = ReminderPreferences(beforeStart = false, atStart = false)

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
    fun requestCodesStayUniqueAndClearOfTheTestReminder() {
        val sessions = REMINDER_SESSION_TYPES.map {
            session(it.type, it.label, "2026-03-07T05:00:00Z")
        }
        val planned = planReminders(schedule(*sessions.toTypedArray()), ReminderPreferences(), now)
        val codes = seasonReminderRequestCodes()

        assertEquals(14, planned.size)
        assertEquals(planned.size, planned.map { it.requestCode }.distinct().size)
        assertTrue(planned.all { it.requestCode in codes })
        assertFalse(codes.contains(TEST_REMINDER_REQUEST_CODE))
        assertEquals(codes.size, codes.distinct().size)
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
