package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.serializers.AppCbor
import dev.dertyp.synara.player.PlayerModel.QueueChange
import dev.dertyp.synara.player.PlayerModel.QueueSnapshot
import dev.dertyp.synara.player.PlayerModel.TrackStart
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.*
import dev.dertyp.synara.settings.SettingsFactory
import dev.dertyp.synara.testing.*
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.utils.compress
import dev.dertyp.synara.utils.decompress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.jetbrains.compose.resources.getString
import synara.synara.generated.resources.*
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
class PlayerModelGaplessTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatchers = TestDispatchers(StandardTestDispatcher(scheduler))
    private val audioPlayer = FakeAudioPlayer()
    private val settings = InMemorySettings()
    private val songCache = spyk(SongCache())
    private val songService = mockk<SongServiceWrapper>(relaxed = true)
    private val snackbarManager = mockk<SnackbarManager>(relaxed = true)
    private val stateDir: File = Files.createTempDirectory("player-model-gapless-test").toFile()
    private val recorder = CoroutineScope(Dispatchers.Unconfined)
    private val events = mutableListOf<Event>()
    private val trackStarts: List<TrackStart> get() = events.filterIsInstance<Event.Start>().map { it.start }
    private val queueChanges: List<QueueChange> get() = events.filterIsInstance<Event.Change>().map { it.change }

    private sealed interface Event {
        data class Start(val start: TrackStart, val loadedSongId: PlatformUUID?, val currentIndex: Int) : Event
        data class Change(val change: QueueChange) : Event
        data class Song(val song: UserSong?) : Event
    }

    @BeforeTest
    fun setUp() {
        coEvery { songService.byId(any()) } returns null
    }

    @AfterTest
    fun tearDown() {
        recorder.cancel()
        stateDir.deleteRecursively()
    }

    private fun createModel(): PlayerModel {
        val model = PlayerModel(
            rpcServiceManager = mockk<RpcServiceManager>(relaxed = true),
            audioPlayer = audioPlayer,
            songService = songService,
            userPlaylistService = mockk<UserPlaylistServiceWrapper>(relaxed = true),
            albumService = mockk<AlbumServiceWrapper>(relaxed = true),
            artistService = mockk<ArtistServiceWrapper>(relaxed = true),
            userService = mockk<UserServiceWrapper>(relaxed = true),
            radioService = mockk<RadioServiceWrapper>(relaxed = true),
            songCache = songCache,
            timecodeTagStore = mockk<TimecodeTagStore>(relaxed = true),
            settings = settings,
            snackbarManager = snackbarManager,
            cbor = AppCbor,
            logger = mockk<Logger>(relaxed = true),
            dispatchers = dispatchers,
            settingsFactory = mockk<SettingsFactory> {
                every { getStatePath(any()) } answers { File(stateDir, firstArg<String>()).path }
            }
        )
        scheduler.runCurrent()
        recorder.launch { model.trackStarts.collect { events += Event.Start(it, model.loadedSongId.value, model.currentIndex.value) } }
        recorder.launch { model.queueChanges.collect { events += Event.Change(it) } }
        recorder.launch { model.currentSong.collect { events += Event.Song(it) } }
        return model
    }

    private fun playingModel(songs: List<UserSong>, repeat: RepeatMode = RepeatMode.OFF, startIndex: Int = 0): PlayerModel {
        val model = createModel()
        model.setRepeatMode(repeat)
        model.playQueue(queueOf(songs), startIndex)
        scheduler.runCurrent()
        return model
    }

    private fun playingModelFromSource(songs: List<UserSong>): PlayerModel {
        val model = playingModel(songs)
        val entries = songs.map { QueueEntry.FromSource(it.id) }
        model.applyRemoteQueue(
            QueueSnapshot(original = entries, active = entries, currentIndex = 0, shuffle = false, repeat = RepeatMode.OFF, sourceId = null)
        )
        scheduler.runCurrent()
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(1, audioPlayer.loads.value.size)
        return model
    }

    private fun holdLookupOf(song: UserSong): CompletableDeferred<Unit> {
        val lookup = CompletableDeferred<Unit>()
        coEvery { songCache.get(song.id) } coAnswers {
            lookup.await()
            callOriginal()
        }
        return lookup
    }

    private val stateFile: File get() = File(stateDir, "player_state.v2.cbor.zstd")

    private fun readState(): PlayerState = AppCbor.decodeFromByteArray<PlayerState>(decompress(stateFile.readBytes()))

    private fun songIdAt(model: PlayerModel, index: Int): PlatformUUID = (model.queue.value[index] as QueueEntry.Explicit).song.id

    private fun transition() {
        audioPlayer.transition()
        scheduler.runCurrent()
    }

    private fun skipped(song: UserSong) = runBlocking { getString(Res.string.playback_failed_skipped, song.title) }

    @Test
    fun aCompletedLoadPreparesTheNextEntry() {
        val songs = testSongs(3)
        playingModel(songs)

        assertEquals(songs[1].id, audioPlayer.prepared.value)
        assertEquals(listOf(songs[1].id), audioPlayer.prepares.value)
        assertEquals(0, audioPlayer.clears.value)
    }

    @Test
    fun repeatOnePreparesTheCurrentEntry() {
        val songs = testSongs(3)
        playingModel(songs, repeat = RepeatMode.ONE)

        assertEquals(songs[0].id, audioPlayer.prepared.value)
    }

    @Test
    fun repeatAllOnTheLastEntryPreparesTheFirst() {
        val songs = testSongs(3)
        playingModel(songs, repeat = RepeatMode.ALL, startIndex = 2)

        assertEquals(songs[0].id, audioPlayer.prepared.value)
    }

    @Test
    fun repeatOffOnTheLastEntryClearsTheSlot() {
        val songs = testSongs(3)
        playingModel(songs, startIndex = 2)

        assertNull(audioPlayer.prepared.value)
        assertEquals(emptyList(), audioPlayer.prepares.value)
        assertEquals(1, audioPlayer.clears.value)
    }

    @Test
    fun aRepeatModeChangeRedeclaresTheNext() {
        val songs = testSongs(3)
        val model = playingModel(songs, startIndex = 2)
        assertNull(audioPlayer.prepared.value)

        model.setRepeatMode(RepeatMode.ALL)
        scheduler.runCurrent()
        assertEquals(songs[0].id, audioPlayer.prepared.value)

        model.setRepeatMode(RepeatMode.ONE)
        scheduler.runCurrent()
        assertEquals(songs[2].id, audioPlayer.prepared.value)
    }

    @Test
    fun removingTheNextEntryPreparesTheOneAfterIt() {
        val songs = testSongs(3)
        val model = playingModel(songs)

        model.removeFromQueue(model.queue.value[1])
        scheduler.runCurrent()

        assertEquals(songs[2].id, audioPlayer.prepared.value)
        assertEquals(1, audioPlayer.loads.value.size)
    }

    @Test
    fun playNextPreparesTheInsertedSong() {
        val songs = testSongs(3)
        val inserted = testSong(9)
        val model = playingModel(songs)

        model.playNext(inserted)
        scheduler.advanceUntilIdle()

        assertEquals(inserted.id, songIdAt(model, 1))
        assertEquals(inserted.id, audioPlayer.prepared.value)
    }

    @Test
    fun shufflePreparesTheEntryShownNext() {
        val songs = testSongs(6)
        val model = playingModel(songs)

        model.setShuffleMode(true)
        scheduler.runCurrent()

        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[0].id, songIdAt(model, 0))
        assertEquals(songIdAt(model, 1), audioPlayer.prepared.value)
    }

    @Test
    fun movingAnEntryBehindTheCurrentOnePreparesIt() {
        val songs = testSongs(3)
        val model = playingModel(songs)

        model.moveQueueItem(2, 1)
        scheduler.runCurrent()

        assertEquals(songs[2].id, audioPlayer.prepared.value)
    }

    @Test
    fun aRemoteQueueKeepingTheCurrentSongPreparesItsNewNext() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        val queue = model.queue.value
        val reordered = listOf(queue[0], queue[2], queue[1])

        model.applyRemoteQueue(
            QueueSnapshot(original = reordered, active = reordered, currentIndex = 0, shuffle = false, repeat = RepeatMode.OFF, sourceId = null),
            keepCurrentQueueId = queue[0].queueId
        )
        scheduler.runCurrent()

        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[2].id, audioPlayer.prepared.value)
        assertEquals(1, audioPlayer.loads.value.size)
    }

    @Test
    fun nothingIsPreparedWhileTheLoadIsPending() {
        val songs = testSongs(3)
        audioPlayer.deferLoads = true
        playingModel(songs)

        assertNull(audioPlayer.prepared.value)
        assertEquals(emptyList(), audioPlayer.prepares.value)

        audioPlayer.finishLoad()
        scheduler.runCurrent()

        assertEquals(songs[1].id, audioPlayer.prepared.value)
    }

    @Test
    fun aRestoredPausedTrackPreparesNothing() {
        val songs = testSongs(3)
        val entries = songs.map { QueueEntry.Explicit(it) }
        stateFile.writeBytes(compress(AppCbor.encodeToByteArray(PlayerState(queue = entries, originalQueue = entries, currentIndex = 0, lastPosition = 30_000))))

        val model = createModel()

        assertEquals(0, model.currentIndex.value)
        assertEquals(30_000L, model.currentPosition.value)
        assertNull(audioPlayer.prepared.value)
        assertEquals(emptyList(), audioPlayer.prepares.value)
        assertEquals(emptyList(), audioPlayer.loads.value)
    }

    @Test
    fun remoteModePreparesNothing() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        assertEquals(songs[1].id, audioPlayer.prepared.value)

        model.remotePlayHandler = {}
        scheduler.runCurrent()

        assertNull(audioPlayer.prepared.value)
        assertEquals(1, audioPlayer.stops.value)
        assertEquals(listOf(songs[1].id), audioPlayer.prepares.value)
        assertNull(model.loadedSongId.value)
    }

    @Test
    fun aLoadErrorOfTheFirstTrackPreparesNothingUntilTheNextTrackIsLoaded() {
        val songs = testSongs(3)
        audioPlayer.deferLoads = true
        val model = playingModel(songs)

        audioPlayer.fail()
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertEquals(2, audioPlayer.loads.value.size)
        assertNull(audioPlayer.prepared.value)

        audioPlayer.finishLoad()
        scheduler.runCurrent()

        assertEquals(songs[1].id, model.loadedSongId.value)
        assertEquals(songs[2].id, audioPlayer.prepared.value)
    }

    @Test
    fun gaplessOffClearsTheSlotAndNeverPreparesUntilItIsOnAgain() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        assertEquals(songs[1].id, audioPlayer.prepared.value)
        assertTrue(model.gaplessPlayback.value)

        model.setGaplessPlayback(false)
        scheduler.runCurrent()

        assertFalse(model.gaplessPlayback.value)
        assertEquals(false, settings.getBooleanOrNull("gapless_playback"))
        assertNull(audioPlayer.prepared.value)
        assertEquals(1, audioPlayer.clears.value)

        model.skipNext()
        scheduler.runCurrent()

        assertEquals(songs[1].id, model.loadedSongId.value)
        assertNull(audioPlayer.prepared.value)
        assertEquals(listOf(songs[1].id), audioPlayer.prepares.value)

        model.setGaplessPlayback(true)
        scheduler.runCurrent()

        assertEquals(true, settings.getBooleanOrNull("gapless_playback"))
        assertEquals(songs[2].id, audioPlayer.prepared.value)
    }

    @Test
    fun aModelBuiltWithGaplessOffClearsOnceAndNeverPrepares() {
        settings.putBoolean("gapless_playback", false)
        val songs = testSongs(3)
        val model = playingModel(songs)

        assertFalse(model.gaplessPlayback.value)
        assertNull(audioPlayer.prepared.value)
        assertEquals(emptyList(), audioPlayer.prepares.value)
        assertEquals(1, audioPlayer.clears.value)
    }

    @Test
    fun skipNextOntoThePreparedTrackConsumesTheSlotInsteadOfClearingIt() {
        val songs = testSongs(3)
        val model = playingModel(songs)

        model.skipNext()
        scheduler.runCurrent()

        assertEquals(2, audioPlayer.loads.value.size)
        assertEquals(listOf(songs[1].id, songs[2].id), audioPlayer.prepares.value)
        assertEquals(0, audioPlayer.clears.value)
        assertEquals(songs[2].id, audioPlayer.prepared.value)
    }

    @Test
    fun aTransitionAdvancesToThePreparedEntryWithoutALoad() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        audioPlayer.position.value = 239_000
        scheduler.runCurrent()

        transition()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1].id, model.currentSongId)
        assertEquals(songs[1], model.currentSong.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertTrue(model.playbackRequested)
        assertTrue(model.isPlaying.value)
        assertEquals(0L, model.currentPosition.value)
        assertEquals(TrackStart(songs[1].id, songs[1], 0L), trackStarts.last())
        assertEquals(2, trackStarts.size)
        assertEquals(QueueChange.CurrentChanged(model.queue.value[1].queueId, 1), queueChanges.last())
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(0, audioPlayer.stops.value)
        assertEquals(0, audioPlayer.seeks.value)
        assertEquals(songs[2].id, audioPlayer.prepared.value)
        val saved = readState()
        assertEquals(1, saved.currentIndex)
        assertEquals(0L, saved.lastPosition)
    }

    @Test
    fun theTrackStartOfATransitionArrivesWhileThePreviousSongIsStillCurrentAndLoaded() {
        val songs = testSongs(3)
        val model = playingModel(songs)

        transition()

        val start = events.indexOfFirst { it is Event.Start && it.start.songId == songs[1].id }
        val change = events.indexOfFirst { it is Event.Change && it.change == QueueChange.CurrentChanged(model.queue.value[1].queueId, 1) }
        val song = events.indexOfFirst { it is Event.Song && it.song == songs[1] }
        assertTrue(start >= 0 && change >= 0 && song >= 0)
        assertTrue(start < change)
        assertTrue(change < song)
        val recorded = events[start] as Event.Start
        assertEquals(songs[0].id, recorded.loadedSongId)
        assertEquals(0, recorded.currentIndex)
        assertEquals(0L, recorded.start.startMs)
    }

    @Test
    fun repeatOneTransitionRestartsTheSameEntryWithoutACurrentChange() {
        val songs = testSongs(3)
        val model = playingModel(songs, repeat = RepeatMode.ONE)
        audioPlayer.position.value = 239_000
        scheduler.runCurrent()
        val changesBefore = queueChanges.size

        transition()

        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[0], model.currentSong.value)
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(0L, model.currentPosition.value)
        assertTrue(model.playbackRequested)
        assertTrue(model.isPlaying.value)
        assertEquals(emptyList(), queueChanges.drop(changesBefore).filterIsInstance<QueueChange.CurrentChanged>())
        assertEquals(listOf(TrackStart(songs[0].id, songs[0], 0L), TrackStart(songs[0].id, songs[0], 0L)), trackStarts)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(songs[0].id, audioPlayer.prepared.value)
        assertEquals(listOf(songs[0].id, songs[0].id), audioPlayer.prepares.value)
    }

    @Test
    fun repeatAllTransitionAtTheLastEntryWrapsToTheFirst() {
        val songs = testSongs(3)
        val model = playingModel(songs, repeat = RepeatMode.ALL, startIndex = 2)
        assertEquals(songs[0].id, audioPlayer.prepared.value)

        transition()

        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[0], model.currentSong.value)
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(QueueChange.CurrentChanged(model.queue.value[0].queueId, 0), queueChanges.last())
        assertEquals(TrackStart(songs[0].id, songs[0], 0L), trackStarts.last())
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(songs[1].id, audioPlayer.prepared.value)
    }

    @Test
    fun repeatAllTransitionOnASingleEntryQueuePreparesItAgain() {
        val songs = testSongs(1)
        val model = playingModel(songs, repeat = RepeatMode.ALL)
        assertEquals(songs[0].id, audioPlayer.prepared.value)

        transition()

        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(QueueChange.CurrentChanged(model.queue.value[0].queueId, 0), queueChanges.last())
        assertEquals(2, trackStarts.size)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(songs[0].id, audioPlayer.prepared.value)
    }

    @Test
    fun aTransitionToAnUnexpectedSongLoadsTheComputedNext() {
        val songs = testSongs(3)
        val other = testSong(9)
        val model = playingModel(songs)

        audioPlayer.prepared.value = other.id
        transition()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertEquals(2, audioPlayer.loads.value.size)
        assertEquals(songs[1].id to true, audioPlayer.loads.value.last())
        assertEquals(TrackStart(songs[1].id, songs[1], 0L), trackStarts.last())
        assertEquals(emptyList(), trackStarts.filter { it.songId == other.id })
        assertEquals(songs[2].id, audioPlayer.prepared.value)
    }

    @Test
    fun aTransitionWhileASkipIsInFlightIsDroppedAndTheSkipWins() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        audioPlayer.deferLoads = true

        model.skipNext()
        audioPlayer.transition()
        scheduler.runCurrent()

        assertEquals(2, audioPlayer.loads.value.size)
        assertEquals(songs[1].id to true, audioPlayer.loads.value.last())
        assertNull(model.loadedSongId.value)

        audioPlayer.finishLoad()
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertEquals(listOf(TrackStart(songs[0].id, songs[0], 0L), TrackStart(songs[1].id, songs[1], 0L)), trackStarts)
        assertEquals(songs[2].id, audioPlayer.prepared.value)
    }

    @Test
    fun aPauseRacingTheTransitionLeavesTheNewTrackPaused() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        audioPlayer.position.value = 100_000

        model.pause()
        transition()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertFalse(model.playbackRequested)
        assertFalse(model.isPlaying.value)
        assertEquals(0L, model.currentPosition.value)

        model.play()
        scheduler.runCurrent()

        assertTrue(model.playbackRequested)
        assertTrue(model.isPlaying.value)
        assertEquals(1, audioPlayer.plays.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(0, audioPlayer.seeks.value)
    }

    @Test
    fun aStopBeforeABufferedTransitionIsHandledDropsIt() {
        val songs = testSongs(3)
        val model = playingModel(songs)

        audioPlayer.transition()
        model.stop()
        scheduler.runCurrent()

        assertNull(model.loadedSongId.value)
        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[0], model.currentSong.value)
        assertEquals(1, audioPlayer.stops.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(1, trackStarts.size)
        assertFalse(model.playbackRequested)
        assertEquals(0L, model.currentPosition.value)
    }

    @Test
    fun aFailureOfTheNewTrackAfterATransitionSkipsIt() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        transition()
        assertEquals(songs[1].id, model.loadedSongId.value)

        audioPlayer.fail()
        scheduler.runCurrent()

        assertEquals(2, model.currentIndex.value)
        assertEquals(songs[2].id, model.loadedSongId.value)
        assertEquals(2, audioPlayer.loads.value.size)
        assertEquals(songs[2].id to true, audioPlayer.loads.value.last())
        verify(timeout = 5000) { snackbarManager.showSnackbar(skipped(songs[1])) }
    }

    @Test
    fun aPauseAfterATransitionSavesTheNewEntryAtZero() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        audioPlayer.position.value = 239_000
        model.pause()
        scheduler.runCurrent()
        assertEquals(239_000L, readState().lastPosition)
        model.play()
        scheduler.runCurrent()

        transition()
        model.pause()
        scheduler.runCurrent()

        val saved = readState()
        assertEquals(1, saved.currentIndex)
        assertEquals(0L, saved.lastPosition)
        assertEquals(0L, model.currentPosition.value)
    }

    @Test
    fun finishTrackCapturedBeforeATransitionDoesNotEndTheNewTrack() {
        val songs = testSongs(3)
        val model = playingModel(songs)

        audioPlayer.transition()
        model.finishTrack(trackStarts.single())
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(2, trackStarts.size)
    }

    @Test
    fun finishTrackForTheTrackThatEndedInATransitionDoesNotEndTheNewTrack() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        val startOfFirst = trackStarts.single()

        transition()
        assertEquals(1, model.currentIndex.value)
        model.finishTrack(startOfFirst)
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(2, trackStarts.size)
    }

    @Test
    fun finishTrackForTheStartOfTheTransitionedTrackEndsIt() {
        val songs = testSongs(3)
        val model = playingModel(songs)

        transition()
        model.finishTrack(trackStarts.last())
        scheduler.runCurrent()

        assertEquals(2, model.currentIndex.value)
        assertEquals(songs[2].id, model.loadedSongId.value)
        assertEquals(2, audioPlayer.loads.value.size)
        assertEquals(3, trackStarts.size)
    }

    @Test
    fun seekForTheTrackThatEndedInATransitionDoesNotSeekTheNewTrack() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        val startOfFirst = trackStarts.single()
        val seeks = mutableListOf<Long>()
        recorder.launch { model.seekEvents.collect { seeks += it } }

        transition()
        model.seekTo(230_000, startOfFirst)
        scheduler.runCurrent()

        assertEquals(0, audioPlayer.seeks.value)
        assertEquals(0L, audioPlayer.position.value)
        assertTrue(seeks.isEmpty())
        assertEquals(1, model.currentIndex.value)
    }

    @Test
    fun seekForTheStartOfTheTransitionedTrackSeeksIt() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        val seeks = mutableListOf<Long>()
        recorder.launch { model.seekEvents.collect { seeks += it } }

        transition()
        model.seekTo(30_000, trackStarts.last())
        scheduler.runCurrent()

        assertEquals(1, audioPlayer.seeks.value)
        assertEquals(30_000L, audioPlayer.position.value)
        assertEquals(listOf(30_000L), seeks)
    }

    @Test
    fun anEndClearlyBeforeTheKnownDurationIsSkippedLikeAFailure() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        audioPlayer.trackDuration.value = 240_000
        audioPlayer.position.value = 120_000
        scheduler.runCurrent()

        audioPlayer.emitFinished()
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertEquals(2, audioPlayer.loads.value.size)
        assertEquals(songs[1].id to true, audioPlayer.loads.value.last())
        verify(timeout = 5000) { snackbarManager.showSnackbar(skipped(songs[0])) }
    }

    @Test
    fun finishTrackForAnEarlierStartOfTheSameSongUnderRepeatOneIsIgnored() {
        val songs = testSongs(2)
        val model = playingModel(songs, repeat = RepeatMode.ONE)
        val firstStart = trackStarts.single()

        transition()
        assertEquals(2, trackStarts.size)
        model.finishTrack(firstStart)
        scheduler.runCurrent()

        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(2, trackStarts.size)
    }

    @Test
    fun aSeekUnderRepeatOneKeepsTheSlotAndTheLoopStaysGapless() {
        val songs = testSongs(2)
        val model = playingModel(songs, repeat = RepeatMode.ONE)
        audioPlayer.position.value = 200_000
        scheduler.runCurrent()

        model.seekTo(100_000)
        scheduler.runCurrent()
        assertEquals(1, audioPlayer.seeks.value)
        assertEquals(songs[0].id, audioPlayer.prepared.value)

        transition()

        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(2, trackStarts.size)
        assertEquals(songs[0].id, audioPlayer.prepared.value)
        assertEquals(listOf(songs[0].id, songs[0].id), audioPlayer.prepares.value)
    }

    @Test
    fun aSeekToZeroUnderRepeatOneKeepsTheSlotAndTheLoopStaysGapless() {
        val songs = testSongs(2)
        val model = playingModel(songs, repeat = RepeatMode.ONE)
        audioPlayer.position.value = 200_000
        scheduler.runCurrent()

        model.seekTo(0)
        scheduler.runCurrent()
        assertEquals(1, audioPlayer.seeks.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(songs[0].id, audioPlayer.prepared.value)

        transition()

        assertEquals(0, model.currentIndex.value)
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(2, trackStarts.size)
        assertEquals(songs[0].id, audioPlayer.prepared.value)
    }

    @Test
    fun restartingTheSongUnderRepeatOneConsumesTheSlotAndDeclaresItAgain() {
        val songs = testSongs(2)
        val model = playingModel(songs, repeat = RepeatMode.ONE)
        assertEquals(songs[0].id, audioPlayer.prepared.value)

        model.playAtIndex(0)
        scheduler.runCurrent()

        assertEquals(2, audioPlayer.loads.value.size)
        assertEquals(listOf(0L, 0L), audioPlayer.starts.value)
        assertEquals(songs[0].id, model.loadedSongId.value)
        assertEquals(songs[0].id, audioPlayer.prepared.value)
        assertEquals(listOf(songs[0].id, songs[0].id), audioPlayer.prepares.value)

        transition()

        assertEquals(0, model.currentIndex.value)
        assertEquals(2, audioPlayer.loads.value.size)
        assertEquals(3, trackStarts.size)
        assertEquals(songs[0].id, audioPlayer.prepared.value)
    }

    @Test
    fun theSameSongTwiceInARowTransitionsToTheSecondEntry() {
        val a = testSong(0)
        val b = testSong(1)
        val model = playingModel(listOf(a, a, b))
        assertEquals(a.id, audioPlayer.prepared.value)

        transition()

        assertEquals(1, model.currentIndex.value)
        assertEquals(a.id, model.loadedSongId.value)
        assertEquals(QueueChange.CurrentChanged(model.queue.value[1].queueId, 1), queueChanges.last())
        assertEquals(TrackStart(a.id, a, 0L), trackStarts.last())
        assertEquals(2, trackStarts.size)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(b.id, audioPlayer.prepared.value)
        assertEquals(listOf(a.id, b.id), audioPlayer.prepares.value)
    }

    @Test
    fun twoEqualEntriesAfterTheCurrentOnePrepareTheSongAgainAndThenClear() {
        val a = testSong(0)
        val b = testSong(1)
        val model = playingModel(listOf(a, b, b))
        assertEquals(b.id, audioPlayer.prepared.value)

        transition()

        assertEquals(1, model.currentIndex.value)
        assertEquals(b.id, model.loadedSongId.value)
        assertEquals(b.id, audioPlayer.prepared.value)
        assertEquals(listOf(b.id, b.id), audioPlayer.prepares.value)

        transition()

        assertEquals(2, model.currentIndex.value)
        assertEquals(b.id, model.loadedSongId.value)
        assertEquals(QueueChange.CurrentChanged(model.queue.value[2].queueId, 2), queueChanges.last())
        assertNull(audioPlayer.prepared.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(3, trackStarts.size)
    }

    @Test
    fun aQueueChangeDuringTheHandlerThatKeepsTheTransitionedSongNextIsFollowed() {
        val songs = testSongs(3)
        val model = playingModelFromSource(songs)
        assertEquals(songs[1].id, audioPlayer.prepared.value)
        val lookup = holdLookupOf(songs[1])

        audioPlayer.transition()
        scheduler.runCurrent()
        assertEquals(0, model.currentIndex.value)
        model.removeFromQueue(model.queue.value[2])
        scheduler.runCurrent()
        lookup.complete(Unit)
        scheduler.runCurrent()

        assertEquals(listOf(songs[0].id, songs[1].id), model.queue.value.map { (it as QueueEntry.FromSource).songId })
        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertEquals(songs[1], model.currentSong.value)
        assertEquals(TrackStart(songs[1].id, songs[1], 0L), trackStarts.last())
        assertEquals(QueueChange.CurrentChanged(model.queue.value[1].queueId, 1), queueChanges.last())
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(0, audioPlayer.stops.value)
        assertNull(audioPlayer.prepared.value)
    }

    @Test
    fun aQueueChangeDuringTheHandlerThatRemovesTheTransitionedSongLoadsTheNewNext() {
        val songs = testSongs(3)
        val model = playingModelFromSource(songs)
        val lookup = holdLookupOf(songs[1])

        audioPlayer.transition()
        scheduler.runCurrent()
        model.removeFromQueue(model.queue.value[1])
        scheduler.runCurrent()
        assertEquals(songs[2].id, audioPlayer.prepared.value)
        lookup.complete(Unit)
        scheduler.runCurrent()

        assertEquals(listOf(songs[0].id, songs[2].id), model.queue.value.map { (it as QueueEntry.FromSource).songId })
        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[2].id, model.loadedSongId.value)
        assertEquals(songs[2], model.currentSong.value)
        assertEquals(2, audioPlayer.loads.value.size)
        assertEquals(songs[2].id to true, audioPlayer.loads.value.last())
        assertEquals(TrackStart(songs[2].id, songs[2], 0L), trackStarts.last())
        assertEquals(emptyList(), trackStarts.filter { it.songId == songs[1].id })
        assertNull(audioPlayer.prepared.value)
    }

    @Test
    fun aTransitionAfterGaplessWasSwitchedOffIsStillFollowedWithoutANewPreparation() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        model.setGaplessPlayback(false)
        scheduler.runCurrent()
        assertNull(audioPlayer.prepared.value)

        audioPlayer.prepared.value = songs[1].id
        transition()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertEquals(songs[1], model.currentSong.value)
        assertEquals(TrackStart(songs[1].id, songs[1], 0L), trackStarts.last())
        assertEquals(1, audioPlayer.loads.value.size)
        assertNull(audioPlayer.prepared.value)
        assertEquals(listOf(songs[1].id), audioPlayer.prepares.value)
    }

    @Test
    fun aPauseAndASeekAfterThePlayersFlipButBeforeTheHandlerLandOnTheNewTrack() {
        val songs = testSongs(3)
        val model = playingModel(songs)
        audioPlayer.position.value = 239_000
        scheduler.runCurrent()

        audioPlayer.transition()
        model.pause()
        model.seekTo(30_000)
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertEquals(songs[1], model.currentSong.value)
        assertFalse(model.playbackRequested)
        assertFalse(model.isPlaying.value)
        assertEquals(30_000L, model.currentPosition.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertEquals(1, audioPlayer.seeks.value)
        assertEquals(0, audioPlayer.stops.value)
        val saved = readState()
        assertEquals(1, saved.currentIndex)
        assertEquals(30_000L, saved.lastPosition)

        model.play()
        scheduler.runCurrent()

        assertTrue(model.isPlaying.value)
        assertEquals(1, audioPlayer.plays.value)
        assertEquals(1, audioPlayer.loads.value.size)
    }

    @Test
    fun aLatePauseBeforeARepeatOneLoopDoesNotSurviveAsTheSavedPosition() {
        val songs = testSongs(2)
        val model = playingModel(songs, repeat = RepeatMode.ONE)
        audioPlayer.position.value = 200_000
        model.pause()
        scheduler.runCurrent()
        assertEquals(200_000L, readState().lastPosition)
        model.play()
        scheduler.runCurrent()

        transition()
        model.setRepeatMode(RepeatMode.ALL)
        scheduler.runCurrent()

        val saved = readState()
        assertEquals(0, saved.currentIndex)
        assertEquals(0L, saved.lastPosition)
        assertEquals(RepeatMode.ALL, saved.repeatMode)
    }

    @Test
    fun aNaturalEndWithGaplessOffStillAdvancesThroughALoad() {
        settings.putBoolean("gapless_playback", false)
        val songs = testSongs(3)
        val model = playingModel(songs)
        assertNull(audioPlayer.prepared.value)

        audioPlayer.emitFinished()
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertEquals(songs[1].id, model.loadedSongId.value)
        assertEquals(2, audioPlayer.loads.value.size)
        assertEquals(songs[1].id to true, audioPlayer.loads.value.last())
        assertTrue(model.isPlaying.value)
    }

    @Test
    fun aNaturalEndOfTheLastEntryWithRepeatOffStillEndsTheQueue() {
        val songs = testSongs(2)
        val model = playingModel(songs, startIndex = 1)
        assertNull(audioPlayer.prepared.value)

        audioPlayer.emitFinished()
        scheduler.runCurrent()

        assertEquals(-1, model.currentIndex.value)
        assertNull(model.loadedSongId.value)
        assertFalse(model.playbackRequested)
        assertEquals(1, audioPlayer.stops.value)
        assertEquals(1, audioPlayer.loads.value.size)
    }
}
