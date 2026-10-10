package dev.dertyp.synara.sync

import dev.dertyp.serializers.AppJson
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.testing.InMemorySettings
import dev.dertyp.synara.utils.aesCbcEncrypt
import dev.dertyp.synara.utils.hmacSha256
import dev.dertyp.synara.utils.pbkdf2HmacSha256
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class SecretsCipherTest {

    private val settings = InMemorySettings()
    private val cipher = SecretsCipher(settings)
    private val key = DerivedKey(
        encKey = ByteArray(32) { it.toByte() },
        macKey = ByteArray(32) { (it + 100).toByte() },
        saltFingerprint = "fingerprint"
    )
    private val storedKeyNames = listOf(
        SettingKey.SecretsSyncEncKey.name,
        SettingKey.SecretsSyncMacKey.name,
        SettingKey.SecretsSyncSaltFingerprint.name
    )

    private fun fastMeta(passphrase: String, salt: ByteArray = ByteArray(16) { (it * 7).toByte() }): Pair<SecretsMeta, DerivedKey> {
        val draft = SecretsMeta(
            iterations = 1,
            salt = Base64.encode(salt),
            check = SecretEnvelope(iv = "", ct = "", mac = "")
        )
        val derived = cipher.deriveKey(passphrase, draft)
        return draft.copy(check = cipher.encrypt(SecretsCipher.META_KEY, SecretsCipher.CHECK_PLAINTEXT, derived)) to derived
    }

    private fun flipFirstByte(encoded: String): String {
        val bytes = Base64.decode(encoded)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        return Base64.encode(bytes)
    }

    private fun roundTrip(plaintext: String) {
        val envelope = cipher.encrypt("name", plaintext, key)
        assertEquals(1, envelope.v)
        assertEquals(plaintext, cipher.decrypt("name", envelope, key))
    }

    @Test
    fun emptyPlaintextRoundTrips() = roundTrip("")

    @Test
    fun asciiPlaintextRoundTrips() = roundTrip("listenbrainz-token")

    @Test
    fun nonAsciiPlaintextRoundTrips() = roundTrip("Schlüssel 鍵 \uD83D\uDD11")

    @Test
    fun multiBlockPlaintextRoundTrips() = roundTrip("0123456789abcdef".repeat(20) + "tail")

    @Test
    fun envelopeDoesNotDecryptUnderAnotherKeyName() {
        val envelope = cipher.encrypt("first", "value", key)

        assertNull(cipher.decrypt("second", envelope, key))
    }

    @Test
    fun flippedCiphertextByteGivesNull() {
        val envelope = cipher.encrypt("name", "value", key)

        assertNull(cipher.decrypt("name", envelope.copy(ct = flipFirstByte(envelope.ct)), key))
    }

    @Test
    fun flippedIvByteGivesNull() {
        val envelope = cipher.encrypt("name", "value", key)

        assertNull(cipher.decrypt("name", envelope.copy(iv = flipFirstByte(envelope.iv)), key))
    }

    @Test
    fun flippedMacByteGivesNull() {
        val envelope = cipher.encrypt("name", "value", key)

        assertNull(cipher.decrypt("name", envelope.copy(mac = flipFirstByte(envelope.mac)), key))
    }

    @Test
    fun anotherMacKeyGivesNull() {
        val envelope = cipher.encrypt("name", "value", key)
        val other = DerivedKey(key.encKey, ByteArray(32) { (it + 101).toByte() }, key.saltFingerprint)

        assertNull(cipher.decrypt("name", envelope, other))
    }

    @Test
    fun unknownEnvelopeVersionGivesNull() {
        val envelope = cipher.encrypt("name", "value", key)

        assertNull(cipher.decrypt("name", envelope.copy(v = 2), key))
    }

    @Test
    fun invalidBase64GivesNullWithoutThrowing() {
        val envelope = cipher.encrypt("name", "value", key)

        assertNull(cipher.decrypt("name", envelope.copy(iv = "***"), key))
        assertNull(cipher.decrypt("name", envelope.copy(ct = "***"), key))
        assertNull(cipher.decrypt("name", envelope.copy(mac = "***"), key))
    }

    @Test
    fun twoEncryptionsOfTheSameValueDifferInIvAndCiphertext() {
        val first = cipher.encrypt("name", "value", key)
        val second = cipher.encrypt("name", "value", key)

        assertNotEquals(first.iv, second.iv)
        assertNotEquals(first.ct, second.ct)
    }

    @Test
    fun metaVerifiesWithTheRightPassphraseOnly() {
        val (meta, _) = fastMeta("right")

        assertTrue(cipher.verify(meta, cipher.deriveKey("right", meta)))
        assertFalse(cipher.verify(meta, cipher.deriveKey("wrong", meta)))
    }

    @Test
    fun keyWithTheFingerprintOfAnotherSaltFailsVerification() {
        val (meta, derived) = fastMeta("right")
        val (_, otherSalt) = fastMeta("right", ByteArray(16) { (it * 11 + 1).toByte() })
        val mismatched = DerivedKey(derived.encKey, derived.macKey, otherSalt.saltFingerprint)

        assertNotEquals(derived.saltFingerprint, otherSalt.saltFingerprint)
        assertFalse(cipher.verify(meta, mismatched))
    }

    @Test
    fun parseMetaRejectsMalformedAndUnsupportedDocuments() {
        val (meta, _) = fastMeta("right")

        assertNull(cipher.parseMeta("{not json"))
        assertNull(cipher.parseMeta(""))
        assertNull(cipher.parseMeta(AppJson.encodeToString(meta.copy(v = 2))))
        assertNull(cipher.parseMeta(AppJson.encodeToString(meta.copy(kdf = "argon2id"))))
        assertNull(cipher.parseMeta(AppJson.encodeToString(meta.copy(iterations = 0))))
        assertNull(cipher.parseMeta(AppJson.encodeToString(meta.copy(iterations = 10_000_001))))
    }

    @Test
    fun parseMetaAcceptsTheIterationBounds() {
        val (meta, _) = fastMeta("right")
        val lowest = meta.copy(iterations = 1)
        val highest = meta.copy(iterations = 10_000_000)

        assertEquals(lowest, cipher.parseMeta(AppJson.encodeToString(lowest)))
        assertEquals(highest, cipher.parseMeta(AppJson.encodeToString(highest)))
    }

    @Test
    fun rememberedKeyUnlocksANewCipherOverTheSameSettings() {
        val envelope = cipher.encrypt("name", "value", key)
        cipher.remember(key)
        assertTrue(cipher.isUnlocked)

        val reopened = SecretsCipher(settings)

        assertTrue(reopened.isUnlocked)
        assertEquals("value", reopened.decrypt("name", envelope, assertNotNull(reopened.key.value)))
        assertEquals("value", reopened.decryptFromJson("name", AppJson.encodeToString(envelope)))
    }

    @Test
    fun forgottenKeyLocksANewCipherAndRemovesTheStoredKeys() {
        cipher.remember(key)
        storedKeyNames.forEach { assertTrue(settings.hasKey(it)) }

        cipher.forget()

        assertFalse(cipher.isUnlocked)
        assertFalse(SecretsCipher(settings).isUnlocked)
        storedKeyNames.forEach { assertFalse(settings.hasKey(it)) }
    }

    @Test
    fun twoOfThreeStoredKeysGiveALockedCipher() {
        storedKeyNames.forEach { missing ->
            val partial = InMemorySettings()
            SecretsCipher(partial).remember(key)
            partial.remove(missing)

            assertFalse(SecretsCipher(partial).isUnlocked)
        }
    }

    @Test
    fun storedKeyThatIsNotBase64GivesALockedCipher() {
        cipher.remember(key)
        settings.putString(SettingKey.SecretsSyncEncKey.name, "***")

        assertFalse(SecretsCipher(settings).isUnlocked)
    }

    @Test
    fun jsonHelpersReturnNullWhileLocked() {
        val json = AppJson.encodeToString(cipher.encrypt("name", "value", key))

        assertFalse(cipher.isUnlocked)
        assertNull(cipher.encryptToJson("name", "value"))
        assertNull(cipher.decryptFromJson("name", json))
    }

    @Test
    fun jsonHelpersRoundTripWhileUnlocked() {
        cipher.remember(key)

        val json = assertNotNull(cipher.encryptToJson("name", "value"))

        assertEquals("value", cipher.decryptFromJson("name", json))
        assertNull(cipher.decryptFromJson("other", json))
        assertNull(cipher.decryptFromJson("name", "{not json"))
    }

    @Test
    fun envelopeBuiltFromThePrimitivesWithAFixedIvDecrypts() {
        val iv = ByteArray(16) { (it * 3).toByte() }
        val ct = aesCbcEncrypt(key.encKey, iv, "pinned".encodeToByteArray())
        val mac = hmacSha256(key.macKey, "name".encodeToByteArray() + iv + ct)
        val envelope = SecretEnvelope(v = 1, iv = Base64.encode(iv), ct = Base64.encode(ct), mac = Base64.encode(mac))

        assertEquals("pinned", cipher.decrypt("name", envelope, key))
    }

    @Test
    fun derivedKeyHalvesAreTheSixtyFourBytePbkdf2Output() {
        val salt = ByteArray(16) { (it * 5 + 2).toByte() }
        val meta = SecretsMeta(
            iterations = 3,
            salt = Base64.encode(salt),
            check = SecretEnvelope(iv = "", ct = "", mac = "")
        )

        val derived = cipher.deriveKey("pässword", meta)

        val expected = pbkdf2HmacSha256("pässword".encodeToByteArray(), salt, 3, 64)
        assertContentEquals(expected.copyOfRange(0, 32), derived.encKey)
        assertContentEquals(expected.copyOfRange(32, 64), derived.macKey)
        assertEquals(
            Base64.encode(hmacSha256(salt, "synara-salt-fingerprint".encodeToByteArray()).copyOf(8)),
            derived.saltFingerprint
        )
    }

    @Test
    fun createdMetaUsesTheProductionKdfParametersAndVerifies() {
        val (meta, derived) = cipher.createMeta("passphrase")

        assertEquals(1, meta.v)
        assertEquals("pbkdf2-sha256", meta.kdf)
        assertEquals(600_000, meta.iterations)
        assertEquals(16, Base64.decode(meta.salt).size)
        val rederived = cipher.deriveKey("passphrase", meta)
        assertTrue(cipher.verify(meta, rederived))
        assertContentEquals(derived.encKey, rederived.encKey)
        assertContentEquals(derived.macKey, rederived.macKey)
        assertEquals(derived.saltFingerprint, rederived.saltFingerprint)
        assertEquals(meta, cipher.parseMeta(AppJson.encodeToString(meta)))
    }
}
