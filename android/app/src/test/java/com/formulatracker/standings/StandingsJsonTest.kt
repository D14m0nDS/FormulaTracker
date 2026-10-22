package com.formulatracker.standings

import com.formulatracker.schedule.ScheduleParseException
import com.formulatracker.ui.constructorLine
import com.formulatracker.ui.driverLine
import com.formulatracker.ui.driverCardStats
import com.formulatracker.ui.standingsStillLoading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StandingsJsonTest {
    @Test
    fun standingsKeepHalfPointsAndMoreThanOneTeam() {
        val standings = parseStandings(STANDINGS)

        assertEquals(2026, standings.season)
        assertEquals(17, standings.afterRound)
        assertEquals(listOf("ANT", "RUS"), standings.drivers.map { it.code })
        assertEquals(12.5, standings.drivers[1].points, 0.0)
        assertEquals(listOf("Mercedes", "Other"), standings.drivers[1].teams)
        assertEquals("Mercedes", standings.constructors.single().team)
        assertEquals(578.0, standings.constructors.single().points, 0.0)
        assertEquals(
            "2  RUS  12.5 pts  0 wins  Mercedes, Other",
            driverLine(standings.drivers[1]),
        )
        val card = driverCardStats(standings.drivers[1], "Mercedes")
        assertEquals("2", card.place)
        assertEquals("12.5 pts", card.points)
        assertEquals("Mercedes", card.team)
        assertEquals(
            "1  Mercedes  578 pts  11 wins",
            constructorLine(standings.constructors.single()),
        )
    }

    @Test
    fun standingsKeepLoadingUntilEightSeconds() {
        assertEquals(true, standingsStillLoading(1_000, 8_999))
        assertEquals(false, standingsStillLoading(1_000, 9_000))
    }

    @Test
    fun aDriverWithoutACodeUsesTheFamilyName() {
        val line = driverLine(
            DriverStanding(
                positionText = "-",
                code = "",
                familyName = "Hakkinen",
                points = 0.0,
                wins = 0,
                teams = listOf("McLaren"),
            ),
        )

        assertEquals("-  Hakkinen  0 pts  0 wins  McLaren", line)
    }

    @Test
    fun missingAfterRoundStaysEmpty() {
        val standings = parseStandings(STANDINGS.replace("\"after_round\": 17,", "\"after_round\": null,"))

        assertNull(standings.afterRound)
    }

    @Test
    fun aPayloadWithoutDriversIsRejected() {
        assertThrows(ScheduleParseException::class.java) {
            parseStandings("""{"season": 2026}""")
        }
    }

    private companion object {
        val STANDINGS = """
            {
              "season": 2026,
              "after_round": 17,
              "drivers": [
                {
                  "position_text": "1",
                  "points": 340.0,
                  "wins": 8,
                  "driver": {"code": "ANT"},
                  "teams": ["Mercedes"]
                },
                {
                  "position_text": "2",
                  "points": 12.5,
                  "wins": 0,
                  "driver": {"code": "RUS"},
                  "teams": ["Mercedes", "Other"]
                }
              ],
              "constructors": [
                {
                  "position_text": "1",
                  "points": 578.0,
                  "wins": 11,
                  "team": "Mercedes"
                }
              ]
            }
        """.trimIndent()
    }
}
