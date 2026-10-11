package com.formulatracker.favorites

import android.content.Context

class FavoriteStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): FavoriteSelection {
        return FavoriteSelection(
            driverCode = preferences.getString(KEY_DRIVER_CODE, "").orEmpty(),
            driverName = preferences.getString(KEY_DRIVER_NAME, "").orEmpty(),
            team = preferences.getString(KEY_TEAM, "").orEmpty(),
        )
    }

    fun write(value: FavoriteSelection) {
        preferences.edit()
            .putString(KEY_DRIVER_CODE, value.driverCode)
            .putString(KEY_DRIVER_NAME, value.driverName)
            .putString(KEY_TEAM, value.team)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "favorites"
        const val KEY_DRIVER_CODE = "driver_code"
        const val KEY_DRIVER_NAME = "driver_name"
        const val KEY_TEAM = "team"
    }
}
