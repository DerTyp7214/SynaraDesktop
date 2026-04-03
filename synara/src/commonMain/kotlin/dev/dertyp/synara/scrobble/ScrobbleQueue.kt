package dev.dertyp.synara.scrobble

import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.synara.db.ScrobbleQueueRepository
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.runBlocking
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

    fun push(song: UserSong, timestamp: Long, target: String) {
        val userId = globalState.user.value?.id ?: return
        runBlocking {
            repository.insert(
                userId = userId,
                song = song,
                timestamp = timestamp,
                target = target
            )
        }
    }

    fun peek(target: String): QueuedScrobble? {
        val userId = globalState.user.value?.id ?: return null
        return runBlocking {
            repository.peek(userId, target)?.let {
                QueuedScrobble(
                    id = it.id,
                    userId = it.userId,
                    song = it.song,
                    timestamp = it.timestamp,
                    target = it.target
                )
            }
        }
    }

    fun pop(id: Long) {
        runBlocking {
            repository.delete(id)
        }
    }

    fun isEmpty(target: String): Boolean {
        val userId = globalState.user.value?.id ?: return true
        return runBlocking {
            repository.getCount(userId, target) == 0L
        }
    }
}
