package com.formulatracker.ui

import com.formulatracker.results.RaceRow
import com.formulatracker.results.SegmentTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeekendResultsTest {
    @Test
    fun qualifyingKeepsTheLastSegmentAsTheTime() {
        val readout = qualifyingReadout(
            listOf(
                SegmentTime("Q1", "1:30.100"),
                SegmentTime("Q2", "1:29.200"),
                SegmentTime("Q3", "1:28.300"),
            ),
            "FINISHED",
        )

        assertEquals("1:28.300", readout.headline)
        assertEquals("Q1 1:30.100  Q2 1:29.200", readout.note)
    }

    @Test
    fun aDriverWhoMissedLaterSegmentsKeepsTheStatus() {
        val readout = qualifyingReadout(
            listOf(SegmentTime("Q1", "1:30.100"), SegmentTime("Q2", null)),
            "DNQ",
        )

        assertEquals("1:30.100", readout.headline)
        assertEquals("DNQ", readout.note)
    }

    @Test
    fun practiceShowsTheBestLapAndTheLapCount() {
        assertEquals(ResultReadout("1:31.000", "18 laps"), practiceReadout("1:31.000", 18))
        assertEquals(ResultReadout("—", ""), practiceReadout(null, null))
    }

    @Test
    fun theWinnerKeepsTheRaceTimeAndOthersShowTheGap() {
        val winner = raceRow(position = 1, time = "1:23:06.801", laps = 58, grid = 4)
        val sameLap = raceRow(position = 2, time = "1:23:12.345", laps = 58, grid = 9, code = "NOR")
        val lapped = raceRow(position = 8, time = "1:23:18.617", laps = 57, grid = 18, code = "LAW")
        val twoLaps = raceRow(position = 15, time = "1:23:40.000", laps = 56, grid = 15, code = "ALO")
        val rows = listOf(lapped, winner, sameLap, twoLaps)
        val leader = raceLeader(rows)

        assertEquals("1:23:06.801", raceTimeLabel(winner, leader))
        assertEquals("+5.544", raceTimeLabel(sameLap, leader))
        assertEquals("+1 Lap", raceTimeLabel(lapped, leader))
        assertEquals("+2 Laps", raceTimeLabel(twoLaps, leader))
        assertEquals(3, placesMoved(winner.grid, winner.position, winner.classified))
        assertEquals(7, placesMoved(sameLap.grid, sameLap.position, sameLap.classified))
        assertEquals(-7, placesMoved(1, 8, true))
        assertEquals(0, placesMoved(twoLaps.grid, twoLaps.position, twoLaps.classified))
    }

    @Test
    fun aRetirementShowsTheStatusAndNoPlaces() {
        val retired = raceRow(
            position = 19,
            time = "24:31.911",
            laps = 15,
            grid = 12,
            code = "BOT",
            classified = false,
            status = "RETIRED",
        )

        assertEquals("Retired", raceTimeLabel(retired, null))
        assertEquals("Did not start", raceStatusForTest("DID_NOT_START"))
        assertNull(placesMoved(retired.grid, retired.position, retired.classified))
        assertNull(placesMoved(0, 5, true))
    }

    private fun raceStatusForTest(status: String): String {
        return raceTimeLabel(
            raceRow(position = 21, time = null, laps = 0, grid = 5, classified = false, status = status),
            null,
        )
    }

    private fun raceRow(
        position: Int,
        time: String?,
        laps: Int,
        grid: Int,
        code: String = "RUS",
        classified: Boolean = true,
        status: String = "FINISHED",
    ) = RaceRow(
        positionText = position.toString(),
        position = position,
        code = code,
        team = "Mercedes",
        status = status,
        classified = classified,
        time = time,
        points = 0.0,
        laps = laps,
        grid = grid,
    )
}
