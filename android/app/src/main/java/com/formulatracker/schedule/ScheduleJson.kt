package com.formulatracker.schedule

import org.json.JSONArray
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

fun scheduleToJson(schedule: SeasonSchedule): String {
    val events = JSONArray()
    for (weekend in schedule.events) {
        val sessions = JSONArray()
        for (session in weekend.sessions) {
            val sessionJson = JSONObject()
                .put("type", session.type)
                .put("name", session.name)
                .put("start", session.start.toString())
                .put("cancelled", session.cancelled)
            if (session.timezone != null) {
                sessionJson.put("timezone", session.timezone)
            }
            sessions.put(sessionJson)
        }
        events.put(
            JSONObject()
                .put("round", weekend.round)
                .put("name", weekend.name)
                .put(
                    "circuit",
                    JSONObject()
                        .put("name", weekend.circuitName)
                        .put("locality", weekend.locality)
                        .put("country", weekend.country),
                )
                .put("sessions", sessions),
        )
    }
    return JSONObject().put("season", schedule.season).put("events", events).toString()
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
