package com.formulatracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Sizes {
    val touchTarget = 48.dp
    val portrait = 40.dp
    val portraitLarge = 84.dp
    val icon = 24.dp
    val iconSmall = 18.dp
}

// Roles Material 3 has no slot for: race position changes and podium places.
@Immutable
data class TrackerColors(
    val gained: Color,
    val lost: Color,
    val firstPlace: Color,
    val secondPlace: Color,
    val thirdPlace: Color,
    val onPodium: Color,
    val photoScrim: Color,
)

internal val lightTrackerColors = TrackerColors(
    gained = Color(0xFF1B7F3B),
    lost = Color(0xFFC62828),
    firstPlace = Color(0xFFFFE08A),
    secondPlace = Color(0xFFDCDFE6),
    thirdPlace = Color(0xFFF2CDA9),
    onPodium = Color(0xFF1C1B19),
    photoScrim = Color(0xFF0E0D0C),
)

internal val darkTrackerColors = TrackerColors(
    gained = Color(0xFF5FD686),
    lost = Color(0xFFFF7A6E),
    firstPlace = Color(0xFF6B5200),
    secondPlace = Color(0xFF4A505B),
    thirdPlace = Color(0xFF6A4524),
    onPodium = Color(0xFFF6F1E7),
    photoScrim = Color(0xFF000000),
)

internal val LocalTrackerColors = staticCompositionLocalOf { lightTrackerColors }

val MaterialTheme.trackerColors: TrackerColors
    @Composable
    @ReadOnlyComposable
    get() = LocalTrackerColors.current
