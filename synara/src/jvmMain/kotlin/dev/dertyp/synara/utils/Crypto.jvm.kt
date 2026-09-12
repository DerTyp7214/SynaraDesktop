package dev.dertyp.synara.utils

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val HMAC_SHA256 = "HmacSHA256"
private const val HMAC_SHA256_LENGTH = 32

private val secureRandom by lazy { SecureRandom() }

actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    secureRandom.nextBytes(bytes)
    return bytes
}

/**
 * PBKDF2-HMAC-SHA256 over a raw byte password. Built on [Mac] rather than `SecretKeyFactory`, which
 * only takes a `char[]` password and re-encodes it with a provider specific charset.
 */
actual fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLength: Int
): ByteArray {
    require(iterations > 0) { "iterations must be positive" }
    require(keyLength > 0) { "keyLength must be positive" }
    val mac = Mac.getInstance(HMAC_SHA256)
    mac.init(SecretKeySpec(password, HMAC_SHA256))
    val output = ByteArray(keyLength)
    val block = ByteArray(HMAC_SHA256_LENGTH)
    val accumulator = ByteArray(HMAC_SHA256_LENGTH)
    var offset = 0
    var blockIndex = 1
    while (offset < keyLength) {
        mac.reset()
        mac.update(salt)
        mac.update((blockIndex ushr 24).toByte())
        mac.update((blockIndex ushr 16).toByte())
        mac.update((blockIndex ushr 8).toByte())
        mac.update(blockIndex.toByte())
        mac.doFinal(block, 0)
        block.copyInto(accumulator)
        var iteration = 1
        while (iteration < iterations) {
            mac.reset()
            mac.update(block)
            mac.doFinal(block, 0)
            for (index in accumulator.indices) {
                accumulator[index] = (accumulator[index].toInt() xor block[index].toInt()).toByte()
            }
            iteration++
        }
        val take = minOf(HMAC_SHA256_LENGTH, keyLength - offset)
        accumulator.copyInto(output, offset, 0, take)
        offset += take
        blockIndex++
    }
    return output
}

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance(HMAC_SHA256)
    mac.init(SecretKeySpec(key, HMAC_SHA256))
    return mac.doFinal(data)
}

actual fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(plaintext)
}

actual fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray? = try {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    cipher.doFinal(ciphertext)
} catch (_: Throwable) {
    null
}
