package com.formulatracker.reminders

import com.formulatracker.schedule.SeasonSchedule
import java.time.Duration
import java.time.Instant

// The finish status can only be read 30 minutes after a session really ends, so an
// earlier check cannot succeed.
internal val FINISH_CHECK_MARGIN: Duration = Duration.ofMinutes(30)
internal val FINISH_CHECK_RETRY: Duration = Duration.ofMinutes(10)

// Same five hours the backend watches a session for, counted from the expected end.
internal val FINISH_CHECK_WINDOW: Duration = Duration.ofHours(5)

internal const val FINISH_CHECK_REQUEST_CODE_BASE = 20_000
private const val FINISH_CHECK_SLOTS_PER_ROUND = 8

// The calendar only has start times. These are the usual real lengths, which can be
// shorter than the listed ones: a race is listed for two hours and rarely takes that long.
private val EXPECTED_LENGTH_MINUTES = mapOf(
    "FP1" to 60L,
    "FP2" to 60L,
    "FP3" to 60L,
    "SQ" to 45L,
    "SR" to 35L,
    "Q" to 60L,
    "R" to 105L,
)

data class PlannedFinishCheck(
    val requestCode: Int,
    val round: Int,
    val type: String,
    val triggerAt: Instant,
)

internal fun finishCheckRequestCode(round: Int, type: String): Int? {
    if (round !in 1..MAX_REMINDER_ROUND) {
        return null
    }
    val typeIndex = REMINDER_SESSION_TYPES.indexOfFirst { it.type == type }
    if (typeIndex < 0) {
        return null
    }
    return FINISH_CHECK_REQUEST_CODE_BASE + round * FINISH_CHECK_SLOTS_PER_ROUND + typeIndex
}

internal fun finishCheckRequestCodes(): List<Int> {
    return buildList {
        for (round in 1..MAX_REMINDER_ROUND) {
            for (sessionType in REMINDER_SESSION_TYPES) {
                add(finishCheckRequestCode(round, sessionType.type)!!)
            }
        }
    }
}

// The next check for one session. Repeats land on a fixed 10-minute grid after the first
// check, so planning again later gives the same times instead of sliding forward.
internal fun nextFinishCheck(type: String, start: Instant, now: Instant): Instant? {
    val minutes = EXPECTED_LENGTH_MINUTES[type] ?: return null
    val expectedEnd = start.plus(Duration.ofMinutes(minutes))
    val first = expectedEnd.plus(FINISH_CHECK_MARGIN)
    val last = expectedEnd.plus(FINISH_CHECK_WINDOW)
    val next = if (now.isBefore(first)) {
        first
    } else {
        val missed = Duration.between(first, now).toMillis() / FINISH_CHECK_RETRY.toMillis()
        first.plus(FINISH_CHECK_RETRY.multipliedBy(missed + 1))
    }
    return next.takeIf { it.isBefore(last) }
}

fun planFinishChecks(
    schedule: SeasonSchedule,
    preferences: ReminderPreferences,
    notified: Set<String>,
    now: Instant,
): List<PlannedFinishCheck> {
    if (!preferences.sessionFinished || schedule.season < FIRST_SESSION_FINISH_YEAR) {
        return emptyList()
    }
    val checks = mutableListOf<PlannedFinishCheck>()
    for (weekend in schedule.events) {
        for (session in weekend.sessions) {
            if (session.cancelled) {
                continue
            }
            if (sessionFinishKey(schedule.season, weekend.round, session.type) in notified) {
                continue
            }
            val requestCode = finishCheckRequestCode(weekend.round, session.type) ?: continue
            val triggerAt = nextFinishCheck(session.type, session.start, now) ?: continue
            checks += PlannedFinishCheck(requestCode, weekend.round, session.type, triggerAt)
        }
    }
    return checks.sortedWith(compareBy({ it.triggerAt }, { it.requestCode }))
}
