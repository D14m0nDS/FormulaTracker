package com.formulatracker.schedule

import org.json.JSONObject
import java.time.Instant

class ScheduleParseException(message: String) : Exception(message)

fun parseSchedule(json: String): SeasonSchedule {
    val root = JSONObject(json)
    val season = root.optInt("season", -1)
    if (season < 0) {
        throw ScheduleParseException("Schedule is missing a season")
    }
    val eventsJson = root.optJSONArray("events")
        ?: throw ScheduleParseException("Schedule is missing events")
    val events = buildList {
        for (index in 0 until eventsJson.length()) {
            add(parseWeekend(eventsJson.getJSONObject(index)))
        }
    }
    return SeasonSchedule(season, events)
}

private fun parseWeekend(event: JSONObject): RaceWeekend {
    val circuit = event.optJSONObject("circuit")
    val sessionsJson = event.optJSONArray("sessions")
    return RaceWeekend(
        round = event.optInt("round", 0),
        name = event.optString("name"),
        circuitName = circuit?.optString("name").orEmpty(),
        locality = circuit?.optString("locality").orEmpty(),
        country = circuit?.optString("country").orEmpty(),
        sessions = buildList {
            if (sessionsJson == null) {
                return@buildList
            }
            for (index in 0 until sessionsJson.length()) {
                parseSession(sessionsJson.getJSONObject(index))?.let(::add)
            }
        },
    )
}

private fun parseSession(session: JSONObject): ScheduledSession? {
    val startText = session.optString("start")
    if (startText.isEmpty()) {
        return null
    }
    val start = try {
        Instant.parse(startText)
    } catch (_: Exception) {
        throw ScheduleParseException("Session has an unreadable start time")
    }
    val timezone = session.optString("timezone").ifEmpty { null }
    return ScheduledSession(
        type = session.optString("type"),
        name = session.optString("name"),
        start = start,
        timezone = timezone,
        cancelled = session.optBoolean("cancelled", false),
    )
}
