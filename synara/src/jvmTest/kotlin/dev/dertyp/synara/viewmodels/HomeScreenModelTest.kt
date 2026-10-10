package dev.dertyp.synara.viewmodels

import dev.dertyp.data.ServerStats
import dev.dertyp.logging.Logger
import dev.dertyp.synara.db.RecentlyPlayedRepository
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.ServerStatsServiceWrapper
import dev.dertyp.synara.utils.SynaraDispatchers
import io.mockk.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.error_request_failed
import synara.synara.generated.resources.error_server_unreachable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HomeScreenModelTest {

    private object UnconfinedDispatchers : SynaraDispatchers {
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val stats = mockk<ServerStats>()
    private val serverStatsService = mockk<ServerStatsServiceWrapper>()
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }
    private val globalState = mockk<GlobalStateModel> {
        every { user } returns MutableStateFlow(null)
    }

    private fun createModel() = HomeScreenModel(
        rpcServiceManager,
        serverStatsService,
        globalState,
        UnconfinedDispatchers,
        mockk<RecentlyPlayedRepository>(relaxed = true),
        mockk<Logger>(relaxed = true)
    )

    @Test
    fun aFailedFetchShowsTheMappedErrorAndRetryLoadsTheStats() {
        coEvery { serverStatsService.getStats() } throws IOException("down")
        val model = createModel()

        val failed = assertIs<ServerStatsState.Failed>(model.serverStats.value)
        assertEquals(Res.string.error_server_unreachable, failed.message)

        coEvery { serverStatsService.getStats() } returns stats
        model.loadStats()

        assertEquals(ServerStatsState.Loaded(stats), model.serverStats.value)
    }

    @Test
    fun anOtherFailureMapsToTheGenericMessage() {
        coEvery { serverStatsService.getStats() } throws IllegalArgumentException("bad")
        val model = createModel()

        assertEquals(Res.string.error_request_failed, assertIs<ServerStatsState.Failed>(model.serverStats.value).message)
    }
}
