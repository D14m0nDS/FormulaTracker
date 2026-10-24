package com.formulatracker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

// Logos that are drawn in solid black and disappear on dark surfaces.
private val BLACK_LOGOS = setOf("cadillac")

@Composable
fun TeamColorMark(color: String?, team: String? = null, modifier: Modifier = Modifier) {
    val logo = teamLogoRes(team)
    if (logo != null) {
        val key = teamArtKey(team.orEmpty())
        val maxWidth = if (key == "aston_martin") 50.dp else 40.dp
        val darkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        val lightenLogo = key in BLACK_LOGOS && darkSurface
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            modifier = modifier.sizeIn(maxWidth = maxWidth, maxHeight = 22.dp),
            contentScale = ContentScale.Fit,
            colorFilter = if (lightenLogo) ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null,
        )
        return
    }
    val parsed = parseTeamColor(color) ?: return
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(parsed),
    )
}

internal fun parseTeamColor(color: String?): Color? {
    val hex = color?.trim().orEmpty()
    if (!hex.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
        return null
    }
    val rgb = hex.substring(1).toLong(16)
    return Color(0xFF000000 or rgb)
}
