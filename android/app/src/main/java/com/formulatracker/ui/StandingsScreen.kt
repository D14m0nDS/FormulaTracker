package com.formulatracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.formulatracker.ui.theme.tabular
import com.formulatracker.drivers.DriverPhotos
import com.formulatracker.favorites.FavoriteSelection
import com.formulatracker.favorites.matchesDriver
import com.formulatracker.favorites.matchesTeam
import com.formulatracker.schedule.ScheduleSource
import com.formulatracker.teams.latestTeamColor
import com.formulatracker.teams.latestTeamName
import com.formulatracker.teams.teamColor
import com.formulatracker.standings.ConstructorStanding
import com.formulatracker.standings.DriverStanding
import com.formulatracker.standings.SeasonStandings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal const val STANDINGS_GIVE_UP_MILLIS = 8_000L
private const val STANDINGS_RETRY_PAUSE_MILLIS = 1_000L
private const val STANDINGS_SETTLE_MILLIS = 400L

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
    favorite: FavoriteSelection = FavoriteSelection(),
    photoUrls: Map<String, String?> = emptyMap(),
    photos: DriverPhotos? = null,
    teamColors: Map<String, String> = emptyMap(),
) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<StandingsUiState>(StandingsUiState.Loading) }
    var table by remember { mutableStateOf(StandingsTable.Drivers) }

    LaunchedEffect(year, attempt) {
        state = StandingsUiState.Loading
        delay(STANDINGS_SETTLE_MILLIS)
        ensureActive()
        val startedAt = System.currentTimeMillis()
        while (true) {
            try {
                val standings = source.fetchStandings(year)
                ensureActive()
                state = StandingsUiState.Ready(standings)
                return@LaunchedEffect
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ensureActive()
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
            StatusMessage(title = "Loading standings", loading = true)
        }
        is StandingsUiState.Failed -> {
            StatusMessage(
                title = current.message,
                actionLabel = "Try again",
                onAction = { attempt += 1 },
            )
        }
        is StandingsUiState.Ready -> {
            StandingsList(
                standings = current.standings,
                table = table,
                favorite = favorite,
                photoUrls = photoUrls,
                photos = photos,
                teamColors = teamColors,
                onTable = { table = it },
            )
        }
    }
}

@Composable
private fun StandingsList(
    standings: SeasonStandings,
    table: StandingsTable,
    favorite: FavoriteSelection,
    photoUrls: Map<String, String?>,
    photos: DriverPhotos?,
    teamColors: Map<String, String>,
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
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = table == StandingsTable.Drivers,
                    onClick = { onTable(StandingsTable.Drivers) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text("Drivers")
                }
                SegmentedButton(
                    selected = table == StandingsTable.Constructors,
                    onClick = { onTable(StandingsTable.Constructors) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text("Constructors")
                }
            }
        }
        if (table == StandingsTable.Drivers) {
            if (standings.drivers.isEmpty()) {
                item { Text("No drivers in this table") }
            }
            itemsIndexed(standings.drivers, key = { index, _ -> "driver-$index" }) { _, row ->
                val highlighted = favorite.matchesDriver(row.code, row.familyName) ||
                    row.teams.any { favorite.matchesTeam(it) }
                StandingRow(
                    position = row.positionText,
                    title = row.code.ifEmpty { row.familyName },
                    detail = row.teams.joinToString(", "),
                    points = row.points,
                    wins = row.wins,
                    highlighted = highlighted,
                    leading = {
                        if (photos != null) {
                            DriverPortrait(
                                code = row.code.ifBlank { row.familyName },
                                photoUrl = photoUrls[row.code.uppercase()],
                                photos = photos,
                            )
                        }
                    },
                    mark = {
                        TeamColorMark(
                            color = latestTeamColor(teamColors, row.teams),
                            team = latestTeamName(row.teams),
                        )
                    },
                )
            }
        } else {
            if (standings.constructors.isEmpty()) {
                item { Text("No constructors in this table") }
            }
            itemsIndexed(standings.constructors, key = { index, _ -> "team-$index" }) { _, row ->
                StandingRow(
                    position = row.positionText,
                    title = row.team,
                    detail = "",
                    points = row.points,
                    wins = row.wins,
                    highlighted = favorite.matchesTeam(row.team),
                    leading = {},
                    mark = {
                        TeamColorMark(color = teamColor(teamColors, row.team), team = row.team)
                    },
                )
            }
        }
    }
}

@Composable
private fun StandingRow(
    position: String,
    title: String,
    detail: String,
    points: Double,
    wins: Int,
    highlighted: Boolean,
    leading: @Composable () -> Unit,
    mark: @Composable () -> Unit,
) {
    val nameColor = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                if (highlighted) stateDescription = "Favorite"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = position.ifBlank { "—" },
            modifier = Modifier.width(32.dp),
            style = MaterialTheme.typography.titleSmall.tabular(),
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
            color = nameColor,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        leading()
        mark()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${formatPoints(points)} pts",
                style = MaterialTheme.typography.titleSmall.tabular(),
                maxLines = 1,
            )
            Text(
                text = "$wins wins",
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
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
