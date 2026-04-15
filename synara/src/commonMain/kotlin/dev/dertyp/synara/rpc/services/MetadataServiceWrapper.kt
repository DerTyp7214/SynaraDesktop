package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.synara.rpc.RpcServiceManager

class MetadataServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IMetadataService {
    override suspend fun searchArtists(
        type: IMetadataService.MetadataType,
        query: String,
        limit: Int
    ): List<IMetadataService.Artist> {
        return manager.getService<IMetadataService>().searchArtists(type, query, limit)
    }

    override suspend fun search(
        type: IMetadataService.MetadataType,
        query: String,
        limit: Int
    ): List<IMetadataService.Track> {
        return manager.getService<IMetadataService>().search(type, query, limit)
    }

    override suspend fun searchAlbums(
        type: IMetadataService.MetadataType,
        query: String,
        limit: Int,
        includeTracks: Boolean
    ): List<IMetadataService.Album> {
        return manager.getService<IMetadataService>().searchAlbums(type, query, limit, includeTracks)
    }

    override suspend fun getAlbumIdByTrackId(type: IMetadataService.MetadataType, trackId: String): String? {
        return manager.getService<IMetadataService>().getAlbumIdByTrackId(type, trackId)
    }

    override suspend fun getImageUrlByAlbumId(
        type: IMetadataService.MetadataType,
        albumId: String
    ): List<IMetadataService.Image> {
        return manager.getService<IMetadataService>().getImageUrlByAlbumId(type, albumId)
    }

    override suspend fun getArtistByMbId(type: IMetadataService.MetadataType, mbId: PlatformUUID): IMetadataService.Artist? {
        return manager.getService<IMetadataService>().getArtistByMbId(type, mbId)
    }

    override suspend fun getAlbumByMbId(type: IMetadataService.MetadataType, mbId: PlatformUUID): IMetadataService.Album? {
        return manager.getService<IMetadataService>().getAlbumByMbId(type, mbId)
    }

    override suspend fun getTrackByMbId(type: IMetadataService.MetadataType, mbId: PlatformUUID): IMetadataService.Track? {
        return manager.getService<IMetadataService>().getTrackByMbId(type, mbId)
    }

    override suspend fun getImageUrlByArtistMbId(
        type: IMetadataService.MetadataType,
        mbId: PlatformUUID
    ): List<IMetadataService.Image> {
        return manager.getService<IMetadataService>().getImageUrlByArtistMbId(type, mbId)
    }

    override suspend fun getImageUrlByAlbumMbId(
        type: IMetadataService.MetadataType,
        mbId: PlatformUUID
    ): List<IMetadataService.Image> {
        return manager.getService<IMetadataService>().getImageUrlByAlbumMbId(type, mbId)
    }

    override suspend fun getImageUrlByTrackMbId(
        type: IMetadataService.MetadataType,
        mbId: PlatformUUID
    ): List<IMetadataService.Image> {
        return manager.getService<IMetadataService>().getImageUrlByTrackMbId(type, mbId)
    }

    override suspend fun getImageUrlsByAlbumIds(
        type: IMetadataService.MetadataType,
        albumIds: List<String>
    ): Map<String, List<IMetadataService.Image>> {
        return manager.getService<IMetadataService>().getImageUrlsByAlbumIds(type, albumIds)
    }

    override suspend fun getImageUrlByImageId(type: IMetadataService.MetadataType, imageId: PlatformUUID): String? {
        return manager.getService<IMetadataService>().getImageUrlByImageId(type, imageId)
    }

    override suspend fun getTrackById(type: IMetadataService.MetadataType, trackId: String): IMetadataService.Track? {
        return manager.getService<IMetadataService>().getTrackById(type, trackId)
    }

    override suspend fun getTracksByIds(type: IMetadataService.MetadataType, trackIds: List<String>): List<IMetadataService.Track> {
        return manager.getService<IMetadataService>().getTracksByIds(type, trackIds)
    }

    override suspend fun getAlbumsByIds(type: IMetadataService.MetadataType, albumIds: List<String>): List<IMetadataService.Album> {
        return manager.getService<IMetadataService>().getAlbumsByIds(type, albumIds)
    }

    override suspend fun albumExistsById(type: IMetadataService.MetadataType, albumId: String): Boolean {
        return manager.getService<IMetadataService>().albumExistsById(type, albumId)
    }

    override suspend fun getArtistsByIds(
        type: IMetadataService.MetadataType,
        artistIds: List<String>
    ): List<IMetadataService.Artist> {
        return manager.getService<IMetadataService>().getArtistsByIds(type, artistIds)
    }
}
