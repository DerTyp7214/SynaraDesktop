package dev.dertyp.synara.player

import dev.dertyp.core.ifCatch
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.LikeLevel
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserSong
import dev.dertyp.dbus.annotations.DbusDoc
import dev.dertyp.dbus.annotations.DbusFieldDoc
import dev.dertyp.dbus.annotations.DbusMethodDoc
import dev.dertyp.dbus.annotations.DbusParamDoc
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.services.IUserService
import dev.dertyp.synara.core.PlaylistRole
import dev.dertyp.synara.core.roleOf
import dev.dertyp.synara.lyrics.LyricsRepository
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.imageBaseUrl
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@Suppress("FunctionName")
@DBusInterfaceName("dev.dertyp.synara.Api")
@DbusDoc("Interface for interacting with Synara from external programs.")
interface ISynaraApi : DBusInterface {
    @DbusMethodDoc(
        description = "Get information about the Synara server (host, port, image address).",
        returnsModel = SynaraServerInfo::class
    )
    fun GetServerInfo(): String

    @DbusMethodDoc(
        description = "Get the current playback state (position, duration, isPlaying).",
        returnsModel = SynaraPlaybackState::class
    )
    fun GetPlaybackState(): String

    @DbusMethodDoc(
        description = "Get metadata of the currently playing song.",
        returnsModel = UserSong::class
    )
    fun GetCurrentSong(): String

    @DbusMethodDoc(
        description = "Search for music and playlists.",
        returnsModel = SynaraSearchResult::class
    )
    fun Search(
        @DbusParamDoc("query", "The search query.", example = "Radiohead") query: String,
        @DbusParamDoc("options", "Search filters.", example = "false") options: Map<SearchFilter, Variant<*>>
    ): String

    @DbusMethodDoc("Get the plain text of the lyrics of the currently playing song. It is empty when the song has no lyrics.")
    fun GetCurrentLyrics(): String

    @DbusMethodDoc(
        description = "Get the upcoming songs in the queue.",
        returnsModel = SynaraQueueItem::class,
        returnsList = true
    )
    fun GetQueue(
        @DbusParamDoc("limit", "The maximum number of songs to return.", example = "10") limit: Int
    ): String

    @DbusMethodDoc("Play a specific item from the queue by its unique queueId.")
    fun PlayQueueItem(
        @DbusParamDoc("queueId", "The unique identifier of the queue item.", example = "123456789") queueId: String
    )

    @DbusMethodDoc("Insert an item to play immediately after the current song.")
    fun PlayNext(
        @DbusParamDoc("id", "The UUID of the item.", example = "album-uuid") id: String,
        @DbusParamDoc("type", "The type of the item (song, album, artist, playlist).", example = "album") type: String
    )

    @DbusMethodDoc("Append an item to the end of the queue.")
    fun AddToQueue(
        @DbusParamDoc("id", "The UUID of the item.", example = "song-uuid") id: String,
        @DbusParamDoc("type", "The type of the item (song, album, artist, playlist).", example = "song") type: String
    )

    @DbusMethodDoc("Play a specific song by its UUID.")
    fun PlaySong(
        @DbusParamDoc("id", "The UUID of the song.", example = "song-uuid") id: String
    )

    @DbusMethodDoc("Play a specific album by its UUID.")
    fun PlayAlbum(
        @DbusParamDoc("id", "The UUID of the album.", example = "album-uuid") id: String
    )

    @DbusMethodDoc("Play all songs by a specific artist.")
    fun PlayArtist(
        @DbusParamDoc("id", "The UUID of the artist.", example = "artist-uuid") id: String
    )

    @DbusMethodDoc("Play a specific playlist by its UUID.")
    fun PlayPlaylist(
        @DbusParamDoc("id", "The UUID of the playlist.", example = "playlist-uuid") id: String
    )

    @DbusMethodDoc(
        description = "Get the playlists of the user, both the own ones and the ones shared with the user.",
        returnsModel = SynaraPlaylists::class
    )
    fun GetPlaylists(): String

    @DbusMethodDoc(
        description = "Set the like level of a song. Returns the updated song, or an empty string when the id or the level is invalid or the song is unknown.",
        returnsModel = UserSong::class
    )
    fun SetLikeLevel(
        @DbusParamDoc("id", "The UUID of the song.", example = "song-uuid") id: String,
        @DbusParamDoc("level", "The new like level (NONE, LIKE, SUPER).", example = "LIKE") level: String
    ): String

    @DbusMethodDoc("Add an item to a playlist of the user. Needs write access to the playlist.")
    fun AddToPlaylist(
        @DbusParamDoc("playlistId", "The UUID of the playlist the item is added to.", example = "playlist-uuid") playlistId: String,
        @DbusParamDoc("id", "The UUID of the item.", example = "album-uuid") id: String,
        @DbusParamDoc("type", "The type of the item (song, album, artist, playlist).", example = "album") type: String
    )
}

@Suppress("EnumEntryName")
enum class SearchFilter {
    @DbusFieldDoc("Whether to include songs in the search results.")
    includeSongs,
    @DbusFieldDoc("Whether to include albums in the search results.")
    includeAlbums,
    @DbusFieldDoc("Whether to include artists in the search results.")
    includeArtists,
    @DbusFieldDoc("Whether to include playlists in the search results.")
    includePlaylists
}

@Serializable
data class SynaraPlaybackState(
    @field:DbusFieldDoc("Whether music is currently playing.")
    val isPlaying: Boolean,
    @field:DbusFieldDoc("Current playback position in milliseconds.")
    val position: Long,
    @field:DbusFieldDoc("Total duration of the current song in milliseconds.")
    val duration: Long
)

@Serializable
data class SynaraServerInfo(
    @field:DbusFieldDoc("The host of the Synara server.")
    val host: String,
    @field:DbusFieldDoc("The port of the Synara server.")
    val port: Int,
    @field:DbusFieldDoc("The address that an image id is appended to in order to load an image, for example a cover. It ends with a slash and accepts a size query parameter after the id. Empty while no server is configured.")
    val imageBaseUrl: String
)

@Serializable
data class SynaraPlaylistInfo(
    @field:DbusFieldDoc("The UUID of the playlist this information belongs to.")
    val playlistId: String,
    @field:DbusFieldDoc("The name of the owner of the playlist. Empty when the owner is unknown.")
    val ownerName: String,
    @field:DbusFieldDoc("What the user may do with the playlist.")
    val role: PlaylistRole
)

@Serializable
data class SynaraPlaylists(
    @field:DbusFieldDoc("The own playlists of the user and the playlists shared with the user.")
    val playlists: List<UserPlaylist>,
    @field:DbusFieldDoc("Owner and role for each of the playlists.")
    val playlistInfo: List<SynaraPlaylistInfo>
)

@Serializable
data class SynaraSearchResult(
    @field:DbusFieldDoc("List of songs found in the search.")
    val songs: List<UserSong>,
    @field:DbusFieldDoc("List of albums found in the search.")
    val albums: List<Album>,
    @field:DbusFieldDoc("List of artists found in the search.")
    val artists: List<Artist>,
    @field:DbusFieldDoc("List of playlists found in the search.")
    val playlists: List<UserPlaylist>,
    @field:DbusFieldDoc("Owner and role for each of the playlists found in the search.")
    val playlistInfo: List<SynaraPlaylistInfo> = emptyList()
)

@Serializable
data class SynaraQueueItem(
    @field:DbusFieldDoc("The unique identifier for this occurrence of the song in the queue.")
    val queueId: String,
    @field:DbusFieldDoc("Metadata of the song in the queue.")
    val song: UserSong
)

class SynaraApiImpl(
    private val playerModel: PlayerModel
) : ISynaraApi, KoinComponent {
    override fun isRemote() = false
    override fun getObjectPath() = "/dev/dertyp/synara"

    override fun GetServerInfo(): String {
        val info = SynaraServerInfo(
            host = rpcServiceManager.host ?: "localhost",
            port = rpcServiceManager.port ?: 8080,
            imageBaseUrl = rpcServiceManager.imageBaseUrl() ?: ""
        )
        return json.encodeToString(SynaraServerInfo.serializer(), info)
    }

    private val localSurface = LocalPlaybackSurface(playerModel)

    private fun surface(): PlaybackSurface =
        if (remotePlaybackController.target.value != null) remotePlaybackController else localSurface

    private fun currentQueueIndex(queue: List<QueueEntry>): Int {
        val local = playerModel.currentIndex.value
        if (remotePlaybackController.target.value == null) return local
        val reported = remotePlaybackController.currentQueueId.value ?: return local
        return queue.indexOfFirst { it.queueId == reported }.takeIf { it >= 0 } ?: local
    }

    override fun GetPlaybackState(): String {
        val surface = surface()
        val state = SynaraPlaybackState(
            isPlaying = surface.isPlaying.value,
            position = surface.currentPosition.value,
            duration = surface.duration.value
        )
        return json.encodeToString(SynaraPlaybackState.serializer(), state)
    }

    override fun GetCurrentSong(): String {
        val song = surface().currentSong.value ?: return ""
        return json.encodeToString(UserSong.serializer(), song)
    }

    private val rpcServiceManager by inject<RpcServiceManager>()
    private val remotePlaybackController by inject<RemotePlaybackController>()
    private val songService by inject<ISongService>()
    private val lyricsRepository by inject<LyricsRepository>()
    private val albumService by inject<IAlbumService>()
    private val artistService by inject<IArtistService>()
    private val userPlaylistService by inject<IUserPlaylistService>()
    private val userService by inject<IUserService>()
    private val globalState by inject<GlobalStateModel>()
    private val json by inject<Json>()

    override fun Search(query: String, options: Map<SearchFilter, Variant<*>>): String {
        fun Any?.unwrap(): Any? {
            return when (this) {
                is Variant<*> -> value.unwrap()
                is Collection<*> -> firstOrNull()?.unwrap()
                is Array<*> -> firstOrNull()?.unwrap()
                else -> this
            }
        }

        fun Map<*, Variant<*>>.getBool(filter: SearchFilter): Boolean {
            val raw = get(filter) ?: entries.find { it.key.toString() == filter.name }?.value
            return raw.unwrap() as? Boolean ?: true
        }

        val includeSongs = options.getBool(SearchFilter.includeSongs)
        val includeAlbums = options.getBool(SearchFilter.includeAlbums)
        val includeArtists = options.getBool(SearchFilter.includeArtists)
        val includePlaylists = options.getBool(SearchFilter.includePlaylists)

        return runBlocking {
            supervisorScope {
                val songsDef = if (includeSongs) async { songService.rankedSearch(0, 10, query, explicit = true).data } else null
                val albumsDef = if (includeAlbums) async { albumService.rankedSearch(0, 10, query).data } else null
                val artistsDef = if (includeArtists) async { artistService.rankedSearch(0, 10, query).data } else null
                val playlistsDef = if (includePlaylists) async { userPlaylistService.rankedSearch(null, 0, 10, query).data } else null

                val playlists = playlistsDef?.ifCatch(emptyList()) ?: emptyList()
                val result = SynaraSearchResult(
                    songs = songsDef?.ifCatch(emptyList()) ?: emptyList(),
                    albums = albumsDef?.ifCatch(emptyList()) ?: emptyList(),
                    artists = artistsDef?.ifCatch(emptyList()) ?: emptyList(),
                    playlists = playlists,
                    playlistInfo = playlistInfo(playlists)
                )

                json.encodeToString(SynaraSearchResult.serializer(), result)
            }
        }
    }

    override fun GetCurrentLyrics(): String {
        val song = surface().currentSong.value ?: return ""
        return runBlocking { lyricsRepository.shownLyrics(song.id)?.plain }.orEmpty()
    }

    override fun GetQueue(limit: Int): String {
        val currentQueue = playerModel.queue.value
        val currentIndex = currentQueueIndex(currentQueue)
        val nextItems = currentQueue.drop(currentIndex + 1).take(if (limit <= 0) 10 else limit)

        return runBlocking {
            val items = nextItems.mapNotNull { entry ->
                playerModel.resolveSong(entry)?.let { song ->
                    SynaraQueueItem(entry.queueId.toString(), song)
                }
            }
            json.encodeToString(ListSerializer(SynaraQueueItem.serializer()), items)
        }
    }

    override fun PlayQueueItem(queueId: String) {
        val id = queueId.toLongOrNull() ?: return
        val index = playerModel.queue.value.indexOfFirst { it.queueId == id }
        if (index != -1) {
            playerModel.playAtIndex(index)
        }
    }

    override fun PlayNext(id: String, type: String) {
        resolvePlayback(
            id = id,
            type = type,
            onSong = { song -> playerModel.playNext(song) },
            onQueue = { queue -> playerModel.playNext(queue) }
        )
    }

    override fun AddToQueue(id: String, type: String) {
        resolvePlayback(
            id = id,
            type = type,
            onSong = { song -> playerModel.addToQueue(song) },
            onQueue = { queue -> playerModel.addToQueue(queue) }
        )
    }

    override fun PlaySong(id: String) {
        val uuid = id.toUUIDOrNull() ?: return
        runBlocking {
            val song = songService::byId.ifCatch(uuid, null)
            if (song != null) {
                playerModel.playSong(song)
            }
        }
    }

    override fun PlayAlbum(id: String) = playFromSource(id) { PlaybackSource.Album(it) }
    override fun PlayArtist(id: String) = playFromSource(id) { PlaybackSource.Artist(it) }
    override fun PlayPlaylist(id: String) = playFromSource(id) { PlaybackSource.Playlist(it) }

    override fun GetPlaylists(): String {
        val playlists = globalState.userPlaylists.value
        val result = SynaraPlaylists(
            playlists = playlists,
            playlistInfo = runBlocking { playlistInfo(playlists) }
        )
        return json.encodeToString(SynaraPlaylists.serializer(), result)
    }

    override fun SetLikeLevel(id: String, level: String): String {
        val uuid = id.toUUIDOrNull() ?: return ""
        val likeLevel = LikeLevel.entries.find { it.name == level } ?: return ""
        val song = runBlocking { playerModel::setLikeLevel.ifCatch(uuid, likeLevel, null) } ?: return ""
        return json.encodeToString(UserSong.serializer(), song)
    }

    override fun AddToPlaylist(playlistId: String, id: String, type: String) {
        val playlistUuid = playlistId.toUUIDOrNull() ?: return
        resolvePlayback(
            id = id,
            type = type,
            onSong = { song ->
                playerModel.addSongsToPlaylist(playlistUuid, PlaybackQueue(items = listOf(QueueEntry.Explicit(song))))
            },
            onQueue = { queue -> playerModel.addSongsToPlaylist(playlistUuid, queue) }
        )
    }

    private suspend fun playlistInfo(playlists: List<UserPlaylist>): List<SynaraPlaylistInfo> {
        val user = globalState.user.value
        val profiles = if (playlists.any { it.creator != user?.id }) {
            userService::allProfiles.ifCatch(emptyList())
        } else {
            emptyList()
        }
        val ownerNames = profiles.associate { it.id to (it.displayName ?: it.username) } +
            listOfNotNull(user?.let { it.id to (it.displayName ?: it.username) })
        return playlists.map { playlist ->
            SynaraPlaylistInfo(
                playlistId = playlist.id.toString(),
                ownerName = ownerNames[playlist.creator] ?: "",
                role = playlist.roleOf(user?.id)
            )
        }
    }

    private fun playFromSource(id: String, sourceFactory: (dev.dertyp.PlatformUUID) -> PlaybackSource) {
        id.toUUIDOrNull()?.let { uuid ->
            playerModel.playQueue(PlaybackQueue(source = sourceFactory(uuid)))
        }
    }

    private fun resolvePlayback(
        id: String,
        type: String,
        onSong: (UserSong) -> Unit,
        onQueue: (PlaybackQueue) -> Unit
    ) {
        val uuid = id.toUUIDOrNull() ?: return
        when (type.lowercase()) {
            "song" -> {
                runBlocking {
                    songService::byId.ifCatch(uuid, null)?.let(onSong)
                }
            }
            "album" -> onQueue(PlaybackQueue(source = PlaybackSource.Album(uuid)))
            "artist" -> onQueue(PlaybackQueue(source = PlaybackSource.Artist(uuid)))
            "playlist" -> onQueue(PlaybackQueue(source = PlaybackSource.Playlist(uuid)))
        }
    }
}
