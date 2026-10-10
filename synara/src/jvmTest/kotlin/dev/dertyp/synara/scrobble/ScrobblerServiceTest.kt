package dev.dertyp.synara.scrobble

import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.utils.SynaraDispatchers
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.StringResource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.radio
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ScrobblerServiceTest {

    private object UnconfinedDispatchers : SynaraDispatchers {
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    class RecordingScrobbler : BaseScrobbler() {
        override val icon: SynaraIcons = SynaraIcons.Play
        override val name: StringResource = Res.string.radio

        val calls = MutableStateFlow<List<UserSong?>>(emptyList())
        val resets = MutableStateFlow(0)
        val endedListens = MutableStateFlow<List<UserSong>>(emptyList())

        override suspend fun newSong(song: UserSong?) {
            calls.update { it + song }
        }

        override suspend fun reset() {
            resets.update { it + 1 }
        }

        override suspend fun listenEnded(song: UserSong, msPlayed: Long, listenedAt: Long) {
            endedListens.update { it + song }
        }
    }

    class PlayStateAwareScrobbler : BaseScrobbler() {
        override val icon: SynaraIcons = SynaraIcons.Pause
        override val name: StringResource = Res.string.radio

        val calls = MutableStateFlow<List<UserSong?>>(emptyList())
        val resumes = MutableStateFlow<List<UserSong>>(emptyList())

        override suspend fun newSong(song: UserSong?) {
            calls.update { it + song }
        }

        override suspend fun resumed(song: UserSong) {
            resumes.update { it + song }
        }
    }

    private val songFlow = MutableStateFlow<UserSong?>(null)
    private val playingFlow = MutableStateFlow(false)
    private val loadedFlow = MutableStateFlow<PlatformUUID?>(null)
    private val playerModel = mockk<PlayerModel> {
        every { currentSong } returns songFlow
        every { isPlaying } returns playingFlow
        every { loadedSongId } returns loadedFlow
    }

    private lateinit var service: ScrobblerService
    private lateinit var scrobbler: RecordingScrobbler
    private lateinit var playStateAware: PlayStateAwareScrobbler

    @BeforeTest
    fun setUp() {
        val koin = startKoin {
            modules(
                module {
                    single { playerModel }
                    single<Logger> { mockk(relaxed = true) }
                    single<SynaraDispatchers> { UnconfinedDispatchers }
                    singleOf(::ScrobblerService)
                    singleOf(::RecordingScrobbler)
                    singleOf(::PlayStateAwareScrobbler)
                }
            )
        }.koin
        service = koin.get()
        scrobbler = koin.get()
        playStateAware = koin.get()
        service.registerScrobbler(RecordingScrobbler::class)
        service.registerScrobbler(PlayStateAwareScrobbler::class)
        service.registerScrobbler(RecordingScrobbler::class)
        assertEquals(listOf(scrobbler, playStateAware), service.registeredScrobblers())
        service.start()
        awaitCalls(1)
    }

    @AfterTest
    fun tearDown() {
        playingFlow.value = false
        service.stop()
        stopKoin()
    }

    private fun song(index: Int) = UserSong(
        id = randomPlatformUUID(),
        title = "Song $index",
        artists = emptyList(),
        album = null,
        duration = 240_000,
        explicit = false,
        path = "/song$index.flac"
    )

    private fun awaitCalls(count: Int): List<UserSong?> = runBlocking {
        withTimeout(5.seconds) { scrobbler.calls.first { it.size == count } }
    }

    private fun startAudio(song: UserSong) {
        loadedFlow.value = song.id
        playingFlow.value = true
    }

    @Test
    fun songSetWhilePausedIsNotAnnounced() {
        songFlow.value = song(0)

        assertEquals(listOf<UserSong?>(null), scrobbler.calls.value)
        assertEquals(null, service.newSong.value)
    }

    @Test
    fun songIsAnnouncedOnceAudioStarts() {
        val song = song(0)
        songFlow.value = song

        startAudio(song)

        assertEquals(listOf(null, song), awaitCalls(2))
    }

    @Test
    fun songChangeWhilePlayingIsAnnouncedOnce() {
        val first = song(0)
        val second = song(1)
        songFlow.value = first
        startAudio(first)
        awaitCalls(2)

        songFlow.value = second
        assertEquals(listOf(null, first), scrobbler.calls.value)

        loadedFlow.value = second.id

        assertEquals(listOf(null, first, second), awaitCalls(3))
        assertEquals(emptyList(), playStateAware.resumes.value)
    }

    @Test
    fun unplayableSongIsNeverAnnounced() {
        val first = song(0)
        val second = song(1)
        val third = song(2)
        songFlow.value = first
        startAudio(first)
        awaitCalls(2)

        songFlow.value = second
        playingFlow.value = false
        songFlow.value = third
        assertEquals(listOf(null, first), scrobbler.calls.value)

        startAudio(third)

        assertEquals(listOf(null, first, third), awaitCalls(3))
        assertEquals(third, service.newSong.value)
    }

    @Test
    fun songChangeWhilePausedIsAnnouncedWhenAudioStarts() {
        val first = song(0)
        val second = song(1)
        songFlow.value = first
        startAudio(first)
        awaitCalls(2)

        playingFlow.value = false
        songFlow.value = second
        assertEquals(listOf(null, first), scrobbler.calls.value)
        assertEquals(first, service.newSong.value)

        startAudio(second)

        assertEquals(listOf(null, first, second), awaitCalls(3))
    }

    @Test
    fun clearedSongIsAnnouncedAsNull() {
        val song = song(0)
        songFlow.value = song
        startAudio(song)
        awaitCalls(2)

        songFlow.value = null

        assertEquals(listOf(null, song, null), awaitCalls(3))
    }

    @Test
    fun pauseAndResumeOnTheSameSongIsAnnouncedAgain() {
        val song = song(0)
        songFlow.value = song
        startAudio(song)
        awaitCalls(2)
        val resetsBefore = scrobbler.resets.value

        playingFlow.value = false
        assertEquals(listOf(null, song), scrobbler.calls.value)
        playingFlow.value = true

        assertEquals(listOf(null, song, song), awaitCalls(3))
        assertEquals(song, service.newSong.value)
        assertEquals(resetsBefore, scrobbler.resets.value)
        assertEquals(emptyList(), scrobbler.endedListens.value)
        assertEquals(null, service.triggeredSong.value)
    }

    @Test
    fun scrobblerHandlingResumeItselfGetsNoSecondAnnouncement() {
        val song = song(0)
        songFlow.value = song
        startAudio(song)
        awaitCalls(2)

        playingFlow.value = false
        playingFlow.value = true
        awaitCalls(3)

        val resumes = runBlocking { withTimeout(5.seconds) { playStateAware.resumes.first { it.size == 1 } } }
        assertEquals(listOf(song), resumes)
        assertEquals(listOf(null, song), playStateAware.calls.value)
    }

    @Test
    fun unregisteredScrobblerLeavesTheList() {
        val before = service.registeredScrobblers()

        service.unregisterScrobbler(PlayStateAwareScrobbler::class)

        assertEquals(listOf<BaseScrobbler>(scrobbler), service.registeredScrobblers())
        assertEquals(listOf(scrobbler, playStateAware), before)
    }

    @Test
    fun audioOfTheNextEntryStartingBeforeItsSongIsKnownIsNotAResume() {
        val first = song(0)
        val second = song(1)
        songFlow.value = first
        startAudio(first)
        awaitCalls(2)

        playingFlow.value = false
        startAudio(second)

        assertEquals(listOf(null, first), scrobbler.calls.value)
        assertEquals(emptyList(), playStateAware.resumes.value)

        songFlow.value = second

        assertEquals(listOf(null, first, second), awaitCalls(3))
        assertEquals(emptyList(), playStateAware.resumes.value)
        assertEquals(second, service.newSong.value)
    }

    @Test
    fun listenEndsOncePerPlayedSongAndLoadTimeDoesNotCount() {
        val first = song(0)
        val second = song(1)
        val third = song(2)
        songFlow.value = first
        startAudio(first)
        awaitCalls(2)
        runBlocking { withTimeout(5.seconds) { service.scrobbledFor.first { it >= 1 } } }

        loadedFlow.value = null
        songFlow.value = second

        val ended = runBlocking { withTimeout(5.seconds) { scrobbler.endedListens.first { it.isNotEmpty() } } }
        assertEquals(listOf(first), ended)
        runBlocking { delay(1_500) }
        assertEquals(0, service.scrobbledFor.value)

        songFlow.value = third

        assertEquals(listOf(first), scrobbler.endedListens.value)
        assertEquals(listOf(null, first), scrobbler.calls.value)
    }
}
