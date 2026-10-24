package com.formulatracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.formulatracker.schedule.RaceWeekend
import com.formulatracker.schedule.ScheduledSession
import com.formulatracker.schedule.SeasonSchedule
import com.formulatracker.ui.theme.Sizes
import com.formulatracker.ui.theme.Spacing
import com.formulatracker.ui.theme.numeric
import com.formulatracker.ui.theme.overline
import com.formulatracker.ui.theme.trackerColors
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

private val HEADER_HEIGHT = 172.dp
private val CHIP_CLEARANCE = 48.dp

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
            NoticeBanner(
                message = updatedMessage,
                modifier = Modifier.padding(start = Spacing.lg, top = Spacing.sm, end = Spacing.lg),
                tone = NoticeTone.Caution,
            )
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
                        contentPadding = PaddingValues(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
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
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
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
                            .height(HEADER_HEIGHT)
                            .skeleton(MaterialTheme.shapes.extraSmall),
                    )
                    Row(
                        modifier = Modifier.padding(Spacing.lg),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SkeletonLine(Modifier.width(120.dp), height = 18.dp)
                        SkeletonLine(Modifier.width(64.dp), height = 18.dp)
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
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "sessions",
    )
    val interaction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction),
        colors = trackerCardColors(),
        border = trackerCardBorder(emphasized = mark != null),
        interactionSource = interaction,
    ) {
        Column {
            WeekendHeader(weekend, mark)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dates = weekendDateRange(weekend.sessions.map { it.start }, ZoneId.systemDefault())
                if (dates != null) {
                    Icon(
                        AppIcons.Calendar,
                        contentDescription = null,
                        modifier = Modifier.size(Sizes.iconSmall),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = dates,
                        modifier = Modifier.padding(start = Spacing.sm),
                        style = MaterialTheme.typography.titleMedium.numeric().copy(fontSize = 18.sp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Results",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(Sizes.iconSmall),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(240)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(120)),
            ) {
                Column(modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    weekend.sessions.forEach { session ->
                        SessionRow(session)
                    }
                }
            }
            if (weekend.sessions.isNotEmpty()) {
                TextButton(
                    onClick = onToggle,
                    modifier = Modifier
                        .padding(start = Spacing.sm, bottom = Spacing.xs)
                        .heightIn(min = Sizes.touchTarget)
                        .semantics {
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                        },
                ) {
                    Text(if (expanded) "Show less" else "Show more")
                    Spacer(Modifier.size(Spacing.xs))
                    Icon(
                        AppIcons.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier
                            .size(Sizes.iconSmall)
                            .rotate(chevron),
                    )
                }
            } else {
                Spacer(Modifier.height(Spacing.md))
            }
        }
    }
}

@Composable
private fun WeekendHeader(weekend: RaceWeekend, mark: String?) {
    val photo = circuitImageRes(weekend.circuitName)
    if (photo != null) {
        val scrim = MaterialTheme.trackerColors.photoScrim
        // Large text fills most of the photo, so it gets a light base scrim at the top.
        val topScrim = if (LocalDensity.current.fontScale > 1.3f) scrim.copy(alpha = 0.4f) else Color.Transparent
        // A minimum height: larger text grows the photo instead of spilling above the scrim.
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = HEADER_HEIGHT)) {
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
                            0.35f to topScrim,
                            1f to scrim.copy(alpha = 0.88f),
                        ),
                    ),
            )
            if (mark != null) {
                MarkChip(
                    label = mark,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.md),
                )
            }
            HeaderText(
                weekend = weekend,
                onPhoto = true,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        bottom = Spacing.lg,
                        // Room for the chip once the text is tall enough to fill the photo.
                        top = if (mark != null) CHIP_CLEARANCE else Spacing.lg,
                    ),
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (mark != null) {
                MarkChip(label = mark)
            }
            HeaderText(weekend = weekend, onPhoto = false)
        }
    }
}

@Composable
private fun HeaderText(weekend: RaceWeekend, onPhoto: Boolean, modifier: Modifier = Modifier) {
    val primaryText = if (onPhoto) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryText = if (onPhoto) Color.White.copy(alpha = 0.86f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "ROUND ${weekend.round}",
            style = MaterialTheme.typography.labelSmall.overline(),
            color = secondaryText,
        )
        Text(
            text = weekend.name,
            style = MaterialTheme.typography.headlineSmall,
            color = primaryText,
        )
        val place = circuitLine(weekend)
        if (place.isNotEmpty()) {
            Text(
                text = place,
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryText,
            )
        }
    }
}

@Composable
private fun MarkChip(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SessionRow(session: ScheduledSession) {
    val (day, clock) = sessionDayAndClock(session.start, ZoneId.systemDefault(), Locale.getDefault())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = shortSessionLabel(session.type, session.name),
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.titleSmall,
            textDecoration = if (session.cancelled) TextDecoration.LineThrough else null,
            color = if (session.cancelled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (session.cancelled) {
            Text(
                text = "Cancelled",
                modifier = Modifier
                    .weight(1f)
                    .padding(end = Spacing.xs),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = day,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = clock,
                style = MaterialTheme.typography.titleMedium.numeric(),
            )
        }
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

internal fun sessionDayAndClock(start: Instant, zone: ZoneId, locale: Locale): Pair<String, String> {
    val local = start.atZone(zone)
    val day = DateTimeFormatter.ofPattern("EEE", locale).format(local)
    val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(local)
    return day to clock
}

internal fun formatSessionStart(start: Instant, zone: ZoneId, locale: Locale): String {
    val (day, clock) = sessionDayAndClock(start, zone, locale)
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
