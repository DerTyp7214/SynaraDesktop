package dev.dertyp.synara.player

import com.russhwolf.settings.Settings
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppCbor
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.*
import dev.dertyp.synara.settings.SettingsFactory
import dev.dertyp.synara.testing.FakeAudioPlayer
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.synara.utils.compress
import dev.dertyp.synara.utils.decompress
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.jetbrains.compose.resources.getString
import synara.synara.generated.resources.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalSerializationApi::class, ExperimentalCoroutinesApi::class)
class PlayerModelPlaybackTest {

    private object UnconfinedDispatchers : SynaraDispatchers {
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val audioPlayer = FakeAudioPlayer()
    private val songService = mockk<SongServiceWrapper>(relaxed = true)
    private val snackbarManager = mockk<SnackbarManager>(relaxed = true)
    private val logger = mockk<Logger>(relaxed = true)
    private val stateDir: File = Files.createTempDirectory("player-model-test").toFile()

    @BeforeTest
    fun setUp() {
        coEvery { songService.byId(any()) } returns null
    }

    @AfterTest
    fun tearDown() {
        stateDir.deleteRecursively()
    }

    private fun createModel(dispatchers: SynaraDispatchers = UnconfinedDispatchers) = PlayerModel(
        rpcServiceManager = mockk<RpcServiceManager>(relaxed = true),
        audioPlayer = audioPlayer,
        songService = songService,
        userPlaylistService = mockk<UserPlaylistServiceWrapper>(relaxed = true),
        albumService = mockk<AlbumServiceWrapper>(relaxed = true),
        artistService = mockk<ArtistServiceWrapper>(relaxed = true),
        userService = mockk<UserServiceWrapper>(relaxed = true),
        radioService = mockk<RadioServiceWrapper>(relaxed = true),
        songCache = SongCache(),
        settings = mockk<Settings>(relaxed = true),
        snackbarManager = snackbarManager,
        cbor = AppCbor,
        logger = logger,
        dispatchers = dispatchers,
        settingsFactory = mockk<SettingsFactory> {
            every { getStatePath(any()) } answers { File(stateDir, firstArg<String>()).path }
        }
    )

    private fun song(index: Int) = UserSong(
        id = randomPlatformUUID(),
        title = "Song $index",
        artists = emptyList(),
        album = null,
        duration = 1000,
        explicit = false,
        path = "/song$index.flac"
    )

    private fun songs(count: Int) = List(count) { song(it) }

    private fun queueOf(songs: List<UserSong>) = PlaybackQueue(items = songs.map { QueueEntry.Explicit(it) })

    private fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = runBlocking {
        withTimeout(5.seconds) { first(predicate) }
    }

    private fun failLoad(expectedLoads: Int) {
        audioPlayer.fail()
        audioPlayer.loads.await { it.size == expectedLoads }
    }

    private val stateFile: File get() = File(stateDir, "player_state.v2.cbor.zstd")

    private fun writeState(state: PlayerState) {
        stateFile.writeBytes(compress(AppCbor.encodeToByteArray(state)))
    }

    private fun readState(): PlayerState = AppCbor.decodeFromByteArray<PlayerState>(decompress(stateFile.readBytes()))

    private fun restoredModel(songs: List<UserSong>, currentIndex: Int, lastPosition: Long): PlayerModel {
        val entries = songs.map { QueueEntry.Explicit(it) }
        writeState(PlayerState(queue = entries, originalQueue = entries, currentIndex = currentIndex, lastPosition = lastPosition))
        return createModel()
    }

    private fun skipped(song: UserSong) = runBlocking { getString(Res.string.playback_failed_skipped, song.title) }

    private fun stopped() = runBlocking { getString(Res.string.playback_failed_stopped) }

    @Test
    fun loadErrorSkipsToTheNextEntryOnce() {
        val songs = songs(3)
        val model = createModel()
        model.playQueue(queueOf(songs))
        assertEquals(listOf(songs[0].id to true), audioPlayer.loads.await { it.size == 1 })

        failLoad(expectedLoads = 2)

        val message = skipped(songs[0])
        verify(timeout = 5000) { snackbarManager.showSnackbar(message) }
        verify(timeout = 5000) { logger.error(any(), any(), any(), any()) }
        assertEquals(1, model.currentIndex.value)
        assertEquals(listOf(songs[0].id to true, songs[1].id to true), audioPlayer.loads.value)
        assertEquals(0, audioPlayer.stops.value)
    }

    @Test
    fun fiveLoadErrorsInARowStopPlayback() {
        val songs = songs(8)
        val model = createModel()
        model.playQueue(queueOf(songs))

        for (failure in 1..4) failLoad(expectedLoads = failure + 1)
        assertEquals(0, audioPlayer.stops.value)

        audioPlayer.fail()
        audioPlayer.stops.await { it == 1 }

        val message = stopped()
        verify(timeout = 5000) { snackbarManager.showSnackbar(message) }
        assertEquals(4, model.currentIndex.value)
        assertEquals(5, audioPlayer.loads.value.size)
    }

    @Test
    fun shortQueueStopsOnceEveryEntryFailed() {
        val songs = songs(2)
        val model = createModel()
        model.setRepeatMode(RepeatMode.ALL)
        model.playQueue(queueOf(songs))

        failLoad(expectedLoads = 2)
        audioPlayer.fail()
        audioPlayer.stops.await { it == 1 }

        val message = stopped()
        verify(timeout = 5000) { snackbarManager.showSnackbar(message) }
        assertEquals(1, model.currentIndex.value)
        assertEquals(2, audioPlayer.loads.value.size)
    }

    @Test
    fun repeatOneStopsAfterOneLoadError() {
        val songs = songs(3)
        val model = createModel()
        model.setRepeatMode(RepeatMode.ONE)
        model.playQueue(queueOf(songs))

        audioPlayer.fail(AudioLoadError.Failed("broken"))
        audioPlayer.stops.await { it == 1 }

        val message = stopped()
        verify(timeout = 5000) { snackbarManager.showSnackbar(message) }
        assertEquals(0, model.currentIndex.value)
        assertEquals(1, audioPlayer.loads.value.size)
    }

    @Test
    fun userSkipResetsTheAutomaticSkipCount() {
        val songs = songs(12)
        val model = createModel()
        model.playQueue(queueOf(songs))

        for (failure in 1..4) failLoad(expectedLoads = failure + 1)
        model.skipNext()
        audioPlayer.loads.await { it.size == 6 }
        for (failure in 1..4) failLoad(expectedLoads = failure + 6)

        assertEquals(9, model.currentIndex.value)
        assertEquals(0, audioPlayer.stops.value)

        audioPlayer.fail()
        audioPlayer.stops.await { it == 1 }
        assertEquals(9, model.currentIndex.value)
    }

    @Test
    fun startedAudioResetsTheAutomaticSkipCount() {
        val songs = songs(12)
        val model = createModel()
        audioPlayer.deferLoads = true
        model.playQueue(queueOf(songs))

        for (failure in 1..4) failLoad(expectedLoads = failure + 1)
        audioPlayer.playing.value = true
        audioPlayer.playing.value = false
        for (failure in 1..4) failLoad(expectedLoads = failure + 5)

        assertEquals(8, model.currentIndex.value)
        assertEquals(0, audioPlayer.stops.value)
    }

    @Test
    fun loadErrorWhilePausedDoesNotSkip() {
        val songs = songs(3)
        val model = createModel()
        model.playQueue(queueOf(songs))
        model.pause()

        audioPlayer.fail()

        verify(timeout = 5000) { logger.error(any(), any(), any(), any()) }
        assertEquals(0, model.currentIndex.value)
        assertEquals(1, audioPlayer.loads.value.size)
        verify(exactly = 0) { snackbarManager.showSnackbar(any(), any(), any(), any()) }
    }

    @Test
    fun loadErrorInRemoteModeIsIgnored() {
        val songs = songs(4)
        val model = createModel()
        model.playQueue(queueOf(songs))

        model.remotePlayHandler = {}
        audioPlayer.fail()
        model.remotePlayHandler = null
        model.play()
        audioPlayer.loads.await { it.size == 2 }
        failLoad(expectedLoads = 3)

        val first = skipped(songs[0])
        val second = skipped(songs[1])
        verify(timeout = 5000) { snackbarManager.showSnackbar(first) }
        assertEquals(1, model.currentIndex.value)
        verify(exactly = 0) { snackbarManager.showSnackbar(second) }
    }

    @Test
    fun playQueueWithAFailingSourceLeavesQueueAndSourceAlone() {
        val songs = songs(2)
        val model = createModel()
        model.playQueue(queueOf(songs))
        val queueBefore = model.queue.value

        coEvery { songService.byAlbum(any(), any(), any()) } throws IOException("offline")
        every { songService.songIdsByAlbum(any()) } returns emptyFlow()
        model.playQueue(PlaybackQueue(source = PlaybackSource.Album(randomPlatformUUID())))

        val message = runBlocking { getString(Res.string.playback_source_unavailable) }
        verify(timeout = 5000) { snackbarManager.showSnackbar(message) }
        verify(timeout = 5000) { logger.error(any(), any(), ofType<IOException>(), any()) }
        assertEquals(queueBefore, model.queue.value)
        assertEquals(0, model.currentIndex.value)
        assertEquals(PlaybackSource.Manual, model.currentSource.value)
        assertEquals(songs[0].id, model.currentSong.value?.id)
        assertEquals(listOf(songs[0].id to true), audioPlayer.loads.value)
        verify(exactly = 0) { songService.songIdsByAlbum(any()) }
    }

    @Test
    fun pagedSourceRethrowsAFailedPageFetch() {
        coEvery { songService.byAlbum(any(), any(), any()) } throws IOException("offline")
        val source = AlbumQueueSource(songService, randomPlatformUUID())

        assertFailsWith<IOException> { runBlocking { source.getSongAt(0) } }
    }

    @Test
    fun failedIdFetchKeepsTheStartSongPlaying() {
        val start = song(0)
        val album = PlaybackSource.Album(randomPlatformUUID())
        coEvery { songService.byAlbum(any(), any(), any()) } returns PaginatedResponse(data = listOf(start), total = 1)
        every { songService.songIdsByAlbum(any()) } returns flow { throw IOException("offline") }
        val model = createModel()

        model.playQueue(PlaybackQueue(source = album))

        verify(timeout = 5000) { logger.error(any(), any(), ofType<IOException>(), any()) }
        model.changeSerial.await { it == 1L }
        assertEquals(listOf(start.id), model.queue.value.map { (it as QueueEntry.FromSource).songId })
        assertEquals(0, model.currentIndex.value)
        assertEquals(album, model.currentSource.value)
        assertEquals(start.id, model.currentSong.value?.id)
        assertEquals(listOf(start.id to true), audioPlayer.loads.value)
        verify(exactly = 0) { snackbarManager.showSnackbar(any(), any(), any(), any()) }
    }

    @Test
    fun aSourceWhoseIdsLackTheStartSongKeepsItAsTheCurrentEntry() {
        val start = song(0)
        val others = songs(2)
        val album = PlaybackSource.Album(randomPlatformUUID())
        coEvery { songService.byAlbum(any(), any(), any()) } returns PaginatedResponse(data = listOf(start), total = 1)
        every { songService.songIdsByAlbum(any()) } returns others.map { it.id }.asFlow()
        val model = createModel()

        model.playQueue(PlaybackQueue(source = album))
        model.changeSerial.await { it == 1L }

        assertEquals(listOf(start.id) + others.map { it.id }, model.queue.value.map { (it as QueueEntry.FromSource).songId })
        assertEquals(0, model.currentIndex.value)
        assertEquals(start.id, model.currentSong.value?.id)
        assertEquals(start.id, model.loadedSongId.value)
        assertEquals(listOf(start.id to true), audioPlayer.loads.value)
    }

    @Test
    fun playQueueWithAnEmptySourceLeavesNoCurrentIndex() {
        val model = createModel()
        coEvery { songService.byAlbum(any(), any(), any()) } returns PaginatedResponse(data = emptyList<UserSong>(), total = 0)
        every { songService.songIdsByAlbum(any()) } returns emptyFlow()

        model.playQueue(PlaybackQueue(source = PlaybackSource.Album(randomPlatformUUID())), startIndex = 3)
        model.changeSerial.await { it == 1L }

        assertEquals(emptyList(), model.queue.value)
        assertEquals(-1, model.currentIndex.value)
    }

    @Test
    fun shuffleOffKeepsTheCurrentSongWhileTheSourceIsStillLoading() {
        val current = song(0)
        val idsRequested = CompletableDeferred<Unit>()
        val releaseIds = CompletableDeferred<Unit>()
        coEvery { songService.byAlbum(any(), any(), any()) } returns PaginatedResponse(data = listOf(current), total = 1)
        every { songService.songIdsByAlbum(any()) } returns flow {
            idsRequested.complete(Unit)
            releaseIds.await()
            emit(current.id)
        }
        val model = createModel()
        model.setShuffleMode(true)

        model.playQueue(PlaybackQueue(source = PlaybackSource.Album(randomPlatformUUID())))
        runBlocking { withTimeout(5.seconds) { idsRequested.await() } }
        assertEquals(0, model.currentIndex.value)

        model.setShuffleMode(false)

        assertEquals(0, model.currentIndex.value)
        assertEquals(listOf(current.id), model.queue.value.map { (it as QueueEntry.FromSource).songId })
        assertEquals(current.id, model.currentSong.value?.id)

        releaseIds.complete(Unit)
        model.changeSerial.await { it == 3L }
        assertEquals(0, model.currentIndex.value)
        assertEquals(current.id, model.currentSong.value?.id)
    }

    @Test
    fun removingTheCurrentEntryWhilePausedStaysPaused() {
        val songs = songs(3)
        val model = createModel()
        model.playQueue(queueOf(songs))
        audioPlayer.loads.await { it.size == 1 }
        model.pause()

        model.removeFromQueue(model.queue.value[0])

        assertEquals(0, model.currentIndex.value)
        model.currentSong.await { it?.id == songs[1].id }
        model.duration.await { it == songs[1].duration }
        assertEquals(0L, model.currentPosition.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(1, audioPlayer.stops.value)
        assertEquals(0, audioPlayer.plays.value)

        model.play()

        assertEquals(songs[1].id to true, audioPlayer.loads.await { it.size == 2 }.last())
        assertEquals(listOf(0L, 0L), audioPlayer.starts.value)
        assertEquals(0, audioPlayer.plays.value)
    }

    @Test
    fun removingTheCurrentEntryWhilePlayingKeepsPlaying() {
        val songs = songs(3)
        val model = createModel()
        model.playQueue(queueOf(songs))
        audioPlayer.playing.value = true

        model.removeFromQueue(model.queue.value[0])

        assertEquals(songs[1].id to true, audioPlayer.loads.await { it.size == 2 }.last())
    }

    @Test
    fun syncedRemovalOfTheCurrentEntryWhilePausedStaysPaused() {
        val songs = songs(3)
        val model = createModel()
        model.playQueue(queueOf(songs))
        audioPlayer.loads.await { it.size == 1 }
        model.pause()

        model.removeByQueueIds(listOf(model.queue.value[0].queueId))

        assertEquals(0, model.currentIndex.value)
        model.currentSong.await { it?.id == songs[1].id }
        assertEquals(1, audioPlayer.loads.value.size)

        model.play()

        assertEquals(songs[1].id to true, audioPlayer.loads.await { it.size == 2 }.last())
        assertEquals(0, audioPlayer.plays.value)
    }

    @Test
    fun restoredTrackIsShownAtItsPositionAndLoadedOnPlay() {
        val songs = songs(3)
        val model = restoredModel(songs, currentIndex = 1, lastPosition = 42_000)

        assertEquals(1, model.currentIndex.value)
        model.currentSong.await { it?.id == songs[1].id }
        model.currentPosition.await { it == 42_000L }
        model.duration.await { it == songs[1].duration }
        assertEquals(emptyList(), audioPlayer.loads.value)
        assertEquals(false, model.isPlaying.value)

        model.play()

        assertEquals(listOf(songs[1].id to true), audioPlayer.loads.await { it.size == 1 })
        assertEquals(listOf(42_000L), audioPlayer.starts.value)
        assertEquals(0, audioPlayer.plays.value)
        assertEquals(42_000L, model.currentPosition.value)

        model.play()

        assertEquals(1, audioPlayer.plays.value)
        assertEquals(1, audioPlayer.loads.value.size)
    }

    @Test
    fun pauseSavesThePositionForTheNextStart() {
        val songs = songs(3)
        val model = createModel()
        model.playQueue(queueOf(songs))
        model.skipNext()
        audioPlayer.loads.await { it.size == 2 }
        audioPlayer.position.value = 65_000

        model.pause()

        assertEquals(1, readState().currentIndex)
        assertEquals(65_000L, readState().lastPosition)
        assertEquals(false, File(stateFile.path + ".tmp").exists())

        audioPlayer.position.value = 0
        val restored = createModel()

        assertEquals(1, restored.currentIndex.value)
        restored.currentPosition.await { it == 65_000L }
        assertEquals(2, audioPlayer.loads.value.size)
    }

    @Test
    fun seekWhileNothingIsLoadedMovesTheStart() {
        val songs = songs(2)
        val model = restoredModel(songs, currentIndex = 0, lastPosition = 42_000)

        model.seekTo(10_000)

        assertEquals(0, audioPlayer.seeks.value)
        model.currentPosition.await { it == 10_000L }
        assertEquals(10_000L, readState().lastPosition)

        model.play()

        assertEquals(listOf(songs[0].id to true), audioPlayer.loads.await { it.size == 1 })
        assertEquals(listOf(10_000L), audioPlayer.starts.value)

        model.seekTo(5_000)

        assertEquals(1, audioPlayer.seeks.value)
    }

    @Test
    fun playAfterTheSkipGuardStoppedPlaybackRetriesTheLoad() {
        val songs = songs(8)
        val model = createModel()
        model.playQueue(queueOf(songs))
        for (failure in 1..4) failLoad(expectedLoads = failure + 1)
        audioPlayer.fail()
        audioPlayer.stops.await { it == 1 }
        model.duration.await { it == songs[4].duration }
        assertEquals(0L, model.currentPosition.value)

        model.play()

        assertEquals(songs[4].id to true, audioPlayer.loads.await { it.size == 6 }.last())
        assertEquals(0, audioPlayer.plays.value)
        assertEquals(4, model.currentIndex.value)
    }

    @Test
    fun playAfterALoadErrorWhilePausedRetriesTheLoad() {
        val songs = songs(3)
        val model = createModel()
        model.playQueue(queueOf(songs))
        audioPlayer.loads.await { it.size == 1 }
        model.pause()
        audioPlayer.fail()
        verify(timeout = 5000) { logger.error(any(), any(), any(), any()) }

        model.play()

        assertEquals(listOf(songs[0].id to true, songs[0].id to true), audioPlayer.loads.await { it.size == 2 })
        assertEquals(0, audioPlayer.plays.value)
        assertEquals(0, model.currentIndex.value)
        verify(exactly = 0) { snackbarManager.showSnackbar(any(), any(), any(), any()) }
    }

    @Test
    fun removingTheCurrentEntryWhileItsLoadIsPendingPlaysTheNextEntry() {
        val songs = songs(3)
        val model = createModel()
        audioPlayer.deferLoads = true
        model.playQueue(queueOf(songs))
        audioPlayer.loads.await { it.size == 1 }
        assertEquals(false, audioPlayer.playing.value)

        model.removeFromQueue(model.queue.value[0])

        assertEquals(songs[1].id to true, audioPlayer.loads.await { it.size == 2 }.last())
        assertEquals(songs[1].id, audioPlayer.current)
        assertEquals(0, audioPlayer.stops.value)

        audioPlayer.finishLoad()

        assertEquals(true, model.isPlaying.value)
        assertEquals(songs[1].id, audioPlayer.current)
        model.currentSong.await { it?.id == songs[1].id }
        assertEquals(0, model.currentIndex.value)
    }

    @Test
    fun removingTheCurrentEntryPausedDuringItsLoadDiscardsTheLoad() {
        val songs = songs(3)
        val model = createModel()
        audioPlayer.deferLoads = true
        model.playQueue(queueOf(songs))
        audioPlayer.loads.await { it.size == 1 }
        model.pause()

        model.removeFromQueue(model.queue.value[0])

        assertEquals(1, audioPlayer.stops.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(null, audioPlayer.current)

        audioPlayer.finishLoad()

        assertEquals(false, model.isPlaying.value)
        model.currentSong.await { it?.id == songs[1].id }
        assertEquals(0L, model.currentPosition.value)
        assertEquals(1, audioPlayer.loads.value.size)
    }

    @Test
    fun firstPlayKeepsTheRememberedValuesUntilTheTrackIsLoaded() {
        val songs = songs(2)
        val model = restoredModel(songs, currentIndex = 0, lastPosition = 42_000)
        audioPlayer.deferLoads = true
        model.currentPosition.await { it == 42_000L }

        model.play()

        audioPlayer.loads.await { it.size == 1 }
        assertEquals(42_000L, model.currentPosition.value)
        assertEquals(songs[0].duration, model.duration.value)

        model.play()

        assertEquals(0, audioPlayer.plays.value)
        assertEquals(1, audioPlayer.loads.value.size)

        audioPlayer.trackDuration.value = 180_000
        audioPlayer.finishLoad()

        model.duration.await { it == 180_000L }
        assertEquals(42_000L, model.currentPosition.value)
        assertEquals(true, model.isPlaying.value)
    }

    @Test
    fun stopDuringAPendingLoadKeepsTheTrackPending() {
        val songs = songs(2)
        val model = createModel()
        audioPlayer.deferLoads = true
        model.playQueue(queueOf(songs))
        audioPlayer.loads.await { it.size == 1 }

        model.stop()

        assertEquals(1, audioPlayer.stops.value)
        assertEquals(false, audioPlayer.loading.value)
        assertEquals(0L, model.currentPosition.value)
        model.duration.await { it == songs[0].duration }

        model.play()

        assertEquals(listOf(songs[0].id to true, songs[0].id to true), audioPlayer.loads.await { it.size == 2 })
        assertEquals(listOf(0L, 0L), audioPlayer.starts.value)
        assertEquals(0, audioPlayer.plays.value)
    }

    @Test
    fun playAfterTheQueueEndedRestartsTheQueue() {
        val songs = songs(2)
        val model = createModel()
        val starts = mutableListOf<PlayerModel.TrackStart>()
        val recorder = CoroutineScope(Dispatchers.Unconfined).launch { model.trackStarts.collect { starts += it } }
        model.playQueue(queueOf(songs))
        audioPlayer.loads.await { it.size == 1 }
        model.finishTrack(starts.last())
        audioPlayer.loads.await { it.size == 2 }
        model.finishTrack(starts.last())
        recorder.cancel()
        assertEquals(-1, model.currentIndex.value)
        assertEquals(1, audioPlayer.stops.value)

        model.play()

        assertEquals(songs[0].id to true, audioPlayer.loads.await { it.size == 3 }.last())
        assertEquals(0, model.currentIndex.value)
        assertEquals(0, audioPlayer.plays.value)

        model.clearQueue()
        model.play()

        assertEquals(3, audioPlayer.loads.value.size)
        assertEquals(0, audioPlayer.plays.value)
    }

    @Test
    fun seekWhilePausedAndLoadedIsSaved() {
        val songs = songs(2)
        val model = createModel()
        model.playQueue(queueOf(songs))
        audioPlayer.loads.await { it.size == 1 }
        audioPlayer.position.value = 60_000
        model.pause()
        assertEquals(60_000L, readState().lastPosition)

        model.seekTo(180_000)

        assertEquals(1, audioPlayer.seeks.value)
        assertEquals(180_000L, readState().lastPosition)

        audioPlayer.playing.value = true
        model.seekTo(200_000)

        assertEquals(2, audioPlayer.seeks.value)
        assertEquals(180_000L, readState().lastPosition)
    }

    private fun queuedDispatchers(scheduler: TestCoroutineScheduler) = object : SynaraDispatchers {
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = StandardTestDispatcher(scheduler)
    }

    @Test
    fun staleLoadErrorOfThePreviousSongIsIgnored() {
        val scheduler = TestCoroutineScheduler()
        val songs = songs(3)
        val model = createModel(queuedDispatchers(scheduler))
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        assertEquals(listOf(songs[0].id to true), audioPlayer.loads.value)

        model.skipNext()
        audioPlayer.fail()
        scheduler.runCurrent()

        assertEquals(listOf(songs[0].id to true, songs[1].id to true), audioPlayer.loads.value)
        assertEquals(1, model.currentIndex.value)
        assertEquals(0, audioPlayer.stops.value)
        verify(exactly = 0) { snackbarManager.showSnackbar(any(), any(), any(), any()) }
    }

    @Test
    fun pauseBeforeTheLoadIsIssuedStartsNoAudio() {
        val scheduler = TestCoroutineScheduler()
        val songs = songs(2)
        val model = createModel(queuedDispatchers(scheduler))
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()

        model.skipNext()
        model.pause()
        scheduler.runCurrent()

        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(1, model.currentIndex.value)
        assertEquals(0L, model.currentPosition.value)

        model.play()
        scheduler.runCurrent()

        assertEquals(songs[1].id to true, audioPlayer.loads.value.last())
        assertEquals(2, audioPlayer.loads.value.size)
    }

    @Test
    fun stopBeforeTheLoadIsIssuedStartsNoAudio() {
        val scheduler = TestCoroutineScheduler()
        val songs = songs(2)
        val model = createModel(queuedDispatchers(scheduler))
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()

        model.skipNext()
        model.stop()
        scheduler.runCurrent()

        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(1, audioPlayer.stops.value)
        assertEquals(1, model.currentIndex.value)
    }

    @Test
    fun loadedSongIdFollowsCompletedLoadsOnly() {
        val songs = songs(3)
        val model = createModel()
        audioPlayer.deferLoads = true
        model.playQueue(queueOf(songs))
        audioPlayer.loads.await { it.size == 1 }
        assertEquals(null, model.loadedSongId.value)

        audioPlayer.finishLoad()
        assertEquals(songs[0].id, model.loadedSongId.value)

        model.skipNext()
        audioPlayer.loads.await { it.size == 2 }
        assertEquals(null, model.loadedSongId.value)

        audioPlayer.fail()
        audioPlayer.loads.await { it.size == 3 }
        assertEquals(null, model.loadedSongId.value)

        audioPlayer.finishLoad()
        assertEquals(songs[2].id, model.loadedSongId.value)
    }

    @Test
    fun loadErrorAfterASeekWhilePausedKeepsTheSeekTarget() {
        val songs = songs(2)
        val model = createModel()
        model.playQueue(queueOf(songs))
        audioPlayer.loads.await { it.size == 1 }
        audioPlayer.position.value = 60_000
        model.pause()
        model.seekTo(120_000)

        audioPlayer.fail()

        verify(timeout = 5000) { logger.error(any(), any(), any(), any()) }
        model.currentPosition.await { it == 120_000L }
        assertEquals(120_000L, readState().lastPosition)

        model.play()

        assertEquals(listOf(0L, 120_000L), audioPlayer.starts.value)
    }

    @Test
    fun seekWhileALoadIsPendingIsAppliedAfterTheLoad() {
        val songs = songs(2)
        val model = restoredModel(songs, currentIndex = 0, lastPosition = 42_000)
        audioPlayer.deferLoads = true
        model.play()
        audioPlayer.loads.await { it.size == 1 }

        model.seekTo(10_000)

        assertEquals(0, audioPlayer.seeks.value)
        model.currentPosition.await { it == 10_000L }

        audioPlayer.finishLoad()

        assertEquals(1, audioPlayer.seeks.value)
        assertEquals(10_000L, model.currentPosition.value)
        assertEquals(listOf(42_000L), audioPlayer.starts.value)
    }

    @Test
    fun playAfterLeavingRemoteModeLoadsTheEntryThatBecameCurrentWhileRemote() {
        val songs = songs(4)
        val model = createModel()
        model.playQueue(queueOf(songs))
        audioPlayer.loads.await { it.size == 1 }
        model.pause()
        val notified = mutableListOf<Long>()
        model.remotePlayHandler = { notified += it }

        model.playAtIndex(2)

        assertEquals(listOf(model.queue.value[2].queueId), notified)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(null, audioPlayer.current)
        assertEquals(null, model.loadedSongId.value)
        assertEquals(false, model.playbackRequested)

        model.remotePlayHandler = null
        model.play()

        assertEquals(songs[2].id to true, audioPlayer.loads.await { it.size == 2 }.last())
        assertEquals(songs[2].id, model.loadedSongId.value)
        assertEquals(2, model.currentIndex.value)
        assertEquals(0, audioPlayer.plays.value)
    }

    @Test
    fun playInRemoteModeStartsNoLocalAudio() {
        val songs = songs(2)
        val model = createModel()
        model.playQueue(queueOf(songs))
        audioPlayer.loads.await { it.size == 1 }
        model.pause()
        model.remotePlayHandler = {}

        model.play()
        model.togglePlayPause()

        assertEquals(0, audioPlayer.plays.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(false, audioPlayer.playing.value)
        assertEquals(false, model.playbackRequested)
    }

    @Test
    fun playAfterLeavingRemoteModeReloadsTheUnchangedEntryFromTheStart() {
        val songs = songs(2)
        val model = createModel()
        model.playQueue(queueOf(songs))
        audioPlayer.loads.await { it.size == 1 }
        audioPlayer.position.value = 120_000
        model.pause()

        model.remotePlayHandler = {}

        assertEquals(1, audioPlayer.stops.value)
        assertEquals(null, audioPlayer.current)
        assertEquals(null, model.loadedSongId.value)
        assertEquals(0L, model.currentPosition.value)

        model.remotePlayHandler = null
        model.play()

        assertEquals(listOf(songs[0].id to true, songs[0].id to true), audioPlayer.loads.await { it.size == 2 })
        assertEquals(listOf(0L, 0L), audioPlayer.starts.value)
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(0, audioPlayer.plays.value)
    }
}
