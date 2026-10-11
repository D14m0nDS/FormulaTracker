package com.formulatracker.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonYearsTest {
    @Test
    fun yearsRunFromTheCurrentSeasonDownTo1950() {
        val years = seasonYears(2026)

        assertEquals(2026, years.first())
        assertEquals(1950, years.last())
        assertEquals(77, years.size)
    }
}
