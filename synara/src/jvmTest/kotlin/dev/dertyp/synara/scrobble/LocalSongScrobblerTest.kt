package dev.dertyp.synara.scrobble

import dev.dertyp.data.User
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.db.LocalHistoryRepository
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.ui.models.TrayState
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.synara.viewmodels.GlobalStateModel
import io.mockk.Called
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSongScrobblerTest {
    private val testDispatcher = StandardTestDispatcher(TestCoroutineScheduler())

    private val userId = randomPlatformUUID()
    private val userFlow = MutableStateFlow<User?>(User(id = userId, username = "listener"))
    private val playingFlow = MutableStateFlow(false)
    private val globalState = mockk<GlobalStateModel>()
    private val scrobblerService = mockk<ScrobblerService>(relaxed = true)
    private val playerModel = mockk<PlayerModel>()
    private val repository = mockk<LocalHistoryRepository>(relaxed = true)
    private val trayState = TrayState()
    private val song = testSong(0)

    private lateinit var scrobbler: LocalSongScrobbler

    @BeforeTest
    fun setUp() {
        every { globalState.user } returns userFlow
        every { playerModel.isPlaying } returns playingFlow
        startTestKoin(
            module {
                single { globalState }
                single<SynaraDispatchers> { TestDispatchers(testDispatcher) }
                single { scrobblerService }
            }
        )
        scrobbler = LocalSongScrobbler(repository, trayState, playerModel)
    }

    @AfterTest
    fun tearDown() {
        scrobbler.stopScrobbler()
        stopTestKoin()
    }

    @Test
    fun triggeredListenIsStoredForTheUserAndChangesTheBadgeColour() = runTest(testDispatcher) {
        playingFlow.value = true
        runCurrent()
        val initial = assertNotNull(trayState.badgeColor.value)

        scrobbler.triggered(song, 1_700_000_000_000L)
        runCurrent()

        coVerify(exactly = 1) { repository.insert(userId = userId, song = song, timestamp = 1_700_000_000_000L) }
        assertEquals(BaseScrobbler.ScrobbleStatus.SCROBBLED, scrobbler.status.value)
        val scrobbled = assertNotNull(trayState.badgeColor.value)
        assertNotEquals(initial, scrobbled)
    }

    @Test
    fun triggeredListenWithoutAUserIsNotStored() = runTest(testDispatcher) {
        userFlow.value = null
        playingFlow.value = true
        runCurrent()
        val initial = assertNotNull(trayState.badgeColor.value)

        scrobbler.triggered(song, 1_700_000_000_000L)
        runCurrent()

        verify { repository wasNot Called }
        assertEquals(BaseScrobbler.ScrobbleStatus.IDLE, scrobbler.status.value)
        assertEquals(initial, trayState.badgeColor.value)
    }

    @Test
    fun badgeIsShownOnlyWhilePlaying() = runTest(testDispatcher) {
        runCurrent()

        assertNull(trayState.badgeColor.value)

        playingFlow.value = true
        runCurrent()

        assertNotNull(trayState.badgeColor.value)

        playingFlow.value = false
        runCurrent()

        assertNull(trayState.badgeColor.value)
    }

    @Test
    fun resetReturnsTheBadgeToItsInitialColour() = runTest(testDispatcher) {
        playingFlow.value = true
        runCurrent()
        val initial = assertNotNull(trayState.badgeColor.value)
        scrobbler.triggered(song, 1_700_000_000_000L)
        runCurrent()
        assertNotEquals(initial, trayState.badgeColor.value)

        scrobbler.reset()
        runCurrent()

        assertEquals(initial, trayState.badgeColor.value)
    }
}
