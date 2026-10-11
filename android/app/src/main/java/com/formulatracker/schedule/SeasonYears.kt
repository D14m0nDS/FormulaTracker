package com.formulatracker.schedule

const val FIRST_SEASON_YEAR = 1950

fun seasonYears(currentYear: Int): List<Int> {
    val latest = maxOf(currentYear, FIRST_SEASON_YEAR)
    return (FIRST_SEASON_YEAR..latest).reversed().toList()
}
