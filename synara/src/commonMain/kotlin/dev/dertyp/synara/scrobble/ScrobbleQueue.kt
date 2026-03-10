package dev.dertyp.synara.scrobble

import dev.dertyp.data.UserSong
import dev.dertyp.synara.db.SynaraDatabase
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class QueuedScrobble(
    val id: Long,
    val userId: String,
    val song: UserSong,
    val timestamp: Long,
    val target: String
)

class ScrobbleQueue(
    database: SynaraDatabase,
    private val json: Json
) : KoinComponent {
    private val queries = database.scrobbleQueueQueries
    private val globalState: GlobalStateModel by inject()

    fun push(song: UserSong, timestamp: Long, target: String) {
        val userId = globalState.user.value?.id?.toString() ?: return
        queries.insert(
            userId = userId,
            payload = json.encodeToString(song),
            timestamp = timestamp,
            target = target
        )
    }

    fun peek(target: String): QueuedScrobble? {
        val userId = globalState.user.value?.id?.toString() ?: return null
        return queries.peek(userId, target).executeAsOneOrNull()?.let {
            QueuedScrobble(
                id = it.id,
                userId = it.userId,
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
        val userId = globalState.user.value?.id?.toString() ?: return true
        return queries.getCount(userId, target).executeAsOne() == 0L
    }
}
