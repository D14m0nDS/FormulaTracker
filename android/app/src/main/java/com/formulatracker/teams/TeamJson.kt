package com.formulatracker.teams

import org.json.JSONObject

class TeamParseException(message: String) : Exception(message)

fun parseTeams(json: String): SeasonTeams {
    val root = JSONObject(json)
    val season = root.optInt("season", -1)
    if (season < 0) {
        throw TeamParseException("Team list is missing a season")
    }
    val teamsJson = root.optJSONArray("teams")
        ?: throw TeamParseException("Team list is missing teams")
    val teams = buildList {
        for (index in 0 until teamsJson.length()) {
            val team = teamsJson.optJSONObject(index) ?: continue
            val name = team.optString("name").trim()
            val color = team.optString("color").trim()
            if (name.isEmpty() || !COLOR.matches(color)) {
                continue
            }
            add(TeamColor(name = name, color = color))
        }
    }
    return SeasonTeams(season, teams)
}

fun teamColor(colors: Map<String, String>, name: String): String? {
    return colors[name.trim().uppercase()]
}

fun latestTeamName(teams: List<String>): String? {
    return teams.map { it.trim() }.lastOrNull { it.isNotEmpty() }
}

fun latestTeamColor(colors: Map<String, String>, teams: List<String>): String? {
    val name = latestTeamName(teams) ?: return null
    return teamColor(colors, name)
}

private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
