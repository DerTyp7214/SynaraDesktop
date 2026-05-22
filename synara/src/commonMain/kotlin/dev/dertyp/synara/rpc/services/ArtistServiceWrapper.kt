package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.*
import dev.dertyp.services.IArtistService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class ArtistServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IArtistService {
    override suspend fun byId(id: PlatformUUID): Artist? {
        return manager.getService<IArtistService>().byId(id)
    }

    override suspend fun byIds(ids: List<PlatformUUID>): List<Artist> {
        return manager.getService<IArtistService>().byIds(ids)
    }

    override suspend fun byMusicBrainzId(mbId: PlatformUUID): List<Artist> {
        return manager.getService<IArtistService>().byMusicBrainzId(mbId)
    }

    override suspend fun rankedSearch(page: Int, pageSize: Int, query: String): PaginatedResponse<Artist> {
        return manager.getService<IArtistService>().rankedSearch(page, pageSize, query)
    }

    override suspend fun setGroup(id: PlatformUUID, artistIds: List<PlatformUUID>?): Artist? {
        return manager.getService<IArtistService>().setGroup(id, artistIds)
    }

    override suspend fun byGroup(page: Int, pageSize: Int, groupId: PlatformUUID): PaginatedResponse<Artist> {
        return manager.getService<IArtistService>().byGroup(page, pageSize, groupId)
    }

    override suspend fun mergeArtists(mergeArtists: MergeArtists): Artist? {
        return manager.getService<IArtistService>().mergeArtists(mergeArtists)
    }

    override suspend fun splitArtist(splitArtist: SplitArtist): List<Artist> {
        return manager.getService<IArtistService>().splitArtist(splitArtist)
    }

    override suspend fun allArtists(page: Int, pageSize: Int): PaginatedResponse<Artist> {
        return manager.getService<IArtistService>().allArtists(page, pageSize)
    }

    override suspend fun byColor(
        page: Int,
        pageSize: Int,
        color: Int,
        range: Int,
    ): PaginatedResponse<Artist> {
        return manager.getService<IArtistService>().byColor(page, pageSize, color, range)
    }

    override suspend fun createArtist(name: String, isGroup: Boolean, about: String, musicBrainzId: PlatformUUID?): Artist {
        return manager.getService<IArtistService>().createArtist(name, isGroup, about, musicBrainzId)
    }

    override suspend fun searchArtistOnMusicBrainz(query: String, page: Int, pageSize: Int): PaginatedResponse<MusicBrainzArtist> {
        return manager.getService<IArtistService>().searchArtistOnMusicBrainz(query, page, pageSize)
    }

    override suspend fun fetchMusicBrainzId(id: PlatformUUID): Artist? {
        return manager.getService<IArtistService>().fetchMusicBrainzId(id)
    }

    override suspend fun setMusicBrainzId(id: PlatformUUID, musicBrainzId: PlatformUUID?): Artist? {
        return manager.getService<IArtistService>().setMusicBrainzId(id, musicBrainzId)
    }

    override suspend fun searchArtistImages(
        type: IMetadataService.MetadataType,
        query: String,
        limit: Int,
    ): List<IMetadataService.Image> {
        return manager.getService<IArtistService>().searchArtistImages(type, query, limit)
    }

    override suspend fun setArtistImageByUrl(id: PlatformUUID, url: String): Artist? {
        return manager.getService<IArtistService>().setArtistImageByUrl(id, url)
    }

    override fun artistsWithoutMusicBrainzIdFlow(): Flow<Artist> {
        return manager.getService<IArtistService>().artistsWithoutMusicBrainzIdFlow()
    }

    override fun artistIdsWithoutMusicBrainzId(): Flow<PlatformUUID> {
        return manager.getService<IArtistService>().artistIdsWithoutMusicBrainzId()
    }
}
