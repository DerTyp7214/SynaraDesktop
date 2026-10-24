package dev.dertyp.synara.lyrics

import dev.dertyp.PlatformUUID
import dev.dertyp.data.SongLyricsInfo
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.ILyricsCandidateService
import dev.dertyp.services.models.LyricsCandidate
import dev.dertyp.services.models.LyricsSearchResult
import dev.dertyp.synara.db.OfflineLyricsStore
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.ui.components.lyrics.LyricsDisplay
import dev.dertyp.synara.ui.components.lyrics.LyricsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val LYRICS_TAG = LogTag("lyrics")

data class PickedLyrics(val songId: PlatformUUID, val candidateId: PlatformUUID)

class LyricsRepository(
    private val service: ILyricsCandidateService,
    private val offlineStore: OfflineLyricsStore,
    private val rpcServiceManager: RpcServiceManager,
    private val logger: Logger
) {
    companion object {
        const val CACHE_SIZE = 20
    }

    private data class Key(val songId: PlatformUUID, val candidateId: PlatformUUID?)

    private class Entry(val candidate: LyricsCandidate?)

    private val mutex = Mutex()
    private val cache = LinkedHashMap<Key, Entry>()
    private val invalidations = MutableSharedFlow<PlatformUUID>(extraBufferCapacity = 16)

    private val _picked = MutableStateFlow<PickedLyrics?>(null)
    val picked: StateFlow<PickedLyrics?> = _picked.asStateFlow()

    private val online: Flow<Boolean> = combine(
        rpcServiceManager.isServerReachable,
        rpcServiceManager.connectionState
    ) { reachable, connection ->
        reachable && connection == RpcServiceManager.ConnectionState.Authenticated
    }.distinctUntilChanged()

    private fun isOnline(): Boolean =
        rpcServiceManager.isServerReachable.value &&
            rpcServiceManager.connectionState.value == RpcServiceManager.ConnectionState.Authenticated

    @OptIn(ExperimentalCoroutinesApi::class)
    fun lyricsState(songId: PlatformUUID, info: SongLyricsInfo? = null): Flow<LyricsState> {
        return _picked
            .onStart {
                _picked.update { if (it?.songId == songId) it else null }
                if (info != null) dropStaleDefault(songId, info)
            }
            .map { if (it?.songId == songId) it.candidateId else null }
            .distinctUntilChanged()
            .flatMapLatest { candidateId -> observe(songId, candidateId) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(songId: PlatformUUID, candidateId: PlatformUUID?): Flow<LyricsState> = flow {
        val key = Key(songId, candidateId)
        var shown: LyricsState? = null
        val triggers = merge(online, invalidations.filter { it == songId }.map { isOnline() })
        emitAll(triggers.transformLatest { isOnline ->
            val hit = cached(key)
            val next = if (hit != null) {
                stateOf(hit.candidate)
            } else {
                if (shown !is LyricsState.Loaded) emit(LyricsState.Loading)
                load(key, isOnline)
            }
            shown = next
            emit(next)
        })
    }

    suspend fun lyricsFor(songId: PlatformUUID, candidateId: PlatformUUID? = null): LyricsState {
        val key = Key(songId, candidateId)
        val hit = cached(key) ?: return load(key, isOnline())
        return stateOf(hit.candidate)
    }

    suspend fun shownLyrics(songId: PlatformUUID): LyricsCandidate? {
        val candidateId = _picked.value?.takeIf { it.songId == songId }?.candidateId
        cached(Key(songId, candidateId))?.let { return it.candidate }
        return offlineStore.getLyrics(songId)
    }

    fun pick(songId: PlatformUUID, candidateId: PlatformUUID?) {
        _picked.value = candidateId?.let { PickedLyrics(songId, it) }
    }

    suspend fun candidates(songId: PlatformUUID): LyricsSearchResult {
        return service.getLyricsCandidates(songId, includeContent = false)
    }

    fun search(songId: PlatformUUID): Flow<LyricsCandidate> {
        return service.streamLyrics(songId).onCompletion { if (it == null) invalidate(songId) }
    }

    suspend fun makeDefault(songId: PlatformUUID, candidateId: PlatformUUID): LyricsSearchResult {
        val result = service.setPreferredLyrics(songId, candidateId)
        invalidate(songId)
        return result
    }

    suspend fun invalidate(songId: PlatformUUID) {
        mutex.withLock { cache.keys.removeAll { it.songId == songId } }
        invalidations.emit(songId)
    }

    private suspend fun dropStaleDefault(songId: PlatformUUID, info: SongLyricsInfo) {
        val stale = mutex.withLock {
            val entry = cache[Key(songId, null)] ?: return@withLock false
            entry.candidate?.id != info.defaultCandidateId
        }
        if (stale) invalidate(songId)
    }

    private suspend fun cached(key: Key): Entry? = mutex.withLock {
        val entry = cache.remove(key) ?: return@withLock null
        cache[key] = entry
        entry
    }

    private suspend fun remember(key: Key, candidate: LyricsCandidate?) = mutex.withLock {
        cache.remove(key)
        cache[key] = Entry(candidate)
        while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
    }

    private suspend fun load(key: Key, online: Boolean): LyricsState {
        if (online) {
            try {
                val candidate = service.getLyrics(key.songId, key.candidateId)
                remember(key, candidate)
                return stateOf(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warning(LYRICS_TAG, "Loading lyrics of ${key.songId} failed: ${e.message}")
            }
        }
        val offline = try {
            offlineStore.getLyrics(key.songId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(LYRICS_TAG, "Reading offline lyrics of ${key.songId} failed", e)
            null
        }
        return if (offline != null) stateOf(offline) else LyricsState.Failed
    }

    private fun stateOf(candidate: LyricsCandidate?): LyricsState {
        if (candidate == null) return LyricsState.Empty
        val display = LyricsDisplay.from(candidate)
        return if (display.isEmpty) LyricsState.Empty else LyricsState.Loaded(candidate, display)
    }
}
