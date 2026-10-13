package com.formulatracker.reminders

import android.content.Context

class SessionFinishStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): Set<String> {
        return preferences.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty().toSet()
    }

    fun write(keys: Set<String>) {
        preferences.edit().putStringSet(KEY_NOTIFIED, keys.toSet()).apply()
    }

    private companion object {
        const val PREFS_NAME = "session_finish"
        const val KEY_NOTIFIED = "notified"
    }
}
