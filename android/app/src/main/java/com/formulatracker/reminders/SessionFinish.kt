package com.formulatracker.reminders

import org.json.JSONObject
import java.time.Instant

internal const val FIRST_SESSION_FINISH_YEAR = 2023
internal const val SESSION_FINISH_NOTIFICATION_BASE = 40_000

data class FinishedSession(
    val round: Int,
    val eventName: String,
    val type: String,
    val name: String,
    val listedEnd: Instant,
    val finishedAt: Instant,
)

data class SessionFinishWatch(
    val season: Int,
    val sessions: List<FinishedSession>,
)

data class SessionFinishNotice(
    val notificationId: Int,
    val title: String,
    val text: String,
    val key: String,
)

data class SessionFinishPlan(
    val notices: List<SessionFinishNotice>,
    val notified: Set<String>,
)

class SessionFinishParseException(message: String) : Exception(message)

fun parseSessionFinish(json: String): SessionFinishWatch {
    val root = try {
        JSONObject(json)
    } catch (_: Exception) {
        throw SessionFinishParseException("Session finish is not readable")
    }
    val season = root.optInt("season", -1)
    if (season < 0) {
        throw SessionFinishParseException("Session finish is missing a season")
    }
    val sessionsJson = root.optJSONArray("sessions")
        ?: throw SessionFinishParseException("Session finish is missing sessions")
    val sessions = buildList {
        for (index in 0 until sessionsJson.length()) {
            add(parseFinishedSession(sessionsJson.optJSONObject(index)))
        }
    }
    return SessionFinishWatch(season, sessions)
}

fun planSessionFinishNotices(
    watch: SessionFinishWatch?,
    alreadyNotified: Set<String>,
): SessionFinishPlan {
    if (watch == null || watch.season < FIRST_SESSION_FINISH_YEAR) {
        return SessionFinishPlan(emptyList(), alreadyNotified)
    }
    val notices = watch.sessions.mapNotNull { session ->
        val key = sessionFinishKey(watch.season, session.round, session.type)
        if (key in alreadyNotified) {
            return@mapNotNull null
        }
        val notificationId = sessionFinishNotificationId(session.round, session.type) ?: return@mapNotNull null
        val label = session.name.ifBlank { labelFor(session.type) }
        SessionFinishNotice(
            notificationId = notificationId,
            title = session.eventName.ifBlank { "Round ${session.round}" },
            text = "$label has finished",
            key = key,
        )
    }.sortedBy { it.notificationId }
    return SessionFinishPlan(notices, alreadyNotified + notices.map { it.key })
}

internal fun sessionFinishNotificationId(round: Int, type: String): Int? {
    if (round !in 1..MAX_REMINDER_ROUND) {
        return null
    }
    val typeIndex = REMINDER_SESSION_TYPES.indexOfFirst { it.type == type }
    val slot = if (typeIndex >= 0) typeIndex else REMINDER_SESSION_TYPES.size
    return SESSION_FINISH_NOTIFICATION_BASE + round * 16 + slot
}

internal fun sessionFinishKey(season: Int, round: Int, type: String): String {
    return "$season:$round:$type"
}

private fun parseFinishedSession(session: JSONObject?): FinishedSession {
    if (session == null) {
        throw SessionFinishParseException("Session finish is missing a session")
    }
    val round = session.optInt("round", 0)
    val type = session.optString("session_type")
    if (round !in 1..MAX_REMINDER_ROUND || type.isEmpty()) {
        throw SessionFinishParseException("Session finish is missing a round")
    }
    return FinishedSession(
        round = round,
        eventName = session.optString("event_name"),
        type = type,
        name = session.optString("session_name"),
        listedEnd = parseInstant(session.optString("listed_end")),
        finishedAt = parseInstant(session.optString("finished_at")),
    )
}

private fun parseInstant(value: String): Instant {
    if (value.isEmpty()) {
        throw SessionFinishParseException("Session finish is missing a time")
    }
    return try {
        Instant.parse(value)
    } catch (_: Exception) {
        throw SessionFinishParseException("Session finish has an unreadable time")
    }
}

private fun labelFor(type: String): String {
    return REMINDER_SESSION_TYPES.firstOrNull { it.type == type }?.label ?: type
}
