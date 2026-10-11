package com.formulatracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.formulatracker.reminders.ReminderScheduleStore
import com.formulatracker.reminders.ReminderSettings
import com.formulatracker.reminders.SessionReminderScheduler
import com.formulatracker.reminders.scheduleChanges
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
    val reminderScheduler = remember { SessionReminderScheduler(appContext) }
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
        val changes = withContext(Dispatchers.IO) {
            val previous = reminderScheduleStore.read()
            val found = scheduleChanges(
                previous = previous,
                current = ready.schedule,
                now = Instant.now(),
                zone = ZoneId.systemDefault(),
                locale = Locale.getDefault(),
            )
            reminderScheduleStore.write(ready.schedule)
            found
        }
        ensureActive()
        changes.forEach { change ->
            showScheduledNotification(appContext, change.title, change.text, change.notificationId)
        }
        currentSeasonSchedule = ready.schedule
    }

    LaunchedEffect(currentSeasonSchedule, reminderPreferences) {
        val schedule = currentSeasonSchedule ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            reminderScheduler.replace(schedule, reminderPreferences)
        }
    }

    BackHandler(enabled = opened != Opened.None) {
        opened = Opened.None
    }

    val weekend = (opened as? Opened.Weekend)?.weekend
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
                        weekend != null || opened == Opened.Standings || opened == Opened.Reminders -> {
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
                    WeekendResultsScreen(year = seasonYear, weekend = weekend, source = source)
                }
            }
            opened == Opened.Standings -> {
                Column(modifier = Modifier.padding(innerPadding)) {
                    StandingsScreen(year = seasonYear, source = source)
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
    onRetry: () -> Unit,
    onWeekend: (RaceWeekend) -> Unit,
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
                        WeekendSection(weekend, onClick = { onWeekend(weekend) })
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekendSection(weekend: RaceWeekend, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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

private fun circuitLine(weekend: RaceWeekend): String {
    val place = listOf(weekend.locality, weekend.country).filter { it.isNotEmpty() }.joinToString(", ")
    return when {
        weekend.circuitName.isEmpty() -> place
        place.isEmpty() -> weekend.circuitName
        else -> "${weekend.circuitName} · $place"
    }
}
