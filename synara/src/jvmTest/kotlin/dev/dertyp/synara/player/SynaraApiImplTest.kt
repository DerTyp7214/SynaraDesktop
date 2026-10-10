package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.ClientCapability
import dev.dertyp.data.LikeLevel
import dev.dertyp.data.OnlineDevice
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PlaylistAccess
import dev.dertyp.data.PlaylistShare
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserProfile
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.services.IUserService
import dev.dertyp.synara.core.PlaylistRole
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
    private val userService = mockk<IUserService>()
    private val userPlaylistsFlow = MutableStateFlow<List<UserPlaylist>>(emptyList())
    private val sslOverrideFlow = MutableStateFlow<Boolean?>(null)
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
        every { rpcServiceManager.ssl } returns false
        every { rpcServiceManager.rpcPath } returns "/"
        every { rpcServiceManager.sessionSslOverride } returns sslOverrideFlow
        every { globalState.user } returns MutableStateFlow(null)
        every { globalState.userPlaylists } returns userPlaylistsFlow
        coEvery { userService.allProfiles() } returns emptyList()
        startTestKoin(
            module {
                single<ISongService> { songService }
                single<IAlbumService> { albumService }
                single<IArtistService> { artistService }
                single<IUserPlaylistService> { userPlaylistService }
                single<IUserService> { userService }
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

    private fun testPlaylist(
        name: String,
        creator: PlatformUUID = randomPlatformUUID(),
        isPublic: Boolean = false,
        shares: List<PlaylistShare> = emptyList()
    ) = UserPlaylist(
        id = randomPlatformUUID(),
        name = name,
        songs = emptyList(),
        creator = creator,
        description = "",
        isPublic = isPublic,
        shares = shares
    )

    private fun logIn(user: User) {
        every { globalState.user } returns MutableStateFlow(user)
    }

    private fun configureServer(ssl: Boolean, path: String = "/") {
        every { rpcServiceManager.host } returns "music.example"
        every { rpcServiceManager.port } returns 4433
        every { rpcServiceManager.ssl } returns ssl
        every { rpcServiceManager.rpcPath } returns path
    }

    private fun serverInfo() = AppJson.decodeFromString(SynaraServerInfo.serializer(), api.GetServerInfo())

    private fun decodePlaylists(json: String) = AppJson.decodeFromString(SynaraPlaylists.serializer(), json)

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

        assertEquals(SynaraServerInfo("localhost", 8080, ""), info)
    }

    @Test
    fun `GetServerInfo passes the configured host and port through`() {
        every { rpcServiceManager.host } returns "music.example"
        every { rpcServiceManager.port } returns 4433

        val info = AppJson.decodeFromString(SynaraServerInfo.serializer(), api.GetServerInfo())

        assertEquals(SynaraServerInfo("music.example", 4433, "http://music.example:4433/image/imageData/"), info)
    }

    @Test
    fun `GetServerInfo reports an http image address for a server without ssl`() {
        configureServer(ssl = false)

        assertEquals("http://music.example:4433/image/imageData/", serverInfo().imageBaseUrl)
    }

    @Test
    fun `GetServerInfo reports an https image address for a server with ssl`() {
        configureServer(ssl = true)

        assertEquals("https://music.example:4433/image/imageData/", serverInfo().imageBaseUrl)
    }

    @Test
    fun `GetServerInfo reports an http image address when the session ssl override is false`() {
        configureServer(ssl = true)
        sslOverrideFlow.value = false

        assertEquals("http://music.example:4433/image/imageData/", serverInfo().imageBaseUrl)
    }

    @Test
    fun `GetServerInfo keeps https when the session ssl override is true`() {
        configureServer(ssl = true)
        sslOverrideFlow.value = true

        assertEquals("https://music.example:4433/image/imageData/", serverInfo().imageBaseUrl)
    }

    @Test
    fun `GetServerInfo puts the configured path in front of the image route`() {
        configureServer(ssl = true, path = "/synara/")

        assertEquals("https://music.example:4433/synara/image/imageData/", serverInfo().imageBaseUrl)
    }

    @Test
    fun `GetServerInfo encodes host port and imageBaseUrl`() {
        configureServer(ssl = true)

        assertEquals(
            """{"host":"music.example","port":4433,"imageBaseUrl":"https://music.example:4433/image/imageData/"}""",
            api.GetServerInfo()
        )
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
    fun `Search does not restrict the playlist search to the logged in user`() {
        val user = User(randomPlatformUUID(), "listener")
        every { globalState.user } returns MutableStateFlow(user)
        stubSearch()

        api.Search("query", emptyMap())

        coVerify(exactly = 1) { userPlaylistService.rankedSearch(null, 0, 10, "query") }
        coVerify(exactly = 1) { userPlaylistService.rankedSearch(any(), any(), any(), any()) }
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

    @Test
    fun `Search reports owner and role for own shared and public playlists`() {
        val user = User(randomPlatformUUID(), "listener", displayName = "The Listener")
        val friend = UserProfile(randomPlatformUUID(), "friend", displayName = "Best Friend")
        val colleague = UserProfile(randomPlatformUUID(), "colleague")
        val stranger = UserProfile(randomPlatformUUID(), "stranger")
        val own = testPlaylist("own", creator = user.id)
        val editable = testPlaylist("editable", creator = friend.id, shares = listOf(PlaylistShare(user.id, PlaylistAccess.WRITE)))
        val readable = testPlaylist("readable", creator = colleague.id, shares = listOf(PlaylistShare(user.id, PlaylistAccess.READ)))
        val public = testPlaylist("public", creator = stranger.id, isPublic = true)
        logIn(user)
        coEvery { userService.allProfiles() } returns listOf(friend, colleague, stranger)
        stubSearch(playlists = listOf(own, editable, readable, public))

        val result = decodeSearch(api.Search("query", emptyMap()))

        assertEquals(listOf(own, editable, readable, public), result.playlists)
        assertEquals(
            listOf(
                SynaraPlaylistInfo(own.id.toString(), "The Listener", PlaylistRole.OWNER),
                SynaraPlaylistInfo(editable.id.toString(), "Best Friend", PlaylistRole.EDITOR),
                SynaraPlaylistInfo(readable.id.toString(), "colleague", PlaylistRole.VIEWER),
                SynaraPlaylistInfo(public.id.toString(), "stranger", PlaylistRole.VISITOR)
            ),
            result.playlistInfo
        )
        coVerify(exactly = 1) { userService.allProfiles() }
    }

    @Test
    fun `Search names the owner of an own playlist by username without a display name and loads no profiles`() {
        val user = User(randomPlatformUUID(), "listener")
        val own = testPlaylist("own", creator = user.id)
        logIn(user)
        stubSearch(playlists = listOf(own))

        val result = decodeSearch(api.Search("query", emptyMap()))

        assertEquals(listOf(SynaraPlaylistInfo(own.id.toString(), "listener", PlaylistRole.OWNER)), result.playlistInfo)
        coVerify(exactly = 0) { userService.allProfiles() }
    }

    @Test
    fun `Search still returns results when the profile lookup fails`() {
        val user = User(randomPlatformUUID(), "listener")
        val song = testSong(1)
        val own = testPlaylist("own", creator = user.id)
        val public = testPlaylist("public", isPublic = true)
        logIn(user)
        coEvery { userService.allProfiles() } throws IllegalStateException("profiles down")
        stubSearch(songs = listOf(song), playlists = listOf(own, public))

        val result = decodeSearch(api.Search("query", emptyMap()))

        assertEquals(listOf(song), result.songs)
        assertEquals(listOf(own, public), result.playlists)
        assertEquals(
            listOf(
                SynaraPlaylistInfo(own.id.toString(), "listener", PlaylistRole.OWNER),
                SynaraPlaylistInfo(public.id.toString(), "", PlaylistRole.VISITOR)
            ),
            result.playlistInfo
        )
    }

    @Test
    fun `Search without playlists reports no playlist info and loads no profiles`() {
        stubSearch(songs = listOf(testSong(1)))

        val result = decodeSearch(api.Search("query", emptyMap()))

        assertTrue(result.playlistInfo.isEmpty())
        coVerify(exactly = 0) { userService.allProfiles() }
    }

    @Test
    fun `Search encodes the role of a playlist by its name`() {
        val user = User(randomPlatformUUID(), "listener")
        val own = testPlaylist("own", creator = user.id)
        logIn(user)
        stubSearch(playlists = listOf(own))

        val json = api.Search("query", emptyMap())

        assertTrue(json.contains(""""playlistInfo":[{"playlistId":"${own.id}","ownerName":"listener","role":"OWNER"}]"""))
    }

    @Test
    fun `GetPlaylists returns the own and shared playlists with owner and role`() {
        val user = User(randomPlatformUUID(), "listener", displayName = "The Listener")
        val friend = UserProfile(randomPlatformUUID(), "friend", displayName = "Best Friend")
        val colleague = UserProfile(randomPlatformUUID(), "colleague")
        val own = testPlaylist("own", creator = user.id)
        val editable = testPlaylist("editable", creator = friend.id, shares = listOf(PlaylistShare(user.id, PlaylistAccess.WRITE)))
        val readable = testPlaylist("readable", creator = colleague.id, shares = listOf(PlaylistShare(user.id, PlaylistAccess.READ)))
        logIn(user)
        coEvery { userService.allProfiles() } returns listOf(friend, colleague)
        userPlaylistsFlow.value = listOf(own, editable, readable)

        val result = decodePlaylists(api.GetPlaylists())

        assertEquals(
            SynaraPlaylists(
                playlists = listOf(own, editable, readable),
                playlistInfo = listOf(
                    SynaraPlaylistInfo(own.id.toString(), "The Listener", PlaylistRole.OWNER),
                    SynaraPlaylistInfo(editable.id.toString(), "Best Friend", PlaylistRole.EDITOR),
                    SynaraPlaylistInfo(readable.id.toString(), "colleague", PlaylistRole.VIEWER)
                )
            ),
            result
        )
        coVerify(exactly = 1) { userService.allProfiles() }
    }

    @Test
    fun `GetPlaylists loads no profiles when every playlist belongs to the user`() {
        val user = User(randomPlatformUUID(), "listener")
        val playlists = listOf(testPlaylist("one", creator = user.id), testPlaylist("two", creator = user.id))
        logIn(user)
        userPlaylistsFlow.value = playlists

        val result = decodePlaylists(api.GetPlaylists())

        assertEquals(playlists, result.playlists)
        assertEquals(
            playlists.map { SynaraPlaylistInfo(it.id.toString(), "listener", PlaylistRole.OWNER) },
            result.playlistInfo
        )
        coVerify(exactly = 0) { userService.allProfiles() }
    }

    @Test
    fun `GetPlaylists still returns the playlists when the profile lookup fails`() {
        val user = User(randomPlatformUUID(), "listener")
        val shared = testPlaylist("shared", shares = listOf(PlaylistShare(user.id, PlaylistAccess.WRITE)))
        logIn(user)
        coEvery { userService.allProfiles() } throws IllegalStateException("profiles down")
        userPlaylistsFlow.value = listOf(shared)

        val result = decodePlaylists(api.GetPlaylists())

        assertEquals(listOf(shared), result.playlists)
        assertEquals(listOf(SynaraPlaylistInfo(shared.id.toString(), "", PlaylistRole.EDITOR)), result.playlistInfo)
    }

    @Test
    fun `GetPlaylists without playlists returns two empty lists`() {
        assertEquals("""{"playlists":[],"playlistInfo":[]}""", api.GetPlaylists())
    }

    @Test
    fun `SetLikeLevel sets every like level and returns the updated song`() {
        LikeLevel.entries.forEach { level ->
            val song = testSong(1)
            val updated = song.copy(likeLevel = level)
            coEvery { playerModel.setLikeLevel(song.id, level) } returns updated

            val result = api.SetLikeLevel(song.id.toString(), level.name)

            assertEquals(updated, AppJson.decodeFromString(UserSong.serializer(), result))
            assertTrue(result.contains(""""likeLevel":"${level.name}""""))
            coVerify(exactly = 1) { playerModel.setLikeLevel(song.id, level) }
        }
    }

    @Test
    fun `SetLikeLevel with an unknown level is empty and sets nothing`() {
        val id = randomPlatformUUID().toString()

        assertEquals("", api.SetLikeLevel(id, "LOVE"))
        assertEquals("", api.SetLikeLevel(id, "like"))
        assertEquals("", api.SetLikeLevel(id, ""))

        coVerify(exactly = 0) { playerModel.setLikeLevel(any(), any()) }
    }

    @Test
    fun `SetLikeLevel with an invalid id is empty and sets nothing`() {
        assertEquals("", api.SetLikeLevel("not-a-uuid", LikeLevel.LIKE.name))

        coVerify(exactly = 0) { playerModel.setLikeLevel(any(), any()) }
    }

    @Test
    fun `SetLikeLevel is empty for a song the server does not know`() {
        val id = randomPlatformUUID()
        coEvery { playerModel.setLikeLevel(id, LikeLevel.LIKE) } returns null

        assertEquals("", api.SetLikeLevel(id.toString(), LikeLevel.LIKE.name))
    }

    @Test
    fun `SetLikeLevel is empty when the server call fails`() {
        val id = randomPlatformUUID()
        coEvery { playerModel.setLikeLevel(id, LikeLevel.SUPER) } throws IllegalStateException("server down")

        assertEquals("", api.SetLikeLevel(id.toString(), LikeLevel.SUPER.name))
    }

    @Test
    fun `AddToPlaylist adds albums artists and playlists by source and ignores the case of the type`() {
        val target = randomPlatformUUID()
        val albumId = randomPlatformUUID()
        val artistId = randomPlatformUUID()
        val playlistId = randomPlatformUUID()

        api.AddToPlaylist(target.toString(), albumId.toString(), "ALBUM")
        api.AddToPlaylist(target.toString(), artistId.toString(), "Artist")
        api.AddToPlaylist(target.toString(), playlistId.toString(), "playlist")

        verify(exactly = 1) { playerModel.addSongsToPlaylist(target, PlaybackQueue(source = PlaybackSource.Album(albumId))) }
        verify(exactly = 1) { playerModel.addSongsToPlaylist(target, PlaybackQueue(source = PlaybackSource.Artist(artistId))) }
        verify(exactly = 1) { playerModel.addSongsToPlaylist(target, PlaybackQueue(source = PlaybackSource.Playlist(playlistId))) }
        verify(exactly = 3) { playerModel.addSongsToPlaylist(any(), any()) }
    }

    @Test
    fun `AddToPlaylist with a song id loads the song and adds it`() {
        val target = randomPlatformUUID()
        val song = testSong(1)
        coEvery { songService.byId(song.id) } returns song

        api.AddToPlaylist(target.toString(), song.id.toString(), "SONG")

        verify(exactly = 1) {
            playerModel.addSongsToPlaylist(
                target,
                match { queue ->
                    queue.source == PlaybackSource.Manual &&
                        queue.items.map { (it as QueueEntry.Explicit).song } == listOf(song)
                }
            )
        }
        verify(exactly = 1) { playerModel.addSongsToPlaylist(any(), any()) }
    }

    @Test
    fun `AddToPlaylist with a song id the server does not know does nothing`() {
        val id = randomPlatformUUID()
        coEvery { songService.byId(id) } returns null

        api.AddToPlaylist(randomPlatformUUID().toString(), id.toString(), "song")

        verify(exactly = 0) { playerModel.addSongsToPlaylist(any(), any()) }
    }

    @Test
    fun `AddToPlaylist with an invalid playlist id or item id does nothing`() {
        api.AddToPlaylist("not-a-uuid", randomPlatformUUID().toString(), "album")
        api.AddToPlaylist(randomPlatformUUID().toString(), "not-a-uuid", "album")
        api.AddToPlaylist("not-a-uuid", randomPlatformUUID().toString(), "song")

        verify(exactly = 0) { playerModel.addSongsToPlaylist(any(), any()) }
        coVerify(exactly = 0) { songService.byId(any()) }
    }

    @Test
    fun `AddToPlaylist with an unknown type does nothing`() {
        api.AddToPlaylist(randomPlatformUUID().toString(), randomPlatformUUID().toString(), "podcast")

        verify(exactly = 0) { playerModel.addSongsToPlaylist(any(), any()) }
    }
}
