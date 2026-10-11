package com.formulatracker.drivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriverJsonTest {
    @Test
    fun photosKeepTheCodeAndDropABlankUrl() {
        val directory = parseDrivers(
            """
            {"season":2025,"drivers":[
              {"code":"NOR","name":"Norris","photo_url":"https://images.example/nor.png"},
              {"code":"COL","name":"Colapinto","photo_url":""},
              {"code":"","name":"Nobody","photo_url":"https://images.example/x.png"}
            ]}
            """.trimIndent(),
        )

        assertEquals(2025, directory.season)
        assertEquals(listOf("NOR", "COL"), directory.drivers.map { it.code })
        assertEquals("https://images.example/nor.png", directory.drivers[0].photoUrl)
        assertNull(directory.drivers[1].photoUrl)
    }
}
