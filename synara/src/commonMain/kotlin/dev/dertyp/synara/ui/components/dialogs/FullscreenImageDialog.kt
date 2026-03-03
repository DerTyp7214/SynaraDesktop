package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.dertyp.PlatformUUID
import dev.dertyp.synara.ui.components.SynaraImage

@Composable
fun FullscreenImageDialog(
    isOpen: Boolean,
    imageId: PlatformUUID?,
    onDismissRequest: () -> Unit
) {
    SynaraDialog(
        isOpen = isOpen,
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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismissRequest() },
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = isOpen,
                enter = fadeIn(tween(300)) + scaleIn(
                    initialScale = 0.8f,
                    animationSpec = tween(300)
                ),
                exit = fadeOut(tween(300)) + scaleOut(
                    targetScale = 0.8f,
                    animationSpec = tween(300)
                )
            ) {
                SynaraImage(
                    imageId = imageId,
                    modifier = Modifier.padding(32.dp),
                    shape = RoundedCornerShape(8.dp),
                    aspectRatio = 1f,
                    backgroundColor = Color.Transparent,
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
