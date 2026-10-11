package com.formulatracker.favorites

data class FavoriteSelection(
    val driverCode: String = "",
    val driverName: String = "",
    val team: String = "",
)

fun FavoriteSelection.matchesDriver(code: String, familyName: String = ""): Boolean {
    if (driverCode.isNotEmpty() && code.equals(driverCode, ignoreCase = true)) {
        return true
    }
    return driverCode.isEmpty() &&
        driverName.isNotEmpty() &&
        familyName.equals(driverName, ignoreCase = true)
}

fun FavoriteSelection.matchesTeam(teamName: String): Boolean {
    return team.isNotEmpty() && teamName.equals(team, ignoreCase = true)
}

fun withFavorite(text: String, favorite: FavoriteSelection): String {
    val driver = favorite.driverName.ifBlank { favorite.driverCode }
    val names = listOf(driver, favorite.team).filter { it.isNotBlank() }
    if (names.isEmpty()) {
        return text
    }
    return "$text. Favorite: ${names.joinToString(", ")}."
}
