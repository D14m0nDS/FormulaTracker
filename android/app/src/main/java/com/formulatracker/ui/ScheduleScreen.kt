package com.formulatracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.formulatracker.drivers.DriverPhotos
import com.formulatracker.favorites.FavoriteStore
import com.formulatracker.reminders.ReminderScheduleStore
import com.formulatracker.reminders.ReminderSettings
import com.formulatracker.reminders.SessionReminderScheduler
import com.formulatracker.reminders.scheduleChanges
import com.formulatracker.reminders.calendarUpdatedMessage
import com.formulatracker.reminders.scheduleRemindersFor
import com.formulatracker.reminders.showScheduledNotification
import com.formulatracker.schedule.FIRST_SEASON_YEAR
import com.formulatracker.schedule.HttpScheduleSource
import com.formulatracker.schedule.RaceWeekend
import com.formulatracker.schedule.ScheduleSource
import com.formulatracker.schedule.seasonYears
import com.formulatracker.schedule.ScheduledSession
import com.formulatracker.schedule.SeasonSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
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
    val currentYear = remember { Year.now().value }
    var seasonYear by remember { mutableIntStateOf(currentYear) }
    var yearMenuOpen by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ScheduleUiState>(ScheduleUiState.Loading) }
    var opened by remember { mutableStateOf<Opened>(Opened.None) }
    val appContext = LocalContext.current.applicationContext
    val reminderSettings = remember { ReminderSettings(appContext) }
    val reminderScheduleStore = remember { ReminderScheduleStore(appContext) }
    var savedAt by remember { mutableStateOf(reminderScheduleStore.readSavedAt()) }
    val favoriteStore = remember { FavoriteStore(appContext) }
    var favorite by remember { mutableStateOf(favoriteStore.read()) }
    val reminderScheduler = remember { SessionReminderScheduler(appContext) }
    val driverPhotos = remember { DriverPhotos(File(appContext.cacheDir, "driver-photos")) }
    var photoUrls by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var teamColors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var reminderPreferences by remember { mutableStateOf(reminderSettings.read()) }
    var currentSeasonSchedule by remember { mutableStateOf<SeasonSchedule?>(null) }

    LaunchedEffect(seasonYear, attempt, opened) {
        if (opened != Opened.None) {
            return@LaunchedEffect
        }
        state = ScheduleUiState.Loading
        state = try {
            val schedule = source.fetch(seasonYear)
            ensureActive()
            ScheduleUiState.Ready(schedule)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ensureActive()
            ScheduleUiState.Failed(error.message ?: "Couldn't load the schedule")
        }
    }

    LaunchedEffect(state, currentYear) {
        val ready = state as? ScheduleUiState.Ready ?: return@LaunchedEffect
        if (!scheduleRemindersFor(ready.schedule.season, currentYear)) {
            return@LaunchedEffect
        }
        val loadedAt = Instant.now()
        val changes = withContext(Dispatchers.IO) {
            val previous = reminderScheduleStore.read()
            val found = scheduleChanges(
                previous = previous,
                current = ready.schedule,
                now = loadedAt,
                zone = ZoneId.systemDefault(),
                locale = Locale.getDefault(),
            )
            reminderScheduleStore.write(ready.schedule, loadedAt)
            found
        }
        ensureActive()
        savedAt = loadedAt
        changes.forEach { change ->
            showScheduledNotification(appContext, change.title, change.text, change.notificationId)
        }
        currentSeasonSchedule = ready.schedule
    }

    LaunchedEffect(currentSeasonSchedule, reminderPreferences, favorite) {
        val schedule = currentSeasonSchedule ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            reminderScheduler.replace(schedule, reminderPreferences, favorite)
        }
    }

    val weekend = (opened as? Opened.Weekend)?.weekend
    val photosWanted = weekend != null || opened == Opened.Standings || opened == Opened.Favorites
    val photoYear = if (opened == Opened.Favorites) currentYear else seasonYear
    LaunchedEffect(photosWanted, photoYear) {
        if (!photosWanted) {
            return@LaunchedEffect
        }
        photoUrls = try {
            source.fetchDrivers(photoYear).drivers.associate { driver ->
                driver.code.uppercase() to driver.photoUrl
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyMap()
        }
        teamColors = try {
            source.fetchTeams(photoYear).teams.associate { team ->
                team.name.uppercase() to team.color
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyMap()
        }
    }

    BackHandler(enabled = opened != Opened.None) {
        opened = Opened.None
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (weekend != null) {
                        Text(weekend.name)
                    } else {
                        SeasonYearBar(
                            year = seasonYear,
                            currentYear = currentYear,
                            menuOpen = yearMenuOpen,
                            onMenuOpen = { yearMenuOpen = true },
                            onMenuDismiss = { yearMenuOpen = false },
                            onYear = { seasonYear = it },
                        )
                    }
                },
                navigationIcon = {
                    when {
                        weekend != null || opened != Opened.None -> {
                            TextButton(onClick = { opened = Opened.None }) {
                                Text("Calendar")
                            }
                        }
                        else -> {
                            TextButton(onClick = { opened = Opened.Reminders }) {
                                Text("Reminders")
                            }
                        }
                    }
                },
                actions = {
                    if (opened == Opened.None) {
                        TextButton(onClick = { opened = Opened.Favorites }) {
                            Text("Favorites")
                        }
                        TextButton(onClick = { opened = Opened.Standings }) {
                            Text("Standings")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            weekend != null -> {
                Column(modifier = Modifier.padding(innerPadding)) {
                    WeekendResultsScreen(
                        year = seasonYear,
                        weekend = weekend,
                        source = source,
                        favorite = favorite,
                        photoUrls = photoUrls,
                        photos = driverPhotos,
                        teamColors = teamColors,
                    )
                }
            }
            opened == Opened.Standings -> {
                Column(modifier = Modifier.padding(innerPadding)) {
                    StandingsScreen(
                        year = seasonYear,
                        source = source,
                        favorite = favorite,
                        photoUrls = photoUrls,
                        photos = driverPhotos,
                        teamColors = teamColors,
                    )
                }
            }
            opened == Opened.Favorites -> {
                Column(modifier = Modifier.padding(innerPadding)) {
                    FavoritesScreen(
                        year = currentYear,
                        source = source,
                        favorite = favorite,
                        photoUrls = photoUrls,
                        photos = driverPhotos,
                        teamColors = teamColors,
                        onFavorite = { next ->
                            favoriteStore.write(next)
                            favorite = next
                        },
                    )
                }
            }
            opened == Opened.Reminders -> {
                Column(modifier = Modifier.padding(innerPadding)) {
                    RemindersScreen(
                        preferences = reminderPreferences,
                        onPreferences = { next ->
                            reminderSettings.write(next)
                            reminderPreferences = next
                        },
                    )
                }
            }
            else -> {
                ScheduleContent(
                    state = state,
                    updatedMessage = if (seasonYear == currentYear) {
                        calendarUpdatedMessage(
                            savedAt,
                            Instant.now(),
                            ZoneId.systemDefault(),
                            Locale.getDefault(),
                        )
                    } else {
                        null
                    },
                    onRetry = { attempt += 1 },
                    onWeekend = { opened = Opened.Weekend(it) },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

private sealed interface Opened {
    data object None : Opened
    data class Weekend(val weekend: RaceWeekend) : Opened
    data object Standings : Opened
    data object Reminders : Opened
    data object Favorites : Opened
}

@Composable
private fun SeasonYearBar(
    year: Int,
    currentYear: Int,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onYear: (Int) -> Unit,
) {
    val years = remember(currentYear) { seasonYears(currentYear) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(
            onClick = { onYear(year - 1) },
            enabled = year > FIRST_SEASON_YEAR,
        ) {
            Text("‹", style = MaterialTheme.typography.headlineSmall)
        }
        Box {
            TextButton(onClick = onMenuOpen) {
                Text(year.toString(), style = MaterialTheme.typography.titleLarge)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
                years.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.toString(),
                                color = if (option == year) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        onClick = {
                            onYear(option)
                            onMenuDismiss()
                        },
                    )
                }
            }
        }
        TextButton(
            onClick = { onYear(year + 1) },
            enabled = year < currentYear,
        ) {
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
fun ScheduleContent(
    state: ScheduleUiState,
    updatedMessage: String?,
    onRetry: () -> Unit,
    onWeekend: (RaceWeekend) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        ScheduleUiState.Loading -> {
            Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UpdatedLine(updatedMessage)
                Text("Loading schedule")
            }
        }
        is ScheduleUiState.Failed -> {
            Column(
                modifier = modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                UpdatedLine(updatedMessage)
                Text(state.message)
                Button(onClick = onRetry) {
                    Text("Try again")
                }
            }
        }
        is ScheduleUiState.Ready -> {
            if (state.schedule.events.isEmpty()) {
                Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    UpdatedLine(updatedMessage)
                    Text("No races on this calendar")
                }
            } else {
                val highlight = weekendHighlight(state.schedule.events, Instant.now())
                var openRounds by remember(state.schedule.season) { mutableStateOf(emptySet<Int>()) }
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (updatedMessage != null) {
                        item { UpdatedLine(updatedMessage) }
                    }
                    items(state.schedule.events, key = { it.round }) { weekend ->
                        WeekendSection(
                            weekend,
                            mark = highlight?.takeIf { it.round == weekend.round }?.label,
                            expanded = weekend.round in openRounds,
                            onToggle = {
                                openRounds = if (weekend.round in openRounds) {
                                    openRounds - weekend.round
                                } else {
                                    openRounds + weekend.round
                                }
                            },
                            onClick = { onWeekend(weekend) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatedLine(message: String?) {
    if (message != null) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WeekendSection(
    weekend: RaceWeekend,
    mark: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val highlighted = mark != null
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        border = BorderStroke(
            width = if (highlighted) 2.dp else 1.dp,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column {
            val photo = circuitImageRes(weekend.circuitName)
            if (photo != null) {
                Image(
                    painter = painterResource(photo),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(148.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (mark != null) {
                    Text(
                        text = mark,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "Round ${weekend.round} · ${weekend.name}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = circuitLine(weekend),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val dates = weekendDateRange(
                    weekend.sessions.map { it.start },
                    ZoneId.systemDefault(),
                )
                if (dates != null) {
                    Text(
                        text = dates,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    weekend.sessions.forEach { session ->
                        SessionRow(session)
                    }
                }
                if (weekend.sessions.isNotEmpty()) {
                    TextButton(
                        onClick = onToggle,
                        modifier = Modifier.align(Alignment.Start),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        Text(if (expanded) "Show less" else "Show more")
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: ScheduledSession) {
    val time = formatSessionStart(session.start, ZoneId.systemDefault(), Locale.getDefault())
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = shortSessionLabel(session.type, session.name),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = if (session.cancelled) "Cancelled" else time,
            style = MaterialTheme.typography.bodyMedium,
            color = if (session.cancelled) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

internal fun shortSessionLabel(type: String, name: String): String {
    return when (type) {
        "FP1" -> "FP1"
        "FP2" -> "FP2"
        "FP3" -> "FP3"
        "SQ" -> "SQ"
        "SR" -> "Sprint"
        "Q" -> "Q"
        "R" -> "Race"
        else -> name.ifBlank { type }
    }
}

internal fun weekendDateRange(starts: List<Instant>, zone: ZoneId): String? {
    if (starts.isEmpty()) return null
    val days = starts.map { it.atZone(zone).toLocalDate() }
    val first = days.min()
    val last = days.max()
    val pattern = if (first.year == last.year) "dd.MM" else "dd.MM.yyyy"
    val formatter = DateTimeFormatter.ofPattern(pattern)
    val startText = formatter.format(first)
    if (first == last) return startText
    return "$startText-${formatter.format(last)}"
}

internal fun formatSessionStart(start: Instant, zone: ZoneId, locale: Locale): String {
    val local = start.atZone(zone)
    val day = DateTimeFormatter.ofPattern("EEE", locale).format(local)
    val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(local)
    return "$day $clock"
}

internal data class WeekendHighlight(val round: Int, val label: String)

internal fun weekendHighlight(events: List<RaceWeekend>, now: Instant): WeekendHighlight? {
    val current = events.firstOrNull { weekend ->
        val starts = weekend.sessions.filter { !it.cancelled }.map { it.start }
        starts.any { !it.isAfter(now) } && starts.any { it.isAfter(now) }
    }
    if (current != null) {
        return WeekendHighlight(current.round, "This weekend")
    }
    val next = events.firstOrNull { weekend ->
        weekend.sessions.any { !it.cancelled && it.start.isAfter(now) }
    }
    return next?.let { WeekendHighlight(it.round, "Next") }
}

private fun circuitLine(weekend: RaceWeekend): String {
    val place = listOf(weekend.locality, weekend.country).filter { it.isNotEmpty() }.joinToString(", ")
    return when {
        weekend.circuitName.isEmpty() -> place
        place.isEmpty() -> weekend.circuitName
        else -> "${weekend.circuitName} · $place"
    }
}
