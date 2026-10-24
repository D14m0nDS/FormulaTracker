package com.formulatracker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.formulatracker.schedule.ScheduledSession
import com.formulatracker.teams.teamColor
import com.formulatracker.ui.theme.Sizes
import com.formulatracker.ui.theme.Spacing
import com.formulatracker.ui.theme.numeric
import com.formulatracker.ui.theme.overline
import com.formulatracker.ui.theme.trackerColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private val RESULT_TYPES = setOf("FP1", "FP2", "FP3", "SQ", "SR", "Q", "R")

private val TIME_COLUMN_WIDTH = 92.dp
private val POINTS_COLUMN_WIDTH = 64.dp

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
    var reload by remember(weekend) { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(
        initialPage = remember(sessions) {
            sessions.indexOfFirst { it.type == defaultResultType(sessions, Instant.now()) }.coerceAtLeast(0)
        },
        pageCount = { sessions.size },
    )
    val pagerScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(year, weekend.round, reload) {
        // The first run loads everything. A retry only asks again for sessions that failed.
        val pending = sessions.filter { session ->
            val state = sections[session.type]
            state == null || state is ResultSectionState.Loading || state is ResultSectionState.Failed
        }
        if (pending.isEmpty()) {
            return@LaunchedEffect
        }
        if (reload > 0) {
            sections = sections + pending.associate { it.type to ResultSectionState.Loading }
        }
        val loaded = withContext(Dispatchers.IO) {
            coroutineScope {
                pending.map { session ->
                    async {
                        session.type to loadSection(source, year, weekend.round, session.type)
                    }
                }.awaitAll().toMap()
            }
        }
        sections = sections + loaded
    }

    if (sessions.isEmpty()) {
        StatusMessage(title = "No results for this weekend", icon = AppIcons.Flag)
        return
    }

    val current = sessions[pagerState.currentPage.coerceIn(0, sessions.lastIndex)]
    val tabbed = sessions.size > 1
    LaunchedEffect(pagerState.settledPage) {
        // Keep the tab row pinned and the new session's first row right below it. This waits
        // for the swipe to settle so the list does not jump while the finger is still down.
        if (tabbed && listState.firstVisibleItemIndex >= 1) {
            listState.scrollToItem(1)
        }
    }

    val density = LocalDensity.current
    var summaryHeight by remember { mutableIntStateOf(0) }
    var tabsHeight by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The pager is at least as tall as the space left under the summary and tabs, so a swipe
        // also works on the empty part of a short page such as "No results yet".
        val fillHeight = (maxHeight - with(density) { (summaryHeight + tabsHeight).toDp() } - Spacing.xl)
            .coerceAtLeast(0.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.xl),
        ) {
            item(key = "weekend") {
                Box(modifier = Modifier.onSizeChanged { summaryHeight = it.height }) {
                    WeekendSummary(
                        weekend = weekend,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    )
                }
            }
            if (tabbed) {
                stickyHeader(key = "sessions") {
                    SessionTabs(
                        sessions = sessions,
                        selected = current.type,
                        onSelect = { type ->
                            val page = sessions.indexOfFirst { it.type == type }
                            pagerScope.launch { pagerState.animateScrollToPage(page) }
                        },
                        modifier = Modifier.onSizeChanged { tabsHeight = it.height },
                    )
                }
            }
            item(key = "results") {
                // Edge to edge, so a swiped session slides off the screen instead of under a margin.
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = fillHeight)
                        .padding(vertical = Spacing.md),
                    contentPadding = PaddingValues(horizontal = Spacing.lg),
                    pageSpacing = Spacing.lg,
                    verticalAlignment = Alignment.Top,
                    key = { sessions[it].type },
                ) { page ->
                    val session = sessions[page]
                    ResultSection(
                        session = session,
                        state = sections[session.type] ?: ResultSectionState.Loading,
                        favorite = favorite,
                        photoUrls = photoUrls,
                        photos = photos,
                        teamColors = teamColors,
                        onRetry = { reload += 1 },
                    )
                }
            }
        }
    }
}

// Opens on the latest session that has already started, so a finished weekend lands on
// the race and a live one on whatever has just run. A weekend that has not begun starts
// on its first session.
internal fun defaultResultType(sessions: List<ScheduledSession>, now: Instant): String? {
    val started = sessions.filter { !it.cancelled && !it.start.isAfter(now) }
    return (started.maxByOrNull { it.start } ?: sessions.firstOrNull())?.type
}

@Composable
private fun WeekendSummary(weekend: RaceWeekend, modifier: Modifier = Modifier) {
    val photo = circuitImageRes(weekend.circuitName)
    val place = circuitLine(weekend)
    val dates = weekendDateRange(weekend.sessions.map { it.start }, ZoneId.systemDefault())
    if (photo != null) {
        val scrim = MaterialTheme.trackerColors.photoScrim
        // Large text reaches the top of the photo, so it gets a light base scrim there.
        val topScrim = if (LocalDensity.current.fontScale > 1.3f) scrim.copy(alpha = 0.4f) else Color.Transparent
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 112.dp)
                .clip(MaterialTheme.shapes.large),
        ) {
            Image(
                painter = painterResource(photo),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to topScrim,
                            0.3f to topScrim,
                            1f to scrim.copy(alpha = 0.85f),
                        ),
                    ),
            )
            SummaryLines(
                place = place,
                dates = dates,
                onPhoto = true,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(Spacing.lg),
            )
        }
    } else {
        SummaryLines(place = place, dates = dates, onPhoto = false, modifier = modifier)
    }
}

@Composable
private fun SummaryLines(place: String, dates: String?, onPhoto: Boolean, modifier: Modifier = Modifier) {
    val primary = if (onPhoto) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary = if (onPhoto) Color.White.copy(alpha = 0.88f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (place.isNotEmpty()) {
            Text(text = place, style = MaterialTheme.typography.bodyMedium, color = secondary)
        }
        if (dates != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    AppIcons.Calendar,
                    contentDescription = null,
                    modifier = Modifier.size(Sizes.iconSmall),
                    tint = secondary,
                )
                Text(
                    text = dates,
                    modifier = Modifier.padding(start = Spacing.sm),
                    style = MaterialTheme.typography.titleMedium.numeric().copy(fontSize = 18.sp),
                    color = primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionTabs(
    sessions: List<ScheduledSession>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = sessions.indexOfFirst { it.type == selected }.coerceAtLeast(0)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Equal-width tabs while they fit; they scroll when large text makes them wider.
        val tabWidth = (maxWidth / sessions.size).coerceAtLeast(64.dp)
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedIndex,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 0.dp,
            minTabWidth = tabWidth,
        ) {
            sessions.forEachIndexed { index, session ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = { onSelect(session.type) },
                    modifier = Modifier.heightIn(min = Sizes.touchTarget),
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = {
                        Text(
                            text = shortSessionLabel(session.type, session.name),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ResultSection(
    session: ScheduledSession,
    state: ResultSectionState,
    favorite: FavoriteSelection,
    photoUrls: Map<String, String?>,
    photos: DriverPhotos?,
    teamColors: Map<String, String>,
    onRetry: () -> Unit,
) {
    val shortLabel = shortSessionLabel(session.type, session.name)
    val title = session.name.ifBlank { shortLabel }
    val (day, clock) = sessionDayAndClock(session.start, ZoneId.systemDefault(), Locale.getDefault())
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = trackerCardColors(),
        border = trackerCardBorder(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (session.cancelled) "Cancelled" else "$day $clock",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (session.cancelled) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                contentKey = { it::class },
                label = shortLabel,
            ) { current ->
                // AnimatedContent stacks its children, and each table emits a header and its rows.
                Column {
                    when (current) {
                        ResultSectionState.Loading -> {
                            SkeletonList(rows = 6, label = "Loading $title results")
                        }
                        ResultSectionState.Missing -> {
                            CardMessage(
                                text = if (session.cancelled) "This session was cancelled" else "No results yet",
                            )
                        }
                        is ResultSectionState.Failed -> {
                            CardMessage(text = current.message, actionLabel = "Try again", onAction = onRetry)
                        }
                        is ResultSectionState.Ready -> when (val results = current.results) {
                            is SessionResults.Qualifying -> {
                                QualifyingTable(results.results, favorite, photoUrls, photos, teamColors)
                            }
                            is SessionResults.Practice -> {
                                PracticeTable(results.results, favorite, photoUrls, photos, teamColors)
                            }
                            is SessionResults.Race -> {
                                RaceTable(results.results, favorite, photoUrls, photos, teamColors)
                            }
                        }
                    }
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
    ResultsHeader(hasPortrait = photos != null) {
        HeaderLabel("Time", Modifier.widthIn(min = TIME_COLUMN_WIDTH), TextAlign.End)
    }
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
        ) {
            TimeBlock(headline = readout.headline, note = readout.note)
        }
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
    ResultsHeader(hasPortrait = photos != null) {
        HeaderLabel("Best lap", Modifier.widthIn(min = TIME_COLUMN_WIDTH), TextAlign.End)
    }
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
        ) {
            TimeBlock(headline = readout.headline, note = readout.note)
        }
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
    ResultsHeader(hasPortrait = photos != null) {
        HeaderLabel("Pts", Modifier.width(POINTS_COLUMN_WIDTH), TextAlign.End)
        HeaderLabel("Time / gap", Modifier.widthIn(min = TIME_COLUMN_WIDTH), TextAlign.End)
    }
    DividedColumn(results.rows) { row ->
        ResultLine(
            position = row.positionText,
            code = row.code,
            highlighted = favorite.matches(row.code, row.team),
            photoUrl = photoUrls[row.code.uppercase()],
            photos = photos,
            teamColor = teamColor(teamColors, row.team),
            team = row.team,
        ) {
            RaceClock(
                points = row.points?.let { "+${formatPoints(it)} pts" },
                scored = (row.points ?: 0.0) > 0.0,
                time = raceTimeLabel(row, leader),
                places = placesMoved(row.grid, row.position, row.classified),
            )
        }
    }
}

@Composable
private fun ResultsHeader(hasPortrait: Boolean, right: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        HeaderLabel("Pos", Modifier.width(POSITION_COLUMN_WIDTH), TextAlign.Center)
        if (hasPortrait) {
            Spacer(Modifier.width(Sizes.portrait))
        }
        HeaderLabel("Driver", Modifier.weight(1f), TextAlign.Start)
        right()
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
    right: @Composable () -> Unit,
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
        if (photos != null) {
            DriverPortrait(code = code, photoUrl = photoUrl, photos = photos)
        }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = code.ifBlank { "—" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.SemiBold,
                color = nameColor,
                maxLines = 1,
            )
            if (highlighted) {
                Icon(
                    AppIcons.Favorites,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            TeamColorMark(color = teamColor, team = team)
        }
        right()
    }
}

@Composable
private fun TimeBlock(headline: String, note: String) {
    Column(
        modifier = Modifier.widthIn(min = TIME_COLUMN_WIDTH),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleSmall.numeric().copy(fontSize = 15.sp),
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

@Composable
private fun RaceClock(points: String?, scored: Boolean, time: String, places: Int?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = points.orEmpty(),
            modifier = Modifier.width(POINTS_COLUMN_WIDTH),
            style = MaterialTheme.typography.titleSmall.numeric().copy(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            color = if (scored) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Column(
            modifier = Modifier.widthIn(min = TIME_COLUMN_WIDTH),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = time,
                style = MaterialTheme.typography.titleSmall.numeric().copy(
                    fontSize = 15.sp,
                    lineHeight = 18.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                textAlign = TextAlign.End,
                maxLines = 1,
            )
            if (places != null) {
                val placeLabel = when {
                    places > 0 -> "Gained $places"
                    places < 0 -> "Lost ${-places}"
                    else -> "No places gained or lost"
                }
                Row(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clearAndSetSemantics { contentDescription = placeLabel },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (places == 0) PlacesDash() else PlacesTriangle(up = places > 0)
                    Text(
                        text = kotlin.math.abs(places).toString(),
                        modifier = Modifier.width(20.dp),
                        style = MaterialTheme.typography.labelMedium.numeric().copy(
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
    val colors = MaterialTheme.trackerColors
    val color = if (up) colors.gained else colors.lost
    Canvas(modifier = Modifier.size(width = 11.dp, height = 7.dp)) {
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
        modifier = Modifier.size(width = 11.dp, height = 7.dp),
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
        val results = source.fetchResults(year, round, sessionType)
        if (results.hasRows()) ResultSectionState.Ready(results) else ResultSectionState.Missing
    } catch (_: ResultsNotFound) {
        ResultSectionState.Missing
    } catch (error: Exception) {
        ResultSectionState.Failed(error.message ?: "Couldn't load results")
    }
}

// The provider answers with an empty list for a session it has not published yet.
internal fun SessionResults.hasRows(): Boolean = when (this) {
    is SessionResults.Qualifying -> results.rows.isNotEmpty()
    is SessionResults.Practice -> results.rows.isNotEmpty()
    is SessionResults.Race -> results.rows.isNotEmpty()
}

internal fun formatPoints(points: Double): String {
    return if (points % 1.0 == 0.0) points.toInt().toString() else points.toString()
}
