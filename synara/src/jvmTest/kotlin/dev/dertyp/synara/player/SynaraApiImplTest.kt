package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.ClientCapability
import dev.dertyp.data.OnlineDevice
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.queueOf
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testAlbum
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.viewmodels.GlobalStateModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.freedesktop.dbus.types.Variant
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynaraApiImplTest {

    private val playerModel = mockk<PlayerModel>(relaxed = true)
    private val rpcServiceManager = mockk<RpcServiceManager>()
    private val songService = mockk<ISongService>()
    private val albumService = mockk<IAlbumService>()
    private val artistService = mockk<IArtistService>()
    private val userPlaylistService = mockk<IUserPlaylistService>()
    private val globalState = mockk<GlobalStateModel>()
    private val songFlow = MutableStateFlow<UserSong?>(null)
    private val playingFlow = MutableStateFlow(false)
    private val positionFlow = MutableStateFlow(0L)
    private val durationFlow = MutableStateFlow(0L)
    private val queueFlow = MutableStateFlow<List<QueueEntry>>(emptyList())
    private val indexFlow = MutableStateFlow(-1)
    private val remotePlaybackController = mockk<RemotePlaybackController>(relaxed = true)
    private val remoteTarget = MutableStateFlow<OnlineDevice?>(null)
    private val remoteSong = MutableStateFlow<UserSong?>(null)
    private val remotePlaying = MutableStateFlow(false)
    private val remotePosition = MutableStateFlow(0L)
    private val remoteDuration = MutableStateFlow(0L)
    private val remoteQueueId = MutableStateFlow<Long?>(null)
    private lateinit var api: SynaraApiImpl

    @BeforeTest
    fun setUp() {
        every { playerModel.currentSong } returns songFlow
        every { playerModel.isPlaying } returns playingFlow
        every { playerModel.currentPosition } returns positionFlow
        every { playerModel.duration } returns durationFlow
        every { playerModel.queue } returns queueFlow
        every { playerModel.currentIndex } returns indexFlow
        every { remotePlaybackController.target } returns remoteTarget
        every { remotePlaybackController.currentSong } returns remoteSong
        every { remotePlaybackController.isPlaying } returns remotePlaying
        every { remotePlaybackController.currentPosition } returns remotePosition
        every { remotePlaybackController.duration } returns remoteDuration
        every { remotePlaybackController.currentQueueId } returns remoteQueueId
        every { rpcServiceManager.host } returns null
        every { rpcServiceManager.port } returns null
        every { globalState.user } returns MutableStateFlow(null)
        startTestKoin(
            module {
                single<ISongService> { songService }
                single<IAlbumService> { albumService }
                single<IArtistService> { artistService }
                single<IUserPlaylistService> { userPlaylistService }
                single { globalState }
                single<Json> { AppJson }
                single { rpcServiceManager }
                single { remotePlaybackController }
            }
        )
        api = SynaraApiImpl(playerModel)
    }

    @AfterTest
    fun tearDown() {
        stopTestKoin()
    }

    private fun entries(songs: List<UserSong>): List<QueueEntry> = queueOf(songs).items

    private fun stubResolveSong(vararg unresolved: QueueEntry) {
        coEvery { playerModel.resolveSong(any()) } answers {
            val entry = firstArg<QueueEntry>()
            if (entry in unresolved) null else (entry as QueueEntry.Explicit).song
        }
    }

    private fun stubSearch(
        songs: List<UserSong> = emptyList(),
        albums: List<Album> = emptyList(),
        artists: List<Artist> = emptyList(),
        playlists: List<UserPlaylist> = emptyList()
    ) {
        coEvery { songService.rankedSearch(any(), any(), any(), any(), any()) } returns PaginatedResponse(songs)
        coEvery { albumService.rankedSearch(any(), any(), any()) } returns PaginatedResponse(albums)
        coEvery { artistService.rankedSearch(any(), any(), any()) } returns PaginatedResponse(artists)
        coEvery { userPlaylistService.rankedSearch(any(), any(), any(), any()) } returns PaginatedResponse(playlists)
    }

    private fun testPlaylist(name: String) = UserPlaylist(
        id = randomPlatformUUID(),
        name = name,
        songs = emptyList(),
        creator = randomPlatformUUID(),
        description = ""
    )

    private fun decodeSearch(json: String) = AppJson.decodeFromString(SynaraSearchResult.serializer(), json)

    private fun stringKeyed(vararg options: Pair<String, Variant<*>>): Map<SearchFilter, Variant<*>> {
        val map = HashMap<SearchFilter, Variant<*>>()
        val put = HashMap::class.java.getMethod("put", Any::class.java, Any::class.java)
        options.forEach { (key, value) -> put.invoke(map, key, value) }
        return map
    }

    @Test
    fun `GetServerInfo falls back to localhost and 8080 without a configured server`() {
        val info = AppJson.decodeFromString(SynaraServerInfo.serializer(), api.GetServerInfo())

        assertEquals(SynaraServerInfo("localhost", 8080), info)
    }

    @Test
    fun `GetServerInfo passes the configured host and port through`() {
        every { rpcServiceManager.host } returns "music.example"
        every { rpcServiceManager.port } returns 4433

        val info = AppJson.decodeFromString(SynaraServerInfo.serializer(), api.GetServerInfo())

        assertEquals(SynaraServerInfo("music.example", 4433), info)
    }

    @Test
    fun `GetPlaybackState mirrors the player flows`() {
        playingFlow.value = true
        positionFlow.value = 1234L
        durationFlow.value = 5678L

        val state = AppJson.decodeFromString(SynaraPlaybackState.serializer(), api.GetPlaybackState())

        assertEquals(SynaraPlaybackState(isPlaying = true, position = 1234L, duration = 5678L), state)
    }

    @Test
    fun `GetCurrentSong is empty without a song`() {
        songFlow.value = null

        assertEquals("", api.GetCurrentSong())
    }

    @Test
    fun `GetCurrentSong serialises the current song`() {
        val song = testSong(1)
        songFlow.value = song

        assertEquals(song, AppJson.decodeFromString(UserSong.serializer(), api.GetCurrentSong()))
    }

    @Test
    fun `GetCurrentLyrics is empty without a song`() {
        songFlow.value = null

        assertEquals("", api.GetCurrentLyrics())
    }

    @Test
    fun `GetCurrentLyrics returns the lyrics of the current song`() {
        songFlow.value = testSong(1).copy(lyrics = "first line\nsecond line")

        assertEquals("first line\nsecond line", api.GetCurrentLyrics())
    }

    @Test
    fun `GetQueue with a limit returns that many songs after the current one`() {
        val songs = List(4) { testSong(it) }
        val queue = entries(songs)
        queueFlow.value = queue
        indexFlow.value = 1
        stubResolveSong()

        val items = AppJson.decodeFromString(ListSerializer(SynaraQueueItem.serializer()), api.GetQueue(1))

        assertEquals(listOf(songs[2]), items.map { it.song })
        assertEquals(listOf(queue[2].queueId.toString()), items.map { it.queueId })
    }

    @Test
    fun `GetQueue without a positive limit returns up to ten songs`() {
        val songs = List(15) { testSong(it) }
        queueFlow.value = entries(songs)
        indexFlow.value = 0
        stubResolveSong()

        val zero = AppJson.decodeFromString(ListSerializer(SynaraQueueItem.serializer()), api.GetQueue(0))
        val negative = AppJson.decodeFromString(ListSerializer(SynaraQueueItem.serializer()), api.GetQueue(-5))

        assertEquals(songs.subList(1, 11), zero.map { it.song })
        assertEquals(songs.subList(1, 11), negative.map { it.song })
    }

    @Test
    fun `GetQueue returns what is left when the queue is shorter than the limit`() {
        val songs = List(4) { testSong(it) }
        queueFlow.value = entries(songs)
        indexFlow.value = 1
        stubResolveSong()

        val items = AppJson.decodeFromString(ListSerializer(SynaraQueueItem.serializer()), api.GetQueue(10))

        assertEquals(songs.subList(2, 4), items.map { it.song })
    }

    @Test
    fun `GetQueue skips entries that cannot be resolved`() {
        val songs = List(4) { testSong(it) }
        val queue = entries(songs)
        queueFlow.value = queue
        indexFlow.value = 0
        stubResolveSong(queue[2])

        val items = AppJson.decodeFromString(ListSerializer(SynaraQueueItem.serializer()), api.GetQueue(10))

        assertEquals(listOf(songs[1], songs[3]), items.map { it.song })
    }

    private fun controlDevice() {
        remoteTarget.value = OnlineDevice(
            sessionId = randomPlatformUUID(),
            deviceName = "Device",
            capabilities = setOf(ClientCapability.QUEUE_SYNC, ClientCapability.REMOTE_CONTROL),
            isCurrent = false,
            connectedAt = 0L
        )
    }

    @Test
    fun `GetPlaybackState reports the controlled device`() {
        playingFlow.value = false
        positionFlow.value = 0L
        durationFlow.value = 5678L
        remotePlaying.value = true
        remotePosition.value = 42_000L
        remoteDuration.value = 240_000L
        controlDevice()

        val state = AppJson.decodeFromString(SynaraPlaybackState.serializer(), api.GetPlaybackState())

        assertEquals(SynaraPlaybackState(isPlaying = true, position = 42_000L, duration = 240_000L), state)
    }

    @Test
    fun `GetPlaybackState reports the player again after the device is handed back`() {
        playingFlow.value = true
        positionFlow.value = 1234L
        durationFlow.value = 5678L
        remotePosition.value = 42_000L
        controlDevice()
        remoteTarget.value = null

        val state = AppJson.decodeFromString(SynaraPlaybackState.serializer(), api.GetPlaybackState())

        assertEquals(SynaraPlaybackState(isPlaying = true, position = 1234L, duration = 5678L), state)
    }

    @Test
    fun `GetCurrentSong serialises the song of the controlled device`() {
        val remote = testSong(2)
        songFlow.value = testSong(1)
        remoteSong.value = remote
        controlDevice()

        assertEquals(remote, AppJson.decodeFromString(UserSong.serializer(), api.GetCurrentSong()))
    }

    @Test
    fun `GetCurrentSong is empty while the controlled device reports no song`() {
        songFlow.value = testSong(1)
        controlDevice()

        assertEquals("", api.GetCurrentSong())
    }

    @Test
    fun `GetCurrentLyrics returns the lyrics of the song of the controlled device`() {
        songFlow.value = testSong(1).copy(lyrics = "local line")
        remoteSong.value = testSong(2).copy(lyrics = "remote line")
        controlDevice()

        assertEquals("remote line", api.GetCurrentLyrics())
    }

    @Test
    fun `GetQueue returns the songs after the entry the controlled device plays`() {
        val songs = List(5) { testSong(it) }
        val queue = entries(songs)
        queueFlow.value = queue
        indexFlow.value = 0
        remoteQueueId.value = queue[2].queueId
        controlDevice()
        stubResolveSong()

        val items = AppJson.decodeFromString(ListSerializer(SynaraQueueItem.serializer()), api.GetQueue(10))

        assertEquals(songs.subList(3, 5), items.map { it.song })
    }

    @Test
    fun `GetQueue falls back to the own position while the controlled device reports no known entry`() {
        val songs = List(4) { testSong(it) }
        queueFlow.value = entries(songs)
        indexFlow.value = 1
        controlDevice()
        stubResolveSong()

        val unreported = AppJson.decodeFromString(ListSerializer(SynaraQueueItem.serializer()), api.GetQueue(10))
        remoteQueueId.value = Long.MIN_VALUE
        val unknown = AppJson.decodeFromString(ListSerializer(SynaraQueueItem.serializer()), api.GetQueue(10))

        assertEquals(songs.subList(2, 4), unreported.map { it.song })
        assertEquals(songs.subList(2, 4), unknown.map { it.song })
    }

    @Test
    fun `PlayQueueItem with a non numeric id does nothing`() {
        queueFlow.value = entries(List(3) { testSong(it) })

        api.PlayQueueItem("abc")

        verify(exactly = 0) { playerModel.playAtIndex(any(), any()) }
    }

    @Test
    fun `PlayQueueItem with an unknown id does nothing`() {
        val queue = entries(List(3) { testSong(it) })
        queueFlow.value = queue
        val unknown = (queue.maxOf { it.queueId } + 1).toString()

        api.PlayQueueItem(unknown)

        verify(exactly = 0) { playerModel.playAtIndex(any(), any()) }
    }

    @Test
    fun `PlayQueueItem with a known id plays that index`() {
        val queue = entries(List(3) { testSong(it) })
        queueFlow.value = queue

        api.PlayQueueItem(queue[1].queueId.toString())

        verify(exactly = 1) { playerModel.playAtIndex(1, true) }
    }

    @Test
    fun `PlayNext with an invalid id does nothing`() {
        api.PlayNext("not-a-uuid", "album")

        verify(exactly = 0) { playerModel.playNext(any<PlaybackQueue>()) }
        verify(exactly = 0) { playerModel.playNext(any<UserSong>()) }
    }

    @Test
    fun `PlayNext with an unknown type does nothing`() {
        api.PlayNext(randomPlatformUUID().toString(), "podcast")

        verify(exactly = 0) { playerModel.playNext(any<PlaybackQueue>()) }
        verify(exactly = 0) { playerModel.playNext(any<UserSong>()) }
    }

    @Test
    fun `PlayNext queues albums artists and playlists by source and ignores the case of the type`() {
        val albumId = randomPlatformUUID()
        val artistId = randomPlatformUUID()
        val playlistId = randomPlatformUUID()

        api.PlayNext(albumId.toString(), "ALBUM")
        api.PlayNext(artistId.toString(), "Artist")
        api.PlayNext(playlistId.toString(), "playlist")

        verify(exactly = 1) { playerModel.playNext(PlaybackQueue(source = PlaybackSource.Album(albumId))) }
        verify(exactly = 1) { playerModel.playNext(PlaybackQueue(source = PlaybackSource.Artist(artistId))) }
        verify(exactly = 1) { playerModel.playNext(PlaybackQueue(source = PlaybackSource.Playlist(playlistId))) }
    }

    @Test
    fun `PlayNext with a song id loads the song and plays it next`() {
        val song = testSong(1)
        coEvery { songService.byId(song.id) } returns song

        api.PlayNext(song.id.toString(), "SONG")

        verify(exactly = 1) { playerModel.playNext(song) }
    }

    @Test
    fun `PlayNext with a song id the server does not know does nothing`() {
        val id = randomPlatformUUID()
        coEvery { songService.byId(id) } returns null

        api.PlayNext(id.toString(), "song")

        verify(exactly = 0) { playerModel.playNext(any<UserSong>()) }
    }

    @Test
    fun `AddToQueue with an invalid id does nothing`() {
        api.AddToQueue("not-a-uuid", "album")

        verify(exactly = 0) { playerModel.addToQueue(any<PlaybackQueue>()) }
        verify(exactly = 0) { playerModel.addToQueue(any<UserSong>()) }
    }

    @Test
    fun `AddToQueue with an unknown type does nothing`() {
        api.AddToQueue(randomPlatformUUID().toString(), "podcast")

        verify(exactly = 0) { playerModel.addToQueue(any<PlaybackQueue>()) }
        verify(exactly = 0) { playerModel.addToQueue(any<UserSong>()) }
    }

    @Test
    fun `AddToQueue appends albums artists and playlists by source and ignores the case of the type`() {
        val albumId = randomPlatformUUID()
        val artistId = randomPlatformUUID()
        val playlistId = randomPlatformUUID()

        api.AddToQueue(albumId.toString(), "Album")
        api.AddToQueue(artistId.toString(), "ARTIST")
        api.AddToQueue(playlistId.toString(), "playlist")

        verify(exactly = 1) { playerModel.addToQueue(PlaybackQueue(source = PlaybackSource.Album(albumId))) }
        verify(exactly = 1) { playerModel.addToQueue(PlaybackQueue(source = PlaybackSource.Artist(artistId))) }
        verify(exactly = 1) { playerModel.addToQueue(PlaybackQueue(source = PlaybackSource.Playlist(playlistId))) }
    }

    @Test
    fun `AddToQueue with a song id loads the song and appends it`() {
        val song = testSong(1)
        coEvery { songService.byId(song.id) } returns song

        api.AddToQueue(song.id.toString(), "song")

        verify(exactly = 1) { playerModel.addToQueue(song) }
    }

    @Test
    fun `AddToQueue with a song id the server does not know does nothing`() {
        val id = randomPlatformUUID()
        coEvery { songService.byId(id) } returns null

        api.AddToQueue(id.toString(), "song")

        verify(exactly = 0) { playerModel.addToQueue(any<UserSong>()) }
    }

    @Test
    fun `PlaySong loads the song through the service and plays it`() {
        val song = testSong(1)
        coEvery { songService.byId(song.id) } returns song

        api.PlaySong(song.id.toString())

        verify(exactly = 1) { playerModel.playSong(song) }
    }

    @Test
    fun `PlaySong with an invalid id or an unknown song does nothing`() {
        val id: PlatformUUID = randomPlatformUUID()
        coEvery { songService.byId(id) } returns null

        api.PlaySong("not-a-uuid")
        api.PlaySong(id.toString())

        verify(exactly = 0) { playerModel.playSong(any()) }
    }

    @Test
    fun `PlayAlbum PlayArtist and PlayPlaylist start a queue from the matching source`() {
        val albumId = randomPlatformUUID()
        val artistId = randomPlatformUUID()
        val playlistId = randomPlatformUUID()

        api.PlayAlbum(albumId.toString())
        api.PlayArtist(artistId.toString())
        api.PlayPlaylist(playlistId.toString())

        verify(exactly = 1) { playerModel.playQueue(PlaybackQueue(source = PlaybackSource.Album(albumId)), 0) }
        verify(exactly = 1) { playerModel.playQueue(PlaybackQueue(source = PlaybackSource.Artist(artistId)), 0) }
        verify(exactly = 1) { playerModel.playQueue(PlaybackQueue(source = PlaybackSource.Playlist(playlistId)), 0) }
    }

    @Test
    fun `PlayAlbum PlayArtist and PlayPlaylist with an invalid id do nothing`() {
        api.PlayAlbum("nope")
        api.PlayArtist("nope")
        api.PlayPlaylist("nope")

        verify(exactly = 0) { playerModel.playQueue(any(), any()) }
    }

    @Test
    fun `Search with includeSongs false skips the song service and returns no songs`() {
        val album = testAlbum(1)
        val artist = Artist(randomPlatformUUID(), "Artist", false)
        stubSearch(songs = listOf(testSong(1)), albums = listOf(album), artists = listOf(artist))

        val result = decodeSearch(api.Search("query", mapOf(SearchFilter.includeSongs to Variant(false))))

        coVerify(exactly = 0) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { albumService.rankedSearch(any(), any(), any()) }
        coVerify(exactly = 1) { artistService.rankedSearch(any(), any(), any()) }
        coVerify(exactly = 1) { userPlaylistService.rankedSearch(any(), any(), any(), any()) }
        assertTrue(result.songs.isEmpty())
        assertEquals(listOf(album), result.albums)
        assertEquals(listOf(artist), result.artists)
    }

    @Test
    fun `Search without options queries all four services`() {
        val song = testSong(1)
        stubSearch(songs = listOf(song))

        val result = decodeSearch(api.Search("query", emptyMap()))

        coVerify(exactly = 1) { songService.rankedSearch(0, 10, "query", true, any()) }
        coVerify(exactly = 1) { albumService.rankedSearch(0, 10, "query") }
        coVerify(exactly = 1) { artistService.rankedSearch(0, 10, "query") }
        coVerify(exactly = 1) { userPlaylistService.rankedSearch(null, 0, 10, "query") }
        assertEquals(listOf(song), result.songs)
    }

    @Test
    fun `Search returns the playlists the playlist service found`() {
        val playlists = listOf(testPlaylist("one"), testPlaylist("two"))
        stubSearch(playlists = playlists)

        val result = decodeSearch(api.Search("query", emptyMap()))

        assertEquals(playlists, result.playlists)
    }

    @Test
    fun `Search restricts the playlist search to the logged in user`() {
        val user = User(randomPlatformUUID(), "listener")
        every { globalState.user } returns MutableStateFlow(user)
        stubSearch()

        api.Search("query", emptyMap())

        coVerify(exactly = 1) { userPlaylistService.rankedSearch(user.id, 0, 10, "query") }
    }

    @Test
    fun `Search keeps the other results when the album service fails`() {
        val song = testSong(1)
        val artist = Artist(randomPlatformUUID(), "Artist", false)
        val playlist = testPlaylist("mine")
        stubSearch(songs = listOf(song), artists = listOf(artist), playlists = listOf(playlist))
        coEvery { albumService.rankedSearch(any(), any(), any()) } throws IllegalStateException("album search down")

        val result = decodeSearch(api.Search("query", emptyMap()))

        assertEquals(listOf(song), result.songs)
        assertEquals(listOf(artist), result.artists)
        assertEquals(listOf(playlist), result.playlists)
        assertTrue(result.albums.isEmpty())
    }

    @Test
    fun `Search honours options keyed by the filter name`() {
        stubSearch()

        api.Search("query", stringKeyed("includeAlbums" to Variant(false), "includePlaylists" to Variant(false)))

        coVerify(exactly = 1) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { albumService.rankedSearch(any(), any(), any()) }
        coVerify(exactly = 1) { artistService.rankedSearch(any(), any(), any()) }
        coVerify(exactly = 0) { userPlaylistService.rankedSearch(any(), any(), any(), any()) }
    }

    @Test
    fun `Search unwraps a list valued option`() {
        stubSearch()

        api.Search("query", mapOf(SearchFilter.includeSongs to Variant(listOf(false), "ab")))

        coVerify(exactly = 0) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { albumService.rankedSearch(any(), any(), any()) }
    }

    @Test
    fun `Search unwraps an array valued option`() {
        stubSearch()

        api.Search("query", mapOf(SearchFilter.includeArtists to Variant(arrayOf(false), "ab")))

        coVerify(exactly = 0) { artistService.rankedSearch(any(), any(), any()) }
        coVerify(exactly = 1) { songService.rankedSearch(any(), any(), any(), any(), any()) }
    }
}
