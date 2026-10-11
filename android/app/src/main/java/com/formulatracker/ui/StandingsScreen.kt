package com.formulatracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.formulatracker.schedule.ScheduleSource
import com.formulatracker.standings.ConstructorStanding
import com.formulatracker.standings.DriverStanding
import com.formulatracker.standings.SeasonStandings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal const val STANDINGS_GIVE_UP_MILLIS = 8_000L
private const val STANDINGS_RETRY_PAUSE_MILLIS = 1_000L

internal fun standingsStillLoading(startedAtMillis: Long, nowMillis: Long): Boolean {
    return nowMillis - startedAtMillis < STANDINGS_GIVE_UP_MILLIS
}

private enum class StandingsTable {
    Drivers,
    Constructors,
}

private sealed interface StandingsUiState {
    data object Loading : StandingsUiState
    data class Ready(val standings: SeasonStandings) : StandingsUiState
    data class Failed(val message: String) : StandingsUiState
}

@Composable
fun StandingsScreen(
    year: Int,
    source: ScheduleSource,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<StandingsUiState>(StandingsUiState.Loading) }
    var table by remember { mutableStateOf(StandingsTable.Drivers) }

    LaunchedEffect(year, attempt) {
        state = StandingsUiState.Loading
        val startedAt = System.currentTimeMillis()
        while (true) {
            try {
                state = StandingsUiState.Ready(withContext(Dispatchers.IO) { source.fetchStandings(year) })
                return@LaunchedEffect
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!standingsStillLoading(startedAt, System.currentTimeMillis())) {
                    state = StandingsUiState.Failed("Couldn't load standings")
                    return@LaunchedEffect
                }
                delay(STANDINGS_RETRY_PAUSE_MILLIS)
            }
        }
    }

    when (val current = state) {
        StandingsUiState.Loading -> {
            Text("Loading standings", modifier = Modifier.padding(16.dp))
        }
        is StandingsUiState.Failed -> {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(current.message)
                Button(onClick = { attempt += 1 }) {
                    Text("Try again")
                }
            }
        }
        is StandingsUiState.Ready -> {
            StandingsList(
                standings = current.standings,
                table = table,
                onTable = { table = it },
            )
        }
    }
}

@Composable
private fun StandingsList(
    standings: SeasonStandings,
    table: StandingsTable,
    onTable: (StandingsTable) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = standings.afterRound?.let { "After round $it" } ?: "Championship",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TableButton("Drivers", table == StandingsTable.Drivers) {
                    onTable(StandingsTable.Drivers)
                }
                TableButton("Constructors", table == StandingsTable.Constructors) {
                    onTable(StandingsTable.Constructors)
                }
            }
        }
        if (table == StandingsTable.Drivers) {
            if (standings.drivers.isEmpty()) {
                item { Text("No drivers in this table") }
            }
            itemsIndexed(standings.drivers, key = { index, _ -> "driver-$index" }) { _, row ->
                Text(driverLine(row))
            }
        } else {
            if (standings.constructors.isEmpty()) {
                item { Text("No constructors in this table") }
            }
            itemsIndexed(standings.constructors, key = { index, _ -> "team-$index" }) { _, row ->
                Text(constructorLine(row))
            }
        }
    }
}

@Composable
private fun TableButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) {
            Text(label)
        }
    } else {
        TextButton(onClick = onClick) {
            Text(label)
        }
    }
}

internal fun driverLine(row: DriverStanding): String {
    val teams = row.teams.joinToString(", ")
    val teamText = if (teams.isEmpty()) "" else "  $teams"
    val name = row.code.ifEmpty { row.familyName }
    return "${row.positionText}  $name  ${formatPoints(row.points)} pts  ${row.wins} wins$teamText"
}

internal fun constructorLine(row: ConstructorStanding): String {
    return "${row.positionText}  ${row.team}  ${formatPoints(row.points)} pts  ${row.wins} wins"
}
