package com.formulatracker.reminders

import com.formulatracker.schedule.SeasonSchedule
import java.time.Duration
import java.time.Instant

data class ReminderSessionType(val type: String, val label: String)

val REMINDER_SESSION_TYPES = listOf(
    ReminderSessionType("FP1", "Practice 1"),
    ReminderSessionType("FP2", "Practice 2"),
    ReminderSessionType("FP3", "Practice 3"),
    ReminderSessionType("SQ", "Sprint qualifying"),
    ReminderSessionType("SR", "Sprint"),
    ReminderSessionType("Q", "Qualifying"),
    ReminderSessionType("R", "Race"),
)

data class ReminderPreferences(
    val enabledTypes: Set<String> = REMINDER_SESSION_TYPES.map { it.type }.toSet(),
    val beforeStart: Boolean = true,
    val atStart: Boolean = true,
)

data class PlannedReminder(
    val requestCode: Int,
    val triggerAt: Instant,
    val title: String,
    val text: String,
)

internal const val MAX_REMINDER_ROUND = 30
internal const val TEST_REMINDER_REQUEST_CODE = 10_001
internal val REMINDER_LEAD = Duration.ofMinutes(60)

private const val SLOTS_PER_ROUND = 14

internal fun reminderRequestCode(round: Int, type: String, atStart: Boolean): Int? {
    if (round !in 1..MAX_REMINDER_ROUND) {
        return null
    }
    val typeIndex = REMINDER_SESSION_TYPES.indexOfFirst { it.type == type }
    if (typeIndex < 0) {
        return null
    }
    val timing = if (atStart) 0 else 1
    return round * SLOTS_PER_ROUND + typeIndex * 2 + timing
}

internal fun seasonReminderRequestCodes(): List<Int> {
    return buildList {
        for (round in 1..MAX_REMINDER_ROUND) {
            for (sessionType in REMINDER_SESSION_TYPES) {
                add(reminderRequestCode(round, sessionType.type, atStart = true)!!)
                add(reminderRequestCode(round, sessionType.type, atStart = false)!!)
            }
        }
    }
}

fun planReminders(
    schedule: SeasonSchedule,
    preferences: ReminderPreferences,
    now: Instant,
): List<PlannedReminder> {
    val planned = mutableListOf<PlannedReminder>()
    for (weekend in schedule.events) {
        val title = weekend.name.ifBlank { "Round ${weekend.round}" }
        for (session in weekend.sessions) {
            if (session.cancelled || session.type !in preferences.enabledTypes) {
                continue
            }
            val label = session.name.ifBlank { labelFor(session.type) }
            if (preferences.atStart) {
                reminder(
                    round = weekend.round,
                    type = session.type,
                    atStart = true,
                    triggerAt = session.start,
                    title = title,
                    text = "$label is scheduled to start",
                    now = now,
                )?.let(planned::add)
            }
            if (preferences.beforeStart) {
                reminder(
                    round = weekend.round,
                    type = session.type,
                    atStart = false,
                    triggerAt = session.start.minus(REMINDER_LEAD),
                    title = title,
                    text = "$label is scheduled to start in 60 minutes",
                    now = now,
                )?.let(planned::add)
            }
        }
    }
    return planned.sortedWith(compareBy({ it.triggerAt }, { it.requestCode }))
}

private fun reminder(
    round: Int,
    type: String,
    atStart: Boolean,
    triggerAt: Instant,
    title: String,
    text: String,
    now: Instant,
): PlannedReminder? {
    val requestCode = reminderRequestCode(round, type, atStart) ?: return null
    if (!triggerAt.isAfter(now)) {
        return null
    }
    return PlannedReminder(requestCode, triggerAt, title, text)
}

private fun labelFor(type: String): String {
    return REMINDER_SESSION_TYPES.firstOrNull { it.type == type }?.label ?: type
}
