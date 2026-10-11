package com.formulatracker.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduleJsonTest {
    @Test
    fun parseSchedule_readsWeekendsAndCollapsesNothingFurther() {
        val schedule = parseSchedule(SCHEDULE_JSON)

        assertEquals(2026, schedule.season)
        assertEquals(listOf("FP1", "Q", "R"), schedule.events[0].sessions.map { it.type })
        assertEquals("Australian Grand Prix", schedule.events[0].name)
        assertEquals("Melbourne", schedule.events[0].locality)
        assertEquals(Instant.parse("2026-03-06T01:30:00Z"), schedule.events[0].sessions[0].start)
        assertEquals("Australia/Melbourne", schedule.events[0].sessions[0].timezone)
        assertTrue(schedule.events[1].sessions.single().cancelled)
    }

    @Test
    fun parseSchedule_skipsASessionWithNoStart() {
        val schedule = parseSchedule(
            """
            {"season":2026,"events":[{"round":1,"name":"Test Grand Prix","circuit":{},"sessions":[
              {"type":"FP1","name":"Practice 1","start":"2026-03-06T01:30:00Z","cancelled":false},
              {"type":"FP2","name":"Practice 2","cancelled":false}
            ]}]}
            """.trimIndent(),
        )

        assertEquals(listOf("FP1"), schedule.events.single().sessions.map { it.type })
    }

    @Test
    fun parseSchedule_rejectsAPayloadWithoutEvents() {
        val error = assertThrows(ScheduleParseException::class.java) {
            parseSchedule("""{"season":2026}""")
        }
        assertEquals("Schedule is missing events", error.message)
    }

    @Test
    fun instantsConvertToTheDeviceZoneAcrossDaylightSaving() {
        val sofia = ZoneId.of("Europe/Sofia")
        val beforeChange = Instant.parse("2026-03-06T01:30:00Z").atZone(sofia)
        val afterChange = Instant.parse("2026-04-01T01:30:00Z").atZone(sofia)

        assertEquals(3, beforeChange.hour)
        assertEquals(30, beforeChange.minute)
        assertEquals(4, afterChange.hour)
        assertEquals(30, afterChange.minute)
    }

    private companion object {
        val SCHEDULE_JSON = """
            {
              "season": 2026,
              "events": [
                {
                  "round": 1,
                  "name": "Australian Grand Prix",
                  "circuit": {
                    "name": "Albert Park Grand Prix Circuit",
                    "locality": "Melbourne",
                    "country": "Australia"
                  },
                  "sessions": [
                    {
                      "type": "FP1",
                      "name": "Practice 1",
                      "start": "2026-03-06T01:30:00Z",
                      "timezone": "Australia/Melbourne",
                      "cancelled": false
                    },
                    {
                      "type": "Q",
                      "name": "Qualifying",
                      "start": "2026-03-07T05:00:00Z",
                      "timezone": "Australia/Melbourne",
                      "cancelled": false
                    },
                    {
                      "type": "R",
                      "name": "Race",
                      "start": "2026-03-08T04:00:00Z",
                      "timezone": "Australia/Melbourne",
                      "cancelled": false
                    }
                  ]
                },
                {
                  "round": 4,
                  "name": "Cancelled Grand Prix",
                  "circuit": {"name": "Test Circuit", "locality": "Test City", "country": "Testland"},
                  "sessions": [
                    {
                      "type": "R",
                      "name": "Race",
                      "start": "2026-05-01T12:00:00Z",
                      "cancelled": true
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
