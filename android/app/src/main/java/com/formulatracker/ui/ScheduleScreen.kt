package com.formulatracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.formulatracker.schedule.HttpScheduleSource
import com.formulatracker.schedule.RaceWeekend
import com.formulatracker.schedule.ScheduleSource
import com.formulatracker.schedule.ScheduledSession
import com.formulatracker.schedule.SeasonSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

sealed interface ScheduleUiState {
    data object Loading : ScheduleUiState
    data class Ready(val schedule: SeasonSchedule) : ScheduleUiState
    data class Failed(val message: String) : ScheduleUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(source: ScheduleSource = HttpScheduleSource()) {
    val year = remember { Year.now().value }
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ScheduleUiState>(ScheduleUiState.Loading) }

    LaunchedEffect(year, attempt) {
        state = ScheduleUiState.Loading
        state = try {
            ScheduleUiState.Ready(withContext(Dispatchers.IO) { source.fetch(year) })
        } catch (error: Exception) {
            ScheduleUiState.Failed(error.message ?: "Couldn't load the schedule")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(titleFor(state, year)) })
        },
    ) { innerPadding ->
        ScheduleContent(
            state = state,
            onRetry = { attempt += 1 },
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
fun ScheduleContent(
    state: ScheduleUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        ScheduleUiState.Loading -> {
            Text("Loading schedule", modifier = modifier.padding(16.dp))
        }
        is ScheduleUiState.Failed -> {
            Column(
                modifier = modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.message)
                Button(onClick = onRetry) {
                    Text("Try again")
                }
            }
        }
        is ScheduleUiState.Ready -> {
            if (state.schedule.events.isEmpty()) {
                Text("No races on this calendar", modifier = modifier.padding(16.dp))
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(state.schedule.events, key = { it.round }) { weekend ->
                        WeekendSection(weekend)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekendSection(weekend: RaceWeekend) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Round ${weekend.round} · ${weekend.name}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = circuitLine(weekend),
            style = MaterialTheme.typography.bodyMedium,
        )
        weekend.sessions.forEach { session ->
            SessionRow(session)
        }
    }
}

@Composable
private fun SessionRow(session: ScheduledSession) {
    val time = formatSessionStart(session.start, ZoneId.systemDefault(), Locale.getDefault())
    val label = if (session.cancelled) "${session.name} · Cancelled" else session.name
    Text(
        text = "$label · $time",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
}

internal fun formatSessionStart(start: Instant, zone: ZoneId, locale: Locale): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
    return formatter.format(start.atZone(zone))
}

private fun titleFor(state: ScheduleUiState, requestedYear: Int): String {
    val season = (state as? ScheduleUiState.Ready)?.schedule?.season ?: requestedYear
    return "$season season"
}

private fun circuitLine(weekend: RaceWeekend): String {
    val place = listOf(weekend.locality, weekend.country).filter { it.isNotEmpty() }.joinToString(", ")
    return when {
        weekend.circuitName.isEmpty() -> place
        place.isEmpty() -> weekend.circuitName
        else -> "${weekend.circuitName} · $place"
    }
}
