package com.formulatracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TeamColorMark(color: String?, modifier: Modifier = Modifier) {
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
