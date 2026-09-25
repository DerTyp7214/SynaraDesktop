package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.OpusHead
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusMSDecoder
import kotlin.math.min

internal class OpusStream(head: OpusHead, applyPreSkip: Boolean) {
    val channels = head.channels
    val output = ShortArray(MAX_PACKET_FRAMES * head.channels)

    private val order = head.nativeChannelOrder
    private val scratch = ShortArray(MAX_PACKET_FRAMES * head.channels)
    private val singleStream = if (head.isMultistream) null else OpusDecoder(48000, head.channels)
    private val multiStream = if (head.isMultistream) {
        OpusMSDecoder.create(
            48000,
            head.channels,
            head.streamCount,
            head.coupledStreamCount,
            head.channelMapping
        )
    } else null

    private var remainingPreSkip = if (applyPreSkip) head.preSkip else 0

    init {
        singleStream?.setGain(head.outputGain)
        multiStream?.setGain(head.outputGain)
    }

    fun decode(data: ByteArray): Int {
        val frames = multiStream?.decodeMultistream(data, 0, data.size, scratch, 0, MAX_PACKET_FRAMES, 0)
            ?: singleStream?.decode(data, 0, data.size, scratch, 0, MAX_PACKET_FRAMES, false)
            ?: 0
        if (frames <= 0) return 0

        val skipped = min(remainingPreSkip, frames)
        remainingPreSkip -= skipped
        val kept = frames - skipped
        if (kept <= 0) return 0

        val currentOrder = order
        if (currentOrder == null) {
            System.arraycopy(scratch, skipped * channels, output, 0, kept * channels)
        } else {
            for (frame in 0 until kept) {
                val source = (skipped + frame) * channels
                val target = frame * channels
                for (channel in 0 until channels) {
                    output[target + channel] = scratch[source + currentOrder[channel]]
                }
            }
        }
        return kept * channels
    }

    companion object {
        private const val MAX_PACKET_FRAMES = 5760
    }
}
