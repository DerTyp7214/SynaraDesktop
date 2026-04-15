package dev.dertyp.synara.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

@Composable
expect fun SynaraVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    loop: Boolean = true,
    onLoaded: () -> Unit = {},
    onFrame: (Triple<Int?, Int?, Int?>) -> Unit = {},
    frameIndex: Int? = null
)

val LocalVideoFrameIndex = staticCompositionLocalOf<Int?> { null }
