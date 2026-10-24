package com.formulatracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.formulatracker.drivers.DriverPhotos
import com.formulatracker.favorites.FavoriteSelection
import com.formulatracker.favorites.matchesDriver
import com.formulatracker.favorites.matchesTeam
import com.formulatracker.schedule.ScheduleSource
import com.formulatracker.standings.ConstructorStanding
import com.formulatracker.standings.DriverStanding
import com.formulatracker.standings.SeasonStandings
import com.formulatracker.teams.latestTeamColor
import com.formulatracker.teams.latestTeamName
import com.formulatracker.teams.teamColor
import com.formulatracker.ui.theme.Sizes
import com.formulatracker.ui.theme.Spacing
import com.formulatracker.ui.theme.numeric
import com.formulatracker.ui.theme.tabular
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal const val STANDINGS_GIVE_UP_MILLIS = 8_000L
private const val STANDINGS_RETRY_PAUSE_MILLIS = 1_000L
private const val STANDINGS_SETTLE_MILLIS = 400L

private val POINTS_COLUMN_WIDTH = 72.dp
private val TEAM_LOGO_SLOT_WIDTH = 52.dp

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
    // Held here, above the loading states, so a season change keeps the table the user was on.
    val pagerState = rememberPagerState { StandingsTable.entries.size }

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
            SkeletonList(rows = 10, label = "Loading standings")
        }
        is StandingsUiState.Failed -> {
            StatusMessage(
                title = current.message,
                icon = AppIcons.Warning,
                actionLabel = "Try again",
                onAction = { attempt += 1 },
            )
        }
        is StandingsUiState.Ready -> {
            StandingsList(
                standings = current.standings,
                pagerState = pagerState,
                favorite = favorite,
                photoUrls = photoUrls,
                photos = photos,
                teamColors = teamColors,
            )
        }
    }
}

@Composable
private fun StandingsList(
    standings: SeasonStandings,
    pagerState: PagerState,
    favorite: FavoriteSelection,
    photoUrls: Map<String, String?>,
    photos: DriverPhotos?,
    teamColors: Map<String, String>,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var headerHeight by remember { mutableIntStateOf(0) }
    // The control follows the pager, so it flips as soon as a swipe passes halfway.
    val table = StandingsTable.entries[pagerState.currentPage]
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The pager is at least as tall as the space under the header (three gaps of Spacing.md:
        // top, between, bottom), so a swipe also works beside a short table or message.
        val fillHeight = (maxHeight - with(density) { headerHeight.toDp() } - Spacing.md * 3)
            .coerceAtLeast(0.dp)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // No horizontal padding here: the pager runs edge to edge so a swiped table slides
            // off the screen, not under an invisible margin. The header adds its own.
            contentPadding = PaddingValues(vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .onSizeChanged { headerHeight = it.height }
                        .padding(horizontal = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    SectionLabel(text = standings.afterRound?.let { "After round $it" } ?: "Championship")
                    TableSwitch(
                        table = table,
                        onTable = { target ->
                            scope.launch { pagerState.animateScrollToPage(target.ordinal) }
                        },
                    )
                }
            }
            item(key = "table") {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = fillHeight),
                    contentPadding = PaddingValues(horizontal = Spacing.lg),
                    pageSpacing = Spacing.lg,
                    verticalAlignment = Alignment.Top,
                    key = { StandingsTable.entries[it] },
                ) { page ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = trackerCardColors(),
                        border = trackerCardBorder(),
                    ) {
                        when (StandingsTable.entries[page]) {
                            StandingsTable.Drivers -> {
                                DriversTable(standings, favorite, photoUrls, photos, teamColors)
                            }
                            StandingsTable.Constructors -> {
                                ConstructorsTable(standings, favorite, teamColors)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableSwitch(
    table: StandingsTable,
    onTable: (StandingsTable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = table == StandingsTable.Drivers,
            onClick = { onTable(StandingsTable.Drivers) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.heightIn(min = Sizes.touchTarget),
            colors = colors,
        ) {
            Text("Drivers")
        }
        SegmentedButton(
            selected = table == StandingsTable.Constructors,
            onClick = { onTable(StandingsTable.Constructors) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.heightIn(min = Sizes.touchTarget),
            colors = colors,
        ) {
            Text("Constructors")
        }
    }
}

@Composable
private fun DriversTable(
    standings: SeasonStandings,
    favorite: FavoriteSelection,
    photoUrls: Map<String, String?>,
    photos: DriverPhotos?,
    teamColors: Map<String, String>,
) {
    if (standings.drivers.isEmpty()) {
        Spacer(Modifier.height(Spacing.md))
        CardMessage("No drivers in this table")
        return
    }
    StandingsHeader(label = "Driver", leadingWidth = if (photos != null) Sizes.portrait else 0.dp)
    DividedColumn(standings.drivers) { row ->
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
            afterTitle = {
                TeamColorMark(
                    color = latestTeamColor(teamColors, row.teams),
                    team = latestTeamName(row.teams),
                )
            },
        )
    }
}

@Composable
private fun ConstructorsTable(
    standings: SeasonStandings,
    favorite: FavoriteSelection,
    teamColors: Map<String, String>,
) {
    if (standings.constructors.isEmpty()) {
        Spacer(Modifier.height(Spacing.md))
        CardMessage("No constructors in this table")
        return
    }
    StandingsHeader(label = "Constructor", leadingWidth = TEAM_LOGO_SLOT_WIDTH)
    DividedColumn(standings.constructors) { row ->
        StandingRow(
            position = row.positionText,
            title = row.team,
            detail = "",
            points = row.points,
            wins = row.wins,
            highlighted = favorite.matchesTeam(row.team),
            leading = {
                Box(modifier = Modifier.width(TEAM_LOGO_SLOT_WIDTH), contentAlignment = Alignment.Center) {
                    TeamColorMark(color = teamColor(teamColors, row.team), team = row.team)
                }
            },
            afterTitle = {},
        )
    }
}

@Composable
private fun StandingsHeader(label: String, leadingWidth: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        HeaderLabel("Pos", Modifier.width(POSITION_COLUMN_WIDTH), TextAlign.Center)
        if (leadingWidth > 0.dp) {
            Spacer(Modifier.width(leadingWidth))
        }
        HeaderLabel(label, Modifier.weight(1f), TextAlign.Start)
        HeaderLabel("Points", Modifier.widthIn(min = POINTS_COLUMN_WIDTH), TextAlign.End)
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
    afterTitle: @Composable () -> Unit,
) {
    val nameColor = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(favoriteTint(highlighted))
            .padding(horizontal = Spacing.lg, vertical = 6.dp)
            .heightIn(min = 52.dp)
            .semantics(mergeDescendants = true) {
                if (highlighted) stateDescription = "Favorite"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        PositionMark(position)
        leading()
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.SemiBold,
                    color = nameColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (highlighted) {
                    Icon(
                        AppIcons.Favorites,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                afterTitle()
            }
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            modifier = Modifier.widthIn(min = POINTS_COLUMN_WIDTH),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = pointsLabel(points),
                style = MaterialTheme.typography.titleSmall.numeric(),
                maxLines = 1,
            )
            Text(
                text = winsLabel(wins),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

internal fun winsLabel(wins: Int): String = if (wins == 1) "1 win" else "$wins wins"

internal fun pointsLabel(points: Double): String {
    return if (points == 1.0) "1 pt" else "${formatPoints(points)} pts"
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
