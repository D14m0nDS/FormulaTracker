package com.formulatracker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.formulatracker.drivers.DriverPhotos
import com.formulatracker.favorites.FavoriteSelection
import com.formulatracker.favorites.matchesDriver
import com.formulatracker.favorites.matchesTeam
import com.formulatracker.results.PracticeResults
import com.formulatracker.results.QualifyingResults
import com.formulatracker.results.RaceResults
import com.formulatracker.results.RaceRow
import com.formulatracker.results.ResultsNotFound
import com.formulatracker.results.SegmentTime
import com.formulatracker.results.SessionResults
import com.formulatracker.schedule.RaceWeekend
import com.formulatracker.schedule.ScheduleSource
import com.formulatracker.teams.teamColor
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
    favorite: FavoriteSelection = FavoriteSelection(),
    photoUrls: Map<String, String?> = emptyMap(),
    photos: DriverPhotos? = null,
    teamColors: Map<String, String> = emptyMap(),
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(sessions, key = { it.type }) { session ->
            ResultSection(
                type = session.type,
                title = session.name,
                state = sections[session.type] ?: ResultSectionState.Loading,
                favorite = favorite,
                photoUrls = photoUrls,
                photos = photos,
                teamColors = teamColors,
            )
        }
    }
}

@Composable
private fun ResultSection(
    type: String,
    title: String,
    state: ResultSectionState,
    favorite: FavoriteSelection,
    photoUrls: Map<String, String?>,
    photos: DriverPhotos?,
    teamColors: Map<String, String>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(shortSessionLabel(type, title), style = MaterialTheme.typography.titleMedium)
            when (state) {
                ResultSectionState.Loading -> Text("Loading")
                ResultSectionState.Missing -> Text("No results yet")
                is ResultSectionState.Failed -> Text(state.message)
                is ResultSectionState.Ready -> when (val results = state.results) {
                    is SessionResults.Qualifying -> QualifyingTable(results.results, favorite, photoUrls, photos, teamColors)
                    is SessionResults.Practice -> PracticeTable(results.results, favorite, photoUrls, photos, teamColors)
                    is SessionResults.Race -> RaceTable(results.results, favorite, photoUrls, photos, teamColors)
                }
            }
        }
    }
}

@Composable
private fun QualifyingTable(
    results: QualifyingResults,
    favorite: FavoriteSelection,
    photoUrls: Map<String, String?>,
    photos: DriverPhotos?,
    teamColors: Map<String, String>,
) {
    DividedColumn(results.rows) { row ->
            val readout = qualifyingReadout(row.segments, row.status)
            ResultLine(
                position = row.positionText,
                code = row.code,
                highlighted = favorite.matches(row.code, row.team),
                photoUrl = photoUrls[row.code.uppercase()],
                photos = photos,
                teamColor = teamColor(teamColors, row.team),
                team = row.team,
                headline = readout.headline,
                note = readout.note,
            )
    }
}

@Composable
private fun PracticeTable(
    results: PracticeResults,
    favorite: FavoriteSelection,
    photoUrls: Map<String, String?>,
    photos: DriverPhotos?,
    teamColors: Map<String, String>,
) {
    DividedColumn(results.rows) { row ->
            val readout = practiceReadout(row.time, row.laps)
            ResultLine(
                position = row.positionText,
                code = row.code,
                highlighted = favorite.matches(row.code, row.team),
                photoUrl = photoUrls[row.code.uppercase()],
                photos = photos,
                teamColor = teamColor(teamColors, row.team),
                team = row.team,
                headline = readout.headline,
                note = readout.note,
            )
    }
}

@Composable
private fun RaceTable(
    results: RaceResults,
    favorite: FavoriteSelection,
    photoUrls: Map<String, String?>,
    photos: DriverPhotos?,
    teamColors: Map<String, String>,
) {
    val leader = raceLeader(results.rows)
    DividedColumn(results.rows) { row ->
            ResultLine(
                position = row.positionText,
                code = row.code,
                highlighted = favorite.matches(row.code, row.team),
                photoUrl = photoUrls[row.code.uppercase()],
                photos = photos,
                teamColor = teamColor(teamColors, row.team),
                team = row.team,
                headline = raceTimeLabel(row, leader),
                note = "",
                points = row.points?.let { "+${formatPoints(it)} pts." },
                places = placesMoved(row.grid, row.position, row.classified),
                showPoints = true,
            )
    }
}

@Composable
private fun <T> DividedColumn(items: List<T>, row: @Composable (T) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            row(item)
        }
    }
}

@Composable
private fun ResultLine(
    position: String,
    code: String,
    highlighted: Boolean,
    photoUrl: String?,
    photos: DriverPhotos?,
    teamColor: String?,
    team: String,
    headline: String,
    note: String,
    points: String? = null,
    places: Int? = null,
    showPoints: Boolean = false,
) {
    val nameColor = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = position.ifBlank { "—" },
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.titleSmall,
            color = nameColor,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        if (photos != null) {
            DriverPortrait(code = code, photoUrl = photoUrl, photos = photos)
        }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = code.ifBlank { "—" },
                style = MaterialTheme.typography.titleSmall,
                color = nameColor,
                maxLines = 1,
            )
            TeamColorMark(color = teamColor, team = team)
        }
        if (showPoints) {
            RaceClock(
                points = points,
                time = headline,
                places = places,
            )
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
                if (note.isNotEmpty()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
private fun RaceClock(points: String?, time: String, places: Int?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = points.orEmpty(),
            modifier = Modifier.width(92.dp),
            style = MaterialTheme.typography.titleSmall.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = time,
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 16.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                textAlign = TextAlign.End,
            )
            if (places != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (places == 0) PlacesDash() else PlacesTriangle(up = places > 0)
                    Text(
                        text = kotlin.math.abs(places).toString(),
                        modifier = Modifier.width(18.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            lineHeight = 12.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlacesTriangle(up: Boolean) {
    val color = if (up) Color(0xFF34A853) else Color(0xFFFF3B30)
    Canvas(modifier = Modifier.size(width = 10.dp, height = 6.dp)) {
        val path = Path().apply {
            if (up) {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
            }
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun PlacesDash() {
    Box(
        modifier = Modifier.size(width = 10.dp, height = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 10.dp, height = 2.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

internal data class ResultReadout(val headline: String, val note: String)

internal fun qualifyingReadout(segments: List<SegmentTime>, status: String?): ResultReadout {
    val timed = segments.filter { !it.time.isNullOrBlank() }
    val headline = timed.lastOrNull()?.time
    val earlier = timed.dropLast(1).joinToString("  ") { "${it.code} ${it.time}" }
    val flag = status?.takeIf { it.isNotBlank() && !it.equals("FINISHED", ignoreCase = true) }
    if (headline == null) {
        return ResultReadout(flag ?: "—", "")
    }
    val note = listOf(earlier, flag).filter { !it.isNullOrBlank() }.joinToString("  ")
    return ResultReadout(headline, note)
}

internal fun practiceReadout(time: String?, laps: Int?): ResultReadout {
    return ResultReadout(
        headline = time?.takeIf { it.isNotBlank() } ?: "—",
        note = laps?.let { "$it laps" }.orEmpty(),
    )
}

internal fun raceLeader(rows: List<RaceRow>): RaceRow? {
    return rows
        .filter { it.classified && !it.time.isNullOrBlank() }
        .minWithOrNull(
            compareByDescending<RaceRow> { it.laps ?: -1 }
                .thenBy { parseRaceClock(it.time.orEmpty()) ?: Long.MAX_VALUE },
        )
}

internal fun raceTimeLabel(row: RaceRow, leader: RaceRow?): String {
    if (!row.classified) {
        return row.status?.let { raceStatusLabel(it) } ?: "—"
    }
    if (leader == null || leader === row) {
        return row.time?.takeIf { it.isNotBlank() } ?: "—"
    }
    val leaderLaps = leader.laps
    val laps = row.laps
    if (leaderLaps != null && laps != null && laps < leaderLaps) {
        val behind = leaderLaps - laps
        return if (behind == 1) "+1 Lap" else "+$behind Laps"
    }
    val leaderClock = leader.time?.let { parseRaceClock(it) }
    val clock = row.time?.let { parseRaceClock(it) }
    if (leaderClock == null || clock == null) {
        return row.time?.takeIf { it.isNotBlank() } ?: "—"
    }
    return formatGap(clock - leaderClock)
}

internal fun placesMoved(grid: Int?, finish: Int?, classified: Boolean): Int? {
    if (!classified || grid == null || grid <= 0 || finish == null || finish <= 0) {
        return null
    }
    return grid - finish
}

internal fun parseRaceClock(value: String): Long? {
    val parts = value.trim().split(":")
    if (parts.size !in 2..3) {
        return null
    }
    val secondsPart = parts.last().split(".")
    if (secondsPart.size > 2) {
        return null
    }
    val seconds = secondsPart[0].toLongOrNull() ?: return null
    val millisText = if (secondsPart.size == 2) secondsPart[1] else "0"
    if (millisText.length > 3 || millisText.any { !it.isDigit() }) {
        return null
    }
    val millis = millisText.padEnd(3, '0').toLongOrNull() ?: return null
    val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
    val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
    if (hours < 0 || minutes !in 0..59 || seconds !in 0..59) {
        return null
    }
    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
}

internal fun formatGap(millis: Long): String {
    val sign = if (millis < 0) "-" else "+"
    val abs = kotlin.math.abs(millis)
    val minutes = abs / 60_000
    val seconds = (abs % 60_000) / 1000
    val fraction = abs % 1000
    val clock = if (minutes > 0) {
        "%d:%02d.%03d".format(minutes, seconds, fraction)
    } else {
        "%d.%03d".format(seconds, fraction)
    }
    return sign + clock
}

private fun raceStatusLabel(status: String): String {
    return status.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
}

private fun FavoriteSelection.matches(code: String, team: String): Boolean {
    return matchesDriver(code) || matchesTeam(team)
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
