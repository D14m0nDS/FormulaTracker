package com.formulatracker.reminders

import android.content.Context
import com.formulatracker.schedule.ScheduleParseException
import com.formulatracker.schedule.SeasonSchedule
import com.formulatracker.schedule.parseSchedule
import com.formulatracker.schedule.scheduleToJson
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

val CALENDAR_STALE_AFTER: Duration = Duration.ofHours(24)

class ReminderScheduleStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): SeasonSchedule? {
        val json = preferences.getString(KEY_SCHEDULE, null) ?: return null
        return try {
            parseSchedule(json)
        } catch (_: ScheduleParseException) {
            null
        }
    }

    fun readSavedAt(): Instant? {
        if (!preferences.contains(KEY_SAVED_AT)) {
            return null
        }
        return Instant.ofEpochMilli(preferences.getLong(KEY_SAVED_AT, 0L))
    }

    fun write(schedule: SeasonSchedule, savedAt: Instant = Instant.now()) {
        preferences.edit()
            .putString(KEY_SCHEDULE, scheduleToJson(schedule))
            .putLong(KEY_SAVED_AT, savedAt.toEpochMilli())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "reminder_schedule"
        const val KEY_SCHEDULE = "schedule"
        const val KEY_SAVED_AT = "saved_at"
    }
}

fun calendarUpdatedMessage(
    savedAt: Instant?,
    now: Instant,
    zone: ZoneId,
    locale: Locale,
): String? {
    if (savedAt == null || Duration.between(savedAt, now) <= CALENDAR_STALE_AFTER) {
        return null
    }
    val whenText = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .format(savedAt.atZone(zone))
    return "Calendar last updated $whenText"
}
