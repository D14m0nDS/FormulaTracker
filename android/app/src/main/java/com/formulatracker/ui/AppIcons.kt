package com.formulatracker.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

internal object AppIcons {
    val Calendar: ImageVector = icon("Calendar") {
        stroke {
            moveTo(4.5f, 7f)
            lineTo(4.5f, 19.5f)
            lineTo(19.5f, 19.5f)
            lineTo(19.5f, 7f)
            close()
            moveTo(4.5f, 11f)
            lineTo(19.5f, 11f)
            moveTo(8f, 4.5f)
            lineTo(8f, 8.5f)
            moveTo(16f, 4.5f)
            lineTo(16f, 8.5f)
        }
    }

    val Standings: ImageVector = icon("Standings") {
        stroke(2.1f) {
            moveTo(4.5f, 7f)
            lineTo(19.5f, 7f)
            moveTo(4.5f, 12f)
            lineTo(14.5f, 12f)
            moveTo(4.5f, 17f)
            lineTo(17f, 17f)
        }
    }

    val Favorites: ImageVector = icon("Favorites") {
        fill {
            moveTo(12f, 3.2f)
            lineTo(14.6f, 8.7f)
            lineTo(20.6f, 9.4f)
            lineTo(16.1f, 13.6f)
            lineTo(17.4f, 19.5f)
            lineTo(12f, 16.6f)
            lineTo(6.6f, 19.5f)
            lineTo(7.9f, 13.6f)
            lineTo(3.4f, 9.4f)
            lineTo(9.4f, 8.7f)
            close()
        }
    }

    val Reminders: ImageVector = icon("Reminders") {
        fill {
            moveTo(12f, 3.2f)
            curveTo(9.1f, 3.2f, 7.1f, 5.8f, 7.1f, 8.8f)
            lineTo(7.1f, 12.1f)
            lineTo(5f, 15.2f)
            lineTo(19f, 15.2f)
            lineTo(16.9f, 12.1f)
            lineTo(16.9f, 8.8f)
            curveTo(16.9f, 5.8f, 14.9f, 3.2f, 12f, 3.2f)
            close()
        }
        stroke(1.75f) {
            moveTo(10f, 16.4f)
            curveTo(10.3f, 18f, 11f, 19.2f, 12f, 19.2f)
            curveTo(13f, 19.2f, 13.7f, 18f, 14f, 16.4f)
        }
    }

    val ChevronLeft: ImageVector = icon("ChevronLeft") {
        stroke {
            moveTo(14.5f, 5.5f)
            lineTo(8f, 12f)
            lineTo(14.5f, 18.5f)
        }
    }

    val ChevronRight: ImageVector = icon("ChevronRight") {
        stroke {
            moveTo(9.5f, 5.5f)
            lineTo(16f, 12f)
            lineTo(9.5f, 18.5f)
        }
    }

    val ChevronDown: ImageVector = icon("ChevronDown") {
        stroke {
            moveTo(6f, 9f)
            lineTo(12f, 15f)
            lineTo(18f, 9f)
        }
    }

    val Check: ImageVector = icon("Check") {
        stroke(2f) {
            moveTo(5f, 12.5f)
            lineTo(10f, 17.5f)
            lineTo(19.5f, 6.5f)
        }
    }

    val ArrowBack: ImageVector = icon("ArrowBack") {
        stroke(2f) {
            moveTo(19.5f, 12f)
            lineTo(4.5f, 12f)
            moveTo(11f, 5.5f)
            lineTo(4.5f, 12f)
            lineTo(11f, 18.5f)
        }
    }

    val Minus: ImageVector = icon("Minus") {
        stroke(2f) {
            moveTo(6f, 12f)
            lineTo(18f, 12f)
        }
    }

    val Plus: ImageVector = icon("Plus") {
        stroke(2f) {
            moveTo(6f, 12f)
            lineTo(18f, 12f)
            moveTo(12f, 6f)
            lineTo(12f, 18f)
        }
    }

    val Info: ImageVector = icon("Info") {
        stroke(1.75f) {
            moveTo(12f, 3.5f)
            arcTo(8.5f, 8.5f, 0f, true, true, 12f, 20.5f)
            arcTo(8.5f, 8.5f, 0f, true, true, 12f, 3.5f)
            close()
            moveTo(12f, 11f)
            lineTo(12f, 16.5f)
            moveTo(12f, 7.9f)
            lineTo(12f, 8f)
        }
    }

    val Warning: ImageVector = icon("Warning") {
        stroke(1.75f) {
            moveTo(12f, 4f)
            lineTo(21f, 19.5f)
            lineTo(3f, 19.5f)
            close()
            moveTo(12f, 10f)
            lineTo(12f, 14.5f)
            moveTo(12f, 17f)
            lineTo(12f, 17.1f)
        }
    }

    val Flag: ImageVector = icon("Flag") {
        stroke(1.75f) {
            moveTo(5.5f, 20f)
            lineTo(5.5f, 4f)
            moveTo(5.5f, 5f)
            lineTo(18.5f, 5f)
            lineTo(15.5f, 9f)
            lineTo(18.5f, 13f)
            lineTo(5.5f, 13f)
        }
    }
}

private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector {
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()
}

private fun ImageVector.Builder.stroke(width: Float = 1.75f, block: PathBuilder.() -> Unit) {
    addPath(
        pathData = PathBuilder().apply(block).nodes,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

private fun ImageVector.Builder.fill(block: PathBuilder.() -> Unit) {
    addPath(
        pathData = PathBuilder().apply(block).nodes,
        fill = SolidColor(Color.Black),
    )
}
