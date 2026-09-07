package dev.dertyp.synara.scrobble

import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.synara.db.RawScrobbleQueueEntry
import dev.dertyp.synara.db.ScrobbleQueueRepository
import dev.dertyp.synara.viewmodels.GlobalStateModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class QueuedScrobble(
    val id: Long,
    val userId: PlatformUUID,
    val song: UserSong,
    val timestamp: Long,
    val target: String
)

class ScrobbleQueue(
    private val repository: ScrobbleQueueRepository
) : KoinComponent {
    private val globalState: GlobalStateModel by inject()

    suspend fun push(song: UserSong, timestamp: Long, target: String) {
        val userId = globalState.user.value?.id ?: return
        repository.insert(
            userId = userId,
            song = song,
            timestamp = timestamp,
            target = target
        )
    }

    suspend fun pushRaw(songId: String, payload: String, timestamp: Long, target: String) {
        val userId = globalState.user.value?.id ?: return
        repository.insertRaw(
            userId = userId,
            songId = songId,
            payload = payload,
            timestamp = timestamp,
            target = target
        )
    }

    suspend fun getAllRaw(target: String): List<RawScrobbleQueueEntry> {
        val userId = globalState.user.value?.id ?: return emptyList()
        return repository.getAllRaw(userId, target)
    }

    suspend fun peek(target: String): QueuedScrobble? {
        val userId = globalState.user.value?.id ?: return null
        return repository.peek(userId, target)?.let {
            QueuedScrobble(
                id = it.id,
                userId = it.userId,
                song = it.song,
                timestamp = it.timestamp,
                target = it.target
            )
        }
    }

    suspend fun pop(id: Long) {
        repository.delete(id)
    }

    suspend fun isEmpty(target: String): Boolean {
        val userId = globalState.user.value?.id ?: return true
        return repository.getCount(userId, target) == 0L
    }
}
