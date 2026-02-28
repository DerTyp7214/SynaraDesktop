package dev.dertyp.synara.scrobble

import dev.dertyp.data.UserSong
import dev.dertyp.synara.db.SynaraDatabase
import kotlinx.serialization.json.Json

data class QueuedScrobble(
    val id: Long,
    val song: UserSong,
    val timestamp: Long,
    val target: String
)

class ScrobbleQueue(
    database: SynaraDatabase,
    private val json: Json
) {
    private val queries = database.scrobbleQueueQueries

    fun push(song: UserSong, timestamp: Long, target: String) {
        queries.insert(
            payload = json.encodeToString(song),
            timestamp = timestamp,
            target = target
        )
    }

    fun peek(target: String): QueuedScrobble? {
        return queries.peek(target).executeAsOneOrNull()?.let {
            QueuedScrobble(
                id = it.id,
                song = json.decodeFromString(it.payload),
                timestamp = it.timestamp,
                target = it.target
            )
        }
    }

    fun pop(id: Long) {
        queries.delete(id)
    }

    fun isEmpty(target: String): Boolean {
        return queries.getCount(target).executeAsOne() == 0L
    }
}
