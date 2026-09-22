package dev.dertyp.synara.sync

import com.russhwolf.settings.Settings
import dev.dertyp.getPlatformName
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.Config
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.getOrNull
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.utils.defaultDeviceName

class DeviceIdentity(private val settings: Settings) {
    val deviceId: String by lazy { ensureDeviceId() }

    val platformDeviceName: String get() = defaultDeviceName()

    val platform: String get() = getPlatformName()

    fun deviceName(): String =
        Config.queueSyncDeviceName.value.takeIf { it.isNotBlank() } ?: platformDeviceName

    private fun ensureDeviceId(): String {
        settings.getOrNull(SettingKey.DeviceId)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = randomPlatformUUID().toString()
        settings.put(SettingKey.DeviceId, id)
        return id
    }
}
