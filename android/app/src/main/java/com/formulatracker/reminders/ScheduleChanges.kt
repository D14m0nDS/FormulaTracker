package com.formulatracker.reminders

import com.formulatracker.schedule.ScheduledSession
import com.formulatracker.schedule.SeasonSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class ScheduleRefreshPlan(
    val changes: List<ScheduleChange>,
    val schedule: SeasonSchedule?,
)

fun planScheduleRefresh(
    previous: SeasonSchedule?,
    fetched: SeasonSchedule?,
    now: Instant,
    zone: ZoneId,
    locale: Locale,
): ScheduleRefreshPlan {
    if (fetched == null) {
        return ScheduleRefreshPlan(emptyList(), null)
    }
    return ScheduleRefreshPlan(
        changes = scheduleChanges(previous, fetched, now, zone, locale),
        schedule = fetched,
    )
}

data class ScheduleChange(
    val notificationId: Int,
    val title: String,
    val text: String,
)

internal const val SCHEDULE_CHANGE_NOTIFICATION_BASE = 20_000

private data class SessionKey(val round: Int, val type: String)

private data class IndexedSession(val title: String, val session: ScheduledSession)

internal fun scheduleChangeNotificationId(round: Int, type: String): Int {
    val typeIndex = REMINDER_SESSION_TYPES.indexOfFirst { it.type == type }
    val slot = if (typeIndex >= 0) typeIndex else REMINDER_SESSION_TYPES.size
    return SCHEDULE_CHANGE_NOTIFICATION_BASE + round * 16 + slot
}

fun scheduleChanges(
    previous: SeasonSchedule?,
    current: SeasonSchedule,
    now: Instant,
    zone: ZoneId,
    locale: Locale,
): List<ScheduleChange> {
    if (previous == null || previous.season != current.season) {
        return emptyList()
    }
    val before = index(previous)
    val after = index(current)
    return before.keys.intersect(after.keys).mapNotNull { key ->
        val old = before.getValue(key)
        val new = after.getValue(key)
        val text = changeText(old.session, new.session, now, zone, locale) ?: return@mapNotNull null
        ScheduleChange(
            notificationId = scheduleChangeNotificationId(key.round, key.type),
            title = new.title,
            text = text,
        )
    }.sortedBy { it.notificationId }
}

private fun index(schedule: SeasonSchedule): Map<SessionKey, IndexedSession> {
    val indexed = linkedMapOf<SessionKey, IndexedSession>()
    for (weekend in schedule.events) {
        val title = weekend.name.ifBlank { "Round ${weekend.round}" }
        for (session in weekend.sessions) {
            indexed[SessionKey(weekend.round, session.type)] = IndexedSession(title, session)
        }
    }
    return indexed
}

private fun changeText(
    old: ScheduledSession,
    new: ScheduledSession,
    now: Instant,
    zone: ZoneId,
    locale: Locale,
): String? {
    val label = new.name.ifBlank { labelFor(new.type) }
    if (old.cancelled != new.cancelled) {
        if (new.cancelled) {
            if (!old.start.isAfter(now)) {
                return null
            }
            return "$label is now cancelled"
        }
        if (!new.start.isAfter(now)) {
            return null
        }
        return "$label is scheduled again for ${formatStart(new.start, zone, locale)}"
    }
    if (new.cancelled || old.start == new.start) {
        return null
    }
    if (!old.start.isAfter(now) && !new.start.isAfter(now)) {
        return null
    }
    return "$label has a new scheduled start of ${formatStart(new.start, zone, locale)}"
}

private fun labelFor(type: String): String {
    return REMINDER_SESSION_TYPES.firstOrNull { it.type == type }?.label ?: type
}

private fun formatStart(start: Instant, zone: ZoneId, locale: Locale): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
    return formatter.format(start.atZone(zone))
}
