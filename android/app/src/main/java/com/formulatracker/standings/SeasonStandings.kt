package com.formulatracker.standings

data class SeasonStandings(
    val season: Int,
    val afterRound: Int?,
    val drivers: List<DriverStanding>,
    val constructors: List<ConstructorStanding>,
)

data class DriverStanding(
    val positionText: String,
    val code: String,
    val familyName: String,
    val points: Double,
    val wins: Int,
    val teams: List<String>,
)

data class ConstructorStanding(
    val positionText: String,
    val team: String,
    val points: Double,
    val wins: Int,
)
