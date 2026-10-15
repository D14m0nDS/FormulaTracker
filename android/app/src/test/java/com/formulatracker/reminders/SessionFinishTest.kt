package com.formulatracker.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SessionFinishTest {
    private val race = FinishedSession(
        round = 17,
        eventName = "Singapore Grand Prix",
        type = "R",
        name = "Race",
        listedEnd = Instant.parse("2026-10-11T14:00:00Z"),
        finishedAt = Instant.parse("2026-10-11T13:45:40Z"),
    )

    @Test
    fun aFinishBecomesOneNotice() {
        val plan = planSessionFinishNotices(SessionFinishWatch(2026, listOf(race)), emptySet())

        val notice = plan.notices.single()
        assertEquals("Singapore Grand Prix", notice.title)
        assertEquals("Race has finished", notice.text)
        assertEquals(sessionFinishNotificationId(17, "R"), notice.notificationId)
        assertTrue(notice.notificationId > SESSION_FINISH_NOTIFICATION_BASE)
        assertEquals(setOf(sessionFinishKey(2026, 17, "R")), plan.notified)
    }

    @Test
    fun theSameSessionIsNotAnnouncedAgain() {
        val key = sessionFinishKey(2026, 17, "R")
        val qualifying = race.copy(type = "Q", name = "Qualifying")

        val plan = planSessionFinishNotices(
            SessionFinishWatch(2026, listOf(race, qualifying)),
            setOf(key),
        )

        assertEquals(listOf("Qualifying has finished"), plan.notices.map { it.text })
        assertEquals(setOf(key, sessionFinishKey(2026, 17, "Q")), plan.notified)
    }

    @Test
    fun aFailedCheckAndASeasonBefore2023StayQuiet() {
        val saved = setOf(sessionFinishKey(2026, 17, "R"))

        assertTrue(planSessionFinishNotices(null, saved).notices.isEmpty())
        assertEquals(saved, planSessionFinishNotices(null, saved).notified)
        assertTrue(planSessionFinishNotices(SessionFinishWatch(2022, listOf(race)), saved).notices.isEmpty())
    }

    @Test
    fun theSampleNoticeDoesNotUseARealSessionId() {
        val realIds = (1..30).flatMap { round ->
            REMINDER_SESSION_TYPES.map { sessionFinishNotificationId(round, it.type) }
        }

        assertTrue(TEST_SESSION_FINISH_NOTIFICATION_ID > SESSION_FINISH_NOTIFICATION_BASE)
        assertTrue(realIds.none { it == TEST_SESSION_FINISH_NOTIFICATION_ID })
        assertTrue(TEST_SESSION_FINISH_NOTIFICATION_ID != TEST_REMINDER_REQUEST_CODE)
    }

    @Test
    fun thePayloadReadsTheListedEndAndTheFinishTime() {
        val watch = parseSessionFinish(
            """
            {
              "season": 2026,
              "sessions": [
                {
                  "round": 17,
                  "event_name": "Singapore Grand Prix",
                  "session_type": "R",
                  "session_name": "Race",
                  "listed_end": "2026-10-11T14:00:00Z",
                  "finished_at": "2026-10-11T13:45:40Z"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(SessionFinishWatch(2026, listOf(race)), watch)
    }
}
