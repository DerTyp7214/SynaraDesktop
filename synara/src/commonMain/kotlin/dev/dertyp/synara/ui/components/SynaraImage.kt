package dev.dertyp.synara.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import dev.dertyp.PlatformUUID
import dev.dertyp.synara.ui.SynaraIcons

@Composable
fun SynaraImage(
    imageId: PlatformUUID?,
    size: Dp? = null,
    modifier: Modifier = Modifier,
    aspectRatio: Float? = null,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackIcon: SynaraIcons = SynaraIcons.Songs,
    shape: Shape = MaterialTheme.shapes.small,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    var isError by remember(imageId) { mutableStateOf(false) }
    val imageRequest = rememberImageRequest(imageId, size = size ?: 0.dp)

    Box(
        modifier = modifier
            .then(if (size != null) Modifier.size(size) else Modifier)
            .then(if (aspectRatio != null) Modifier.aspectRatio(aspectRatio) else Modifier)
            .clip(shape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (imageId != null && !isError) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onState = { state ->
                    isError = state is AsyncImagePainter.State.Error
                }
            )
        } else {
            Icon(
                imageVector = fallbackIcon.get(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(if (size != null) size / 2 else 48.dp)
            )
        }
    }
}
