package com.formulatracker.teams

data class SeasonTeams(
    val season: Int,
    val teams: List<TeamColor>,
)

data class TeamColor(
    val name: String,
    val color: String,
)
