package dev.dertyp.synara.services

import dev.dertyp.data.RecommendationWindow
import dev.dertyp.logging.Logger
import dev.dertyp.services.IListeningStatsService
import dev.dertyp.services.IRecommendationService
import dev.dertyp.synara.Config
import dev.dertyp.synara.mixes.MixSource
import dev.dertyp.synara.mixes.MixSpec
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.RpcServiceManager.ConnectionState
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSongs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MixDownloadSyncTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatchers = TestDispatchers(StandardTestDispatcher(scheduler))

    private val weekMix = MixSpec.YourMix(MixSource.Recent(RecommendationWindow.WEEK), 50)
    private val chill = MixSpec.YourMix(MixSource.Mood("chill"), 10)
    private val weekSongs = testSongs(3)
    private val chillSongs = testSongs(2)

    private val reachable = MutableStateFlow(true)
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Loading)

    private val downloadManager = mockk<IDownloadManager>(relaxed = true)
    private val recommendationService = mockk<IRecommendationService>()
    private val listeningStatsService = mockk<IListeningStatsService>()
    private val rpcServiceManager = mockk<RpcServiceManager>()

    private lateinit var sync: MixDownloadSync

    @BeforeTest
    fun setUp() {
        startTestKoin()
        ConfigFixture.resetToDefaults()
        every { rpcServiceManager.isServerReachable } returns reachable
        every { rpcServiceManager.connectionState } returns connection
        coEvery { recommendationService.getMix(RecommendationWindow.WEEK, 50) } returns weekSongs
        coEvery { recommendationService.getMoodPlaylist("chill", 10) } returns chillSongs
        sync = MixDownloadSync(
            downloadManager,
            recommendationService,
            listeningStatsService,
            rpcServiceManager,
            mockk<Logger>(relaxed = true),
            dispatchers
        )
    }

    @AfterTest
    fun tearDown() {
        sync.stop()
        scheduler.runCurrent()
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    private fun startSync() {
        sync.start()
        scheduler.runCurrent()
    }

    private fun setMixes(vararg mixes: MixSpec) {
        Config.setDownloadedMixes(mixes.toList())
        scheduler.runCurrent()
    }

    private fun setConnection(state: ConnectionState) {
        connection.value = state
        scheduler.runCurrent()
    }

    @Test
    fun downloadedMixesAreRefreshedOnceAuthenticated() {
        Config.setDownloadedMixes(listOf(weekMix, chill))
        startSync()

        coVerify(exactly = 0) { recommendationService.getMix(any(), any()) }
        verify(exactly = 0) { downloadManager.downloadMix(any(), any()) }

        setConnection(ConnectionState.Authenticated)

        verify(exactly = 1) { downloadManager.downloadMix(weekMix.key, weekSongs) }
        verify(exactly = 1) { downloadManager.downloadMix(chill.key, chillSongs) }
        verify(exactly = 0) { downloadManager.removeMix(any()) }
    }

    @Test
    fun downloadedMixesAreRefreshedAgainAfterAReconnect() {
        Config.setDownloadedMixes(listOf(weekMix))
        connection.value = ConnectionState.Authenticated
        startSync()
        verify(exactly = 1) { downloadManager.downloadMix(weekMix.key, weekSongs) }

        setConnection(ConnectionState.LoginRequired)
        verify(exactly = 1) { downloadManager.downloadMix(weekMix.key, weekSongs) }

        setConnection(ConnectionState.Authenticated)
        verify(exactly = 2) { downloadManager.downloadMix(weekMix.key, weekSongs) }
    }

    @Test
    fun downloadedMixesAreRefreshedPeriodically() {
        Config.setDownloadedMixes(listOf(weekMix))
        connection.value = ConnectionState.Authenticated
        startSync()

        scheduler.advanceTimeBy(MixDownloadSync.REFRESH_INTERVAL.inWholeMilliseconds - 1)
        scheduler.runCurrent()
        verify(exactly = 1) { downloadManager.downloadMix(weekMix.key, weekSongs) }

        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        verify(exactly = 2) { downloadManager.downloadMix(weekMix.key, weekSongs) }
    }

    @Test
    fun noRefreshWhileTheServerIsUnreachable() {
        Config.setDownloadedMixes(listOf(weekMix))
        reachable.value = false
        connection.value = ConnectionState.Authenticated
        startSync()

        coVerify(exactly = 0) { recommendationService.getMix(any(), any()) }

        reachable.value = true
        scheduler.runCurrent()

        verify(exactly = 1) { downloadManager.downloadMix(weekMix.key, weekSongs) }
    }

    @Test
    fun addedMixesAreDownloadedAndRemovedMixesAreDropped() {
        Config.setDownloadedMixes(listOf(weekMix))
        startSync()

        setMixes(weekMix, chill)

        coVerify(exactly = 0) { recommendationService.getMix(any(), any()) }
        verify(exactly = 1) { downloadManager.downloadMix(chill.key, chillSongs) }
        verify(exactly = 0) { downloadManager.removeMix(any()) }

        setMixes(chill)

        verify(exactly = 1) { downloadManager.removeMix(weekMix.key) }
        verify(exactly = 0) { downloadManager.removeMix(chill.key) }
        verify(exactly = 1) { downloadManager.downloadMix(chill.key, chillSongs) }
    }

    @Test
    fun aResizedMixIsFetchedAgain() {
        Config.setDownloadedMixes(listOf(weekMix))
        startSync()
        val resized = weekMix.copy(size = 25)
        val resizedSongs = testSongs(1)
        coEvery { recommendationService.getMix(RecommendationWindow.WEEK, 25) } returns resizedSongs

        setMixes(resized)

        verify(exactly = 1) { downloadManager.downloadMix(weekMix.key, resizedSongs) }
        verify(exactly = 0) { downloadManager.removeMix(any()) }
    }

    @Test
    fun aFailedFetchStoresNothingAndKeepsTheSyncRunning() {
        Config.setDownloadedMixes(listOf(weekMix))
        startSync()
        coEvery { recommendationService.getMoodPlaylist("chill", 10) } throws IOException("offline")

        setMixes(weekMix, chill)

        verify(exactly = 0) { downloadManager.downloadMix(any(), any()) }

        setMixes(weekMix)
        verify(exactly = 1) { downloadManager.removeMix(chill.key) }
    }

    @Test
    fun aMixRemovedDuringItsFetchIsNotStored() {
        Config.setDownloadedMixes(listOf(weekMix))
        startSync()
        coEvery { recommendationService.getMoodPlaylist("chill", 10) } coAnswers {
            Config.setDownloadedMixes(listOf(weekMix))
            chillSongs
        }

        setMixes(weekMix, chill)

        verify(exactly = 0) { downloadManager.downloadMix(chill.key, any()) }
    }
}
