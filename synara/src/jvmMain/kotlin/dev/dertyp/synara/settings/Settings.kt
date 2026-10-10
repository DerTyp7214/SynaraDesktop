package dev.dertyp.synara.settings

import com.charleskorn.kaml.*
import com.russhwolf.settings.Settings
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.synara.services.LocalStorageService
import dev.dertyp.synara.utils.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

actual class SettingsFactory actual constructor(
    private val storageService: LocalStorageService,
    private val logger: Logger
) {
    actual fun create(): PersistentSettings {
        val configDir = storageService.getConfigDir()
        val configFile = File(configDir, "config.yml")
        val authFile = File(configDir, "auth.yml")

        return CompositeSettings(
            configSettings = YamlFileSettings(configFile, logger),
            authSettings = YamlFileSettings(authFile, logger),
            authKeys = SettingKey.authKeys
        )
    }

    actual fun getStatePath(fileName: String): String {
        return File(storageService.getDataDir(), fileName).absolutePath
    }
}

private class CompositeSettings(
    private val configSettings: PersistentSettings,
    private val authSettings: PersistentSettings,
    private val authKeys: Set<String>
) : PersistentSettings {
    private fun getSettings(key: String): Settings = if (key in authKeys) authSettings else configSettings

    override val keys: Set<String> get() = configSettings.keys + authSettings.keys
    override val size: Int get() = keys.size

    override fun clear() {
        configSettings.clear()
        authSettings.clear()
    }

    override fun flush() {
        configSettings.flush()
        authSettings.flush()
    }

    override fun remove(key: String) = getSettings(key).remove(key)
    override fun hasKey(key: String): Boolean = getSettings(key).hasKey(key)

    override fun putString(key: String, value: String) = getSettings(key).putString(key, value)
    override fun putInt(key: String, value: Int) = getSettings(key).putInt(key, value)
    override fun putLong(key: String, value: Long) = getSettings(key).putLong(key, value)
    override fun putFloat(key: String, value: Float) = getSettings(key).putFloat(key, value)
    override fun putDouble(key: String, value: Double) = getSettings(key).putDouble(key, value)
    override fun putBoolean(key: String, value: Boolean) = getSettings(key).putBoolean(key, value)

    override fun getString(key: String, defaultValue: String): String = getSettings(key).getString(key, defaultValue)
    override fun getInt(key: String, defaultValue: Int): Int = getSettings(key).getInt(key, defaultValue)
    override fun getLong(key: String, defaultValue: Long): Long = getSettings(key).getLong(key, defaultValue)
    override fun getFloat(key: String, defaultValue: Float): Float = getSettings(key).getFloat(key, defaultValue)
    override fun getDouble(key: String, defaultValue: Double): Double = getSettings(key).getDouble(key, defaultValue)
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = getSettings(key).getBoolean(key, defaultValue)

    override fun getStringOrNull(key: String): String? = getSettings(key).getStringOrNull(key)
    override fun getIntOrNull(key: String): Int? = getSettings(key).getIntOrNull(key)
    override fun getLongOrNull(key: String): Long? = getSettings(key).getLongOrNull(key)
    override fun getFloatOrNull(key: String): Float? = getSettings(key).getFloatOrNull(key)
    override fun getDoubleOrNull(key: String): Double? = getSettings(key).getDoubleOrNull(key)
    override fun getBooleanOrNull(key: String): Boolean? = getSettings(key).getBooleanOrNull(key)
}

private class YamlFileSettings(private val configFile: File, private val logger: Logger) : PersistentSettings {

    private companion object {
        val writerScope = CoroutineScope(AppDispatchers.createNamed("Synara-Settings") + SupervisorJob())
    }

    private val yaml = Yaml.default
    private val logTag = LogTag("settings")
    private val path = configFile.toPath().let { if (Files.isSymbolicLink(it) && Files.exists(it)) it.toRealPath() else it }
    private val tempPath = path.resolveSibling("${path.fileName}.tmp")
    private val posix = "posix" in path.fileSystem.supportedFileAttributeViews()
    private val ownerOnly = PosixFilePermissions.fromString("rw-------")
    private val lock = Any()
    private val writeLock = Any()
    private var latestSnapshot: Map<String, String> = emptyMap()
    private var dirty = false
    private val map: MutableMap<String, Any?>

    init {
        map = load()
    }

    private fun load(): MutableMap<String, Any?> {
        if (!configFile.exists()) return mutableMapOf()

        if (posix) {
            try {
                Files.setPosixFilePermissions(path, ownerOnly)
            } catch (e: Exception) {
                logger.error(logTag, "Failed to restrict permissions of ${configFile.name}", e)
            }
        }

        return try {
            val text = configFile.readText()
            if (text.isBlank()) return mutableMapOf()
            val node = yaml.parseToYamlNode(text)
            if (node is YamlMap) {
                node.entries.mapKeys { it.key.content }.mapValues { toAny(it.value) }.toMutableMap()
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            logger.error(logTag, "Failed to load ${configFile.name}", e)
            mutableMapOf()
        }
    }

    private fun save() {
        latestSnapshot = map.filterValues { it != null }.mapValues { it.value.toString() }
        if (dirty) return
        dirty = true
        writerScope.launch { writePending() }
    }

    private fun writePending() {
        synchronized(writeLock) {
            while (true) {
                val snapshot = synchronized(lock) {
                    if (!dirty) return
                    dirty = false
                    latestSnapshot
                }
                write(snapshot)
            }
        }
    }

    private fun write(snapshot: Map<String, String>) {
        try {
            val serializer = MapSerializer(String.serializer(), String.serializer())
            val yamlString = yaml.encodeToString(serializer, snapshot)
            Files.deleteIfExists(tempPath)
            if (posix) {
                Files.createFile(tempPath, PosixFilePermissions.asFileAttribute(ownerOnly))
            } else {
                Files.createFile(tempPath)
            }
            Files.write(tempPath, yamlString.toByteArray())
            moveIntoPlace()
        } catch (e: Exception) {
            logger.error(logTag, "Failed to save ${configFile.name}", e)
        }
    }

    private fun moveIntoPlace() {
        repeat(3) { attempt ->
            try {
                Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (e: AccessDeniedException) {
                if (attempt == 2) {
                    logger.warning(logTag, "Atomic replace of ${configFile.name} was denied, replacing without it", e.message)
                } else {
                    Thread.sleep(50)
                }
            }
        }
        Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun flush() = writePending()

    private fun toAny(node: YamlNode): Any? {
        return when (node) {
            is YamlScalar -> node.content
            is YamlMap -> node.entries.mapKeys { it.key.content }.mapValues { toAny(it.value) }
            is YamlList -> node.items.map { toAny(it) }
            is YamlNull -> null
            else -> null
        }
    }

    override val keys: Set<String> get() = synchronized(lock) { map.keys.toSet() }
    override val size: Int get() = synchronized(lock) { map.size }

    override fun clear() {
        synchronized(lock) {
            map.clear()
            save()
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            map.remove(key)
            save()
        }
    }

    override fun hasKey(key: String): Boolean = synchronized(lock) { map.containsKey(key) }

    private fun store(key: String, value: Any) {
        synchronized(lock) {
            map[key] = value
            save()
        }
    }

    override fun putString(key: String, value: String) = store(key, value)
    override fun putInt(key: String, value: Int) = store(key, value)
    override fun putLong(key: String, value: Long) = store(key, value)
    override fun putFloat(key: String, value: Float) = store(key, value)
    override fun putDouble(key: String, value: Double) = store(key, value)
    override fun putBoolean(key: String, value: Boolean) = store(key, value)

    override fun getString(key: String, defaultValue: String): String = getStringOrNull(key) ?: defaultValue
    override fun getInt(key: String, defaultValue: Int): Int = getIntOrNull(key) ?: defaultValue
    override fun getLong(key: String, defaultValue: Long): Long = getLongOrNull(key) ?: defaultValue
    override fun getFloat(key: String, defaultValue: Float): Float = getFloatOrNull(key) ?: defaultValue
    override fun getDouble(key: String, defaultValue: Double): Double = getDoubleOrNull(key) ?: defaultValue
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = getBooleanOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? = synchronized(lock) { map[key] } as? String
    override fun getIntOrNull(key: String): Int? = synchronized(lock) { map[key] }.let { (it as? Number)?.toInt() ?: (it as? String)?.toIntOrNull() }
    override fun getLongOrNull(key: String): Long? = synchronized(lock) { map[key] }.let { (it as? Number)?.toLong() ?: (it as? String)?.toLongOrNull() }
    override fun getFloatOrNull(key: String): Float? = synchronized(lock) { map[key] }.let { (it as? Number)?.toFloat() ?: (it as? String)?.toFloatOrNull() }
    override fun getDoubleOrNull(key: String): Double? = synchronized(lock) { map[key] }.let { (it as? Number)?.toDouble() ?: (it as? String)?.toDoubleOrNull() }
    override fun getBooleanOrNull(key: String): Boolean? = synchronized(lock) { map[key] }.let { (it as? Boolean) ?: (it as? String)?.toBooleanStrictOrNull() }
}
