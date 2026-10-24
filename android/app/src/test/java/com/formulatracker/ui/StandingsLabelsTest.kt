package com.formulatracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StandingsLabelsTest {
    @Test
    fun oneWinIsSingular() {
        assertEquals("1 win", winsLabel(1))
    }

    @Test
    fun zeroAndSeveralWinsArePlural() {
        assertEquals("0 wins", winsLabel(0))
        assertEquals("8 wins", winsLabel(8))
    }

    @Test
    fun oneChampionshipPointIsSingular() {
        assertEquals("1 pt", pointsLabel(1.0))
    }

    @Test
    fun otherTotalsKeepTheirFormatting() {
        assertEquals("0 pts", pointsLabel(0.0))
        assertEquals("340 pts", pointsLabel(340.0))
        assertEquals("0.5 pts", pointsLabel(0.5))
    }
}
