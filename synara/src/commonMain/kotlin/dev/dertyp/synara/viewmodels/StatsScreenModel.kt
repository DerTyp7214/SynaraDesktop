package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.LinkUnmatchedTrackRequest
import dev.dertyp.data.LinkUnmatchedTrackResult
import dev.dertyp.data.ListeningStats
import dev.dertyp.data.StatsRange
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

class StatsScreenModel(
    private val listeningStatsService: IListeningStatsService,
    private val songService: ISongService,
    private val rpcServiceManager: RpcServiceManager,
    private val dispatchers: SynaraDispatchers
) : StateScreenModel<StatsScreenModel.StatsState>(StatsState()) {

    data class StatsState(
        val stats: ListeningStats? = null,
        val selectedRange: StatsRange = StatsRange.WEEK,
        val isLoading: Boolean = false,
        val error: String? = null,
        val linkSearchResults: List<UserSong> = emptyList(),
    )

    init {
        load(StatsRange.WEEK)
    }

    fun load(range: StatsRange) {
        screenModelScope.launch(dispatchers.io) {
            mutableState.update { it.copy(selectedRange = range, isLoading = true, error = null) }
            try {
                rpcServiceManager.awaitAuthentication()
                val stats = listeningStatsService.getStats(range, currentTimezoneId(), topLimit = 50)
                mutableState.update { it.copy(stats = stats, isLoading = false) }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun reload() = load(state.value.selectedRange)

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
