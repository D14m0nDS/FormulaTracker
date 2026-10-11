package com.formulatracker.results

import com.formulatracker.schedule.ScheduleParseException
import com.formulatracker.ui.formatPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ResultsJsonTest {
    @Test
    fun qualifyingKeepsSegmentOrderAndOmitsMissingTimes() {
        val results = parseSessionResults(QUALIFYING, "Q") as SessionResults.Qualifying
        val rows = results.results.rows

        assertEquals(listOf("Q1", "Q2", "Q3"), results.results.segmentCodes)
        assertEquals(listOf("1:19.507", "1:18.934", "1:18.518"), rows[0].segments.map { it.time })
        assertEquals(listOf("1:21.969", null, null), rows[1].segments.map { it.time })
        assertEquals("NC", rows[2].positionText)
        assertEquals("ACCIDENT", rows[2].status)
        assertNull(rows[2].segments[0].time)
    }

    @Test
    fun raceReadsGridPointsAndAStartThatDidNotHappen() {
        val results = parseSessionResults(RACE, "R") as SessionResults.Race
        val winner = results.results.rows[0]
        val dns = results.results.rows[1]

        assertEquals("Race", results.results.sessionName)
        assertEquals("1:23:06.801", winner.time)
        assertEquals(25.0, winner.points)
        assertEquals(1, winner.grid)
        assertEquals("DID_NOT_START", dns.status)
        assertNull(dns.time)
        assertEquals(5, dns.grid)
        assertEquals("25", formatPoints(25.0))
        assertEquals("12.5", formatPoints(12.5))
    }

    @Test
    fun sprintQualifyingUsesTheSameParser() {
        val results = parseSessionResults(
            QUALIFYING.replace("\"Q\"", "\"SQ\"").replace("Q1", "SQ1").replace("Q2", "SQ2").replace("Q3", "SQ3"),
            "SQ",
        ) as SessionResults.Qualifying

        assertEquals("SQ1", results.results.rows[0].segments[0].code)
    }

    @Test
    fun practiceResultsAreRejected() {
        assertThrows(ScheduleParseException::class.java) {
            parseSessionResults(QUALIFYING, "FP1")
        }
    }

    private companion object {
        val QUALIFYING = """
            {
              "session_type": "Q",
              "session_name": "Qualifying",
              "segment_codes": ["Q1", "Q2", "Q3"],
              "results": [
                {
                  "position_text": "1",
                  "status": "FINISHED",
                  "driver": {"code": "RUS", "given_name": "George", "family_name": "Russell"},
                  "team": "Mercedes",
                  "segments": [
                    {"code": "Q1", "position": 1, "time": "1:19.507"},
                    {"code": "Q2", "position": 1, "time": "1:18.934"},
                    {"code": "Q3", "position": 1, "time": "1:18.518"}
                  ]
                },
                {
                  "position_text": "17",
                  "status": "FINISHED",
                  "driver": {"code": "ALO"},
                  "team": "Aston Martin",
                  "segments": [{"code": "Q1", "position": 17, "time": "1:21.969"}]
                },
                {
                  "position_text": "NC",
                  "status": "ACCIDENT",
                  "driver": {"code": "VER"},
                  "team": "Red Bull",
                  "segments": [{"code": "Q1"}]
                }
              ]
            }
        """.trimIndent()

        val RACE = """
            {
              "session_type": "R",
              "session_name": "Race",
              "results": [
                {
                  "position_text": "1",
                  "classified": true,
                  "status": "FINISHED",
                  "time": "1:23:06.801",
                  "points": 25.0,
                  "grid": 1,
                  "driver": {"code": "RUS"},
                  "team": "Mercedes"
                },
                {
                  "position_text": "NC",
                  "classified": false,
                  "status": "DID_NOT_START",
                  "points": 0.0,
                  "laps": 0,
                  "grid": 5,
                  "driver": {"code": "PIA"},
                  "team": "McLaren"
                }
              ]
            }
        """.trimIndent()
    }
}
