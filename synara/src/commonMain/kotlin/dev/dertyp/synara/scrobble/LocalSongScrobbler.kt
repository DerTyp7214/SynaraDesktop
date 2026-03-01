package dev.dertyp.synara.scrobble

import androidx.compose.ui.graphics.Color
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.synara.db.SynaraDatabase
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.ui.models.TrayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class LocalSongScrobbler(
    database: SynaraDatabase,
    private val trayState: TrayState,
    private val playerModel: PlayerModel,
    private val json: Json
) : BaseScrobbler() {
    override val name: String = "Local History"
    override val icon: String = ""

    private val queries = database.scrobbleQueueQueries
    private val currentColor = MutableStateFlow<Color>(Color(0xFFB3B3B3))

    init {
        this += scope.launch {
            combine(
                playerModel.isPlaying,
                currentColor
            ) { isPlaying, color ->
                if (isPlaying) color else null
            }.distinctUntilChanged().collectLatest { color ->
                trayState.setBadgeColor(color)
            }
        }
    }

    override suspend fun triggered(song: UserSong) {
        logger.info(LogTag.SCROBBLER, "Local scrobble triggered for ${song.title}")
        queries.insertHistory(
            song_id = song.id.toString(),
            timestamp = currentTimeMillis(),
            payload = json.encodeToString(song)
        )

        currentColor.emit(Color(0xFF87F487))
    }

    override suspend fun reset() {
        currentColor.emit(Color(0xFFB3B3B3))
    }
}
