package com.formulatracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.formulatracker.results.PracticeResults
import com.formulatracker.results.QualifyingResults
import com.formulatracker.results.RaceResults
import com.formulatracker.results.ResultsNotFound
import com.formulatracker.results.SessionResults
import com.formulatracker.schedule.RaceWeekend
import com.formulatracker.schedule.ScheduleSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val RESULT_TYPES = setOf("FP1", "FP2", "FP3", "SQ", "SR", "Q", "R")

sealed interface ResultSectionState {
    data object Loading : ResultSectionState
    data class Ready(val results: SessionResults) : ResultSectionState
    data object Missing : ResultSectionState
    data class Failed(val message: String) : ResultSectionState
}

@Composable
fun WeekendResultsScreen(
    year: Int,
    weekend: RaceWeekend,
    source: ScheduleSource,
) {
    val sessions = remember(weekend) { weekend.sessions.filter { it.type in RESULT_TYPES } }
    var sections by remember(weekend) {
        mutableStateOf(sessions.associate { it.type to ResultSectionState.Loading as ResultSectionState })
    }

    LaunchedEffect(year, weekend.round) {
        if (sessions.isEmpty()) {
            return@LaunchedEffect
        }
        sections = withContext(Dispatchers.IO) {
            coroutineScope {
                sessions.map { session ->
                    async {
                        session.type to loadSection(source, year, weekend.round, session.type)
                    }
                }.awaitAll().toMap()
            }
        }
    }

    if (sessions.isEmpty()) {
        Text("No results for this weekend", modifier = Modifier.padding(16.dp))
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(sessions, key = { it.type }) { session ->
            ResultSection(
                title = session.name,
                state = sections[session.type] ?: ResultSectionState.Loading,
            )
        }
    }
}

@Composable
private fun ResultSection(title: String, state: ResultSectionState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        when (state) {
            ResultSectionState.Loading -> Text("Loading")
            ResultSectionState.Missing -> Text("No results yet")
            is ResultSectionState.Failed -> Text(state.message)
            is ResultSectionState.Ready -> when (val results = state.results) {
                is SessionResults.Qualifying -> QualifyingTable(results.results)
                is SessionResults.Practice -> PracticeTable(results.results)
                is SessionResults.Race -> RaceTable(results.results)
            }
        }
    }
}

@Composable
private fun QualifyingTable(results: QualifyingResults) {
    results.rows.forEach { row ->
        val segments = row.segments.joinToString("  ") { segment ->
            val time = segment.time ?: "—"
            "${segment.code} $time"
        }
        val status = if (row.status != null && row.status != "FINISHED") "  ${row.status}" else ""
        Text("${row.positionText}  ${row.code}  ${row.team}  $segments$status")
    }
}

@Composable
private fun PracticeTable(results: PracticeResults) {
    results.rows.forEach { row ->
        val time = row.time?.let { "  $it" }.orEmpty()
        val laps = row.laps?.let { "  $it laps" }.orEmpty()
        Text("${row.positionText}  ${row.code}  ${row.team}$time$laps")
    }
}

@Composable
private fun RaceTable(results: RaceResults) {
    results.rows.forEach { row ->
        val detail = when {
            row.classified && row.time != null -> row.time
            row.status != null -> row.status
            else -> ""
        }
        val points = row.points?.let { "  ${formatPoints(it)} pts" }.orEmpty()
        val grid = row.grid?.let { "  grid $it" }.orEmpty()
        Text("${row.positionText}  ${row.code}  ${row.team}  $detail$points$grid")
    }
}

private suspend fun loadSection(
    source: ScheduleSource,
    year: Int,
    round: Int,
    sessionType: String,
): ResultSectionState {
    return try {
        ResultSectionState.Ready(source.fetchResults(year, round, sessionType))
    } catch (_: ResultsNotFound) {
        ResultSectionState.Missing
    } catch (error: Exception) {
        ResultSectionState.Failed(error.message ?: "Couldn't load results")
    }
}

internal fun formatPoints(points: Double): String {
    return if (points % 1.0 == 0.0) points.toInt().toString() else points.toString()
}
