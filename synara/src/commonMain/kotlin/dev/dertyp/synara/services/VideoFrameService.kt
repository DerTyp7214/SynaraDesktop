package dev.dertyp.synara.services

import kotlinx.coroutines.flow.StateFlow

interface VideoFrameService {
    fun getFrames(key: String, loader: suspend () -> ByteArray?, onLoaded: () -> Unit): StateFlow<VideoFrames?>
    fun clearCache()
}

expect class VideoFrame

data class VideoFrames(
    val frames: List<VideoFrame>,
    val fps: Float,
    val seeds: List<Triple<Int?, Int?, Int?>>
)
