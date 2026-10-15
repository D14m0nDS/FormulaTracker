package com.formulatracker.reminders

import android.content.Context

class ReminderSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): ReminderPreferences {
        val enabled = REMINDER_SESSION_TYPES
            .map { it.type }
            .filter { preferences.getBoolean(typeKey(it), true) }
            .toSet()
        return ReminderPreferences(
            enabledTypes = enabled,
            beforeStart = preferences.getBoolean(KEY_BEFORE, true),
            atStart = preferences.getBoolean(KEY_AT, true),
            raceWeek = preferences.getBoolean(KEY_RACE_WEEK, true),
            sessionFinished = preferences.getBoolean(KEY_SESSION_FINISHED, true),
            leadMinutes = preferences.getInt(KEY_LEAD, DEFAULT_LEAD_MINUTES),
            beforeTemplate = preferences.getString(KEY_BEFORE_TEXT, DEFAULT_BEFORE_TEMPLATE).orEmpty(),
            atStartTemplate = preferences.getString(KEY_AT_TEXT, DEFAULT_AT_START_TEMPLATE).orEmpty(),
            raceWeekTemplate = preferences.getString(KEY_RACE_WEEK_TEXT, DEFAULT_RACE_WEEK_TEMPLATE).orEmpty(),
        )
    }

    fun write(value: ReminderPreferences) {
        preferences.edit().apply {
            for (sessionType in REMINDER_SESSION_TYPES) {
                putBoolean(typeKey(sessionType.type), sessionType.type in value.enabledTypes)
            }
            putBoolean(KEY_BEFORE, value.beforeStart)
            putBoolean(KEY_AT, value.atStart)
            putBoolean(KEY_RACE_WEEK, value.raceWeek)
            putBoolean(KEY_SESSION_FINISHED, value.sessionFinished)
            putInt(KEY_LEAD, value.leadMinutes)
            putString(KEY_BEFORE_TEXT, value.beforeTemplate)
            putString(KEY_AT_TEXT, value.atStartTemplate)
            putString(KEY_RACE_WEEK_TEXT, value.raceWeekTemplate)
        }.apply()
    }

    private fun typeKey(type: String): String = "type_$type"

    private companion object {
        const val PREFS_NAME = "reminders"
        const val KEY_BEFORE = "before_start"
        const val KEY_AT = "at_start"
        const val KEY_RACE_WEEK = "race_week"
        const val KEY_SESSION_FINISHED = "session_finished"
        const val KEY_LEAD = "lead_minutes"
        const val KEY_BEFORE_TEXT = "before_text"
        const val KEY_AT_TEXT = "at_start_text"
        const val KEY_RACE_WEEK_TEXT = "race_week_text"
    }
}
