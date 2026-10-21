package com.formulatracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalArtTest {
    @Test
    fun knownCircuitsMatchTheirPhotoFiles() {
        assertEquals("albert_park_grand_prix_circuit", circuitArtKey("Albert Park Grand Prix Circuit"))
        assertEquals("autodromo_hermanos_rodriguez", circuitArtKey("Autódromo Hermanos Rodríguez"))
        assertEquals("autodromo_jose_carlos_pace", circuitArtKey("Autódromo José Carlos Pace"))
        assertEquals("circuit_de_barcelona_catalunya", circuitArtKey("Circuit de Barcelona-Catalunya"))
        assertEquals("losail_international_circuit", circuitArtKey("Lusail International Circuit"))
        assertEquals("las_vegas_strip_street_circuit", circuitArtKey("Las Vegas Strip Street Circuit"))
        assertEquals("madring", circuitArtKey("Madring"))
        assertEquals("sepang_international_circuit", circuitArtKey("Sepang International Circuit"))
    }

    @Test
    fun missingAndUnrelatedCircuitsStayBlank() {
        assertNull(circuitArtKey("Las Vegas Street Circuit"))
    }

    @Test
    fun knownTeamsMatchTheirLogoFiles() {
        assertEquals("ferrari", teamArtKey("Ferrari"))
        assertEquals("red_bull", teamArtKey("Red Bull"))
        assertEquals("haas", teamArtKey("Haas F1 Team"))
        assertEquals("alpine", teamArtKey("Alpine F1 Team"))
        assertEquals("rb", teamArtKey("RB F1 Team"))
        assertEquals("sauber", teamArtKey("Kick Sauber"))
        assertEquals("audi", teamArtKey("Audi"))
        assertEquals("cadillac", teamArtKey("Cadillac F1 Team"))
    }

    @Test
    fun teamsWithoutALogoStayBlank() {
        assertNull(teamArtKey("Alfa Romeo"))
    }
}
