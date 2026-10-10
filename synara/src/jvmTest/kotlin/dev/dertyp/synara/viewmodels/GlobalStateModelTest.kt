package dev.dertyp.synara.viewmodels

import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PlaylistAccess
import dev.dertyp.data.PlaylistShare
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.Config
import dev.dertyp.synara.db.ScrobbleQueueRepository
import dev.dertyp.synara.db.UserRepository
import dev.dertyp.synara.player.PlaylistUpdate
import dev.dertyp.synara.player.QueueSyncService
import dev.dertyp.synara.player.RemoteControlService
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.player.TimecodeTagAutomation
import dev.dertyp.synara.rpc.ChangeHub
import dev.dertyp.synara.rpc.PresenceService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.RpcServiceManager.ConnectionState
import dev.dertyp.synara.rpc.services.UserPlaylistServiceWrapper
import dev.dertyp.synara.rpc.services.UserServiceWrapper
import dev.dertyp.synara.scrobble.DiscordScrobbler
import dev.dertyp.synara.scrobble.LastFmScrobbler
import dev.dertyp.synara.scrobble.ListenBrainzScrobbler
import dev.dertyp.synara.scrobble.LocalSongScrobbler
import dev.dertyp.synara.scrobble.RecentlyPlayedScrobbler
import dev.dertyp.synara.scrobble.ScrobbleQueue
import dev.dertyp.synara.scrobble.ScrobblerService
import dev.dertyp.synara.scrobble.ServerScrobbler
import dev.dertyp.synara.sync.SettingsSyncService
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalStateModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val logger = mockk<Logger>(relaxed = true)
    private val connection = MutableStateFlow(ConnectionState.Loading)
    private val playlistUpdates = MutableSharedFlow<PlaylistUpdate>(extraBufferCapacity = 16)

    private val me = User(id = randomPlatformUUID(), username = "me")
    private val storedMe = me.copy(displayName = "Stored")
    private var tokenUserId: String? = null
    private val firstPlaylists = listOf(playlist("first"))
    private val secondPlaylists = listOf(playlist("second"), playlist("third"))

    private lateinit var rpcServiceManager: RpcServiceManager
    private lateinit var userService: UserServiceWrapper
    private lateinit var userPlaylistService: UserPlaylistServiceWrapper
    private lateinit var scrobblerService: ScrobblerService
    private lateinit var presenceService: PresenceService
    private lateinit var changeHub: ChangeHub
    private lateinit var queueSyncService: QueueSyncService
    private lateinit var remoteControlService: RemoteControlService
    private lateinit var timecodeTagAutomation: TimecodeTagAutomation
    private lateinit var settingsSyncService: SettingsSyncService
    private lateinit var songCache: SongCache
    private lateinit var userRepository: UserRepository

    private fun playlist(name: String) = UserPlaylist(
        id = randomPlatformUUID(),
        name = name,
        songs = emptyList(),
        creator = me.id,
        description = ""
    )

    private fun sharedPlaylist(name: String, access: PlaylistAccess) = UserPlaylist(
        id = randomPlatformUUID(),
        name = name,
        songs = emptyList(),
        creator = randomPlatformUUID(),
        description = "",
        shares = listOf(PlaylistShare(me.id, access))
    )

    @BeforeTest
    fun setUp() {
        startTestKoin()
        setScrobblerFlags(server = false, listenBrainz = false, lastFm = false, discord = false)

        rpcServiceManager = mockk {
            every { connectionState } returns connection
            every { userId } answers { tokenUserId }
        }
        userService = mockk {
            coEvery { me() } returns me
            coEvery { setDisplayName(any()) } returns Unit
            coEvery { setProfileImage(any()) } returns Unit
        }
        userPlaylistService = mockk {
            coEvery { allPlaylists(any(), any(), any()) } returns PaginatedResponse(firstPlaylists)
            coEvery { sharedPlaylists(any(), any()) } returns PaginatedResponse(emptyList())
        }
        scrobblerService = mockk(relaxed = true)
        presenceService = mockk(relaxed = true)
        changeHub = mockk(relaxed = true)
        queueSyncService = mockk(relaxed = true)
        remoteControlService = mockk(relaxed = true)
        timecodeTagAutomation = mockk(relaxed = true)
        settingsSyncService = mockk(relaxed = true)
        songCache = mockk { every { playlistUpdates } returns this@GlobalStateModelTest.playlistUpdates }
        userRepository = mockk(relaxed = true)
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    private fun setScrobblerFlags(server: Boolean, listenBrainz: Boolean, lastFm: Boolean, discord: Boolean) {
        Config.setIsServerScrobblingEnabled(server)
        Config.setIsListenBrainzEnabled(listenBrainz)
        Config.setIsLastFmEnabled(lastFm)
        Config.setIsDiscordRpcEnabled(discord)
    }

    private fun createModel(): GlobalStateModel {
        val model = GlobalStateModel(
            rpcServiceManager,
            userService,
            userPlaylistService,
            scrobblerService,
            presenceService,
            changeHub,
            queueSyncService,
            remoteControlService,
            timecodeTagAutomation,
            settingsSyncService,
            songCache,
            userRepository,
            logger,
            TestDispatchers(dispatcher)
        )
        scheduler.runCurrent()
        return model
    }

    private fun authenticate() {
        connection.value = ConnectionState.Authenticated
        scheduler.runCurrent()
    }

    @Test
    fun constructionRegistersTheLocalScrobblersAndStartsEveryServiceOnce() = runTest(dispatcher) {
        createModel()

        verify(exactly = 1) { scrobblerService.registerScrobbler(LocalSongScrobbler::class) }
        verify(exactly = 1) { scrobblerService.registerScrobbler(RecentlyPlayedScrobbler::class) }
        verify(exactly = 1) { presenceService.start() }
        verify(exactly = 1) { changeHub.start() }
        verify(exactly = 1) { queueSyncService.start() }
        verify(exactly = 1) { remoteControlService.start() }
        verify(exactly = 1) { timecodeTagAutomation.start() }
        verify(exactly = 1) { settingsSyncService.start() }
        verify(exactly = 1) { scrobblerService.start() }
    }

    @Test
    fun authenticationLoadsTheUserStoresItAndLoadsItsPlaylists() = runTest(dispatcher) {
        val model = createModel()

        authenticate()

        assertEquals(me, model.user.value)
        assertEquals(firstPlaylists, model.userPlaylists.value)
        assertFalse(model.isRefreshingPlaylists.value)
        coVerify(exactly = 1) { userRepository.saveUser(me) }
        coVerify(exactly = 1) { userPlaylistService.allPlaylists(me.id, 0, Int.MAX_VALUE) }
    }

    @Test
    fun theUserIsNotRequestedWhileTheConnectionIsNotAuthenticated() = runTest(dispatcher) {
        val model = createModel()

        listOf(ConnectionState.LoginRequired, ConnectionState.SetupRequired, ConnectionState.Loading).forEach { state ->
            connection.value = state
            scheduler.runCurrent()
        }

        assertNull(model.user.value)
        assertTrue(model.userPlaylists.value.isEmpty())
        coVerify(exactly = 0) { userService.me() }
        coVerify(exactly = 0) { userPlaylistService.allPlaylists(any(), any(), any()) }
        coVerify(exactly = 0) { userRepository.saveUser(any()) }
    }

    @Test
    fun aFailingUserRequestLeavesNoUserAndEndsThePlaylistRefresh() = runTest(dispatcher) {
        val failure = IllegalStateException("down")
        coEvery { userService.me() } throws failure
        val model = createModel()

        authenticate()

        assertNull(model.user.value)
        assertTrue(model.userPlaylists.value.isEmpty())
        assertFalse(model.isRefreshingPlaylists.value)
        coVerify(exactly = 0) { userRepository.saveUser(any()) }
        coVerify(exactly = 0) { userPlaylistService.allPlaylists(any(), any(), any()) }
        coVerify(exactly = 2) { userService.me() }
        verify(exactly = 2) { logger.error(any(), any(), failure, any()) }
        verify(exactly = 2) { logger.error(any(), any(), any(), any()) }
    }

    @Test
    fun aFailingPlaylistRequestKeepsTheOldPlaylistsAndEndsTheRefresh() = runTest(dispatcher) {
        val model = createModel()
        authenticate()
        val failure = IllegalStateException("down")
        coEvery { userPlaylistService.allPlaylists(any(), any(), any()) } throws failure

        model.refreshPlaylists()
        scheduler.runCurrent()

        assertEquals(firstPlaylists, model.userPlaylists.value)
        assertFalse(model.isRefreshingPlaylists.value)
        verify(exactly = 1) { logger.error(any(), any(), failure, any()) }
        verify(exactly = 1) { logger.error(any(), any(), any(), any()) }
    }

    @Test
    fun thePlaylistRefreshIsFlaggedWhileItsRequestIsPendingAndUnflaggedOnceItReturned() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val model = createModel()
        authenticate()
        assertFalse(model.isRefreshingPlaylists.value)
        coEvery { userPlaylistService.allPlaylists(any(), any(), any()) } coAnswers {
            gate.await()
            PaginatedResponse(secondPlaylists)
        }

        model.refreshPlaylists()
        scheduler.runCurrent()

        assertTrue(model.isRefreshingPlaylists.value)
        assertEquals(firstPlaylists, model.userPlaylists.value)

        gate.complete(Unit)
        scheduler.runCurrent()

        assertFalse(model.isRefreshingPlaylists.value)
        assertEquals(secondPlaylists, model.userPlaylists.value)
    }

    @Test
    fun aPlaylistRefreshWithAKnownUserDoesNotRequestTheUserAgain() = runTest(dispatcher) {
        val model = createModel()
        authenticate()
        coEvery { userPlaylistService.allPlaylists(any(), any(), any()) } returns PaginatedResponse(secondPlaylists)

        model.refreshPlaylists()
        scheduler.runCurrent()

        assertEquals(secondPlaylists, model.userPlaylists.value)
        coVerify(exactly = 1) { userService.me() }
        coVerify(exactly = 2) { userPlaylistService.allPlaylists(me.id, 0, Int.MAX_VALUE) }
    }

    @Test
    fun aPlaylistRefreshWithoutAKnownUserLoadsTheUserFirst() = runTest(dispatcher) {
        val model = createModel()

        model.refreshPlaylists()
        scheduler.runCurrent()

        assertEquals(me, model.user.value)
        assertEquals(firstPlaylists, model.userPlaylists.value)
        coVerifyOrder {
            userService.me()
            userPlaylistService.allPlaylists(me.id, 0, Int.MAX_VALUE)
        }
    }

    @Test
    fun aPlaylistsReloadRequestReloadsThePlaylists() = runTest(dispatcher) {
        val model = createModel()
        authenticate()
        coEvery { userPlaylistService.allPlaylists(any(), any(), any()) } returns PaginatedResponse(secondPlaylists)

        playlistUpdates.tryEmit(PlaylistUpdate.PlaylistsReloadRequired)
        scheduler.runCurrent()

        assertEquals(secondPlaylists, model.userPlaylists.value)
        coVerify(exactly = 2) { userPlaylistService.allPlaylists(me.id, 0, Int.MAX_VALUE) }
    }

    @Test
    fun thePlaylistsAreTheOwnOnesFollowedByTheOnesSharedWithTheUser() = runTest(dispatcher) {
        val shared = listOf(sharedPlaylist("read", PlaylistAccess.READ), sharedPlaylist("write", PlaylistAccess.WRITE))
        coEvery { userPlaylistService.sharedPlaylists(any(), any()) } returns PaginatedResponse(shared)
        val model = createModel()

        authenticate()

        assertEquals(firstPlaylists + shared, model.userPlaylists.value)
        coVerify(exactly = 1) { userPlaylistService.allPlaylists(me.id, 0, Int.MAX_VALUE) }
        coVerify(exactly = 1) { userPlaylistService.sharedPlaylists(0, Int.MAX_VALUE) }
    }

    @Test
    fun aPlaylistsReloadRequestReloadsTheSharedPlaylistsToo() = runTest(dispatcher) {
        val shared = listOf(sharedPlaylist("read", PlaylistAccess.READ))
        val model = createModel()
        authenticate()
        assertEquals(firstPlaylists, model.userPlaylists.value)
        coEvery { userPlaylistService.sharedPlaylists(any(), any()) } returns PaginatedResponse(shared)

        playlistUpdates.tryEmit(PlaylistUpdate.PlaylistsReloadRequired)
        scheduler.runCurrent()

        assertEquals(firstPlaylists + shared, model.userPlaylists.value)
        coVerify(exactly = 2) { userPlaylistService.sharedPlaylists(0, Int.MAX_VALUE) }
    }

    @Test
    fun aFailingSharedPlaylistRequestKeepsTheOldPlaylistsAndEndsTheRefresh() = runTest(dispatcher) {
        val model = createModel()
        authenticate()
        val failure = IllegalStateException("down")
        coEvery { userPlaylistService.allPlaylists(any(), any(), any()) } returns PaginatedResponse(secondPlaylists)
        coEvery { userPlaylistService.sharedPlaylists(any(), any()) } throws failure

        model.refreshPlaylists()
        scheduler.runCurrent()

        assertEquals(firstPlaylists, model.userPlaylists.value)
        assertFalse(model.isRefreshingPlaylists.value)
        verify(exactly = 1) { logger.error(any(), any(), failure, any()) }
    }

    @Test
    fun aSharedPlaylistRequestThatReturnsAfterALogoutLeavesNoPlaylists() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val model = createModel()
        authenticate()
        coEvery { userPlaylistService.sharedPlaylists(any(), any()) } coAnswers {
            gate.await()
            PaginatedResponse(listOf(sharedPlaylist("read", PlaylistAccess.READ)))
        }
        model.refreshPlaylists()
        scheduler.runCurrent()
        assertTrue(model.isRefreshingPlaylists.value)

        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        gate.complete(Unit)
        scheduler.runCurrent()

        assertTrue(model.userPlaylists.value.isEmpty())
        assertFalse(model.isRefreshingPlaylists.value)
    }

    @Test
    fun otherPlaylistUpdatesDoNotReloadThePlaylists() = runTest(dispatcher) {
        val model = createModel()
        authenticate()

        playlistUpdates.tryEmit(PlaylistUpdate.LikedSongsReloadRequired)
        playlistUpdates.tryEmit(PlaylistUpdate.PlaylistContentChanged(firstPlaylists.first().id))
        scheduler.runCurrent()

        assertEquals(firstPlaylists, model.userPlaylists.value)
        coVerify(exactly = 1) { userPlaylistService.allPlaylists(any(), any(), any()) }
    }

    @Test
    fun aFailingUserSaveStillLeavesTheLoadedUserAndItsPlaylists() = runTest(dispatcher) {
        val failure = IllegalStateException("disk full")
        coEvery { userRepository.saveUser(any()) } throws failure
        val model = createModel()

        authenticate()

        assertEquals(me, model.user.value)
        assertEquals(firstPlaylists, model.userPlaylists.value)
        assertFalse(model.isRefreshingPlaylists.value)
        coVerify(exactly = 1) { userRepository.saveUser(me) }
        coVerify(exactly = 1) { userPlaylistService.allPlaylists(me.id, 0, Int.MAX_VALUE) }
        verify(exactly = 1) { logger.error(any(), any(), failure, any()) }
        verify(exactly = 1) { logger.error(any(), any(), any(), any()) }
    }

    @Test
    fun aLoginAsAnotherAccountReplacesTheUserAndLoadsItsPlaylists() = runTest(dispatcher) {
        val other = User(id = randomPlatformUUID(), username = "other")
        coEvery { userPlaylistService.allPlaylists(other.id, any(), any()) } returns PaginatedResponse(secondPlaylists)
        val model = createModel()
        authenticate()
        assertEquals(me, model.user.value)
        assertEquals(firstPlaylists, model.userPlaylists.value)

        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        coEvery { userService.me() } returns other
        authenticate()

        assertEquals(other, model.user.value)
        assertEquals(secondPlaylists, model.userPlaylists.value)
        coVerify(exactly = 1) { userRepository.saveUser(other) }
        coVerify(exactly = 1) { userPlaylistService.allPlaylists(me.id, 0, Int.MAX_VALUE) }
        coVerify(exactly = 1) { userPlaylistService.allPlaylists(other.id, 0, Int.MAX_VALUE) }
    }

    @Test
    fun aLoginAsAnotherAccountWhoseUserRequestIsPendingLoadsNoPlaylistsOfThePreviousAccount() = runTest(dispatcher) {
        val other = User(id = randomPlatformUUID(), username = "other")
        val gate = CompletableDeferred<Unit>()
        coEvery { userPlaylistService.allPlaylists(other.id, any(), any()) } returns PaginatedResponse(secondPlaylists)
        val model = createModel()
        authenticate()
        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        coEvery { userService.me() } coAnswers {
            gate.await()
            other
        }

        authenticate()

        coVerify(exactly = 1) { userPlaylistService.allPlaylists(me.id, 0, Int.MAX_VALUE) }

        gate.complete(Unit)
        scheduler.runCurrent()

        assertEquals(other, model.user.value)
        assertEquals(secondPlaylists, model.userPlaylists.value)
        coVerify(exactly = 1) { userPlaylistService.allPlaylists(me.id, 0, Int.MAX_VALUE) }
        coVerify(exactly = 1) { userPlaylistService.allPlaylists(other.id, 0, Int.MAX_VALUE) }
    }

    @Test
    fun aStoredUserIsAvailableBeforeAnyServerAnswer() = runTest(dispatcher) {
        tokenUserId = me.id.toString()
        coEvery { userRepository.getUser(me.id) } returns storedMe

        val model = createModel()

        assertEquals(storedMe, model.user.value)
        assertTrue(model.userPlaylists.value.isEmpty())
        coVerify(exactly = 0) { userService.me() }
        coVerify(exactly = 0) { userPlaylistService.allPlaylists(any(), any(), any()) }
        coVerify(exactly = 0) { userRepository.saveUser(any()) }
    }

    @Test
    fun aStoredUserStaysWhileTheServerDoesNotAnswer() = runTest(dispatcher) {
        val failure = IllegalStateException("down")
        tokenUserId = me.id.toString()
        coEvery { userRepository.getUser(me.id) } returns storedMe
        coEvery { userService.me() } throws failure
        val model = createModel()

        authenticate()

        assertEquals(storedMe, model.user.value)
        coVerify(exactly = 0) { userRepository.saveUser(any()) }
        verify(exactly = 1) { logger.error(any(), any(), failure, any()) }
        verify(exactly = 1) { logger.error(any(), any(), any(), any()) }
    }

    @Test
    fun aListenInASessionWithoutAServerAnswerIsQueuedUnderTheStoredUser() = runTest(dispatcher) {
        val queueRepository = mockk<ScrobbleQueueRepository>(relaxed = true)
        tokenUserId = me.id.toString()
        coEvery { userRepository.getUser(me.id) } returns storedMe
        coEvery { userService.me() } throws IllegalStateException("down")
        val model = createModel()
        authenticate()
        loadKoinModules(module { single { model } })

        ScrobbleQueue(queueRepository).pushRaw("song", "payload", 1_700_000_000_000L, "server")

        coVerify(exactly = 1) {
            queueRepository.insertRaw(
                userId = me.id,
                songId = "song",
                payload = "payload",
                timestamp = 1_700_000_000_000L,
                target = "server"
            )
        }
    }

    @Test
    fun theServerUserReplacesTheStoredUserOnceItArrives() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        tokenUserId = me.id.toString()
        coEvery { userRepository.getUser(me.id) } returns storedMe
        coEvery { userService.me() } coAnswers {
            gate.await()
            me
        }
        val model = createModel()

        authenticate()

        assertEquals(storedMe, model.user.value)
        coVerify(exactly = 0) { userRepository.saveUser(any()) }

        gate.complete(Unit)
        scheduler.runCurrent()

        assertEquals(me, model.user.value)
        coVerify(exactly = 1) { userRepository.saveUser(me) }
    }

    @Test
    fun withoutAStoredRowNoUserIsKnownBeforeTheServerAnswers() = runTest(dispatcher) {
        tokenUserId = me.id.toString()
        coEvery { userRepository.getUser(me.id) } returns null

        val model = createModel()

        assertNull(model.user.value)
        coVerify(exactly = 0) { userService.me() }
    }

    @Test
    fun aFailingRestoreIsLoggedAndTheServerUserStillLoads() = runTest(dispatcher) {
        val failure = IllegalStateException("locked")
        tokenUserId = me.id.toString()
        coEvery { userRepository.getUser(me.id) } throws failure
        val model = createModel()

        assertNull(model.user.value)
        verify(exactly = 1) { logger.error(any(), any(), failure, any()) }

        coEvery { userRepository.getUser(me.id) } returns null
        authenticate()

        assertEquals(me, model.user.value)
        assertEquals(firstPlaylists, model.userPlaylists.value)
        verify(exactly = 1) { logger.error(any(), any(), any(), any()) }
    }

    @Test
    fun aLogoutClearsTheUserAndItIsNotRestoredAfterwards() = runTest(dispatcher) {
        tokenUserId = me.id.toString()
        coEvery { userRepository.getUser(me.id) } returns storedMe
        val model = createModel()
        authenticate()
        assertEquals(me, model.user.value)

        tokenUserId = null
        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()

        assertNull(model.user.value)

        connection.value = ConnectionState.Loading
        scheduler.runCurrent()

        assertNull(model.user.value)
        coVerify(exactly = 1) { userRepository.getUser(any()) }
    }

    @Test
    fun aDifferentAccountLoggingInNeverSeesThePreviousOne() = runTest(dispatcher) {
        val other = User(id = randomPlatformUUID(), username = "other")
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<User?>()
        tokenUserId = me.id.toString()
        coEvery { userRepository.getUser(me.id) } returns storedMe
        coEvery { userRepository.getUser(other.id) } returns null
        coEvery { userPlaylistService.allPlaylists(other.id, any(), any()) } returns PaginatedResponse(secondPlaylists)
        val model = createModel()
        authenticate()
        assertEquals(me, model.user.value)

        tokenUserId = null
        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        val observer = launch { model.user.collect { seen += it } }
        scheduler.runCurrent()
        coEvery { userService.me() } coAnswers {
            gate.await()
            other
        }
        tokenUserId = other.id.toString()

        authenticate()

        assertNull(model.user.value)

        gate.complete(Unit)
        scheduler.runCurrent()
        observer.cancel()

        assertEquals(other, model.user.value)
        assertEquals(listOf(null, other), seen)
        assertEquals(secondPlaylists, model.userPlaylists.value)
        coVerify(exactly = 1) { userPlaylistService.allPlaylists(me.id, 0, Int.MAX_VALUE) }
        coVerify(exactly = 1) { userPlaylistService.allPlaylists(other.id, 0, Int.MAX_VALUE) }
    }

    @Test
    fun aReturningAccountIsRestoredFromItsOwnRowAfterASwitch() = runTest(dispatcher) {
        val other = User(id = randomPlatformUUID(), username = "other")
        val storedOther = other.copy(displayName = "Stored other")
        val gate = CompletableDeferred<Unit>()
        tokenUserId = me.id.toString()
        coEvery { userRepository.getUser(me.id) } returns storedMe
        coEvery { userRepository.getUser(other.id) } returns storedOther
        val model = createModel()
        authenticate()

        tokenUserId = null
        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        coEvery { userService.me() } coAnswers {
            gate.await()
            other
        }
        tokenUserId = other.id.toString()

        authenticate()

        assertEquals(storedOther, model.user.value)

        gate.complete(Unit)
        scheduler.runCurrent()

        assertEquals(other, model.user.value)
    }

    @Test
    fun aLogoutClearsThePlaylists() = runTest(dispatcher) {
        val model = createModel()
        authenticate()
        assertEquals(firstPlaylists, model.userPlaylists.value)

        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()

        assertTrue(model.userPlaylists.value.isEmpty())
        assertNull(model.user.value)
    }

    @Test
    fun aUserRequestThatReturnsAfterALogoutLeavesNoUser() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { userService.me() } coAnswers {
            gate.await()
            me
        }
        val model = createModel()
        authenticate()
        coVerify(exactly = 2) { userService.me() }

        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        gate.complete(Unit)
        scheduler.runCurrent()

        assertNull(model.user.value)
        assertTrue(model.userPlaylists.value.isEmpty())
        assertFalse(model.isRefreshingPlaylists.value)
        coVerify(exactly = 0) { userRepository.saveUser(any()) }
        coVerify(exactly = 0) { userPlaylistService.allPlaylists(any(), any(), any()) }
    }

    @Test
    fun aPlaylistRequestThatReturnsAfterALogoutLeavesNoPlaylists() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val model = createModel()
        authenticate()
        coEvery { userPlaylistService.allPlaylists(any(), any(), any()) } coAnswers {
            gate.await()
            PaginatedResponse(secondPlaylists)
        }
        model.refreshPlaylists()
        scheduler.runCurrent()
        assertTrue(model.isRefreshingPlaylists.value)

        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        gate.complete(Unit)
        scheduler.runCurrent()

        assertTrue(model.userPlaylists.value.isEmpty())
        assertNull(model.user.value)
        assertFalse(model.isRefreshingPlaylists.value)
    }

    @Test
    fun aUserRequestOfThePreviousAccountThatReturnsAfterTheNextLoginIsDropped() = runTest(dispatcher) {
        val other = User(id = randomPlatformUUID(), username = "other")
        val gate = CompletableDeferred<Unit>()
        coEvery { userPlaylistService.allPlaylists(other.id, any(), any()) } returns PaginatedResponse(secondPlaylists)
        coEvery { userService.me() } coAnswers {
            gate.await()
            me
        }
        val model = createModel()
        authenticate()

        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        coEvery { userService.me() } returns other
        authenticate()
        assertEquals(other, model.user.value)
        assertEquals(secondPlaylists, model.userPlaylists.value)

        gate.complete(Unit)
        scheduler.runCurrent()

        assertEquals(other, model.user.value)
        assertEquals(secondPlaylists, model.userPlaylists.value)
        assertFalse(model.isRefreshingPlaylists.value)
        coVerify(exactly = 0) { userRepository.saveUser(me) }
        coVerify(exactly = 1) { userRepository.saveUser(other) }
        coVerify(exactly = 0) { userPlaylistService.allPlaylists(me.id, any(), any()) }
        coVerify(exactly = 1) { userPlaylistService.allPlaylists(other.id, 0, Int.MAX_VALUE) }
    }

    @Test
    fun aPlaylistRequestOfThePreviousAccountThatReturnsAfterTheNextLoginIsDropped() = runTest(dispatcher) {
        val other = User(id = randomPlatformUUID(), username = "other")
        val gate = CompletableDeferred<Unit>()
        val model = createModel()
        authenticate()
        coEvery { userPlaylistService.allPlaylists(me.id, any(), any()) } coAnswers {
            gate.await()
            PaginatedResponse(firstPlaylists)
        }
        coEvery { userPlaylistService.allPlaylists(other.id, any(), any()) } returns PaginatedResponse(secondPlaylists)
        model.refreshPlaylists()
        scheduler.runCurrent()

        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        coEvery { userService.me() } returns other
        authenticate()
        assertEquals(secondPlaylists, model.userPlaylists.value)

        gate.complete(Unit)
        scheduler.runCurrent()

        assertEquals(other, model.user.value)
        assertEquals(secondPlaylists, model.userPlaylists.value)
        assertFalse(model.isRefreshingPlaylists.value)
    }

    @Test
    fun enabledScrobblerFlagsRegisterTheirScrobblers() = runTest(dispatcher) {
        setScrobblerFlags(server = true, listenBrainz = true, lastFm = true, discord = true)

        createModel()

        verify(exactly = 1) { scrobblerService.registerScrobbler(ServerScrobbler::class) }
        verify(exactly = 1) { scrobblerService.registerScrobbler(ListenBrainzScrobbler::class) }
        verify(exactly = 1) { scrobblerService.registerScrobbler(LastFmScrobbler::class) }
        verify(exactly = 1) { scrobblerService.registerScrobbler(DiscordScrobbler::class) }
        verify(exactly = 0) { scrobblerService.unregisterScrobbler(ServerScrobbler::class) }
        verify(exactly = 0) { scrobblerService.unregisterScrobbler(ListenBrainzScrobbler::class) }
        verify(exactly = 0) { scrobblerService.unregisterScrobbler(LastFmScrobbler::class) }
        verify(exactly = 0) { scrobblerService.unregisterScrobbler(DiscordScrobbler::class) }
    }

    @Test
    fun disabledScrobblerFlagsUnregisterTheirScrobblers() = runTest(dispatcher) {
        setScrobblerFlags(server = false, listenBrainz = false, lastFm = false, discord = false)

        createModel()

        verify(exactly = 0) { scrobblerService.registerScrobbler(ServerScrobbler::class) }
        verify(exactly = 0) { scrobblerService.registerScrobbler(ListenBrainzScrobbler::class) }
        verify(exactly = 0) { scrobblerService.registerScrobbler(LastFmScrobbler::class) }
        verify(exactly = 0) { scrobblerService.registerScrobbler(DiscordScrobbler::class) }
        verify(exactly = 1) { scrobblerService.unregisterScrobbler(ServerScrobbler::class) }
        verify(exactly = 1) { scrobblerService.unregisterScrobbler(ListenBrainzScrobbler::class) }
        verify(exactly = 1) { scrobblerService.unregisterScrobbler(LastFmScrobbler::class) }
        verify(exactly = 1) { scrobblerService.unregisterScrobbler(DiscordScrobbler::class) }
    }

    @Test
    fun enablingTheServerScrobblerFlagRegistersItAndDisablingUnregistersIt() = runTest(dispatcher) {
        createModel()

        Config.setIsServerScrobblingEnabled(true)
        scheduler.runCurrent()
        verify(exactly = 1) { scrobblerService.registerScrobbler(ServerScrobbler::class) }

        Config.setIsServerScrobblingEnabled(false)
        scheduler.runCurrent()
        verify(exactly = 2) { scrobblerService.unregisterScrobbler(ServerScrobbler::class) }
        verify(exactly = 1) { scrobblerService.registerScrobbler(ServerScrobbler::class) }
    }

    @Test
    fun enablingTheListenBrainzFlagRegistersItAndDisablingUnregistersIt() = runTest(dispatcher) {
        createModel()

        Config.setIsListenBrainzEnabled(true)
        scheduler.runCurrent()
        verify(exactly = 1) { scrobblerService.registerScrobbler(ListenBrainzScrobbler::class) }

        Config.setIsListenBrainzEnabled(false)
        scheduler.runCurrent()
        verify(exactly = 2) { scrobblerService.unregisterScrobbler(ListenBrainzScrobbler::class) }
        verify(exactly = 1) { scrobblerService.registerScrobbler(ListenBrainzScrobbler::class) }
    }

    @Test
    fun enablingTheLastFmFlagRegistersItAndDisablingUnregistersIt() = runTest(dispatcher) {
        createModel()

        Config.setIsLastFmEnabled(true)
        scheduler.runCurrent()
        verify(exactly = 1) { scrobblerService.registerScrobbler(LastFmScrobbler::class) }

        Config.setIsLastFmEnabled(false)
        scheduler.runCurrent()
        verify(exactly = 2) { scrobblerService.unregisterScrobbler(LastFmScrobbler::class) }
        verify(exactly = 1) { scrobblerService.registerScrobbler(LastFmScrobbler::class) }
    }

    @Test
    fun enablingTheDiscordFlagRegistersItAndDisablingUnregistersIt() = runTest(dispatcher) {
        createModel()

        Config.setIsDiscordRpcEnabled(true)
        scheduler.runCurrent()
        verify(exactly = 1) { scrobblerService.registerScrobbler(DiscordScrobbler::class) }

        Config.setIsDiscordRpcEnabled(false)
        scheduler.runCurrent()
        verify(exactly = 2) { scrobblerService.unregisterScrobbler(DiscordScrobbler::class) }
        verify(exactly = 1) { scrobblerService.registerScrobbler(DiscordScrobbler::class) }
    }

    @Test
    fun settingTheDisplayNameCallsTheServiceAndThenRefreshesTheUser() = runTest(dispatcher) {
        val model = createModel()

        model.setDisplayName("New Name")
        scheduler.runCurrent()

        coVerifyOrder {
            userService.setDisplayName("New Name")
            userService.me()
        }
        assertEquals(me, model.user.value)
        coVerify(exactly = 1) { userRepository.saveUser(me) }
    }

    @Test
    fun aFailingDisplayNameChangeDoesNotRefreshTheUser() = runTest(dispatcher) {
        val failure = IllegalStateException("rejected")
        coEvery { userService.setDisplayName(any()) } throws failure
        val model = createModel()

        model.setDisplayName("New Name")
        scheduler.runCurrent()

        coVerify(exactly = 1) { userService.setDisplayName("New Name") }
        coVerify(exactly = 0) { userService.me() }
        assertNull(model.user.value)
        verify(exactly = 1) { logger.error(any(), any(), failure, any()) }
        verify(exactly = 1) { logger.error(any(), any(), any(), any()) }
    }

    @Test
    fun settingTheProfileImageCallsTheServiceAndThenRefreshesTheUser() = runTest(dispatcher) {
        val model = createModel()
        val bytes = byteArrayOf(1, 2, 3)

        model.setProfileImage(bytes)
        scheduler.runCurrent()

        coVerifyOrder {
            userService.setProfileImage(bytes)
            userService.me()
        }
        assertEquals(me, model.user.value)
        coVerify(exactly = 1) { userRepository.saveUser(me) }
    }

    @Test
    fun aFailingProfileImageChangeDoesNotRefreshTheUser() = runTest(dispatcher) {
        val failure = IllegalStateException("rejected")
        coEvery { userService.setProfileImage(any()) } throws failure
        val model = createModel()
        val bytes = byteArrayOf(1, 2, 3)

        model.setProfileImage(bytes)
        scheduler.runCurrent()

        coVerify(exactly = 1) { userService.setProfileImage(bytes) }
        coVerify(exactly = 0) { userService.me() }
        assertNull(model.user.value)
        verify(exactly = 1) { logger.error(any(), any(), failure, any()) }
        verify(exactly = 1) { logger.error(any(), any(), any(), any()) }
    }

    @Test
    fun collapsingThePlayerClosesQueueLyricsAndTags() = runTest(dispatcher) {
        val model = createModel()

        model.setQueueExpanded(true)
        model.setPlayerExpanded(false)
        assertFalse(model.isPlayerExpanded.value)
        assertFalse(model.isQueueExpanded.value)

        model.setLyricsExpanded(true)
        model.setPlayerExpanded(false)
        assertFalse(model.isLyricsExpanded.value)

        model.setTagsExpanded(true)
        model.setPlayerExpanded(false)
        assertFalse(model.isTagsExpanded.value)
        assertFalse(model.isPlayerExpanded.value)
    }

    @Test
    fun expandingThePlayerAloneOpensNoPanel() = runTest(dispatcher) {
        val model = createModel()

        model.setPlayerExpanded(true)

        assertTrue(model.isPlayerExpanded.value)
        assertFalse(model.isQueueExpanded.value)
        assertFalse(model.isLyricsExpanded.value)
        assertFalse(model.isTagsExpanded.value)
    }

    @Test
    fun expandingTheQueueOpensThePlayerAndClosesLyricsAndTags() = runTest(dispatcher) {
        val model = createModel()
        model.setLyricsExpanded(true)

        model.setQueueExpanded(true)

        assertTrue(model.isPlayerExpanded.value)
        assertTrue(model.isQueueExpanded.value)
        assertFalse(model.isLyricsExpanded.value)
        assertFalse(model.isTagsExpanded.value)

        model.setTagsExpanded(true)
        model.setQueueExpanded(true)

        assertTrue(model.isQueueExpanded.value)
        assertFalse(model.isTagsExpanded.value)
    }

    @Test
    fun expandingTheLyricsOpensThePlayerAndClosesQueueAndTags() = runTest(dispatcher) {
        val model = createModel()
        model.setQueueExpanded(true)

        model.setLyricsExpanded(true)

        assertTrue(model.isPlayerExpanded.value)
        assertTrue(model.isLyricsExpanded.value)
        assertFalse(model.isQueueExpanded.value)
        assertFalse(model.isTagsExpanded.value)

        model.setTagsExpanded(true)
        model.setLyricsExpanded(true)

        assertTrue(model.isLyricsExpanded.value)
        assertFalse(model.isTagsExpanded.value)
    }

    @Test
    fun expandingTheTagsOpensThePlayerAndClosesQueueAndLyrics() = runTest(dispatcher) {
        val model = createModel()
        model.setQueueExpanded(true)

        model.setTagsExpanded(true)

        assertTrue(model.isPlayerExpanded.value)
        assertTrue(model.isTagsExpanded.value)
        assertFalse(model.isQueueExpanded.value)
        assertFalse(model.isLyricsExpanded.value)

        model.setLyricsExpanded(true)
        model.setTagsExpanded(true)

        assertTrue(model.isTagsExpanded.value)
        assertFalse(model.isLyricsExpanded.value)
    }

    @Test
    fun closingAPanelLeavesThePlayerExpanded() = runTest(dispatcher) {
        val model = createModel()

        model.setQueueExpanded(true)
        model.setQueueExpanded(false)
        assertFalse(model.isQueueExpanded.value)
        assertTrue(model.isPlayerExpanded.value)

        model.setLyricsExpanded(true)
        model.setLyricsExpanded(false)
        assertFalse(model.isLyricsExpanded.value)
        assertTrue(model.isPlayerExpanded.value)

        model.setTagsExpanded(true)
        model.setTagsExpanded(false)
        assertFalse(model.isTagsExpanded.value)
        assertTrue(model.isPlayerExpanded.value)
    }

    @Test
    fun theDialogCounterNeverDropsBelowZeroAndDrivesTheOverlayState() = runTest(dispatcher) {
        val model = createModel()

        model.decrementDialogCount()
        scheduler.runCurrent()
        assertEquals(0, model.openDialogsCount.value)
        assertFalse(model.isAnyDialogOpen.value)
        assertFalse(model.isAnyOverlayOpen.value)

        model.incrementDialogCount()
        model.incrementDialogCount()
        scheduler.runCurrent()
        assertEquals(2, model.openDialogsCount.value)
        assertTrue(model.isAnyDialogOpen.value)
        assertFalse(model.isAnyMenuOpen.value)
        assertTrue(model.isAnyOverlayOpen.value)

        model.decrementDialogCount()
        scheduler.runCurrent()
        assertTrue(model.isAnyOverlayOpen.value)

        model.decrementDialogCount()
        model.decrementDialogCount()
        scheduler.runCurrent()
        assertEquals(0, model.openDialogsCount.value)
        assertFalse(model.isAnyDialogOpen.value)
        assertFalse(model.isAnyOverlayOpen.value)
    }

    @Test
    fun theMenuCounterNeverDropsBelowZeroAndDrivesTheOverlayState() = runTest(dispatcher) {
        val model = createModel()

        model.decrementMenuCount()
        scheduler.runCurrent()
        assertEquals(0, model.openMenusCount.value)
        assertFalse(model.isAnyMenuOpen.value)
        assertFalse(model.isAnyOverlayOpen.value)

        model.incrementMenuCount()
        scheduler.runCurrent()
        assertEquals(1, model.openMenusCount.value)
        assertTrue(model.isAnyMenuOpen.value)
        assertFalse(model.isAnyDialogOpen.value)
        assertTrue(model.isAnyOverlayOpen.value)

        model.decrementMenuCount()
        model.decrementMenuCount()
        scheduler.runCurrent()
        assertEquals(0, model.openMenusCount.value)
        assertFalse(model.isAnyMenuOpen.value)
        assertFalse(model.isAnyOverlayOpen.value)
    }

    @Test
    fun theOverlayStaysOpenWhileEitherADialogOrAMenuIsOpen() = runTest(dispatcher) {
        val model = createModel()

        model.incrementDialogCount()
        model.incrementMenuCount()
        scheduler.runCurrent()
        assertTrue(model.isAnyOverlayOpen.value)

        model.decrementDialogCount()
        scheduler.runCurrent()
        assertTrue(model.isAnyOverlayOpen.value)

        model.decrementMenuCount()
        scheduler.runCurrent()
        assertFalse(model.isAnyOverlayOpen.value)
    }
}
