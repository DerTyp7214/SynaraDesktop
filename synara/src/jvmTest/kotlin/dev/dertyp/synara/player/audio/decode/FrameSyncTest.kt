package dev.dertyp.synara.player.audio.decode

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameSyncTest {
    private class TrickleStream(private val data: ByteArray, private val maxRead: Int) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(length, maxRead, data.size - position)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    private fun input(size: Int, syncAt: List<Int> = emptyList(), falseAt: List<Int> = emptyList()): ByteArray {
        val data = ByteArray(size) { 0x11 }
        for (index in falseAt) {
            data[index] = 0xFF.toByte()
            data[index + 1] = 0x00
        }
        for (index in syncAt) {
            data[index] = 0xFF.toByte()
            data[index + 1] = 0xAA.toByte()
        }
        return data
    }

    private val confirmAa: (ByteArray, Int, Int) -> Boolean = { bytes, index, _ -> bytes[index + 1] == 0xAA.toByte() }

    private fun find(data: ByteArray, confirm: (ByteArray, Int, Int) -> Boolean = confirmAa): InputStream? =
        FrameSync.find(ByteArrayInputStream(data), confirm)

    @Test
    fun findsASyncAtOffsetZero() {
        val data = input(200, syncAt = listOf(0))
        val stream = assertNotNull(find(data))
        assertContentEquals(data, stream.readBytes())
    }

    @Test
    fun findsASyncAtOffsetFiveAndStartsTheStreamThere() {
        val data = input(200, syncAt = listOf(5))
        val stream = assertNotNull(find(data))
        assertContentEquals(data.copyOfRange(5, data.size), stream.readBytes())
    }

    @Test
    fun skipsAFalseFfByteRejectedByConfirmAndAcceptsTheNext() {
        val data = input(200, syncAt = listOf(10), falseAt = listOf(3))
        val checked = mutableListOf<Int>()
        val stream = assertNotNull(
            find(data) { bytes, index, _ ->
                checked.add(index)
                bytes[index + 1] == 0xAA.toByte()
            }
        )
        assertEquals(listOf(3, 10), checked)
        assertContentEquals(data.copyOfRange(10, data.size), stream.readBytes())
    }

    @Test
    fun confirmReceivesTheBufferTheIndexAndTheNumberOfValidBytes() {
        val data = input(100, syncAt = listOf(7))
        var seenLength = -1
        var seenIndex = -1
        var seenByte = 0
        var seenNext = 0
        assertNotNull(
            find(data) { bytes, index, length ->
                seenIndex = index
                seenLength = length
                seenByte = bytes[index].toInt() and 0xFF
                seenNext = bytes[index + 1].toInt() and 0xFF
                true
            }
        )
        assertEquals(7, seenIndex)
        assertEquals(100, seenLength)
        assertEquals(0xFF, seenByte)
        assertEquals(0xAA, seenNext)
    }

    @Test
    fun onlyBytesEqualToFfAreOfferedToConfirm() {
        val data = input(300, syncAt = listOf(40, 80))
        val checked = mutableListOf<Int>()
        find(data) { _, index, _ ->
            checked.add(index)
            false
        }
        assertEquals(listOf(40, 80), checked)
    }

    @Test
    fun findsASyncPastTheFirst64KibWindow() {
        val data = input(100_000, syncAt = listOf(70_000))
        val stream = assertNotNull(find(data))
        assertContentEquals(data.copyOfRange(70_000, data.size), stream.readBytes())
    }

    @Test
    fun findsASyncStraddlingTheChunkBoundary() {
        val data = input(70_000, syncAt = listOf(65_535))
        val stream = assertNotNull(find(data))
        assertContentEquals(data.copyOfRange(65_535, data.size), stream.readBytes())
    }

    @Test
    fun findsASyncStraddlingTheChunkBoundaryOnAStreamThatReturnsShortReads() {
        val data = input(70_000, syncAt = listOf(65_535))
        val stream = assertNotNull(FrameSync.find(TrickleStream(data, 1000), confirmAa))
        assertContentEquals(data.copyOfRange(65_535, data.size), stream.readBytes())
    }

    @Test
    fun findsASyncOnAStreamThatReturnsShortReads() {
        val data = input(20_000, syncAt = listOf(12_345))
        val stream = assertNotNull(FrameSync.find(TrickleStream(data, 7), confirmAa))
        assertContentEquals(data.copyOfRange(12_345, data.size), stream.readBytes())
    }

    @Test
    fun returnsNullWhenNoSyncExistsBeforeTheEnd() {
        assertNull(find(input(100)))
        assertNull(find(input(200_000)))
    }

    @Test
    fun returnsNullWhenEveryCandidateIsRejected() {
        assertNull(find(input(500, falseAt = listOf(10, 100, 200))))
    }

    @Test
    fun returnsNullForInputTooShortToHoldASync() {
        assertNull(find(ByteArray(0)))
        assertNull(find(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun findsASyncInTheLastTwoBytesOfShortInput() {
        val data = input(10, syncAt = listOf(8))
        val stream = assertNotNull(find(data))
        assertContentEquals(data.copyOfRange(8, 10), stream.readBytes())
    }

    @Test
    fun findsASyncJustInsideTheOneMibScanLimit() {
        val data = input(1_500_000, syncAt = listOf(1_000_000))
        val stream = assertNotNull(find(data))
        assertContentEquals(data.copyOfRange(1_000_000, data.size), stream.readBytes())
    }

    @Test
    fun givesUpOnASyncBeyondTheOneMibScanLimit() {
        assertNull(find(input(1_500_000, syncAt = listOf(1_100_000))))
    }

    @Test
    fun returnedStreamContinuesIntoTheUnreadRemainderOfTheInput() {
        val data = input(300_000, syncAt = listOf(100))
        val stream = assertNotNull(find(data))
        val rest = stream.readBytes()
        assertEquals(data.size - 100, rest.size)
        assertTrue(rest.contentEquals(data.copyOfRange(100, data.size)))
    }
}
