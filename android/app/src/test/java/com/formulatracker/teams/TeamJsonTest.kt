package com.formulatracker.teams

import com.formulatracker.ui.parseTeamColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeamJsonTest {
    @Test
    fun aBadColorIsDroppedAndAKnownNameMatches() {
        val season = parseTeams(
            """
            {"season":2026,"teams":[
              {"name":"Ferrari","color":"#ED1131"},
              {"name":"Mystery","color":"red"}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("Ferrari"), season.teams.map { it.name })
        val colors = season.teams.associate { it.name.uppercase() to it.color }
        assertEquals("#ED1131", teamColor(colors, " ferrari "))
        assertNull(teamColor(colors, "Mercedes"))
        assertNull(parseTeamColor("red"))
    }
}