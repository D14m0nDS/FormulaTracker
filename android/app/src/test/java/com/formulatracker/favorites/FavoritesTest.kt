package com.formulatracker.favorites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesTest {
    @Test
    fun anEmptySelectionMatchesNothing() {
        val favorite = FavoriteSelection()

        assertFalse(favorite.matchesDriver("NOR", "Norris"))
        assertFalse(favorite.matchesTeam("McLaren"))
        assertEquals("Qualifying is scheduled to start", withFavorite("Qualifying is scheduled to start", favorite))
    }

    @Test
    fun driverCodeAndTeamMatchWithoutCase() {
        val favorite = FavoriteSelection(driverCode = "NOR", driverName = "Norris", team = "McLaren")

        assertTrue(favorite.matchesDriver("nor"))
        assertTrue(favorite.matchesTeam("mclaren"))
        assertFalse(favorite.matchesDriver("VER", "Verstappen"))
        assertEquals(
            "Qualifying is scheduled to start. Favorite: Norris, McLaren.",
            withFavorite("Qualifying is scheduled to start", favorite),
        )
    }

    @Test
    fun aDriverWithoutACodeMatchesTheFamilyName() {
        val favorite = FavoriteSelection(driverName = "Hakkinen")

        assertTrue(favorite.matchesDriver(code = "", familyName = "Hakkinen"))
        assertFalse(favorite.matchesDriver(code = "HAK"))
        assertEquals(
            "Race week is scheduled to begin. Favorite: Hakkinen.",
            withFavorite("Race week is scheduled to begin", favorite),
        )
    }
}
