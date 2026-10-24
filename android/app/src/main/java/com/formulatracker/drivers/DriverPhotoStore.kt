package com.formulatracker.drivers

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

// OpenF1 refuses requests while a session is live and for 30 minutes after it, so the backend
// cannot always return photo links. The phone keeps the last list that loaded for each season and
// shows it when a request fails. The image files themselves are already cached by DriverPhotos.
class DriverPhotoStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(year: Int): Map<String, String> {
        return decodePhotoUrls(preferences.getString(key(year), null))
    }

    fun write(year: Int, urls: Map<String, String>) {
        preferences.edit().putString(key(year), encodePhotoUrls(urls)).apply()
    }

    private fun key(year: Int) = "urls_$year"

    private companion object {
        const val PREFS_NAME = "driver_photo_urls"
    }
}

internal class PhotoUrlChoice(val urls: Map<String, String>, val save: Boolean)

// A list that loaded with at least one photo link replaces the saved one. A failed request or a
// list with no links (a season before 2023, or no finished race yet) leaves the saved one in use.
internal fun choosePhotoUrls(fetched: DriverDirectory?, saved: Map<String, String>): PhotoUrlChoice {
    val urls = buildMap {
        fetched?.drivers?.forEach { driver ->
            val url = driver.photoUrl
            if (!url.isNullOrBlank()) {
                put(driver.code.uppercase(), url)
            }
        }
    }
    return if (urls.isEmpty()) PhotoUrlChoice(saved, save = false) else PhotoUrlChoice(urls, save = true)
}

internal fun encodePhotoUrls(urls: Map<String, String>): String {
    val json = JSONObject()
    urls.forEach { (code, url) ->
        if (url.isNotBlank()) {
            json.put(code, url)
        }
    }
    return json.toString()
}

internal fun decodePhotoUrls(text: String?): Map<String, String> {
    if (text.isNullOrBlank()) {
        return emptyMap()
    }
    val json = try {
        JSONObject(text)
    } catch (_: JSONException) {
        return emptyMap()
    }
    return buildMap {
        json.keys().forEach { code ->
            val url = json.optString(code).trim()
            if (url.isNotEmpty()) {
                put(code, url)
            }
        }
    }
}
