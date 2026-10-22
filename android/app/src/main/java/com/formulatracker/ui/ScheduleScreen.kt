package com.formulatracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.formulatracker.ui.theme.tabular
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

    val section = opened.section()
    val showYear = section == AppSection.Calendar || section == AppSection.Standings
    val updatedMessage = if (seasonYear == currentYear) {
        calendarUpdatedMessage(
            savedAt,
            Instant.now(),
            ZoneId.systemDefault(),
            Locale.getDefault(),
        )
    } else {
        null
    }
    val barColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = barColors,
                title = {
                    if (weekend != null) {
                        Text(
                            weekend.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(section?.title ?: AppSection.Calendar.title)
                    }
                },
                navigationIcon = {
                    if (weekend != null) {
                        TextButton(onClick = { opened = Opened.None }) {
                            Icon(
                                AppIcons.ChevronLeft,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text("Calendar")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (section != null) {
                AppNavigationBar(
                    section = section,
                    onSection = { opened = it.opened() },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            AnimatedVisibility(visible = showYear) {
                SeasonYearBar(
                    year = seasonYear,
                    currentYear = currentYear,
                    menuOpen = yearMenuOpen,
                    onMenuOpen = { yearMenuOpen = true },
                    onMenuDismiss = { yearMenuOpen = false },
                    onYear = { seasonYear = it },
                )
            }
            AnimatedContent(
                targetState = opened,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                },
                contentKey = { it.screenKey() },
                label = "section",
            ) { destination ->
                val openedWeekend = (destination as? Opened.Weekend)?.weekend
                when {
                    openedWeekend != null -> {
                        WeekendResultsScreen(
                            year = seasonYear,
                            weekend = openedWeekend,
                            source = source,
                            favorite = favorite,
                            photoUrls = photoUrls,
                            photos = driverPhotos,
                            teamColors = teamColors,
                        )
                    }
                    destination == Opened.Standings -> {
                        StandingsScreen(
                            year = seasonYear,
                            source = source,
                            favorite = favorite,
                            photoUrls = photoUrls,
                            photos = driverPhotos,
                            teamColors = teamColors,
                        )
                    }
                    destination == Opened.Favorites -> {
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
                    destination == Opened.Reminders -> {
                        RemindersScreen(
                            preferences = reminderPreferences,
                            onPreferences = { next ->
                                reminderSettings.write(next)
                                reminderPreferences = next
                            },
                        )
                    }
                    else -> {
                        ScheduleContent(
                            state = state,
                            updatedMessage = updatedMessage,
                            onRetry = { attempt += 1 },
                            onWeekend = { opened = Opened.Weekend(it) },
                        )
                    }
                }
            }
        }
    }
}

private enum class AppSection(val title: String) {
    Calendar("Calendar"),
    Standings("Standings"),
    Favorites("Favorites"),
    Reminders("Reminders"),
}

private fun Opened.section(): AppSection? = when (this) {
    Opened.None -> AppSection.Calendar
    Opened.Standings -> AppSection.Standings
    Opened.Favorites -> AppSection.Favorites
    Opened.Reminders -> AppSection.Reminders
    is Opened.Weekend -> null
}

private fun AppSection.opened(): Opened = when (this) {
    AppSection.Calendar -> Opened.None
    AppSection.Standings -> Opened.Standings
    AppSection.Favorites -> Opened.Favorites
    AppSection.Reminders -> Opened.Reminders
}

private fun Opened.screenKey(): String = when (this) {
    Opened.None -> "calendar"
    is Opened.Weekend -> "weekend-${weekend.round}"
    Opened.Standings -> "standings"
    Opened.Favorites -> "favorites"
    Opened.Reminders -> "reminders"
}

@Composable
private fun AppNavigationBar(section: AppSection, onSection: (AppSection) -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        AppSection.entries.forEach { item ->
            NavigationBarItem(
                selected = item == section,
                onClick = { onSection(item) },
                icon = {
                    Icon(
                        imageVector = when (item) {
                            AppSection.Calendar -> AppIcons.Calendar
                            AppSection.Standings -> AppIcons.Standings
                            AppSection.Favorites -> AppIcons.Favorites
                            AppSection.Reminders -> AppIcons.Reminders
                        },
                        contentDescription = null,
                    )
                },
                label = { Text(item.title) },
                alwaysShowLabel = true,
                colors = colors,
            )
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(
            onClick = { onYear(year - 1) },
            enabled = year > FIRST_SEASON_YEAR,
        ) {
            Icon(AppIcons.ChevronLeft, contentDescription = "Previous season")
        }
        Box {
            TextButton(onClick = onMenuOpen) {
                Text(year.toString(), style = MaterialTheme.typography.titleLarge.tabular())
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
                years.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.toString(),
                                fontWeight = if (option == year) FontWeight.SemiBold else FontWeight.Normal,
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
        IconButton(
            onClick = { onYear(year + 1) },
            enabled = year < currentYear,
        ) {
            Icon(AppIcons.ChevronRight, contentDescription = "Next season")
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
            Column(modifier.fillMaxSize()) {
                if (updatedMessage != null) {
                    NoticeBanner(updatedMessage, Modifier.padding(16.dp))
                }
                StatusMessage(title = "Loading schedule", loading = true)
            }
        }
        is ScheduleUiState.Failed -> {
            Column(modifier.fillMaxSize()) {
                if (updatedMessage != null) {
                    NoticeBanner(updatedMessage, Modifier.padding(16.dp))
                }
                StatusMessage(
                    title = state.message,
                    actionLabel = "Try again",
                    onAction = onRetry,
                )
            }
        }
        is ScheduleUiState.Ready -> {
            if (state.schedule.events.isEmpty()) {
                Column(modifier.fillMaxSize()) {
                    if (updatedMessage != null) {
                        NoticeBanner(updatedMessage, Modifier.padding(16.dp))
                    }
                    StatusMessage(title = "No races on this calendar")
                }
            } else {
                val highlight = weekendHighlight(state.schedule.events, Instant.now())
                var openRounds by remember(state.schedule.season) { mutableStateOf(emptySet<Int>()) }
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (updatedMessage != null) {
                        item { NoticeBanner(updatedMessage) }
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
private fun WeekendSection(
    weekend: RaceWeekend,
    mark: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val highlighted = mark != null
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "sessions",
    )
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = trackerCardColors(),
        border = trackerCardBorder(highlighted),
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            val photo = circuitImageRes(weekend.circuitName)
            if (photo != null) {
                Image(
                    painter = painterResource(photo),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(156.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (mark != null) {
                    Text(
                        text = mark,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
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
                                style = MaterialTheme.typography.titleSmall.tabular(),
                            )
                        }
                    }
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = "Open results",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp, end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        weekend.sessions.forEach { session ->
                            SessionRow(session)
                        }
                    }
                }
                if (weekend.sessions.isNotEmpty()) {
                    TextButton(
                        onClick = onToggle,
                        modifier = Modifier
                            .align(Alignment.Start)
                            .heightIn(min = 48.dp)
                            .semantics {
                                stateDescription = if (expanded) "Expanded" else "Collapsed"
                            },
                    ) {
                        Text(if (expanded) "Show less" else "Show more")
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            AppIcons.ChevronDown,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(chevron),
                        )
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
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = shortSessionLabel(session.type, session.name),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            textDecoration = if (session.cancelled) TextDecoration.LineThrough else null,
            color = if (session.cancelled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = if (session.cancelled) "Cancelled" else time,
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = if (session.cancelled) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
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

internal fun circuitLine(weekend: RaceWeekend): String {
    val place = listOf(weekend.locality, weekend.country).filter { it.isNotEmpty() }.joinToString(", ")
    return when {
        weekend.circuitName.isEmpty() -> place
        place.isEmpty() -> weekend.circuitName
        else -> "${weekend.circuitName} · $place"
    }
}
