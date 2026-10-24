package com.formulatracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.formulatracker.drivers.DriverPhotos
import com.formulatracker.ui.theme.Sizes

@Composable
fun DriverPortrait(
    code: String,
    photoUrl: String?,
    photos: DriverPhotos,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.portrait,
) {
    val label = code.ifBlank { "?" }
    var image by remember(photoUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photoUrl) {
        image = if (photoUrl.isNullOrBlank()) null else photos.load(photoUrl)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val portrait = image
        if (portrait != null) {
            Image(
                bitmap = portrait,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            SilhouetteMark(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = label },
            )
        }
    }
}

// Stands in for a missing headshot. The driver code is already printed beside every
// portrait, so repeating it inside the circle only adds noise.
@Composable
private fun SilhouetteMark(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        drawCircle(
            color = tint,
            radius = width * 0.17f,
            center = Offset(width / 2f, height * 0.39f),
        )
        drawOval(
            color = tint,
            topLeft = Offset(width * 0.2f, height * 0.62f),
            size = Size(width * 0.6f, height * 0.55f),
        )
    }
}
