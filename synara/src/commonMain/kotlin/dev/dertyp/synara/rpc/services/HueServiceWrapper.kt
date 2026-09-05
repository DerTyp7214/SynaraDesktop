package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.HueBridgeCandidate
import dev.dertyp.data.HueBridgeInfo
import dev.dertyp.data.HuePairingStatus
import dev.dertyp.data.HueStatus
import dev.dertyp.data.HueTarget
import dev.dertyp.data.HueUserLink
import dev.dertyp.services.IHueService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class HueServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IHueService {
    override suspend fun discoverBridges(): List<HueBridgeCandidate> {
        return manager.getService<IHueService>().discoverBridges()
    }

    override suspend fun listBridges(): List<HueBridgeInfo> {
        return manager.getService<IHueService>().listBridges()
    }

    override fun startPairing(ip: String): Flow<HuePairingStatus> {
        return manager.getService<IHueService>().startPairing(ip)
    }

    override suspend fun removeBridge(bridgeId: PlatformUUID): Boolean {
        return manager.getService<IHueService>().removeBridge(bridgeId)
    }

    override suspend fun listTargets(bridgeId: PlatformUUID): List<HueTarget> {
        return manager.getService<IHueService>().listTargets(bridgeId)
    }

    override suspend fun getLinks(): List<HueUserLink> {
        return manager.getService<IHueService>().getLinks()
    }

    override suspend fun setLink(link: HueUserLink): HueUserLink {
        return manager.getService<IHueService>().setLink(link)
    }

    override suspend fun removeLink(bridgeId: PlatformUUID): Boolean {
        return manager.getService<IHueService>().removeLink(bridgeId)
    }

    override suspend fun test(bridgeId: PlatformUUID, targets: List<HueTarget>): Boolean {
        return manager.getService<IHueService>().test(bridgeId, targets)
    }

    override suspend fun status(): HueStatus {
        return manager.getService<IHueService>().status()
    }
}
