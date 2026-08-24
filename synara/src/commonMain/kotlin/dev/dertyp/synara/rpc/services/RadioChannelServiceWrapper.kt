package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.InsertableRadioChannel
import dev.dertyp.data.RadioChannel
import dev.dertyp.data.RadioChannelItemType
import dev.dertyp.data.RadioChannelSearchResults
import dev.dertyp.services.IRadioChannelService
import dev.dertyp.synara.rpc.RpcServiceManager

class RadioChannelServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IRadioChannelService {
    override suspend fun listChannels(): List<RadioChannel> {
        return manager.getService<IRadioChannelService>().listChannels()
    }

    override suspend fun getChannel(id: PlatformUUID): RadioChannel? {
        return manager.getService<IRadioChannelService>().getChannel(id)
    }

    override suspend fun rankedSearch(
        channelId: PlatformUUID,
        query: String,
        explicit: Boolean,
        page: Int,
        pageSize: Int
    ): RadioChannelSearchResults {
        return manager.getService<IRadioChannelService>().rankedSearch(channelId, query, explicit, page, pageSize)
    }

    override suspend fun startChannel(id: PlatformUUID): PlatformUUID {
        return manager.getService<IRadioChannelService>().startChannel(id)
    }

    override suspend fun createChannel(channel: InsertableRadioChannel): PlatformUUID {
        return manager.getService<IRadioChannelService>().createChannel(channel)
    }

    override suspend fun updateChannel(id: PlatformUUID, channel: InsertableRadioChannel): Boolean {
        return manager.getService<IRadioChannelService>().updateChannel(id, channel)
    }

    override suspend fun deleteChannel(id: PlatformUUID): Boolean {
        return manager.getService<IRadioChannelService>().deleteChannel(id)
    }

    override suspend fun setChannelImage(id: PlatformUUID, bytes: ByteArray) {
        manager.getService<IRadioChannelService>().setChannelImage(id, bytes)
    }

    override suspend fun addChannelItem(id: PlatformUUID, type: RadioChannelItemType, itemId: PlatformUUID): Boolean {
        return manager.getService<IRadioChannelService>().addChannelItem(id, type, itemId)
    }

    override suspend fun removeChannelItem(id: PlatformUUID, type: RadioChannelItemType, itemId: PlatformUUID): Boolean {
        return manager.getService<IRadioChannelService>().removeChannelItem(id, type, itemId)
    }
}
