package dev.dertyp.synara.scrobble

import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.synara.db.RecentlyPlayedRepository
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.viewmodels.GlobalStateModel
import org.koin.core.component.inject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.recently_played
import kotlin.concurrent.Volatile

class RecentlyPlayedScrobbler(
    private val repository: RecentlyPlayedRepository,
) : BaseScrobbler() {
    override val name = Res.string.recently_played
    override val icon: SynaraIcons = SynaraIcons.History
    override val sortOrder: Int = 0

    private val globalState: GlobalStateModel by inject()

    @Volatile
    private var isDropLogged = false

    override suspend fun triggered(song: UserSong, listenedAt: Long) {
        val userId = globalState.user.value?.id
        if (userId == null) {
            if (!isDropLogged) {
                isDropLogged = true
                logger.warning(LogTag.RECENTLY_PLAYED, "No user is known, recently played is not updated until one is")
            }
            return
        }
        isDropLogged = false
        val timestamp = listenedAt

        repository.insertListen(userId, song, timestamp)

        updateStatus(ScrobbleStatus.SCROBBLED)
        logger.info(LogTag.RECENTLY_PLAYED, "Recently played updated for ${song.title}")
    }

    override suspend fun reset() {
        updateStatus(ScrobbleStatus.IDLE)
    }
}
