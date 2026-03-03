package dev.dertyp.synara.scrobble

import dev.dertyp.currentTimeMillis
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.synara.db.SynaraDatabase
import kotlinx.serialization.json.Json

class RecentlyPlayedScrobbler(
    database: SynaraDatabase,
    private val json: Json,
) : BaseScrobbler() {
    override val name: String = "Recently Played"
    override val icon: String = ""

    private val queries = database.recentlyPlayedQueries

    override suspend fun triggered(song: UserSong) {
        val timestamp = currentTimeMillis()

        queries.insertSong(
            id = song.id.toString(),
            timestamp = timestamp,
            payload = json.encodeToString(song)
        )

        song.album?.let { album ->
            queries.insertAlbum(
                id = album.id.toString(),
                timestamp = timestamp,
                payload = json.encodeToString(album)
            )
        }

        song.artists.forEach { artist ->
            queries.insertArtist(
                id = artist.id.toString(),
                timestamp = timestamp,
                payload = json.encodeToString(artist)
            )
        }

        logger.info(LogTag.RECENTLY_PLAYED, "Recently played updated for ${song.title}")
    }
}
