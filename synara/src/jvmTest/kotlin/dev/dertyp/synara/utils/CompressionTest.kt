package dev.dertyp.synara.utils

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class CompressionTest {
    @Test
    fun oneKilobyteRoundTrips() {
        val data = ByteArray(1024) { (it % 16).toByte() }
        assertContentEquals(data, decompress(compress(data)))
    }

    @Test
    fun compressibleMebibyteRoundTripsAndShrinks() {
        val data = ByteArray(1024 * 1024) { (it % 64).toByte() }
        val compressed = compress(data)
        assertTrue(compressed.size < data.size)
        assertContentEquals(data, decompress(compressed))
    }

    @Test
    fun randomBytesRoundTrip() {
        val data = Random(42).nextBytes(256 * 1024)
        assertContentEquals(data, decompress(compress(data)))
    }

    @Test
    fun singleByteRoundTrips() {
        val data = byteArrayOf(7)
        assertContentEquals(data, decompress(compress(data)))
    }

    @Test
    fun dataThatIsNotZstdIsReturnedUnchanged() {
        val data = "plain text that was stored before compression existed".encodeToByteArray()
        assertContentEquals(data, decompress(data))
    }

    @Test
    fun emptyPayloadRoundTripsToEmpty() {
        assertContentEquals(ByteArray(0), decompress(compress(ByteArray(0))))
    }
}
