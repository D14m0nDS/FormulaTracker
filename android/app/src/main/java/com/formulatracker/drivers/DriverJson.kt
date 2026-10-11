package com.formulatracker.drivers

import org.json.JSONObject

class DriverParseException(message: String) : Exception(message)

fun parseDrivers(json: String): DriverDirectory {
    val root = JSONObject(json)
    val season = root.optInt("season", -1)
    if (season < 0) {
        throw DriverParseException("Driver list is missing a season")
    }
    val driversJson = root.optJSONArray("drivers")
        ?: throw DriverParseException("Driver list is missing drivers")
    val drivers = buildList {
        for (index in 0 until driversJson.length()) {
            val driver = driversJson.optJSONObject(index) ?: continue
            val code = driver.optString("code").trim()
            if (code.isEmpty()) {
                continue
            }
            val url = driver.optString("photo_url").trim().ifEmpty { null }
            add(DriverPhoto(code = code, name = driver.optString("name").ifBlank { code }, photoUrl = url))
        }
    }
    return DriverDirectory(season, drivers)
}
