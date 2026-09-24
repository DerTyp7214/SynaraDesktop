package dev.dertyp.synara.ui.components.visualizer

import dev.dertyp.synara.settings.VisualizerStyle

interface VisualizerReaction {
    val mirrored: Boolean

    fun update(
        fft: FloatArray,
        isPlaying: Boolean,
        heights: FloatArray,
        bandCount: Int,
        heightPx: Float,
        minHeightPx: Float,
        deltaMs: Long
    )
}

fun VisualizerStyle.createReaction(): VisualizerReaction = when (this) {
    VisualizerStyle.Synara -> SynaraReaction()
    VisualizerStyle.Monstercat -> MonstercatReaction()
}
