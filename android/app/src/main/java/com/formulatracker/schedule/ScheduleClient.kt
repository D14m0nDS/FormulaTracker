package com.formulatracker.schedule

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

fun interface ScheduleSource {
    fun fetch(year: Int): SeasonSchedule
}

class HttpScheduleSource(
    private val baseUrl: String = EMULATOR_BACKEND,
) : ScheduleSource {
    override fun fetch(year: Int): SeasonSchedule {
        val connection = URL("$baseUrl/seasons/$year/schedule").openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.requestMethod = "GET"
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("Schedule request failed ($code)")
            }
            return parseSchedule(body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val EMULATOR_BACKEND = "http://10.0.2.2:8000"
        const val TIMEOUT_MILLIS = 10_000
    }
}
