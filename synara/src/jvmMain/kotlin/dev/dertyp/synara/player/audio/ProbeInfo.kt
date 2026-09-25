package dev.dertyp.synara.player.audio

import dev.dertyp.synara.player.audio.decode.AdtsHeader
import dev.dertyp.synara.player.audio.decode.Mp3Header
import dev.dertyp.synara.player.audio.decode.Mp4Track
import dev.dertyp.synara.player.audio.decode.VbriInfo
import dev.dertyp.synara.player.audio.decode.XingInfo

sealed class ProbeInfo {
    class Flac(val metadata: ByteArray, val durationMs: Long?) : ProbeInfo()

    class OggOpus(val metadata: ByteArray) : ProbeInfo()

    class Pcm(val header: PcmHeader) : ProbeInfo() {
        val durationMs: Long?
            get() = if (header.dataSize > 0 && header.bytesPerFrame > 0 && header.sampleRate > 0) {
                header.dataSize / header.bytesPerFrame * 1000 / header.sampleRate
            } else null
    }

    class Mp3(
        val firstFrameOffset: Long,
        val header: Mp3Header,
        val xing: XingInfo?,
        val vbri: VbriInfo?,
    ) : ProbeInfo() {
        val audioStart: Long
            get() = if (xing != null || vbri != null) firstFrameOffset + header.frameLength else firstFrameOffset
    }

    class Mp4(val track: Mp4Track) : ProbeInfo()

    class Adts(val audioStart: Long, val header: AdtsHeader, val averageFrameBytes: Double) : ProbeInfo()
}
