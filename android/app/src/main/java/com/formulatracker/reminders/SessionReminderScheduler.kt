package com.formulatracker.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.formulatracker.favorites.FavoriteSelection
import com.formulatracker.schedule.SeasonSchedule
import java.time.Instant

class SessionReminderScheduler(private val context: Context) {
    fun replace(
        schedule: SeasonSchedule,
        preferences: ReminderPreferences,
        favorite: FavoriteSelection = FavoriteSelection(),
        now: Instant = Instant.now(),
    ) {
        // Android drops these alarms on reboot. They are set again the next time the calendar loads.
        clear()
        for (reminder in planReminders(schedule, preferences, now, favorite = favorite)) {
            schedule(reminder)
        }
        scheduleFinishChecks(schedule, preferences, now)
    }

    // Replaces only the finish checks, so a check that just ran can plan the next one
    // without touching the reminders.
    fun replaceFinishChecks(
        schedule: SeasonSchedule,
        preferences: ReminderPreferences,
        now: Instant = Instant.now(),
    ) {
        clearFinishChecks()
        scheduleFinishChecks(schedule, preferences, now)
    }

    fun clear() {
        val alarmManager = alarmManager() ?: return
        // A session that left the calendar must not keep the alarm from the previous pass.
        for (requestCode in seasonReminderRequestCodes() + raceWeekRequestCodes()) {
            alarmManager.cancel(pendingIntent(requestCode, title = null, text = null))
        }
        clearFinishChecks()
    }

    fun clearFinishChecks() {
        val alarmManager = alarmManager() ?: return
        for (requestCode in finishCheckRequestCodes()) {
            alarmManager.cancel(finishCheckIntent(requestCode))
        }
    }

    fun scheduleTest(now: Instant = Instant.now()) {
        schedule(
            PlannedReminder(
                requestCode = TEST_REMINDER_REQUEST_CODE,
                triggerAt = now.plusSeconds(60),
                title = "FormulaTracker",
                text = "This is a scheduled reminder",
            ),
        )
    }

    fun exactAlarmsAllowed(): Boolean {
        val alarmManager = alarmManager() ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    }

    private fun schedule(reminder: PlannedReminder) {
        val pending = pendingIntent(reminder.requestCode, reminder.title, reminder.text)
        setAlarm(reminder.triggerAt, pending)
    }

    private fun scheduleFinishChecks(
        schedule: SeasonSchedule,
        preferences: ReminderPreferences,
        now: Instant,
    ) {
        val notified = SessionFinishStore(context).read()
        for (check in planFinishChecks(schedule, preferences, notified, now)) {
            setAlarm(check.triggerAt, finishCheckIntent(check.requestCode))
        }
    }

    private fun setAlarm(triggerAt: Instant, pending: PendingIntent) {
        val alarmManager = alarmManager() ?: return
        val triggerAtMillis = triggerAt.toEpochMilli()
        if (exactAlarmsAllowed()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
    }

    private fun finishCheckIntent(requestCode: Int): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, SessionFinishAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pendingIntent(requestCode: Int, title: String?, text: String?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            if (title != null) {
                putExtra(ReminderReceiver.EXTRA_TITLE, title)
            }
            if (text != null) {
                putExtra(ReminderReceiver.EXTRA_TEXT, text)
            }
            putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, requestCode)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alarmManager(): AlarmManager? {
        return context.getSystemService(AlarmManager::class.java)
    }
}
