package com.formulatracker.standings

import com.formulatracker.schedule.ScheduleParseException
import org.json.JSONObject

fun parseStandings(json: String): SeasonStandings {
    val root = JSONObject(json)
    if (!root.has("season")) {
        throw ScheduleParseException("Standings are missing")
    }
    val drivers = root.optJSONArray("drivers")
        ?: throw ScheduleParseException("Standings are missing")
    val constructors = root.optJSONArray("constructors")
        ?: throw ScheduleParseException("Standings are missing")
    return SeasonStandings(
        season = root.getInt("season"),
        afterRound = root.optionalInt("after_round"),
        drivers = buildList {
            for (index in 0 until drivers.length()) {
                add(parseDriver(drivers.getJSONObject(index)))
            }
        },
        constructors = buildList {
            for (index in 0 until constructors.length()) {
                add(parseConstructor(constructors.getJSONObject(index)))
            }
        },
    )
}

private fun parseDriver(row: JSONObject): DriverStanding {
    val driver = row.optJSONObject("driver")
    val teams = row.optJSONArray("teams")
    return DriverStanding(
        positionText = row.optString("position_text"),
        code = driver?.optString("code").orEmpty(),
        points = row.getDouble("points"),
        wins = row.getInt("wins"),
        teams = buildList {
            if (teams == null) {
                return@buildList
            }
            for (index in 0 until teams.length()) {
                val name = teams.optString(index)
                if (name.isNotEmpty()) {
                    add(name)
                }
            }
        },
    )
}

private fun parseConstructor(row: JSONObject): ConstructorStanding {
    return ConstructorStanding(
        positionText = row.optString("position_text"),
        team = row.optString("team"),
        points = row.getDouble("points"),
        wins = row.getInt("wins"),
    )
}

private fun JSONObject.optionalInt(key: String): Int? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return getInt(key)
}
