package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.ProbeInfo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdtsHeaderTest {
    private fun headerBytes(
        profile: Int = 1,
        rateIndex: Int = 4,
        channels: Int = 2,
        frameLength: Int = 200,
        protectionAbsent: Boolean = true,
    ): ByteArray {
        val bytes = ByteArray(if (protectionAbsent) 7 else 9)
        bytes[0] = 0xFF.toByte()
        bytes[1] = (0xF0 or (if (protectionAbsent) 1 else 0)).toByte()
        bytes[2] = ((profile shl 6) or (rateIndex shl 2) or (channels shr 2)).toByte()
        bytes[3] = (((channels and 3) shl 6) or ((frameLength shr 11) and 3)).toByte()
        bytes[4] = ((frameLength shr 3) and 0xFF).toByte()
        bytes[5] = (((frameLength and 7) shl 5) or 0x1F).toByte()
        bytes[6] = 0xFC.toByte()
        return bytes
    }

    private fun frame(
        frameLength: Int,
        profile: Int = 1,
        rateIndex: Int = 4,
        channels: Int = 2,
        protectionAbsent: Boolean = true,
    ): ByteArray {
        val header = headerBytes(profile, rateIndex, channels, frameLength, protectionAbsent)
        return header + ByteArray(frameLength - header.size) { 0x22 }
    }

    private fun info(audioStart: Long = 0, averageFrameBytes: Double = 372.0, rateIndex: Int = 3): ProbeInfo.Adts =
        ProbeInfo.Adts(audioStart, AdtsHeader(1, rateIndex, 2, 372, true), averageFrameBytes)

    @Test
    fun parsesAacLcStereoAt44100() {
        val header = assertNotNull(AdtsHeader.parse(headerBytes(profile = 1, rateIndex = 4, channels = 2, frameLength = 371), 0))
        assertEquals(1, header.profile)
        assertEquals(4, header.sampleRateIndex)
        assertEquals(2, header.channelConfig)
        assertEquals(371, header.frameLength)
        assertTrue(header.protectionAbsent)
        assertEquals(7, header.headerLength)
        assertEquals(44100, header.sampleRate)
    }

    @Test
    fun audioSpecificConfigOfAacLcStereo44100() {
        val header = assertNotNull(AdtsHeader.parse(headerBytes(profile = 1, rateIndex = 4, channels = 2), 0))
        assertContentEquals(byteArrayOf(0x12, 0x10), header.audioSpecificConfig())
    }

    @Test
    fun audioSpecificConfigCarriesChannelsAndRate() {
        val header = assertNotNull(AdtsHeader.parse(headerBytes(profile = 1, rateIndex = 3, channels = 6), 0))
        assertContentEquals(byteArrayOf(0x11, 0xB0.toByte()), header.audioSpecificConfig())
    }

    @Test
    fun readsTheHighBitsOfTheFrameLength() {
        val header = assertNotNull(AdtsHeader.parse(headerBytes(frameLength = 8191), 0))
        assertEquals(8191, header.frameLength)
        assertEquals(1000, assertNotNull(AdtsHeader.parse(headerBytes(frameLength = 1000), 0)).frameLength)
    }

    @Test
    fun readsTheChannelConfigAcrossBothBytes() {
        for (channels in 0..7) {
            val header = assertNotNull(AdtsHeader.parse(headerBytes(channels = channels), 0), "channels $channels")
            assertEquals(channels, header.channelConfig, "channels $channels")
        }
    }

    @Test
    fun protectionPresentGivesNineByteHeader() {
        val header = assertNotNull(AdtsHeader.parse(headerBytes(protectionAbsent = false, frameLength = 300), 0))
        assertFalse(header.protectionAbsent)
        assertEquals(9, header.headerLength)
    }

    @Test
    fun parsesAtANonZeroOffset() {
        val bytes = ByteArray(5) { 0x11 } + headerBytes(frameLength = 300)
        val header = assertNotNull(AdtsHeader.parse(bytes, 5))
        assertEquals(300, header.frameLength)
    }

    @Test
    fun rejectsAMissingSyncWord() {
        val first = headerBytes()
        first[0] = 0xFE.toByte()
        assertNull(AdtsHeader.parse(first, 0))

        val second = headerBytes()
        second[1] = 0xE1.toByte()
        assertNull(AdtsHeader.parse(second, 0))
    }

    @Test
    fun rejectsNonZeroLayerBits() {
        val bytes = headerBytes()
        bytes[1] = 0xF3.toByte()
        assertNull(AdtsHeader.parse(bytes, 0))
    }

    @Test
    fun rejectsSampleRateIndexBeyondTheTable() {
        assertNull(AdtsHeader.parse(headerBytes(rateIndex = 13), 0))
        assertNull(AdtsHeader.parse(headerBytes(rateIndex = 15), 0))
        assertEquals(7350, assertNotNull(AdtsHeader.parse(headerBytes(rateIndex = 12), 0)).sampleRate)
    }

    @Test
    fun rejectsAFrameLengthNotLargerThanTheHeader() {
        assertNull(AdtsHeader.parse(headerBytes(frameLength = 5), 0))
        assertNull(AdtsHeader.parse(headerBytes(frameLength = 7), 0))
        assertNotNull(AdtsHeader.parse(headerBytes(frameLength = 8), 0))
        assertNull(AdtsHeader.parse(headerBytes(protectionAbsent = false, frameLength = 9), 0))
        assertNotNull(AdtsHeader.parse(headerBytes(protectionAbsent = false, frameLength = 10), 0))
    }

    @Test
    fun rejectsAnOffsetThatLeavesFewerThanSevenBytes() {
        val bytes = headerBytes()
        assertNull(AdtsHeader.parse(bytes, 1))
        assertNull(AdtsHeader.parse(bytes, bytes.size))
        assertNull(AdtsHeader.parse(bytes, bytes.size + 10))
        assertNull(AdtsHeader.parse(bytes, -1))
        assertNull(AdtsHeader.parse(bytes.copyOf(6), 0))
    }

    @Test
    fun compatibilityComparesProfileRateAndChannelsOnly() {
        val base = AdtsHeader(1, 4, 2, 200, true)
        assertTrue(base.isCompatible(AdtsHeader(1, 4, 2, 999, false)))
        assertFalse(base.isCompatible(AdtsHeader(0, 4, 2, 200, true)))
        assertFalse(base.isCompatible(AdtsHeader(1, 3, 2, 200, true)))
        assertFalse(base.isCompatible(AdtsHeader(1, 4, 1, 200, true)))
    }

    @Test
    fun parseConfirmedAcceptsACompatibleFollowingHeader() {
        val bytes = frame(20) + frame(30)
        val header = assertNotNull(AdtsHeader.parseConfirmed(bytes, 0))
        assertEquals(20, header.frameLength)
    }

    @Test
    fun parseConfirmedWorksAtANonZeroOffset() {
        val bytes = ByteArray(9) { 0x11 } + frame(20) + frame(30)
        val header = assertNotNull(AdtsHeader.parseConfirmed(bytes, 9))
        assertEquals(20, header.frameLength)
    }

    @Test
    fun parseConfirmedRejectsAFollowingHeaderWithAnotherSampleRate() {
        val bytes = frame(20, rateIndex = 4) + frame(30, rateIndex = 3)
        assertNull(AdtsHeader.parseConfirmed(bytes, 0))
    }

    @Test
    fun parseConfirmedRejectsAFollowingHeaderThatIsNotAHeader() {
        val bytes = frame(20) + ByteArray(30) { 0x33 }
        assertNull(AdtsHeader.parseConfirmed(bytes, 0))
    }

    @Test
    fun parseConfirmedRejectsAHeaderThatDoesNotParse() {
        val bytes = ByteArray(60) { 0x33 }
        assertNull(AdtsHeader.parseConfirmed(bytes, 0))
    }

    @Test
    fun parseConfirmedRejectsAHeaderIncompatibleWithTheReference() {
        val bytes = frame(20) + frame(30)
        assertNull(AdtsHeader.parseConfirmed(bytes, 0, reference = AdtsHeader(1, 3, 2, 20, true)))
    }

    @Test
    fun parseConfirmedAcceptsAHeaderCompatibleWithTheReference() {
        val bytes = frame(20) + frame(30)
        val header = assertNotNull(AdtsHeader.parseConfirmed(bytes, 0, reference = AdtsHeader(1, 4, 2, 999, false)))
        assertEquals(20, header.frameLength)
    }

    @Test
    fun parseConfirmedRejectsWhenTheNextHeaderLiesBeyondTheArray() {
        val bytes = frame(20) + headerBytes(frameLength = 30).copyOf(6)
        assertNull(AdtsHeader.parseConfirmed(bytes, 0))
    }

    @Test
    fun parseConfirmedAcceptsWhenTheNextHeaderEndsExactlyAtTheArrayEnd() {
        val bytes = frame(20) + headerBytes(frameLength = 30)
        assertNotNull(AdtsHeader.parseConfirmed(bytes, 0))
    }

    @Test
    fun parseConfirmedRejectsWhenTheNextHeaderLiesBeyondTheGivenLength() {
        val bytes = frame(20) + frame(30)
        assertNotNull(AdtsHeader.parseConfirmed(bytes, 0, length = bytes.size))
        assertNotNull(AdtsHeader.parseConfirmed(bytes, 0, length = 27))
        assertNull(AdtsHeader.parseConfirmed(bytes, 0, length = 26))
    }

    @Test
    fun bitRateFollowsAverageFrameBytesAndSampleRate() {
        assertEquals(139_500L, AdtsDecoding.bitRate(info(averageFrameBytes = 372.0, rateIndex = 3)))
    }

    @Test
    fun bitRateTruncatesTowardsZero() {
        assertEquals(137_812L, AdtsDecoding.bitRate(info(averageFrameBytes = 400.0, rateIndex = 4)))
    }

    @Test
    fun durationFollowsBytesAfterAudioStartAndBitRate() {
        val adts = info(audioStart = 1000, averageFrameBytes = 372.0, rateIndex = 3)
        assertEquals(57_347L, AdtsDecoding.durationMs(adts, 1_001_000L))
    }

    @Test
    fun durationIsUnknownWhenSizeDoesNotExceedAudioStart() {
        val adts = info(audioStart = 1000)
        assertNull(AdtsDecoding.durationMs(adts, 1000))
        assertNull(AdtsDecoding.durationMs(adts, 500))
        assertNull(AdtsDecoding.durationMs(adts, 0))
        assertNull(AdtsDecoding.durationMs(adts, -1))
    }

    @Test
    fun durationIsUnknownWhenBitRateIsNotPositive() {
        assertNull(AdtsDecoding.durationMs(info(audioStart = 0, averageFrameBytes = 0.0), 1_000_000))
        assertNull(AdtsDecoding.durationMs(info(audioStart = 0, averageFrameBytes = -5.0), 1_000_000))
    }
}
