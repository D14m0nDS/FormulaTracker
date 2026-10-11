package com.formulatracker.schedule

import com.formulatracker.results.ResultsNotFound
import com.formulatracker.results.SessionResults
import com.formulatracker.results.parseSessionResults
import com.formulatracker.standings.SeasonStandings
import com.formulatracker.standings.parseStandings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

interface ScheduleSource {
    suspend fun fetch(year: Int): SeasonSchedule

    suspend fun fetchResults(year: Int, round: Int, sessionType: String): SessionResults

    suspend fun fetchStandings(year: Int): SeasonStandings
}

class HttpScheduleSource(
    private val baseUrl: String = EMULATOR_BACKEND,
) : ScheduleSource {
    override suspend fun fetch(year: Int): SeasonSchedule {
        return try {
            parseSchedule(get("/seasons/$year/schedule"))
        } catch (_: ResultsNotFound) {
            throw IOException("Schedule not found")
        }
    }

    override suspend fun fetchResults(year: Int, round: Int, sessionType: String): SessionResults {
        val body = get("/seasons/$year/rounds/$round/sessions/$sessionType/results")
        return parseSessionResults(body, sessionType)
    }

    override suspend fun fetchStandings(year: Int): SeasonStandings {
        return try {
            parseStandings(get("/seasons/$year/standings"))
        } catch (_: ResultsNotFound) {
            throw IOException("Standings not found")
        }
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.requestMethod = "GET"
        // Changing year cancels this coroutine. Disconnect so a slow 502 for the
        // year already left cannot stay open and later overwrite the screen.
        val cancel = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause != null) connection.disconnect()
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            ensureActive()
            if (code == 404) {
                throw ResultsNotFound()
            }
            if (code !in 200..299) {
                throw IOException("Request failed ($code)")
            }
            body
        } catch (error: IOException) {
            ensureActive()
            throw error
        } finally {
            cancel.dispose()
            connection.disconnect()
        }
    }

    private companion object {
        const val EMULATOR_BACKEND = "http://10.0.2.2:8000"
        const val TIMEOUT_MILLIS = 10_000
    }
}
