package com.formulatracker.schedule

import com.formulatracker.results.ResultsNotFound
import com.formulatracker.results.SessionResults
import com.formulatracker.results.parseSessionResults
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

interface ScheduleSource {
    fun fetch(year: Int): SeasonSchedule

    fun fetchResults(year: Int, round: Int, sessionType: String): SessionResults
}

class HttpScheduleSource(
    private val baseUrl: String = EMULATOR_BACKEND,
) : ScheduleSource {
    override fun fetch(year: Int): SeasonSchedule {
        return try {
            parseSchedule(get("/seasons/$year/schedule"))
        } catch (_: ResultsNotFound) {
            throw IOException("Schedule not found")
        }
    }

    override fun fetchResults(year: Int, round: Int, sessionType: String): SessionResults {
        val body = get("/seasons/$year/rounds/$round/sessions/$sessionType/results")
        return parseSessionResults(body, sessionType)
    }

    private fun get(path: String): String {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.requestMethod = "GET"
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 404) {
                throw ResultsNotFound()
            }
            if (code !in 200..299) {
                throw IOException("Request failed ($code)")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val EMULATOR_BACKEND = "http://10.0.2.2:8000"
        const val TIMEOUT_MILLIS = 10_000
    }
}
