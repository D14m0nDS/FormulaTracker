package com.formulatracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.formulatracker.drivers.DriverPhotoStore
import com.formulatracker.drivers.DriverPhotos
import com.formulatracker.drivers.choosePhotoUrls
import com.formulatracker.favorites.FavoriteStore
import com.formulatracker.reminders.ReminderScheduleStore
import com.formulatracker.reminders.ReminderSettings
import com.formulatracker.reminders.SessionReminderScheduler
import com.formulatracker.reminders.calendarUpdatedMessage
import com.formulatracker.reminders.scheduleChanges
import com.formulatracker.reminders.scheduleRemindersFor
import com.formulatracker.reminders.showScheduledNotification
import com.formulatracker.schedule.FIRST_SEASON_YEAR
import com.formulatracker.schedule.HttpScheduleSource
import com.formulatracker.schedule.RaceWeekend
import com.formulatracker.schedule.ScheduleSource
import com.formulatracker.schedule.SeasonSchedule
import com.formulatracker.schedule.seasonYears
import com.formulatracker.ui.theme.Sizes
import com.formulatracker.ui.theme.Spacing
import com.formulatracker.ui.theme.numeric
import com.formulatracker.ui.theme.overline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(source: ScheduleSource = HttpScheduleSource()) {
    val currentYear = remember { Year.now().value }
    var seasonYear by rememberSaveable { mutableIntStateOf(currentYear) }
    var seasonSheetOpen by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var loads by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ScheduleUiState>(ScheduleUiState.Loading) }
    var opened by rememberSaveable(stateSaver = OpenedSaver) { mutableStateOf<Opened>(Opened.None) }
    val appContext = LocalContext.current.applicationContext
    val reminderSettings = remember { ReminderSettings(appContext) }
    val reminderScheduleStore = remember { ReminderScheduleStore(appContext) }
    var savedAt by remember { mutableStateOf(reminderScheduleStore.readSavedAt()) }
    val favoriteStore = remember { FavoriteStore(appContext) }
    var favorite by remember { mutableStateOf(favoriteStore.read()) }
    val reminderScheduler = remember { SessionReminderScheduler(appContext) }
    val driverPhotos = remember { DriverPhotos(File(appContext.cacheDir, "driver-photos")) }
    val photoStore = remember { DriverPhotoStore(appContext) }
    var photoUrls by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var teamColors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var reminderPreferences by remember { mutableStateOf(reminderSettings.read()) }
    var currentSeasonSchedule by remember { mutableStateOf<SeasonSchedule?>(null) }
    val calendarListState = rememberLazyListState()
    var openRounds by rememberSaveable(seasonYear, stateSaver = RoundSetSaver) {
        mutableStateOf(emptySet<Int>())
    }
    // Saved too, so a recreated activity keeps its restored scroll position.
    var positionedSeason by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(seasonYear, attempt, opened) {
        if (opened != Opened.None) {
            return@LaunchedEffect
        }
        // Coming back to the calendar refreshes it quietly. Showing the loading view
        // again would hide the list and lose the scroll position for no reason.
        val shown = state as? ScheduleUiState.Ready
        val refreshing = shown != null && shown.schedule.season == seasonYear
        if (!refreshing) {
            state = ScheduleUiState.Loading
        }
        try {
            val schedule = source.fetch(seasonYear)
            ensureActive()
            state = ScheduleUiState.Ready(schedule)
            loads += 1
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ensureActive()
            if (!refreshing) {
                state = ScheduleUiState.Failed(error.message ?: "Couldn't load the schedule")
            }
        }
    }

    LaunchedEffect(state, loads, currentYear) {
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

    // The first time a season shows, bring the live or next weekend into view instead
    // of leaving the list at round 1.
    LaunchedEffect(state) {
        val ready = state as? ScheduleUiState.Ready ?: return@LaunchedEffect
        val season = ready.schedule.season
        if (positionedSeason == season) {
            return@LaunchedEffect
        }
        positionedSeason = season
        val target = weekendHighlight(ready.schedule.events, Instant.now())
        val index = target?.let { mark ->
            ready.schedule.events.indexOfFirst { it.round == mark.round }
        } ?: 0
        calendarListState.scrollToItem(index.coerceAtLeast(0))
    }

    val weekend = (opened as? Opened.Weekend)?.weekend
    val photosWanted = weekend != null || opened == Opened.Standings || opened == Opened.Favorites
    val photoYear = if (opened == Opened.Favorites) currentYear else seasonYear
    LaunchedEffect(photosWanted, photoYear) {
        if (!photosWanted) {
            return@LaunchedEffect
        }
        val fetched = try {
            source.fetchDrivers(photoYear)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        photoUrls = withContext(Dispatchers.IO) {
            val choice = choosePhotoUrls(fetched, photoStore.read(photoYear))
            if (choice.save) {
                photoStore.write(photoYear, choice.urls)
            }
            choice.urls
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
    // A year picked on Standings must not show last year's calendar while it reloads.
    val calendarState = (state as? ScheduleUiState.Ready)
        ?.takeIf { it.schedule.season != seasonYear }
        ?.let { ScheduleUiState.Loading }
        ?: state

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    LaunchedEffect(opened.screenKey()) {
        scrollBehavior.state.contentOffset = 0f
    }
    val barTitle = BarTitle(
        overline = weekend?.let { "Round ${it.round}" },
        text = weekend?.name ?: (section ?: AppSection.Calendar).title,
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                scrollBehavior = scrollBehavior,
                title = {
                    Crossfade(targetState = barTitle, animationSpec = tween(160), label = "title") { title ->
                        Column {
                            if (title.overline != null) {
                                Text(
                                    text = title.overline.uppercase(),
                                    style = MaterialTheme.typography.labelSmall.overline(),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                text = title.text,
                                modifier = Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    AnimatedVisibility(
                        visible = weekend != null,
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(100)),
                    ) {
                        IconButton(onClick = { opened = Opened.None }) {
                            Icon(AppIcons.ArrowBack, contentDescription = "Back to calendar")
                        }
                    }
                },
                actions = {
                    AnimatedVisibility(
                        visible = showYear,
                        enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.92f),
                        exit = fadeOut(tween(100)),
                    ) {
                        SeasonSwitcher(
                            year = seasonYear,
                            currentYear = currentYear,
                            onYear = { seasonYear = it },
                            onPick = { seasonSheetOpen = true },
                        )
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
        AnimatedContent(
            targetState = opened,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            transitionSpec = { screenTransition() },
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
                        state = calendarState,
                        updatedMessage = updatedMessage,
                        onRetry = { attempt += 1 },
                        onWeekend = { opened = Opened.Weekend(it) },
                        listState = calendarListState,
                        openRounds = openRounds,
                        onToggleRound = { round ->
                            openRounds = if (round in openRounds) openRounds - round else openRounds + round
                        },
                    )
                }
            }
        }
    }

    if (seasonSheetOpen) {
        SeasonPickerSheet(
            years = remember(currentYear) { seasonYears(currentYear) },
            selected = seasonYear,
            onPick = { seasonYear = it },
            onDismiss = { seasonSheetOpen = false },
        )
    }
}

private data class BarTitle(val overline: String?, val text: String)

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

// The incoming screen waits for the outgoing one to fade, so two screens are never
// visible on top of each other. Opening a weekend also slides in from the right.
private fun AnimatedContentTransitionScope<Opened>.screenTransition(): ContentTransform {
    val enterDelay = 80
    val transition = when {
        targetState is Opened.Weekend -> {
            (fadeIn(tween(220, enterDelay)) +
                slideInHorizontally(tween(280, enterDelay, FastOutSlowInEasing)) { it / 10 }) togetherWith
                fadeOut(tween(90))
        }
        initialState is Opened.Weekend -> {
            fadeIn(tween(200, enterDelay)) togetherWith
                (fadeOut(tween(110)) + slideOutHorizontally(tween(180)) { it / 10 })
        }
        else -> {
            (fadeIn(tween(200, enterDelay)) + scaleIn(tween(200, enterDelay), initialScale = 0.98f)) togetherWith
                fadeOut(tween(90))
        }
    }
    return transition using SizeTransform(clip = false)
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
    // Four labels share the width, so past 1.3x they would break mid-word. The icon and the
    // selected pill still identify each section at larger sizes.
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = density.fontScale.coerceAtMost(1.3f)),
    ) {
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
                    label = { Text(item.title, maxLines = 1) },
                    alwaysShowLabel = true,
                    colors = colors,
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

// An open weekend is not restored because RaceWeekend is not parcelable; it falls back to the calendar.
private val OpenedSaver = Saver<Opened, String>(
    save = { (it.section() ?: AppSection.Calendar).name },
    restore = { AppSection.valueOf(it).opened() },
)

private val RoundSetSaver = listSaver<Set<Int>, Int>(
    save = { it.toList() },
    restore = { it.toSet() },
)

@Composable
private fun SeasonSwitcher(
    year: Int,
    currentYear: Int,
    onYear: (Int) -> Unit,
    onPick: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(end = Spacing.sm),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onYear(year - 1) },
                enabled = year > FIRST_SEASON_YEAR,
            ) {
                Icon(AppIcons.ChevronLeft, contentDescription = "Previous season")
            }
            Row(
                modifier = Modifier
                    .heightIn(min = Sizes.touchTarget)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Choose season", role = Role.Button, onClick = onPick)
                    .padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.titleLarge.numeric(),
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    AppIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { onYear(year + 1) },
                enabled = year < currentYear,
            ) {
                Icon(AppIcons.ChevronRight, contentDescription = "Next season")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonPickerSheet(
    years: List<Int>,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val columns = 4
    val gridState: LazyGridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = ((years.indexOf(selected) / columns) - 1).coerceAtLeast(0) * columns,
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text = "Choose season",
            modifier = Modifier
                .padding(horizontal = Spacing.xl)
                .semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(years, key = { it }) { year ->
                YearCell(
                    year = year,
                    selected = year == selected,
                    onClick = {
                        onPick(year)
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    },
                )
            }
        }
    }
}

@Composable
private fun YearCell(year: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = Sizes.touchTarget)
            .semantics { stateDescription = if (selected) "Selected" else "" },
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.touchTarget),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Icon(
                        AppIcons.Check,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = Spacing.xs)
                            .size(Sizes.iconSmall - 2.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.titleMedium.numeric(),
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
