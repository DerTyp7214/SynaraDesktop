package dev.dertyp.synara.utils

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {

    private val password = "correct horse battery staple"
    private val salt = ByteArray(16) { (it * 13 + 5).toByte() }
    private val aesKey = ByteArray(32) { (it * 3 + 1).toByte() }
    private val iv = ByteArray(16) { (it * 7 + 2).toByte() }

    private fun oracle(iterations: Int, keyLength: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, keyLength * 8))
            .encoded

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun pbkdf2MatchesTheOracle(keyLength: Int) {
        for (iterations in listOf(1, 2, 1000)) {
            val derived = pbkdf2HmacSha256(password.encodeToByteArray(), salt, iterations, keyLength)

            assertEquals(keyLength, derived.size)
            assertContentEquals(oracle(iterations, keyLength), derived)
        }
    }

    private fun aesRoundTrips(size: Int) {
        val plaintext = ByteArray(size) { (it * 31 + 7).toByte() }

        val ciphertext = aesCbcEncrypt(aesKey, iv, plaintext)

        assertEquals((size / 16 + 1) * 16, ciphertext.size)
        assertContentEquals(plaintext, aesCbcDecrypt(aesKey, iv, ciphertext))
    }

    @Test
    fun pbkdf2MatchesTheJdkForAKeyShorterThanOneBlock() = pbkdf2MatchesTheOracle(16)

    @Test
    fun pbkdf2MatchesTheJdkForAKeyOfExactlyOneBlock() = pbkdf2MatchesTheOracle(32)

    @Test
    fun pbkdf2MatchesTheJdkForAKeyWithATruncatedSecondBlock() = pbkdf2MatchesTheOracle(40)

    @Test
    fun pbkdf2MatchesTheJdkForAKeyOfTwoBlocks() = pbkdf2MatchesTheOracle(64)

    @Test
    fun pbkdf2RejectsIterationsThatAreNotPositive() {
        assertFailsWith<IllegalArgumentException> { pbkdf2HmacSha256(password.encodeToByteArray(), salt, 0, 32) }
        assertFailsWith<IllegalArgumentException> { pbkdf2HmacSha256(password.encodeToByteArray(), salt, -1, 32) }
    }

    @Test
    fun pbkdf2RejectsAKeyLengthThatIsNotPositive() {
        assertFailsWith<IllegalArgumentException> { pbkdf2HmacSha256(password.encodeToByteArray(), salt, 1, 0) }
        assertFailsWith<IllegalArgumentException> { pbkdf2HmacSha256(password.encodeToByteArray(), salt, 1, -1) }
    }

    @Test
    fun hmacSha256MatchesRfc4231TestCaseOne() {
        val mac = hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray())

        assertContentEquals(hex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"), mac)
    }

    @Test
    fun aesCbcRoundTripsAnEmptyPlaintext() = aesRoundTrips(0)

    @Test
    fun aesCbcRoundTripsOneByte() = aesRoundTrips(1)

    @Test
    fun aesCbcRoundTripsOneByteLessThanABlock() = aesRoundTrips(15)

    @Test
    fun aesCbcRoundTripsExactlyOneBlock() = aesRoundTrips(16)

    @Test
    fun aesCbcRoundTripsOneByteMoreThanABlock() = aesRoundTrips(17)

    @Test
    fun aesCbcRoundTripsManyBlocks() = aesRoundTrips(1000)

    @Test
    fun aesCbcDecryptWithAWrongKeyLengthGivesNull() {
        val ciphertext = aesCbcEncrypt(aesKey, iv, "plaintext".encodeToByteArray())

        assertNotNull(aesCbcDecrypt(aesKey, iv, ciphertext))
        assertNull(aesCbcDecrypt(aesKey.copyOf(15), iv, ciphertext))
        assertNull(aesCbcDecrypt(aesKey.copyOf(33), iv, ciphertext))
    }

    @Test
    fun aesCbcDecryptOfATruncatedCiphertextGivesNull() {
        val ciphertext = aesCbcEncrypt(aesKey, iv, ByteArray(40) { it.toByte() })

        assertNull(aesCbcDecrypt(aesKey, iv, ciphertext.copyOf(ciphertext.size - 1)))
    }

    @Test
    fun constantTimeEqualsAcceptsEqualArrays() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun constantTimeEqualsRejectsADifferenceInTheLastByte() {
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
    }

    @Test
    fun constantTimeEqualsRejectsDifferentLengths() {
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(constantTimeEquals(byteArrayOf(), byteArrayOf(0)))
    }

    @Test
    fun constantTimeEqualsAcceptsTwoEmptyArrays() {
        assertTrue(constantTimeEquals(byteArrayOf(), byteArrayOf()))
    }
}
