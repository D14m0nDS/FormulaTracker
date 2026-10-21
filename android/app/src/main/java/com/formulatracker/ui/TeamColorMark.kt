package com.formulatracker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
fun TeamColorMark(color: String?, team: String? = null, modifier: Modifier = Modifier) {
    val logo = teamLogoRes(team)
    if (logo != null) {
        val maxWidth = if (teamArtKey(team.orEmpty()) == "aston_martin") 50.dp else 40.dp
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            modifier = modifier.sizeIn(maxWidth = maxWidth, maxHeight = 22.dp),
            contentScale = ContentScale.Fit,
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
