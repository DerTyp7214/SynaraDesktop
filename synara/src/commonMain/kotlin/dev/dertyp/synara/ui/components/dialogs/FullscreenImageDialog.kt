package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.dertyp.PlatformUUID
import dev.dertyp.synara.ui.components.rememberImageRequest

@Composable
fun FullscreenImageDialog(
    isOpen: Boolean,
    imageId: PlatformUUID?,
    onDismissRequest: () -> Unit
) {
    if (isOpen) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable { onDismissRequest() },
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = isOpen,
                    enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.8f, animationSpec = tween(300)),
                    exit = fadeOut(tween(300)) + scaleOut(targetScale = 0.8f, animationSpec = tween(300))
                ) {
                    AsyncImage(
                        model = rememberImageRequest(imageId, size = 1024.dp),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(32.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}
