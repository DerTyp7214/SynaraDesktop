package dev.dertyp.synara.scrobble

import androidx.compose.ui.graphics.Color
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.synara.db.LocalHistoryRepository
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.models.TrayState
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.core.component.inject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.local_scrobbler

class LocalSongScrobbler(
    private val repository: LocalHistoryRepository,
    private val trayState: TrayState,
    private val playerModel: PlayerModel,
) : BaseScrobbler() {
    override val name = Res.string.local_scrobbler
    override val icon: SynaraIcons = SynaraIcons.Library
    override val sortOrder: Int = 1
    override val showInDialog: Boolean = false

    private val globalState: GlobalStateModel by inject()
    private val currentColor = MutableStateFlow(Color(0xFFB3B3B3))

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
        val userId = globalState.user.value?.id ?: return
        logger.info(LogTag.SCROBBLER, "Local scrobble triggered for ${song.title}")
        repository.insert(
            userId = userId,
            song = song,
            timestamp = currentTimeMillis()
        )

        currentColor.emit(Color(0xFF87F487))
        updateStatus(ScrobbleStatus.SCROBBLED)
    }

    override suspend fun reset() {
        currentColor.emit(Color(0xFFB3B3B3))
    }
}
