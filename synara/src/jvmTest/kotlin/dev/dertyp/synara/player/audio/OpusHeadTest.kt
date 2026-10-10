package dev.dertyp.synara.player.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpusHeadTest {
    private fun head(
        version: Int = 1,
        channels: Int = 2,
        preSkip: Int = 312,
        inputSampleRate: Int = 48000,
        outputGain: Int = 0,
        family: Int = 0,
        table: ByteArray = ByteArray(0),
        magic: String = "OpusHead",
    ): ByteArray {
        val out = ByteArray(19 + table.size)
        magic.toByteArray(Charsets.US_ASCII).copyInto(out)
        out[8] = version.toByte()
        out[9] = channels.toByte()
        out[10] = preSkip.toByte()
        out[11] = (preSkip shr 8).toByte()
        out[12] = inputSampleRate.toByte()
        out[13] = (inputSampleRate shr 8).toByte()
        out[14] = (inputSampleRate shr 16).toByte()
        out[15] = (inputSampleRate shr 24).toByte()
        out[16] = outputGain.toByte()
        out[17] = (outputGain shr 8).toByte()
        out[18] = family.toByte()
        table.copyInto(out, 19)
        return out
    }

    private fun table(streams: Int, coupled: Int, vararg mapping: Int): ByteArray =
        byteArrayOf(streams.toByte(), coupled.toByte()) + ByteArray(mapping.size) { mapping[it].toByte() }

    private fun familyOne(channels: Int, streams: Int, coupled: Int): ByteArray =
        head(channels = channels, family = 1, table = table(streams, coupled, *IntArray(channels) { it }))

    @Test
    fun parsesStereoFamilyZeroHead() {
        val parsed = assertNotNull(
            OpusHead.parse(head(channels = 2, preSkip = 0x0138, inputSampleRate = 44100, outputGain = 0, family = 0))
        )
        assertEquals(2, parsed.channels)
        assertEquals(0x0138, parsed.preSkip)
        assertEquals(44100, parsed.inputSampleRate)
        assertEquals(0, parsed.outputGain)
        assertEquals(0, parsed.mappingFamily)
        assertEquals(1, parsed.streamCount)
        assertEquals(1, parsed.coupledStreamCount)
        assertContentEquals(shortArrayOf(0, 1), parsed.channelMapping)
        assertFalse(parsed.isMultistream)
    }

    @Test
    fun parsesMonoFamilyZeroHead() {
        val parsed = assertNotNull(OpusHead.parse(head(channels = 1)))
        assertEquals(1, parsed.channels)
        assertEquals(1, parsed.streamCount)
        assertEquals(0, parsed.coupledStreamCount)
        assertContentEquals(shortArrayOf(0), parsed.channelMapping)
    }

    @Test
    fun outputGainIsSigned() {
        val parsed = assertNotNull(OpusHead.parse(head(outputGain = 0xFF00)))
        assertEquals(-256, parsed.outputGain)
        assertEquals(256, assertNotNull(OpusHead.parse(head(outputGain = 256))).outputGain)
    }

    @Test
    fun preSkipAndSampleRateAreLittleEndian() {
        val parsed = assertNotNull(OpusHead.parse(head(preSkip = 0xABCD, inputSampleRate = 0x01020304)))
        assertEquals(0xABCD, parsed.preSkip)
        assertEquals(0x01020304, parsed.inputSampleRate)
    }

    @Test
    fun rejectsNullAndTooShortData() {
        assertNull(OpusHead.parse(null))
        assertNull(OpusHead.parse(head().copyOf(18)))
        assertNull(OpusHead.parse(ByteArray(0)))
    }

    @Test
    fun rejectsWrongMagic() {
        assertNull(OpusHead.parse(head(magic = "OpusTags")))
        assertNull(OpusHead.parse(head(magic = "opusHead")))
    }

    @Test
    fun rejectsVersionWithHighNibbleSet() {
        assertNull(OpusHead.parse(head(version = 0x10)))
        assertNull(OpusHead.parse(head(version = 0x11)))
        assertNull(OpusHead.parse(head(version = 0xF0)))
    }

    @Test
    fun acceptsVersionsWithZeroHighNibble() {
        assertNotNull(OpusHead.parse(head(version = 0)))
        assertNotNull(OpusHead.parse(head(version = 1)))
        assertNotNull(OpusHead.parse(head(version = 0x0F)))
    }

    @Test
    fun rejectsZeroChannels() {
        assertNull(OpusHead.parse(head(channels = 0)))
        assertNull(OpusHead.parse(head(channels = 0, family = 1, table = table(1, 0))))
    }

    @Test
    fun rejectsFamilyZeroWithMoreThanTwoChannels() {
        assertNull(OpusHead.parse(head(channels = 3, family = 0)))
        assertNull(OpusHead.parse(head(channels = 8, family = 0)))
    }

    @Test
    fun rejectsFamilyOneWithTruncatedMappingTable() {
        val full = familyOne(channels = 6, streams = 4, coupled = 2)
        assertNull(OpusHead.parse(full.copyOf(full.size - 1)))
        assertNull(OpusHead.parse(full.copyOf(19)))
        assertNull(OpusHead.parse(full.copyOf(20)))
        assertNotNull(OpusHead.parse(full))
    }

    @Test
    fun rejectsZeroStreamCount() {
        assertNull(OpusHead.parse(head(channels = 1, family = 1, table = table(0, 0, 0))))
    }

    @Test
    fun rejectsMoreCoupledStreamsThanStreams() {
        assertNull(OpusHead.parse(head(channels = 2, family = 1, table = table(1, 2, 0, 1))))
    }

    @Test
    fun rejectsMoreStreamChannelsThanTheMappingAllows() {
        assertNull(OpusHead.parse(head(channels = 1, family = 1, table = table(200, 100, 0))))
    }

    @Test
    fun rejectsMappingIndexBeyondStreamChannels() {
        assertNull(OpusHead.parse(head(channels = 2, family = 1, table = table(1, 1, 0, 2))))
        assertNull(OpusHead.parse(head(channels = 2, family = 1, table = table(1, 0, 0, 1))))
    }

    @Test
    fun acceptsSilentChannelIndex() {
        val parsed = assertNotNull(OpusHead.parse(head(channels = 2, family = 1, table = table(1, 1, 0, 255))))
        assertContentEquals(shortArrayOf(0, 255), parsed.channelMapping)
    }

    @Test
    fun parsesFamilyOneSixChannelHead() {
        val parsed = assertNotNull(OpusHead.parse(familyOne(channels = 6, streams = 4, coupled = 2)))
        assertEquals(6, parsed.channels)
        assertEquals(1, parsed.mappingFamily)
        assertEquals(4, parsed.streamCount)
        assertEquals(2, parsed.coupledStreamCount)
        assertContentEquals(shortArrayOf(0, 1, 2, 3, 4, 5), parsed.channelMapping)
        assertTrue(parsed.isMultistream)
    }

    @Test
    fun nativeChannelOrderOfFamilyOneSixChannelsIsTheVorbisTable() {
        val parsed = assertNotNull(OpusHead.parse(familyOne(channels = 6, streams = 4, coupled = 2)))
        assertContentEquals(intArrayOf(0, 2, 1, 5, 3, 4), parsed.nativeChannelOrder)
    }

    @Test
    fun nativeChannelOrderCoversThreeFiveSevenAndEightChannels() {
        val expected = mapOf(
            3 to intArrayOf(0, 2, 1),
            5 to intArrayOf(0, 2, 1, 3, 4),
            7 to intArrayOf(0, 2, 1, 6, 5, 3, 4),
            8 to intArrayOf(0, 2, 1, 7, 5, 6, 3, 4),
        )
        for ((channels, order) in expected) {
            val parsed = assertNotNull(OpusHead.parse(familyOne(channels = channels, streams = channels, coupled = 0)), "channels $channels")
            assertContentEquals(order, parsed.nativeChannelOrder, "channels $channels")
        }
    }

    @Test
    fun nativeChannelOrderIsNullForOneTwoAndFourChannelsOfFamilyOne() {
        assertNull(assertNotNull(OpusHead.parse(familyOne(channels = 1, streams = 1, coupled = 0))).nativeChannelOrder)
        assertNull(assertNotNull(OpusHead.parse(familyOne(channels = 2, streams = 1, coupled = 1))).nativeChannelOrder)
        assertNull(assertNotNull(OpusHead.parse(familyOne(channels = 4, streams = 2, coupled = 2))).nativeChannelOrder)
    }

    @Test
    fun nativeChannelOrderIsNullOutsideFamilyOne() {
        val familyTwo = head(channels = 6, family = 2, table = table(4, 2, 0, 1, 2, 3, 4, 5))
        val parsed = assertNotNull(OpusHead.parse(familyTwo))
        assertNull(parsed.nativeChannelOrder)
        assertTrue(parsed.isMultistream)
        assertNull(assertNotNull(OpusHead.parse(head(channels = 2))).nativeChannelOrder)
    }
}
