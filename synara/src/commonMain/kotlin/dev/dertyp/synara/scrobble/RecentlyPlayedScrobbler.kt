package dev.dertyp.synara.scrobble

import dev.dertyp.currentTimeMillis
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.synara.db.RecentlyPlayedRepository
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.viewmodels.GlobalStateModel
import org.koin.core.component.inject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.recently_played

class RecentlyPlayedScrobbler(
    private val repository: RecentlyPlayedRepository,
) : BaseScrobbler() {
    override val name = Res.string.recently_played
    override val icon: SynaraIcons = SynaraIcons.History
    override val sortOrder: Int = 0

    private val globalState: GlobalStateModel by inject()

    override suspend fun triggered(song: UserSong) {
        val userId = globalState.user.value?.id ?: return
        val timestamp = currentTimeMillis()

        repository.insertSong(userId, song, timestamp)

        song.album?.let { album ->
            repository.insertAlbum(userId, album, timestamp)
        }

        song.artists.forEach { artist ->
            repository.insertArtist(userId, artist, timestamp)
        }

        updateStatus(ScrobbleStatus.SCROBBLED)
        logger.info(LogTag.RECENTLY_PLAYED, "Recently played updated for ${song.title}")
    }

    override suspend fun reset() {
        updateStatus(ScrobbleStatus.IDLE)
    }
}
