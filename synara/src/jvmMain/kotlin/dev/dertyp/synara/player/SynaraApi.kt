package dev.dertyp.synara.player

import dev.dertyp.core.ifCatch
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
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
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
        description = "Get information about the Synara server (host, port).",
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

    @DbusMethodDoc("Get the lyrics of the currently playing song.")
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
    val port: Int
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
    val playlists: List<UserPlaylist>
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
            port = rpcServiceManager.port ?: 8080
        )
        return json.encodeToString(SynaraServerInfo.serializer(), info)
    }

    override fun GetPlaybackState(): String {
        val state = SynaraPlaybackState(
            isPlaying = playerModel.isPlaying.value,
            position = playerModel.currentPosition.value,
            duration = playerModel.duration.value
        )
        return json.encodeToString(SynaraPlaybackState.serializer(), state)
    }

    override fun GetCurrentSong(): String {
        val song = playerModel.currentSong.value ?: return ""
        return json.encodeToString(UserSong.serializer(), song)
    }

    private val rpcServiceManager by inject<RpcServiceManager>()
    private val songService by inject<ISongService>()
    private val albumService by inject<IAlbumService>()
    private val artistService by inject<IArtistService>()
    private val userPlaylistService by inject<IUserPlaylistService>()
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
            val songsDef = if (includeSongs) async { songService.rankedSearch(0, 10, query, explicit = true).data } else null
            val albumsDef = if (includeAlbums) async { albumService.rankedSearch(0, 10, query).data } else null
            val artistsDef = if (includeArtists) async { artistService.rankedSearch(0, 10, query).data } else null
            val playlistsDef = if (includePlaylists) async { userPlaylistService.rankedSearch(globalState.user.value?.id, 0, 10, query).data } else null

            val result = SynaraSearchResult(
                songs = songsDef?.ifCatch(emptyList()) ?: emptyList(),
                albums = albumsDef?.ifCatch(emptyList()) ?: emptyList(),
                artists = artistsDef?.ifCatch(emptyList()) ?: emptyList(),
                playlists = playlistsDef?.ifCatch(emptyList()) ?: emptyList()
            )

            json.encodeToString(SynaraSearchResult.serializer(), result)
        }
    }

    override fun GetCurrentLyrics(): String {
        return playerModel.currentSong.value?.lyrics ?: ""
    }

    override fun GetQueue(limit: Int): String {
        val currentQueue = playerModel.queue.value
        val currentIndex = playerModel.currentIndex.value
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
