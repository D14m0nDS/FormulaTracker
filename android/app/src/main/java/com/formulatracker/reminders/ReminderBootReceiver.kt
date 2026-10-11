package com.formulatracker.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.formulatracker.favorites.FavoriteStore

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val pending = goAsync()
        Thread {
            try {
                val schedule = ReminderScheduleStore(context).read() ?: return@Thread
                val preferences = ReminderSettings(context).read()
                val favorite = FavoriteStore(context).read()
                // AlarmManager drops alarms on reboot. Set them again from the saved calendar. Do not notify.
                SessionReminderScheduler(context).replace(schedule, preferences, favorite)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
