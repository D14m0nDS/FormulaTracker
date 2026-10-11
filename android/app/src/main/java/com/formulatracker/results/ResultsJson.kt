package com.formulatracker.results

import com.formulatracker.schedule.ScheduleParseException
import org.json.JSONObject

private val QUALIFYING_TYPES = setOf("Q", "SQ")
private val RACE_TYPES = setOf("R", "SR")

fun parseSessionResults(json: String, sessionType: String): SessionResults {
    return when (sessionType) {
        in QUALIFYING_TYPES -> SessionResults.Qualifying(parseQualifying(json))
        in RACE_TYPES -> SessionResults.Race(parseRace(json))
        else -> throw ScheduleParseException("Results are not available for $sessionType")
    }
}

private fun parseQualifying(json: String): QualifyingResults {
    val root = JSONObject(json)
    val segmentCodes = stringList(root.optJSONArray("segment_codes"))
    val rowsJson = root.optJSONArray("results")
        ?: throw ScheduleParseException("Results are missing")
    return QualifyingResults(
        sessionType = root.optString("session_type"),
        sessionName = root.optString("session_name"),
        segmentCodes = segmentCodes,
        rows = buildList {
            for (index in 0 until rowsJson.length()) {
                add(parseQualifyingRow(rowsJson.getJSONObject(index), segmentCodes))
            }
        },
    )
}

private fun parseQualifyingRow(row: JSONObject, segmentCodes: List<String>): QualifyingRow {
    val driver = row.optJSONObject("driver")
    val byCode = segmentMap(row.optJSONArray("segments"))
    return QualifyingRow(
        positionText = row.optString("position_text"),
        code = driver?.optString("code").orEmpty(),
        team = row.optString("team"),
        status = row.optString("status").ifEmpty { null },
        segments = segmentCodes.map { code -> SegmentTime(code, byCode[code]) },
    )
}

private fun parseRace(json: String): RaceResults {
    val root = JSONObject(json)
    val rowsJson = root.optJSONArray("results")
        ?: throw ScheduleParseException("Results are missing")
    return RaceResults(
        sessionType = root.optString("session_type"),
        sessionName = root.optString("session_name"),
        rows = buildList {
            for (index in 0 until rowsJson.length()) {
                add(parseRaceRow(rowsJson.getJSONObject(index)))
            }
        },
    )
}

private fun parseRaceRow(row: JSONObject): RaceRow {
    val driver = row.optJSONObject("driver")
    return RaceRow(
        positionText = row.optString("position_text"),
        code = driver?.optString("code").orEmpty(),
        team = row.optString("team"),
        status = row.optString("status").ifEmpty { null },
        classified = row.optBoolean("classified", false),
        time = row.optionalString("time"),
        points = if (row.has("points") && !row.isNull("points")) row.getDouble("points") else null,
        grid = row.optionalInt("grid"),
    )
}

private fun segmentMap(segments: org.json.JSONArray?): Map<String, String?> {
    if (segments == null) {
        return emptyMap()
    }
    return buildMap {
        for (index in 0 until segments.length()) {
            val segment = segments.getJSONObject(index)
            put(segment.optString("code"), segment.optionalString("time"))
        }
    }
}

private fun stringList(array: org.json.JSONArray?): List<String> {
    if (array == null) {
        return emptyList()
    }
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index)
            if (value.isNotEmpty()) {
                add(value)
            }
        }
    }
}

private fun JSONObject.optionalString(key: String): String? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return optString(key).ifEmpty { null }
}

private fun JSONObject.optionalInt(key: String): Int? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return getInt(key)
}
