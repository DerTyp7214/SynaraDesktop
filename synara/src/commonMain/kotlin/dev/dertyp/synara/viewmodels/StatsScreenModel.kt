package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.LinkUnmatchedTrackRequest
import dev.dertyp.data.LinkUnmatchedTrackResult
import dev.dertyp.data.ListeningStats
import dev.dertyp.data.StatsRange
import dev.dertyp.data.TopOrder
import dev.dertyp.data.UserSong
import dev.dertyp.services.IListeningStatsService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.synara.utils.currentTimezoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

enum class StatsPeriod(val supportsLast: Boolean) {
    DAY(false), WEEK(true), MONTH(true), YEAR(true), ALL_TIME(false)
}

fun StatsRange.toPeriod(): StatsPeriod = when (this) {
    StatsRange.DAY -> StatsPeriod.DAY
    StatsRange.WEEK, StatsRange.LAST_WEEK -> StatsPeriod.WEEK
    StatsRange.MONTH, StatsRange.LAST_MONTH -> StatsPeriod.MONTH
    StatsRange.YEAR, StatsRange.LAST_YEAR -> StatsPeriod.YEAR
    StatsRange.ALL_TIME -> StatsPeriod.ALL_TIME
}

fun StatsRange.isLastPeriod(): Boolean =
    this == StatsRange.LAST_WEEK || this == StatsRange.LAST_MONTH || this == StatsRange.LAST_YEAR

fun StatsPeriod.toRange(last: Boolean): StatsRange = when (this) {
    StatsPeriod.DAY -> StatsRange.DAY
    StatsPeriod.WEEK -> if (last) StatsRange.LAST_WEEK else StatsRange.WEEK
    StatsPeriod.MONTH -> if (last) StatsRange.LAST_MONTH else StatsRange.MONTH
    StatsPeriod.YEAR -> if (last) StatsRange.LAST_YEAR else StatsRange.YEAR
    StatsPeriod.ALL_TIME -> StatsRange.ALL_TIME
}

class StatsScreenModel(
    private val listeningStatsService: IListeningStatsService,
    private val songService: ISongService,
    private val rpcServiceManager: RpcServiceManager,
    private val dispatchers: SynaraDispatchers
) : StateScreenModel<StatsScreenModel.StatsState>(StatsState()), Refreshable {

    private val refresher = RefreshCoalescer(screenModelScope, dispatchers.io) {
        fetchStats(state.value.selectedRange, state.value.topOrder)
    }
    override val isRefreshing = refresher.isRefreshing

    override fun refresh() {
        refresher.refresh()
    }

    data class StatsState(
        val stats: ListeningStats? = null,
        val selectedRange: StatsRange = StatsRange.WEEK,
        val topOrder: TopOrder = TopOrder.LISTEN_COUNT,
        val isLoading: Boolean = false,
        val error: String? = null,
        val linkSearchResults: List<UserSong> = emptyList(),
    )

    init {
        load(StatsRange.WEEK, TopOrder.LISTEN_COUNT)
    }

    fun load(range: StatsRange, topOrder: TopOrder = state.value.topOrder) {
        screenModelScope.launch(dispatchers.io) {
            mutableState.update { it.copy(selectedRange = range, topOrder = topOrder, isLoading = true, error = null) }
            fetchStats(range, topOrder)
        }
    }

    private suspend fun fetchStats(range: StatsRange, topOrder: TopOrder) {
        try {
            rpcServiceManager.awaitAuthentication()
            val stats = listeningStatsService.getStats(range, currentTimezoneId(), topLimit = 50, topOrder = topOrder)
            mutableState.update {
                if (it.selectedRange == range && it.topOrder == topOrder) it.copy(stats = stats, isLoading = false) else it
            }
        } catch (e: Exception) {
            mutableState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
        }
    }

    fun reload() = load(state.value.selectedRange, state.value.topOrder)

    fun selectPeriod(period: StatsPeriod) {
        val last = period.supportsLast && state.value.selectedRange.isLastPeriod()
        load(period.toRange(last))
    }

    fun setLast(last: Boolean) {
        val period = state.value.selectedRange.toPeriod()
        if (!period.supportsLast) return
        load(period.toRange(last))
    }

    fun setTopOrder(order: TopOrder) {
        load(state.value.selectedRange, order)
    }

    private var linkSearchJob: Job? = null

    fun searchLinkTarget(query: String) {
        linkSearchJob?.cancel()
        if (query.length < 2) {
            mutableState.update { it.copy(linkSearchResults = emptyList()) }
            return
        }
        linkSearchJob = screenModelScope.launch(dispatchers.io) {
            delay(300.milliseconds)
            try {
                val songs = songService.rankedSearch(0, 20, query, explicit = true).data
                mutableState.update { it.copy(linkSearchResults = songs) }
            } catch (_: Exception) {
            }
        }
    }

    fun clearLinkSearch() {
        linkSearchJob?.cancel()
        mutableState.update { it.copy(linkSearchResults = emptyList()) }
    }

    suspend fun linkUnmatchedTrack(
        songId: PlatformUUID,
        recordingMsid: PlatformUUID?,
        recordingMbid: PlatformUUID?
    ): LinkUnmatchedTrackResult? {
        return try {
            val result = listeningStatsService.linkUnmatchedTrack(
                LinkUnmatchedTrackRequest(
                    songId = songId,
                    recordingMsid = recordingMsid,
                    recordingMbid = recordingMbid
                )
            )
            reload()
            result
        } catch (e: Exception) {
            mutableState.update { it.copy(error = e.message) }
            null
        }
    }
}
