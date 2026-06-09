package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.MusicBrainzArtist
import dev.dertyp.data.MusicBrainzRecording
import dev.dertyp.data.MusicBrainzRelease
import dev.dertyp.data.MusicBrainzReleaseGroup
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.synara.rpc.RpcServiceManager

class MusicBrainzServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IMusicBrainzService {
    override suspend fun getArtist(id: PlatformUUID): MusicBrainzArtist? {
        return manager.getService<IMusicBrainzService>().getArtist(id)
    }

    override suspend fun getRecording(id: PlatformUUID): MusicBrainzRecording? {
        return manager.getService<IMusicBrainzService>().getRecording(id)
    }

    override suspend fun getRecordingByIsrc(isrc: String): MusicBrainzRecording? {
        return manager.getService<IMusicBrainzService>().getRecordingByIsrc(isrc)
    }

    override suspend fun getRelease(id: PlatformUUID): MusicBrainzRelease? {
        return manager.getService<IMusicBrainzService>().getRelease(id)
    }

    override suspend fun getReleaseGroup(id: PlatformUUID): MusicBrainzReleaseGroup? {
        return manager.getService<IMusicBrainzService>().getReleaseGroup(id)
    }

    override suspend fun searchRecording(title: String, artists: List<String>): MusicBrainzRecording? {
        return manager.getService<IMusicBrainzService>().searchRecording(title, artists)
    }

    override suspend fun searchRelease(title: String, artists: List<String>): MusicBrainzRelease? {
        return manager.getService<IMusicBrainzService>().searchRelease(title, artists)
    }

    override suspend fun searchReleaseByBarcode(barcode: String, artists: List<String>): MusicBrainzRelease? {
        return manager.getService<IMusicBrainzService>().searchReleaseByBarcode(barcode, artists)
    }

    override suspend fun getReleasesByReleaseGroup(id: PlatformUUID): List<MusicBrainzRelease> {
        return manager.getService<IMusicBrainzService>().getReleasesByReleaseGroup(id)
    }
}
