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
        )
    }

    fun write(value: ReminderPreferences) {
        preferences.edit().apply {
            for (sessionType in REMINDER_SESSION_TYPES) {
                putBoolean(typeKey(sessionType.type), sessionType.type in value.enabledTypes)
            }
            putBoolean(KEY_BEFORE, value.beforeStart)
            putBoolean(KEY_AT, value.atStart)
        }.apply()
    }

    private fun typeKey(type: String): String = "type_$type"

    private companion object {
        const val PREFS_NAME = "reminders"
        const val KEY_BEFORE = "before_start"
        const val KEY_AT = "at_start"
    }
}
