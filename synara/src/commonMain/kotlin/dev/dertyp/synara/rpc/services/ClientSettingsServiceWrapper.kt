package dev.dertyp.synara.rpc.services

import dev.dertyp.data.ClientDevice
import dev.dertyp.data.ClientSetting
import dev.dertyp.data.ClientSettingScope
import dev.dertyp.data.ClientSettingWrite
import dev.dertyp.data.ClientSettingsChange
import dev.dertyp.data.ClientSettingsChanges
import dev.dertyp.data.ClientSettingsSnapshot
import dev.dertyp.data.ClientSettingsWriteResult
import dev.dertyp.services.IClientSettingsService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class ClientSettingsServiceWrapper(manager: RpcServiceManager) :
    BaseServiceWrapper(manager), IClientSettingsService {

    override suspend fun getSettings(
        scope: ClientSettingScope,
        device: String?,
        includeDeleted: Boolean
    ): List<ClientSetting> {
        return manager.getService<IClientSettingsService>().getSettings(scope, device, includeDeleted)
    }

    override suspend fun getSnapshot(deviceId: String): ClientSettingsSnapshot {
        return manager.getService<IClientSettingsService>().getSnapshot(deviceId)
    }

    override suspend fun getChanges(
        scope: ClientSettingScope,
        sinceVersion: Long,
        device: String?,
        limit: Int
    ): ClientSettingsChanges {
        return manager.getService<IClientSettingsService>().getChanges(scope, sinceVersion, device, limit)
    }

    override suspend fun setSettings(
        entries: List<ClientSettingWrite>,
        scope: ClientSettingScope,
        device: String?,
        force: Boolean
    ): ClientSettingsWriteResult {
        return manager.getService<IClientSettingsService>().setSettings(entries, scope, device, force)
    }

    override suspend fun getHistory(
        scope: ClientSettingScope,
        key: String,
        device: String?,
        limit: Int
    ): List<ClientSetting> {
        return manager.getService<IClientSettingsService>().getHistory(scope, key, device, limit)
    }

    override suspend fun restore(
        scope: ClientSettingScope,
        key: String,
        version: Long,
        device: String?,
        force: Boolean
    ): ClientSettingsWriteResult {
        return manager.getService<IClientSettingsService>().restore(scope, key, version, device, force)
    }

    override fun observeSettings(): Flow<ClientSettingsChange> {
        return manager.getService<IClientSettingsService>().observeSettings()
    }

    override suspend fun getDevices(): List<ClientDevice> {
        return manager.getService<IClientSettingsService>().getDevices()
    }

    override suspend fun registerDevice(deviceId: String, name: String, platform: String): ClientDevice {
        return manager.getService<IClientSettingsService>().registerDevice(deviceId, name, platform)
    }

    override suspend fun deleteDevice(deviceId: String) {
        manager.getService<IClientSettingsService>().deleteDevice(deviceId)
    }
}
