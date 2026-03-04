package dev.dertyp.synara.settings

import com.charleskorn.kaml.*
import com.russhwolf.settings.Settings
import dev.dertyp.synara.utils.getAppDataDir
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

actual class SettingsFactory actual constructor() {
    actual fun create(): Settings {
        val configDir = getAppDataDir()
        val configFile = File(configDir, "config.yml")
        val authFile = File(configDir, "auth.yml")

        return CompositeSettings(
            configSettings = YamlFileSettings(configFile),
            authSettings = YamlFileSettings(authFile),
            authKeys = SettingKey.authKeys
        )
    }

    actual fun getStatePath(fileName: String): String {
        return File(getAppDataDir(), fileName).absolutePath
    }
}

private class CompositeSettings(
    private val configSettings: Settings,
    private val authSettings: Settings,
    private val authKeys: Set<String>
) : Settings {
    private fun getSettings(key: String): Settings = if (key in authKeys) authSettings else configSettings

    override val keys: Set<String> get() = configSettings.keys + authSettings.keys
    override val size: Int get() = keys.size

    override fun clear() {
        configSettings.clear()
        authSettings.clear()
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

private class YamlFileSettings(private val configFile: File) : Settings {

    private val yaml = Yaml.default
    private val map: MutableMap<String, Any?>

    init {
        map = load()
    }

    private fun load(): MutableMap<String, Any?> {
        if (!configFile.exists()) return mutableMapOf()
        val text = configFile.readText()
        if (text.isBlank()) return mutableMapOf()

        return try {
            val node = yaml.parseToYamlNode(text)
            if (node is YamlMap) {
                node.entries.mapKeys { it.key.content }.mapValues { toAny(it.value) }.toMutableMap()
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            mutableMapOf()
        }
    }

    private fun save() {
        try {
            val stringMap = map.filterValues { it != null }.mapValues { it.value.toString() }
            val serializer = MapSerializer(String.serializer(), String.serializer())
            val yamlString = yaml.encodeToString(serializer, stringMap)
            configFile.writeText(yamlString)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toAny(node: YamlNode): Any? {
        return when (node) {
            is YamlScalar -> {
                val content = node.content
                content.toLongOrNull() ?: content.toDoubleOrNull() ?: content.toBooleanStrictOrNull() ?: content
            }
            is YamlMap -> node.entries.mapKeys { it.key.content }.mapValues { toAny(it.value) }
            is YamlList -> node.items.map { toAny(it) }
            is YamlNull -> null
            else -> null
        }
    }

    override val keys: Set<String> get() = map.keys.toSet()
    override val size: Int get() = map.size

    override fun clear() {
        map.clear()
        save()
    }

    override fun remove(key: String) {
        map.remove(key)
        save()
    }

    override fun hasKey(key: String): Boolean = map.containsKey(key)

    override fun putString(key: String, value: String) { map[key] = value; save() }
    override fun putInt(key: String, value: Int) { map[key] = value; save() }
    override fun putLong(key: String, value: Long) { map[key] = value; save() }
    override fun putFloat(key: String, value: Float) { map[key] = value; save() }
    override fun putDouble(key: String, value: Double) { map[key] = value; save() }
    override fun putBoolean(key: String, value: Boolean) { map[key] = value; save() }

    override fun getString(key: String, defaultValue: String): String = (map[key] as? String) ?: defaultValue
    override fun getInt(key: String, defaultValue: Int): Int = (map[key] as? Number)?.toInt() ?: (map[key] as? String)?.toIntOrNull() ?: defaultValue
    override fun getLong(key: String, defaultValue: Long): Long = (map[key] as? Number)?.toLong() ?: (map[key] as? String)?.toLongOrNull() ?: defaultValue
    override fun getFloat(key: String, defaultValue: Float): Float = (map[key] as? Number)?.toFloat() ?: (map[key] as? String)?.toFloatOrNull() ?: defaultValue
    override fun getDouble(key: String, defaultValue: Double): Double = (map[key] as? Number)?.toDouble() ?: (map[key] as? String)?.toDoubleOrNull() ?: defaultValue
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = (map[key] as? Boolean) ?: (map[key] as? String)?.toBooleanStrictOrNull() ?: defaultValue

    override fun getStringOrNull(key: String): String? = map[key] as? String
    override fun getIntOrNull(key: String): Int? = (map[key] as? Number)?.toInt() ?: (map[key] as? String)?.toIntOrNull()
    override fun getLongOrNull(key: String): Long? = (map[key] as? Number)?.toLong() ?: (map[key] as? String)?.toLongOrNull()
    override fun getFloatOrNull(key: String): Float? = (map[key] as? Number)?.toFloat() ?: (map[key] as? String)?.toFloatOrNull()
    override fun getDoubleOrNull(key: String): Double? = (map[key] as? Number)?.toDouble() ?: (map[key] as? String)?.toDoubleOrNull()
    override fun getBooleanOrNull(key: String): Boolean? = (map[key] as? Boolean) ?: (map[key] as? String)?.toBooleanStrictOrNull()
}
