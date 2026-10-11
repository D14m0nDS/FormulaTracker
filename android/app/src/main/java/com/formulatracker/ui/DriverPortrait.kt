package com.formulatracker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.formulatracker.drivers.DriverPhotos

@Composable
fun DriverPortrait(
    code: String,
    photoUrl: String?,
    photos: DriverPhotos,
    modifier: Modifier = Modifier,
) {
    val label = code.ifBlank { "?" }
    var image by remember(photoUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photoUrl) {
        image = if (photoUrl.isNullOrBlank()) null else photos.load(photoUrl)
    }
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
            Text(label.take(3), style = MaterialTheme.typography.labelSmall)
        }
    }
}
