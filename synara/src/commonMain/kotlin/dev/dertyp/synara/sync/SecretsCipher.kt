package dev.dertyp.synara.sync

import com.russhwolf.settings.Settings
import dev.dertyp.serializers.AppJson
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.getOrNull
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.utils.aesCbcDecrypt
import dev.dertyp.synara.utils.aesCbcEncrypt
import dev.dertyp.synara.utils.constantTimeEquals
import dev.dertyp.synara.utils.hmacSha256
import dev.dertyp.synara.utils.pbkdf2HmacSha256
import dev.dertyp.synara.utils.secureRandomBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
data class SecretEnvelope(
    val v: Int = 1,
    val iv: String,
    val ct: String,
    val mac: String
)

@Serializable
data class SecretsMeta(
    val v: Int = 1,
    val kdf: String = SecretsCipher.KDF,
    val iterations: Int = SecretsCipher.ITERATIONS,
    val salt: String,
    val check: SecretEnvelope
)

class DerivedKey(
    val encKey: ByteArray,
    val macKey: ByteArray,
    val saltFingerprint: String
)

/**
 * Passphrase derived encryption of the synced scrobbler secrets. Envelope, meta, KDF parameters and
 * the salt fingerprint form a cross client wire format: changing any of them locks the other
 * clients of the account out of their secrets.
 */
@OptIn(ExperimentalEncodingApi::class)
class SecretsCipher(private val settings: Settings) {
    companion object {
        const val KDF = "pbkdf2-sha256"
        const val ITERATIONS = 600_000
        const val CHECK_PLAINTEXT = "synara-secrets-check"
        const val META_KEY = "secrets.meta"

        private const val KEY_LENGTH = 64
        private const val SUB_KEY_LENGTH = 32
        private const val IV_LENGTH = 16
        private const val SALT_LENGTH = 16
        private const val FINGERPRINT_LENGTH = 8
        private const val FINGERPRINT_CONTEXT = "synara-salt-fingerprint"
        private const val MAX_ITERATIONS = 10_000_000
    }

    private val _key = MutableStateFlow(loadKey())
    val key: StateFlow<DerivedKey?> = _key.asStateFlow()

    val isUnlocked: Boolean get() = key.value != null

    fun deriveKey(passphrase: String, meta: SecretsMeta): DerivedKey {
        val salt = Base64.decode(meta.salt)
        val bytes = pbkdf2HmacSha256(passphrase.encodeToByteArray(), salt, meta.iterations, KEY_LENGTH)
        return DerivedKey(
            encKey = bytes.copyOfRange(0, SUB_KEY_LENGTH),
            macKey = bytes.copyOfRange(SUB_KEY_LENGTH, KEY_LENGTH),
            saltFingerprint = fingerprint(salt)
        )
    }

    fun createMeta(passphrase: String): Pair<SecretsMeta, DerivedKey> {
        val salt = secureRandomBytes(SALT_LENGTH)
        val bytes = pbkdf2HmacSha256(passphrase.encodeToByteArray(), salt, ITERATIONS, KEY_LENGTH)
        val derived = DerivedKey(
            encKey = bytes.copyOfRange(0, SUB_KEY_LENGTH),
            macKey = bytes.copyOfRange(SUB_KEY_LENGTH, KEY_LENGTH),
            saltFingerprint = fingerprint(salt)
        )
        val meta = SecretsMeta(
            kdf = KDF,
            iterations = ITERATIONS,
            salt = Base64.encode(salt),
            check = encrypt(META_KEY, CHECK_PLAINTEXT, derived)
        )
        return meta to derived
    }

    fun verify(meta: SecretsMeta, key: DerivedKey): Boolean {
        val salt = try {
            Base64.decode(meta.salt)
        } catch (_: Throwable) {
            return false
        }
        if (key.saltFingerprint != fingerprint(salt)) return false
        return decrypt(META_KEY, meta.check, key) == CHECK_PLAINTEXT
    }

    fun encrypt(keyName: String, plaintext: String, key: DerivedKey): SecretEnvelope {
        val iv = secureRandomBytes(IV_LENGTH)
        val ct = aesCbcEncrypt(key.encKey, iv, plaintext.encodeToByteArray())
        val mac = hmacSha256(key.macKey, keyName.encodeToByteArray() + iv + ct)
        return SecretEnvelope(
            v = 1,
            iv = Base64.encode(iv),
            ct = Base64.encode(ct),
            mac = Base64.encode(mac)
        )
    }

    fun decrypt(keyName: String, envelope: SecretEnvelope, key: DerivedKey): String? {
        if (envelope.v != 1) return null
        return try {
            val iv = Base64.decode(envelope.iv)
            val ct = Base64.decode(envelope.ct)
            val mac = Base64.decode(envelope.mac)
            val expected = hmacSha256(key.macKey, keyName.encodeToByteArray() + iv + ct)
            if (!constantTimeEquals(expected, mac)) return null
            aesCbcDecrypt(key.encKey, iv, ct)?.decodeToString()
        } catch (_: Throwable) {
            null
        }
    }

    fun encryptToJson(keyName: String, plaintext: String): String? {
        val current = key.value ?: return null
        return AppJson.encodeToString(encrypt(keyName, plaintext, current))
    }

    fun decryptFromJson(keyName: String, json: String): String? {
        val current = key.value ?: return null
        val envelope = try {
            AppJson.decodeFromString<SecretEnvelope>(json)
        } catch (_: Throwable) {
            return null
        }
        return decrypt(keyName, envelope, current)
    }

    fun parseMeta(json: String): SecretsMeta? {
        val meta = try {
            AppJson.decodeFromString<SecretsMeta>(json)
        } catch (_: Throwable) {
            return null
        }
        if (meta.v != 1) return null
        if (meta.kdf != KDF) return null
        if (meta.iterations !in 1..MAX_ITERATIONS) return null
        return meta
    }

    fun remember(key: DerivedKey) {
        settings.put(SettingKey.SecretsSyncEncKey, Base64.encode(key.encKey))
        settings.put(SettingKey.SecretsSyncMacKey, Base64.encode(key.macKey))
        settings.put(SettingKey.SecretsSyncSaltFingerprint, key.saltFingerprint)
        _key.value = key
    }

    fun forget() {
        settings.put(SettingKey.SecretsSyncEncKey, null)
        settings.put(SettingKey.SecretsSyncMacKey, null)
        settings.put(SettingKey.SecretsSyncSaltFingerprint, null)
        _key.value = null
    }

    private fun loadKey(): DerivedKey? {
        val encKey = settings.getOrNull(SettingKey.SecretsSyncEncKey)?.takeIf { it.isNotBlank() } ?: return null
        val macKey = settings.getOrNull(SettingKey.SecretsSyncMacKey)?.takeIf { it.isNotBlank() } ?: return null
        val saltFingerprint = settings.getOrNull(SettingKey.SecretsSyncSaltFingerprint)
            ?.takeIf { it.isNotBlank() } ?: return null
        return try {
            DerivedKey(Base64.decode(encKey), Base64.decode(macKey), saltFingerprint)
        } catch (_: Throwable) {
            null
        }
    }

    private fun fingerprint(salt: ByteArray): String =
        Base64.encode(hmacSha256(salt, FINGERPRINT_CONTEXT.encodeToByteArray()).copyOf(FINGERPRINT_LENGTH))
}
