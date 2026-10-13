package com.formulatracker.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class CalendarAgeTest {
    private val zone = ZoneId.of("UTC")
    private val locale = Locale.US
    private val now = Instant.parse("2026-03-06T00:00:00Z")

    @Test
    fun aMissingOrRecentSaveStaysQuiet() {
        assertNull(calendarUpdatedMessage(null, now, zone, locale))
        assertNull(calendarUpdatedMessage(now.minus(CALENDAR_STALE_AFTER), now, zone, locale))
        assertNull(calendarUpdatedMessage(now.plusSeconds(60), now, zone, locale))
    }

    @Test
    fun anOlderSaveNamesTheLocalTime() {
        val savedAt = now.minus(CALENDAR_STALE_AFTER).minusSeconds(60)
        val whenText = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .format(savedAt.atZone(zone))

        assertEquals(
            "Calendar last updated $whenText",
            calendarUpdatedMessage(savedAt, now, zone, locale),
        )
    }
}
