package com.formulatracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.formulatracker.schedule.RaceWeekend
import com.formulatracker.schedule.ScheduledSession
import com.formulatracker.schedule.SeasonSchedule
import com.formulatracker.ui.theme.tabular
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

sealed interface ScheduleUiState {
    data object Loading : ScheduleUiState
    data class Ready(val schedule: SeasonSchedule) : ScheduleUiState
    data class Failed(val message: String) : ScheduleUiState
}

@Composable
fun ScheduleContent(
    state: ScheduleUiState,
    updatedMessage: String?,
    onRetry: () -> Unit,
    onWeekend: (RaceWeekend) -> Unit,
    listState: LazyListState,
    openRounds: Set<Int>,
    onToggleRound: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        if (updatedMessage != null) {
            NoticeBanner(updatedMessage, Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp))
        }
        when (state) {
            ScheduleUiState.Loading -> CalendarSkeleton()
            is ScheduleUiState.Failed -> {
                StatusMessage(
                    title = state.message,
                    icon = AppIcons.Warning,
                    actionLabel = "Try again",
                    onAction = onRetry,
                )
            }
            is ScheduleUiState.Ready -> {
                if (state.schedule.events.isEmpty()) {
                    StatusMessage(title = "No races on this calendar", icon = AppIcons.Calendar)
                } else {
                    val highlight = weekendHighlight(state.schedule.events, Instant.now())
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(state.schedule.events, key = { it.round }) { weekend ->
                            WeekendSection(
                                weekend,
                                mark = highlight?.takeIf { it.round == weekend.round }?.label,
                                expanded = weekend.round in openRounds,
                                onToggle = { onToggleRound(weekend.round) },
                                onClick = { onWeekend(weekend) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(2) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = trackerCardColors(),
                border = trackerCardBorder(),
            ) {
                Column {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(156.dp)
                            .skeleton(MaterialTheme.shapes.extraSmall),
                    )
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SkeletonLine(Modifier.fillMaxWidth(0.6f), height = 18.dp)
                        SkeletonLine(Modifier.fillMaxWidth(0.8f), height = 12.dp)
                        SkeletonLine(Modifier.fillMaxWidth(0.3f), height = 12.dp)
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
