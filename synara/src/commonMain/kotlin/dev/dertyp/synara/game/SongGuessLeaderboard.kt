package dev.dertyp.synara.game

import dev.dertyp.PlatformUUID
import dev.dertyp.synara.db.SongGuessRepository
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SongGuessLeaderboard(
    private val repository: SongGuessRepository,
    private val globalState: GlobalStateModel,
    dispatchers: SynaraDispatchers,
) {
    private val scope = CoroutineScope(dispatchers.database + SupervisorJob())

    private val _entries = MutableStateFlow<List<LeaderboardEntry>>(emptyList())
    val entries: StateFlow<List<LeaderboardEntry>> = _entries.asStateFlow()

    private val userId: PlatformUUID? get() = globalState.user.value?.id

    init {
        scope.launch {
            globalState.user.map { it?.id }.distinctUntilChanged().collect { reload(it) }
        }
    }

    fun add(entry: LeaderboardEntry) {
        val userId = userId ?: return
        scope.launch {
            try {
                repository.insert(userId, entry)
                reload(userId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clear() {
        val userId = userId ?: return
        scope.launch {
            try {
                repository.clear(userId)
                reload(userId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ---------------------------------------------------------------- saved game

    fun saveGame(game: SavedGame) {
        val userId = userId ?: return
        scope.launch {
            try {
                repository.saveGame(userId, game)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearGame() {
        val userId = userId ?: return
        scope.launch {
            try {
                repository.clearGame(userId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun loadGame(): SavedGame? {
        val userId = globalState.user.filterNotNull().first().id
        return try {
            repository.loadGame(userId)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun reload(userId: PlatformUUID?) {
        if (userId == null) {
            _entries.value = emptyList()
            return
        }
        try {
            _entries.value = repository.getAll(userId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
