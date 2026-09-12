package dev.dertyp.synara.utils

expect fun secureRandomBytes(count: Int): ByteArray

expect fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray

expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

expect fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray

expect fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray?

fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (index in a.indices) {
        diff = diff or (a[index].toInt() xor b[index].toInt())
    }
    return diff == 0
}
