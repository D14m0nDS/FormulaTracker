package com.formulatracker.drivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverPhotoStoreTest {
    private val saved = mapOf("NOR" to "https://example.test/old-nor.png")

    private fun directory(vararg drivers: DriverPhoto) = DriverDirectory(season = 2026, drivers = drivers.toList())

    @Test
    fun aLoadedListIsShownAndSaved() {
        val fetched = directory(
            DriverPhoto("nor", "Norris", "https://example.test/nor.png"),
            DriverPhoto("COL", "Colapinto", null),
        )

        val choice = choosePhotoUrls(fetched, saved)

        assertEquals(mapOf("NOR" to "https://example.test/nor.png"), choice.urls)
        assertTrue(choice.save)
    }

    @Test
    fun aFailedRequestFallsBackToTheSavedList() {
        val choice = choosePhotoUrls(fetched = null, saved = saved)

        assertEquals(saved, choice.urls)
        assertFalse(choice.save)
    }

    @Test
    fun aListWithoutLinksDoesNotReplaceTheSavedList() {
        val fetched = directory(DriverPhoto("NOR", "Norris", null), DriverPhoto("PIA", "Piastri", "  "))

        val choice = choosePhotoUrls(fetched, saved)

        assertEquals(saved, choice.urls)
        assertFalse(choice.save)
    }

    @Test
    fun noSavedListAndNoRequestGivesNothing() {
        val choice = choosePhotoUrls(fetched = null, saved = emptyMap())

        assertTrue(choice.urls.isEmpty())
        assertFalse(choice.save)
    }

    @Test
    fun theSavedFormatRoundTripsAndDropsBlankLinks() {
        val text = encodePhotoUrls(mapOf("NOR" to "https://example.test/nor.png", "PIA" to " "))

        assertEquals(mapOf("NOR" to "https://example.test/nor.png"), decodePhotoUrls(text))
    }

    @Test
    fun unreadableSavedTextGivesAnEmptyList() {
        assertTrue(decodePhotoUrls(null).isEmpty())
        assertTrue(decodePhotoUrls("").isEmpty())
        assertTrue(decodePhotoUrls("not json").isEmpty())
    }
}
