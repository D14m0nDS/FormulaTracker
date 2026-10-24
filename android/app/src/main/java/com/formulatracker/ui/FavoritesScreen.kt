package com.formulatracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.formulatracker.drivers.DriverPhotos
import com.formulatracker.favorites.FavoriteSelection
import com.formulatracker.favorites.matchesDriver
import com.formulatracker.favorites.matchesTeam
import com.formulatracker.schedule.ScheduleSource
import com.formulatracker.teams.latestTeamName
import com.formulatracker.teams.teamColor
import com.formulatracker.standings.DriverStanding
import com.formulatracker.standings.SeasonStandings
import com.formulatracker.ui.theme.Sizes
import com.formulatracker.ui.theme.Spacing
import com.formulatracker.ui.theme.numeric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

private const val FAVORITES_GIVE_UP_MILLIS = 8_000L
private const val FAVORITES_RETRY_PAUSE_MILLIS = 1_000L

private val TEAM_LOGO_SLOT_WIDTH = 52.dp

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
        FavoritesLoad.Loading -> SkeletonList(rows = 8, label = "Loading drivers and teams")
        FavoritesLoad.Failed -> {
            StatusMessage(
                title = "Couldn't load drivers and teams",
                icon = AppIcons.Warning,
                actionLabel = "Try again",
                onAction = { attempt += 1 },
            )
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
    val noDriver = favorite.driverCode.isEmpty() && favorite.driverName.isEmpty()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Optional. Matching rows are highlighted, and scheduled reminders use this name.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ChoiceHeader(
                title = "Driver",
                choice = favorite.driverName.ifBlank { favorite.driverCode }.ifBlank { "None" },
                chosen = !noDriver,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ChoiceRow("None", selected = noDriver) {
                onFavorite(favorite.copy(driverCode = "", driverName = ""))
            }
        }
        itemsIndexed(standings.drivers, key = { index, _ -> "driver-$index" }) { _, driver ->
            DriverChoiceCard(
                driver = driver,
                selected = favorite.matchesDriver(driver),
                photoUrl = photoUrls[driver.code.uppercase()],
                photos = photos,
                team = latestTeamName(driver.teams),
                onClick = {
                    onFavorite(
                        favorite.copy(
                            driverCode = driver.code,
                            driverName = driver.familyName.ifBlank { driver.code },
                        ),
                    )
                },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ChoiceHeader(
                title = "Team",
                choice = favorite.team.ifBlank { "None" },
                chosen = favorite.team.isNotEmpty(),
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ChoiceRow("None", selected = favorite.team.isEmpty()) {
                onFavorite(favorite.copy(team = ""))
            }
        }
        itemsIndexed(
            standings.constructors,
            key = { index, _ -> "team-$index" },
            span = { _, _ -> GridItemSpan(maxLineSpan) },
        ) { _, constructor ->
            ChoiceRow(
                constructor.team,
                selected = favorite.matchesTeam(constructor.team),
                leading = {
                    TeamColorMark(color = teamColor(teamColors, constructor.team), team = constructor.team)
                },
            ) {
                onFavorite(favorite.copy(team = constructor.team))
            }
        }
    }
}

@Composable
private fun ChoiceHeader(title: String, choice: String, chosen: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = choice,
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
            color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private class ChoiceStyle(val container: Color, val border: BorderStroke)

@Composable
private fun choiceStyle(selected: Boolean): ChoiceStyle {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) colors.primaryContainer.copy(alpha = 0.4f) else colors.surfaceContainerLowest,
        animationSpec = tween(160),
        label = "choiceContainer",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.outlineVariant,
        animationSpec = tween(160),
        label = "choiceBorder",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 2.dp else 1.dp,
        animationSpec = tween(160),
        label = "choiceBorderWidth",
    )
    return ChoiceStyle(container, BorderStroke(borderWidth, borderColor))
}

@Composable
private fun SelectedBadge(selected: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = selected,
        modifier = modifier,
        enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.6f),
        exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.6f),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val style = choiceStyle(selected)
    val interactions = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactions)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            },
        shape = MaterialTheme.shapes.medium,
        color = style.container,
        border = style.border,
        interactionSource = interactions,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(modifier = Modifier.width(TEAM_LOGO_SLOT_WIDTH), contentAlignment = Alignment.Center) {
                if (leading != null) {
                    leading()
                } else {
                    Icon(
                        AppIcons.Minus,
                        contentDescription = null,
                        modifier = Modifier.size(Sizes.iconSmall),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            SelectedBadge(selected)
        }
    }
}

private fun FavoriteSelection.matchesDriver(driver: DriverStanding): Boolean {
    return matchesDriver(driver.code, driver.familyName)
}

@Composable
private fun DriverChoiceCard(
    driver: DriverStanding,
    selected: Boolean,
    photoUrl: String?,
    photos: DriverPhotos,
    team: String?,
    onClick: () -> Unit,
) {
    val stats = driverCardStats(driver, team)
    val name = driver.familyName.ifBlank { driver.code }
    val style = choiceStyle(selected)
    val interactions = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactions)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            },
        colors = CardDefaults.cardColors(containerColor = style.container),
        border = style.border,
        interactionSource = interactions,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // The position sits in the corner like the Standings table, so it never has to share
            // a line with the points.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clearAndSetSemantics { contentDescription = "Position ${stats.place}" },
            ) {
                PositionMark(stats.place)
            }
            SelectedBadge(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.sm),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // The extra top padding keeps the portrait clear of the position badge.
                    .padding(start = 10.dp, end = 10.dp, top = Spacing.lg, bottom = Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                DriverPortrait(
                    code = driver.code.ifBlank { name },
                    photoUrl = photoUrl,
                    photos = photos,
                    size = Sizes.portraitLarge,
                )
                Text(
                    text = name,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stats.points,
                        style = MaterialTheme.typography.titleSmall.numeric(),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    // Own line, so team names such as "Aston Martin" are not cut off.
                    Text(
                        text = stats.team,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal data class DriverCardStats(
    val place: String,
    val points: String,
    val team: String,
)

internal fun driverCardStats(driver: DriverStanding, team: String?): DriverCardStats {
    return DriverCardStats(
        place = driver.positionText.trim().ifEmpty { "—" },
        points = "${formatPoints(driver.points)} pts",
        team = team?.trim().orEmpty().ifEmpty { "—" },
    )
}
