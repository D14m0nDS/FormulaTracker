package com.formulatracker.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.formulatracker.drivers.DriverPhotos
import com.formulatracker.favorites.FavoriteSelection
import com.formulatracker.favorites.matchesDriver
import com.formulatracker.favorites.matchesTeam
import com.formulatracker.schedule.ScheduleSource
import com.formulatracker.teams.singleTeamColor
import com.formulatracker.teams.singleTeamName
import com.formulatracker.teams.teamColor
import com.formulatracker.standings.DriverStanding
import com.formulatracker.standings.SeasonStandings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

private const val FAVORITES_GIVE_UP_MILLIS = 8_000L
private const val FAVORITES_RETRY_PAUSE_MILLIS = 1_000L

private sealed interface FavoritesLoad {
    data object Loading : FavoritesLoad
    data class Ready(val standings: SeasonStandings) : FavoritesLoad
    data object Failed : FavoritesLoad
}

@Composable
fun FavoritesScreen(
    year: Int,
    source: ScheduleSource,
    favorite: FavoriteSelection,
    photoUrls: Map<String, String?>,
    photos: DriverPhotos,
    teamColors: Map<String, String>,
    onFavorite: (FavoriteSelection) -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<FavoritesLoad>(FavoritesLoad.Loading) }

    LaunchedEffect(year, attempt) {
        state = FavoritesLoad.Loading
        val startedAt = System.currentTimeMillis()
        while (true) {
            try {
                val standings = source.fetchStandings(year)
                ensureActive()
                state = FavoritesLoad.Ready(standings)
                return@LaunchedEffect
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ensureActive()
                if (System.currentTimeMillis() - startedAt >= FAVORITES_GIVE_UP_MILLIS) {
                    state = FavoritesLoad.Failed
                    return@LaunchedEffect
                }
                delay(FAVORITES_RETRY_PAUSE_MILLIS)
            }
        }
    }

    when (val current = state) {
        FavoritesLoad.Loading -> Text("Loading drivers and teams", modifier = Modifier.padding(16.dp))
        FavoritesLoad.Failed -> {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Couldn't load drivers and teams")
                Button(onClick = { attempt += 1 }) {
                    Text("Try again")
                }
            }
        }
        is FavoritesLoad.Ready -> {
            FavoriteLists(current.standings, favorite, photoUrls, photos, teamColors, onFavorite)
        }
    }
}

@Composable
private fun FavoriteLists(
    standings: SeasonStandings,
    favorite: FavoriteSelection,
    photoUrls: Map<String, String?>,
    photos: DriverPhotos,
    teamColors: Map<String, String>,
    onFavorite: (FavoriteSelection) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Driver", style = MaterialTheme.typography.titleMedium) }
        item {
            ChoiceRow("None", selected = favorite.driverCode.isEmpty() && favorite.driverName.isEmpty()) {
                onFavorite(favorite.copy(driverCode = "", driverName = ""))
            }
        }
        itemsIndexed(standings.drivers, key = { index, _ -> "driver-$index" }) { _, driver ->
            ChoiceRow(
                label = driverChoiceLabel(driver),
                selected = favorite.matchesDriver(driver),
                portrait = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DriverPortrait(
                            code = driver.code.ifBlank { driver.familyName },
                            photoUrl = photoUrls[driver.code.uppercase()],
                            photos = photos,
                        )
                        TeamColorMark(
                            color = singleTeamColor(teamColors, driver.teams),
                            team = singleTeamName(driver.teams),
                        )
                    }
                },
            ) {
                onFavorite(
                    favorite.copy(
                        driverCode = driver.code,
                        driverName = driver.familyName.ifBlank { driver.code },
                    ),
                )
            }
        }
        item { Text("Team", style = MaterialTheme.typography.titleMedium) }
        item {
            ChoiceRow("None", selected = favorite.team.isEmpty()) {
                onFavorite(favorite.copy(team = ""))
            }
        }
        itemsIndexed(standings.constructors, key = { index, _ -> "team-$index" }) { _, constructor ->
            ChoiceRow(
                constructor.team,
                selected = favorite.matchesTeam(constructor.team),
                portrait = {
                    TeamColorMark(color = teamColor(teamColors, constructor.team), team = constructor.team)
                },
            ) {
                onFavorite(favorite.copy(team = constructor.team))
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    portrait: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        portrait?.invoke()
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun FavoriteSelection.matchesDriver(driver: DriverStanding): Boolean {
    return matchesDriver(driver.code, driver.familyName)
}

private fun driverChoiceLabel(driver: DriverStanding): String {
    val name = driver.code.ifEmpty { driver.familyName }
    val teams = driver.teams.filter { it.isNotEmpty() }.joinToString(", ")
    return if (teams.isEmpty()) name else "$name  $teams"
}
