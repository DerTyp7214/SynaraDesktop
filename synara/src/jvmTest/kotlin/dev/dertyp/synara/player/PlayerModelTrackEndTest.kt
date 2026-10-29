package dev.dertyp.synara.player

import dev.dertyp.data.RepeatMode
import dev.dertyp.logging.Logger
import dev.dertyp.serializers.AppCbor
import dev.dertyp.synara.player.PlayerModel.QueueChange
import dev.dertyp.synara.player.PlayerModel.TrackStart
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.*
import dev.dertyp.synara.settings.SettingsFactory
import dev.dertyp.synara.testing.*
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.utils.decompress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class, ExperimentalCoroutinesApi::class)
class PlayerModelTrackEndTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatchers = TestDispatchers(StandardTestDispatcher(scheduler))
    private val audioPlayer = FakeAudioPlayer()
    private val songService = mockk<SongServiceWrapper>(relaxed = true)
    private val stateDir: File = Files.createTempDirectory("player-model-track-end-test").toFile()
    private val recorder = CoroutineScope(Dispatchers.Unconfined)
    private val trackStarts = mutableListOf<TrackStart>()
    private val queueChanges = mutableListOf<QueueChange>()

    @BeforeTest
    fun setUp() {
        coEvery { songService.byId(any()) } returns null
    }

    @AfterTest
    fun tearDown() {
        recorder.cancel()
        stateDir.deleteRecursively()
    }

    private fun buildModel(): PlayerModel {
        val model = PlayerModel(
            rpcServiceManager = mockk<RpcServiceManager>(relaxed = true),
            audioPlayer = audioPlayer,
            songService = songService,
            userPlaylistService = mockk<UserPlaylistServiceWrapper>(relaxed = true),
            albumService = mockk<AlbumServiceWrapper>(relaxed = true),
            artistService = mockk<ArtistServiceWrapper>(relaxed = true),
            userService = mockk<UserServiceWrapper>(relaxed = true),
            radioService = mockk<RadioServiceWrapper>(relaxed = true),
            songCache = SongCache(),
            timecodeTagStore = mockk<TimecodeTagStore>(relaxed = true),
            settings = InMemorySettings(),
            snackbarManager = mockk<SnackbarManager>(relaxed = true),
            cbor = AppCbor,
            logger = mockk<Logger>(relaxed = true),
            dispatchers = dispatchers,
            settingsFactory = mockk<SettingsFactory> {
                every { getStatePath(any()) } answers { File(stateDir, firstArg<String>()).path }
            }
        )
        scheduler.runCurrent()
        return model
    }

    private fun createModel(): PlayerModel {
        val model = buildModel()
        recorder.launch { model.trackStarts.collect { trackStarts += it } }
        recorder.launch { model.queueChanges.collect { queueChanges += it } }
        return model
    }

    private fun readState(): PlayerState =
        AppCbor.decodeFromByteArray<PlayerState>(decompress(File(stateDir, "player_state.v2.cbor.zstd").readBytes()))

    private fun finishCurrentTrack() {
        audioPlayer.emitFinished()
        scheduler.runCurrent()
    }

    @Test
    fun trackEndWithRepeatOffStartsTheNextEntryFromItsBeginning() {
        val songs = testSongs(3)
        val model = createModel()
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        assertTrue(model.isPlaying.value)
        audioPlayer.position.value = 239_000
        scheduler.runCurrent()

        finishCurrentTrack()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1].id, model.currentSongId)
        assertEquals(songs[1], model.currentSong.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertTrue(model.playbackRequested)
        assertTrue(model.isPlaying.value)
        assertEquals(0L, model.currentPosition.value)
        assertEquals(
            listOf(TrackStart(songs[0].id, songs[0], 0L), TrackStart(songs[1].id, songs[1], 0L)),
            trackStarts
        )
        assertEquals(QueueChange.CurrentChanged(model.queue.value[1].queueId, 1), queueChanges.last())
    }

    @Test
    fun handOverShowsTheNextSongAtZeroWithItsOwnDuration() {
        val songs = listOf(testSong(0, duration = 200_000), testSong(1, duration = 180_000))
        val model = createModel()
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        audioPlayer.trackDuration.value = 200_000
        audioPlayer.position.value = 200_000
        scheduler.runCurrent()
        assertEquals(200_000L, model.currentPosition.value)
        assertEquals(200_000L, model.duration.value)
        audioPlayer.deferLoads = true

        finishCurrentTrack()

        assertEquals(songs[1], model.currentSong.value)
        assertEquals(0L, model.currentPosition.value)
        assertEquals(180_000L, model.duration.value)

        audioPlayer.trackDuration.value = 180_000
        audioPlayer.finishLoad()
        scheduler.runCurrent()

        assertEquals(0L, model.currentPosition.value)
        assertEquals(180_000L, model.duration.value)
        assertTrue(model.isPlaying.value)
    }

    @Test
    fun trackEndPersistsTheNextEntryAtPositionZero() {
        val songs = testSongs(3)
        val model = createModel()
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        audioPlayer.position.value = 65_000
        model.pause()
        scheduler.runCurrent()
        assertEquals(0, readState().currentIndex)
        assertEquals(65_000L, readState().lastPosition)
        model.play()
        audioPlayer.position.value = 239_000
        scheduler.runCurrent()

        finishCurrentTrack()

        val saved = readState()
        assertEquals(1, saved.currentIndex)
        assertEquals(0L, saved.lastPosition)
        assertEquals(model.queue.value.map { it.queueId }, saved.queue.map { it.queueId })

        val restored = buildModel()

        assertEquals(1, restored.currentIndex.value)
        assertEquals(songs[1].id, restored.currentSong.value?.id)
        assertEquals(0L, restored.currentPosition.value)
    }

    @Test
    fun trackEndWithRepeatOneRestartsTheSameEntryWithoutACurrentChange() {
        val songs = testSongs(3)
        val model = createModel()
        model.setRepeatMode(RepeatMode.ONE)
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        audioPlayer.position.value = 239_000
        scheduler.runCurrent()
        queueChanges.clear()

        finishCurrentTrack()

        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[0], model.currentSong.value)
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(0L, model.currentPosition.value)
        assertTrue(model.playbackRequested)
        assertTrue(model.isPlaying.value)
        assertEquals(emptyList(), queueChanges.filterIsInstance<QueueChange.CurrentChanged>())
        assertEquals(
            listOf(TrackStart(songs[0].id, songs[0], 0L), TrackStart(songs[0].id, songs[0], 0L)),
            trackStarts
        )
    }

    @Test
    fun trackEndOfTheLastEntryWithRepeatAllWrapsToTheFirstEntry() {
        val songs = testSongs(3)
        val model = createModel()
        model.setRepeatMode(RepeatMode.ALL)
        model.playQueue(queueOf(songs), startIndex = 2)
        scheduler.runCurrent()
        assertEquals(2, model.currentIndex.value)
        audioPlayer.position.value = 239_000
        scheduler.runCurrent()

        finishCurrentTrack()

        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[0], model.currentSong.value)
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(0L, model.currentPosition.value)
        assertTrue(model.playbackRequested)
        assertTrue(model.isPlaying.value)
        assertEquals(TrackStart(songs[0].id, songs[0], 0L), trackStarts.last())
        assertEquals(QueueChange.CurrentChanged(model.queue.value[0].queueId, 0), queueChanges.last())
    }

    @Test
    fun trackEndOfTheLastEntryWithRepeatOffEndsTheQueueAndPlayRestartsIt() {
        val songs = testSongs(2)
        val model = createModel()
        model.playQueue(queueOf(songs), startIndex = 1)
        scheduler.runCurrent()
        assertEquals(1, model.currentIndex.value)
        audioPlayer.position.value = 239_000
        scheduler.runCurrent()

        finishCurrentTrack()

        assertEquals(-1, model.currentIndex.value)
        assertNull(model.currentSong.value)
        assertNull(model.loadedSongId.value)
        assertFalse(model.playbackRequested)
        assertFalse(model.isPlaying.value)
        assertEquals(0L, model.currentPosition.value)
        assertEquals(songs.map { it.id }, model.queue.value.map { (it as QueueEntry.Explicit).song.id })
        assertEquals(-1, readState().currentIndex)

        model.play()
        scheduler.runCurrent()

        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[0], model.currentSong.value)
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(0L, model.currentPosition.value)
        assertTrue(model.isPlaying.value)
        assertEquals(TrackStart(songs[0].id, songs[0], 0L), trackStarts.last())
    }

    @Test
    fun finishedEventOfTheSkippedTrackArrivingBeforeTheNextLoadDoesNotAdvanceTwice() {
        val songs = testSongs(4)
        val model = createModel()
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()

        model.skipNext()
        finishCurrentTrack()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1], model.currentSong.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertTrue(model.isPlaying.value)
        assertEquals(
            listOf(TrackStart(songs[0].id, songs[0], 0L), TrackStart(songs[1].id, songs[1], 0L)),
            trackStarts
        )
    }

    @Test
    fun finishedEventOfTheSkippedTrackArrivingWhileTheNextTrackLoadsDoesNotAdvanceTwice() {
        val songs = testSongs(4)
        val model = createModel()
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        audioPlayer.deferLoads = true

        model.playAtIndex(2)
        scheduler.runCurrent()
        finishCurrentTrack()
        audioPlayer.finishLoad()
        scheduler.runCurrent()

        assertEquals(2, model.currentIndex.value)
        assertEquals(songs[2], model.currentSong.value)
        assertEquals(songs[2].id, model.loadedSongId.value)
        assertTrue(model.isPlaying.value)
        assertEquals(
            listOf(TrackStart(songs[0].id, songs[0], 0L), TrackStart(songs[2].id, songs[2], 0L)),
            trackStarts
        )
    }

    @Test
    fun togglePlayPausePausesAPlayingTrackAndResumesAPausedOne() {
        val songs = testSongs(2)
        val model = createModel()
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        assertTrue(model.isPlaying.value)
        audioPlayer.position.value = 30_000

        model.togglePlayPause()
        scheduler.runCurrent()

        assertFalse(model.isPlaying.value)
        assertFalse(model.playbackRequested)
        assertEquals(30_000L, readState().lastPosition)

        model.togglePlayPause()
        scheduler.runCurrent()

        assertTrue(model.isPlaying.value)
        assertTrue(model.playbackRequested)
        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(30_000L, model.currentPosition.value)
        assertEquals(listOf(TrackStart(songs[0].id, songs[0], 0L)), trackStarts)
    }

    @Test
    fun togglePlayPauseWhileTheTrackIsStillLoadingLeavesItPausedOnceLoaded() {
        val songs = testSongs(2)
        val model = createModel()
        audioPlayer.deferLoads = true
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        assertTrue(model.playbackRequested)
        assertFalse(model.isPlaying.value)

        model.togglePlayPause()
        scheduler.runCurrent()
        audioPlayer.finishLoad()
        scheduler.runCurrent()

        assertFalse(model.isPlaying.value)
        assertFalse(model.playbackRequested)
        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[0], model.currentSong.value)
    }

    @Test
    fun finishedEventEmittedBeforeTheSkipIsHandledDoesNotAdvanceTwice() {
        val songs = testSongs(4)
        val model = createModel()
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()

        audioPlayer.emitFinished()
        model.skipNext()
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1], model.currentSong.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertTrue(model.isPlaying.value)
        assertEquals(
            listOf(TrackStart(songs[0].id, songs[0], 0L), TrackStart(songs[1].id, songs[1], 0L)),
            trackStarts
        )
    }

    @Test
    fun finishTrackRequestedBeforeASkipIsHandledDoesNotAdvanceTwice() {
        val songs = testSongs(4)
        val model = createModel()
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()

        model.finishTrack(trackStarts.single())
        model.skipNext()
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1], model.currentSong.value)
        assertEquals(
            listOf(TrackStart(songs[0].id, songs[0], 0L), TrackStart(songs[1].id, songs[1], 0L)),
            trackStarts
        )
    }

    @Test
    fun finishTrackWhileTheTrackIsStillLoadingAdvances() {
        val songs = testSongs(3)
        val model = createModel()
        audioPlayer.deferLoads = true
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        assertNull(model.loadedSongId.value)

        model.finishTrack(trackStarts.single())
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1], model.currentSong.value)
        assertEquals(songs[1].id to true, audioPlayer.loads.value.last())
        assertTrue(model.playbackRequested)
    }

    @Test
    fun anEndClearlyBeforeTheKnownDurationIsAFailureNotAFinish() {
        val songs = testSongs(2)
        val model = createModel()
        model.setRepeatMode(RepeatMode.ONE)
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        audioPlayer.trackDuration.value = 240_000
        audioPlayer.position.value = 120_000
        scheduler.runCurrent()

        finishCurrentTrack()

        assertEquals(0, model.currentIndex.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(1, audioPlayer.stops.value)
        assertFalse(model.playbackRequested)
        assertFalse(model.isPlaying.value)
        assertNull(model.loadedSongId.value)
        assertEquals(120_000L, model.currentPosition.value)
        assertEquals(1, trackStarts.size)
    }

    @Test
    fun anEndWithinTheToleranceOfTheKnownDurationIsAFinish() {
        val songs = testSongs(2)
        val model = createModel()
        model.setRepeatMode(RepeatMode.ONE)
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        audioPlayer.trackDuration.value = 240_000
        audioPlayer.position.value = 237_500
        scheduler.runCurrent()

        finishCurrentTrack()

        assertEquals(0, model.currentIndex.value)
        assertEquals(2, audioPlayer.loads.value.size)
        assertEquals(0, audioPlayer.stops.value)
        assertTrue(model.isPlaying.value)
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(2, trackStarts.size)
    }

    @Test
    fun anEndWithoutAKnownDurationIsAFinish() {
        val songs = testSongs(2)
        val model = createModel()
        model.setRepeatMode(RepeatMode.ONE)
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        audioPlayer.position.value = 120_000
        scheduler.runCurrent()

        finishCurrentTrack()

        assertEquals(2, audioPlayer.loads.value.size)
        assertEquals(0, audioPlayer.stops.value)
        assertTrue(model.isPlaying.value)
    }

    @Test
    fun finishTrackForAnEarlierStartOfTheSameSongIsIgnored() {
        val song = testSong(0)
        val songs = listOf(song, song, testSong(1))
        val model = createModel()
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        val firstStart = trackStarts.single()

        finishCurrentTrack()
        assertEquals(1, model.currentIndex.value)
        assertEquals(2, trackStarts.size)

        model.finishTrack(firstStart)
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertEquals(song.id, model.loadedSongId.value)
        assertEquals(2, audioPlayer.loads.value.size)
        assertEquals(2, trackStarts.size)
    }

    @Test
    fun finishTrackForTheCurrentStartOfARepeatedSongAdvances() {
        val song = testSong(0)
        val songs = listOf(song, song, testSong(1))
        val model = createModel()
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()

        finishCurrentTrack()
        model.finishTrack(trackStarts.last())
        scheduler.runCurrent()

        assertEquals(2, model.currentIndex.value)
        assertEquals(songs[2].id, model.loadedSongId.value)
        assertEquals(3, audioPlayer.loads.value.size)
    }
}
