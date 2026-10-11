package com.formulatracker.reminders

import com.formulatracker.favorites.FavoriteSelection
import com.formulatracker.favorites.withFavorite
import com.formulatracker.schedule.SeasonSchedule
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

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

const val DEFAULT_LEAD_MINUTES = 60
const val MIN_LEAD_MINUTES = 1
const val MAX_LEAD_MINUTES = 24 * 60
const val DEFAULT_BEFORE_TEMPLATE = "{session} is scheduled to start in {minutes} minutes"
const val DEFAULT_AT_START_TEMPLATE = "{session} is scheduled to start"
const val DEFAULT_RACE_WEEK_TEMPLATE = "Race week is scheduled to begin"

data class ReminderPreferences(
    val enabledTypes: Set<String> = REMINDER_SESSION_TYPES.map { it.type }.toSet(),
    val beforeStart: Boolean = true,
    val atStart: Boolean = true,
    val raceWeek: Boolean = true,
    val leadMinutes: Int = DEFAULT_LEAD_MINUTES,
    val beforeTemplate: String = DEFAULT_BEFORE_TEMPLATE,
    val atStartTemplate: String = DEFAULT_AT_START_TEMPLATE,
    val raceWeekTemplate: String = DEFAULT_RACE_WEEK_TEMPLATE,
)

data class PlannedReminder(
    val requestCode: Int,
    val triggerAt: Instant,
    val title: String,
    val text: String,
)

internal const val MAX_REMINDER_ROUND = 30
internal const val TEST_REMINDER_REQUEST_CODE = 10_001
internal const val RACE_WEEK_REQUEST_CODE_BASE = 5_000
internal fun usableLeadMinutes(minutes: Int): Int {
    return minutes.coerceIn(MIN_LEAD_MINUTES, MAX_LEAD_MINUTES)
}

internal fun reminderText(template: String, fallback: String, session: String, minutes: Int): String {
    val used = template.ifBlank { fallback }
    return used.replace("{session}", session).replace("{minutes}", minutes.toString())
}

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

internal fun raceWeekRequestCode(round: Int): Int? {
    if (round !in 1..MAX_REMINDER_ROUND) {
        return null
    }
    return RACE_WEEK_REQUEST_CODE_BASE + round
}

internal fun raceWeekRequestCodes(): List<Int> {
    return (1..MAX_REMINDER_ROUND).map { raceWeekRequestCode(it)!! }
}

internal fun raceWeekTrigger(raceStart: Instant, zone: ZoneId): Instant {
    val monday = raceStart.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return monday.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant()
}

internal fun scheduleRemindersFor(loadedSeason: Int, currentYear: Int): Boolean {
    return loadedSeason == currentYear
}

fun planReminders(
    schedule: SeasonSchedule,
    preferences: ReminderPreferences,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    favorite: FavoriteSelection = FavoriteSelection(),
): List<PlannedReminder> {
    val planned = mutableListOf<PlannedReminder>()
    for (weekend in schedule.events) {
        val title = weekend.name.ifBlank { "Round ${weekend.round}" }
        if (preferences.raceWeek) {
            val race = weekend.sessions.firstOrNull { it.type == "R" && !it.cancelled }
            val requestCode = raceWeekRequestCode(weekend.round)
            if (race != null && requestCode != null) {
                val triggerAt = raceWeekTrigger(race.start, zone)
                if (triggerAt.isAfter(now)) {
                    planned.add(
                        PlannedReminder(
                            requestCode = requestCode,
                            triggerAt = triggerAt,
                            title = title,
                            text = withFavorite(
                                reminderText(preferences.raceWeekTemplate, DEFAULT_RACE_WEEK_TEMPLATE, "", usableLeadMinutes(preferences.leadMinutes)),
                                favorite,
                            ),
                        ),
                    )
                }
            }
        }
        for (session in weekend.sessions) {
            if (session.cancelled || session.type !in preferences.enabledTypes) {
                continue
            }
            val label = session.name.ifBlank { labelFor(session.type) }
            val minutes = usableLeadMinutes(preferences.leadMinutes)
            if (preferences.atStart) {
                reminder(
                    round = weekend.round,
                    type = session.type,
                    atStart = true,
                    triggerAt = session.start,
                    title = title,
                    text = withFavorite(
                        reminderText(preferences.atStartTemplate, DEFAULT_AT_START_TEMPLATE, label, minutes),
                        favorite,
                    ),
                    now = now,
                )?.let(planned::add)
            }
            if (preferences.beforeStart) {
                reminder(
                    round = weekend.round,
                    type = session.type,
                    atStart = false,
                    triggerAt = session.start.minus(Duration.ofMinutes(minutes.toLong())),
                    title = title,
                    text = withFavorite(
                        reminderText(preferences.beforeTemplate, DEFAULT_BEFORE_TEMPLATE, label, minutes),
                        favorite,
                    ),
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
