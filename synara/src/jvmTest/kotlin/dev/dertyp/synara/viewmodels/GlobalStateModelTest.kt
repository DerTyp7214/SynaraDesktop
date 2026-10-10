package dev.dertyp.synara.viewmodels

import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.Config
import dev.dertyp.synara.db.DatabaseMigrationRepository
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
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
    private lateinit var migrationRepository: DatabaseMigrationRepository
    private lateinit var userRepository: UserRepository

    private fun playlist(name: String) = UserPlaylist(
        id = randomPlatformUUID(),
        name = name,
        songs = emptyList(),
        creator = me.id,
        description = ""
    )

    @BeforeTest
    fun setUp() {
        startTestKoin()
        setScrobblerFlags(server = false, listenBrainz = false, lastFm = false, discord = false)
        Config.setNeedsUserIdMigration(false)

        rpcServiceManager = mockk { every { connectionState } returns connection }
        userService = mockk {
            coEvery { me() } returns me
            coEvery { setDisplayName(any()) } returns Unit
            coEvery { setProfileImage(any()) } returns Unit
        }
        userPlaylistService = mockk {
            coEvery { allPlaylists(any(), any(), any()) } returns PaginatedResponse(firstPlaylists)
        }
        scrobblerService = mockk(relaxed = true)
        presenceService = mockk(relaxed = true)
        changeHub = mockk(relaxed = true)
        queueSyncService = mockk(relaxed = true)
        remoteControlService = mockk(relaxed = true)
        timecodeTagAutomation = mockk(relaxed = true)
        settingsSyncService = mockk(relaxed = true)
        songCache = mockk { every { playlistUpdates } returns this@GlobalStateModelTest.playlistUpdates }
        migrationRepository = mockk(relaxed = true)
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
            migrationRepository,
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
    fun aPendingUserIdMigrationRunsOnceAndClearsTheFlag() = runTest(dispatcher) {
        Config.setNeedsUserIdMigration(true)
        val model = createModel()

        authenticate()
        model.refreshUser()
        scheduler.runCurrent()

        coVerify(exactly = 1) { migrationRepository.migrateUserIds(me.id) }
        assertFalse(Config.needsUserIdMigration.value)
    }

    @Test
    fun noUserIdMigrationRunsWhenNoneIsPending() = runTest(dispatcher) {
        Config.setNeedsUserIdMigration(false)
        createModel()

        authenticate()

        coVerify(exactly = 0) { migrationRepository.migrateUserIds(any()) }
        assertFalse(Config.needsUserIdMigration.value)
    }

    @Test
    fun aFailingUserIdMigrationKeepsTheFlagAndIsRetriedWithTheNextUserRefresh() = runTest(dispatcher) {
        val failure = IllegalStateException("locked")
        Config.setNeedsUserIdMigration(true)
        coEvery { migrationRepository.migrateUserIds(any()) } throws failure
        val model = createModel()

        authenticate()

        assertTrue(Config.needsUserIdMigration.value)
        assertEquals(me, model.user.value)
        assertEquals(firstPlaylists, model.userPlaylists.value)
        coVerify(exactly = 1) { migrationRepository.migrateUserIds(me.id) }
        verify(exactly = 1) { logger.error(any(), any(), failure, any()) }
        verify(exactly = 1) { logger.error(any(), any(), any(), any()) }

        coEvery { migrationRepository.migrateUserIds(any()) } returns Unit
        model.refreshUser()
        scheduler.runCurrent()

        assertFalse(Config.needsUserIdMigration.value)
        coVerify(exactly = 2) { migrationRepository.migrateUserIds(me.id) }
        verify(exactly = 1) { logger.error(any(), any(), any(), any()) }
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
