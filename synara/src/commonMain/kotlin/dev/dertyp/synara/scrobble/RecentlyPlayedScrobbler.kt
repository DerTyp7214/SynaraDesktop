package dev.dertyp.synara.scrobble

import dev.dertyp.currentTimeMillis
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.synara.db.SynaraDatabase
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.serialization.json.Json
import org.koin.core.component.inject

class RecentlyPlayedScrobbler(
    database: SynaraDatabase,
    private val json: Json,
) : BaseScrobbler() {
    override val name: String = "Recently Played"
    override val icon: String = ""

    private val queries = database.recentlyPlayedQueries
    private val globalState: GlobalStateModel by inject()

    override suspend fun triggered(song: UserSong) {
        val userId = globalState.user.value?.id?.toString() ?: return
        val timestamp = currentTimeMillis()

        queries.insertSong(
            userId = userId,
            id = song.id.toString(),
            timestamp = timestamp,
            payload = json.encodeToString(song)
        )

        song.album?.let { album ->
            queries.insertAlbum(
                userId = userId,
                id = album.id.toString(),
                timestamp = timestamp,
                payload = json.encodeToString(album)
            )
        }

        song.artists.forEach { artist ->
            queries.insertArtist(
                userId = userId,
                id = artist.id.toString(),
                timestamp = timestamp,
                payload = json.encodeToString(artist)
            )
        }

        logger.info(LogTag.RECENTLY_PLAYED, "Recently played updated for ${song.title}")
    }
}
