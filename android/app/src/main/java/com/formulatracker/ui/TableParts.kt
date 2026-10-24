package com.formulatracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.formulatracker.ui.theme.Sizes
import com.formulatracker.ui.theme.Spacing
import com.formulatracker.ui.theme.numeric
import com.formulatracker.ui.theme.overline
import com.formulatracker.ui.theme.trackerColors

// Pieces shared by the results and standings tables so both read as one table style.

internal val POSITION_COLUMN_WIDTH = 32.dp

@Composable
internal fun favoriteTint(highlighted: Boolean): Color {
    return if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    } else {
        Color.Transparent
    }
}

@Composable
internal fun <T> DividedColumn(items: List<T>, row: @Composable (T) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            row(item)
        }
        Spacer(Modifier.height(Spacing.xs))
    }
}

@Composable
internal fun HeaderLabel(text: String, modifier: Modifier, align: TextAlign) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall.overline(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        maxLines = 1,
        // At large font sizes "Pos" outgrows its narrow column; it may spill into the gap beside it.
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

@Composable
internal fun PositionMark(position: String) {
    val colors = MaterialTheme.trackerColors
    val podium = when (position.trim()) {
        "1" -> colors.firstPlace
        "2" -> colors.secondPlace
        "3" -> colors.thirdPlace
        else -> null
    }
    Box(
        modifier = Modifier
            .size(POSITION_COLUMN_WIDTH)
            .clip(RoundedCornerShape(9.dp))
            .background(podium ?: Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = position.ifBlank { "—" },
            style = MaterialTheme.typography.titleMedium.numeric(),
            color = if (podium != null) colors.onPodium else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
internal fun CardMessage(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.xs, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
                Text(actionLabel)
            }
        }
    }
}
