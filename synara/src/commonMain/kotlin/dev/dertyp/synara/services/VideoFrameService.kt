package dev.dertyp.synara.services

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

interface VideoFrameService {
    fun getFrames(url: String, onLoaded: () -> Unit): StateFlow<VideoFrames?>
    fun clearCache()
}

data class VideoFrames(
    val frames: List<ImageBitmap>,
    val fps: Float,
    val seeds: List<Triple<Int?, Int?, Int?>>
)
