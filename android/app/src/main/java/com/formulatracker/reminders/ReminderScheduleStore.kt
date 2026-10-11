package com.formulatracker.reminders

import android.content.Context
import com.formulatracker.schedule.ScheduleParseException
import com.formulatracker.schedule.SeasonSchedule
import com.formulatracker.schedule.parseSchedule
import com.formulatracker.schedule.scheduleToJson

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

    fun write(schedule: SeasonSchedule) {
        preferences.edit().putString(KEY_SCHEDULE, scheduleToJson(schedule)).apply()
    }

    private companion object {
        const val PREFS_NAME = "reminder_schedule"
        const val KEY_SCHEDULE = "schedule"
    }
}
