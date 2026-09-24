package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TimecodeTagAction
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.TimecodeTagServiceWrapper
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TimecodeTagStore(
    private val service: TimecodeTagServiceWrapper,
    private val rpcServiceManager: RpcServiceManager,
    private val logger: Logger,
    dispatchers: SynaraDispatchers
) {
    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())

    private val _tagsBySong = MutableStateFlow<Map<PlatformUUID, List<TimecodeTag>>>(emptyMap())
    val tagsBySong: StateFlow<Map<PlatformUUID, List<TimecodeTag>>> = _tagsBySong.asStateFlow()

    private val loading = MutableStateFlow<Set<PlatformUUID>>(emptySet())

    fun observe(songId: PlatformUUID, seed: List<TimecodeTag> = emptyList()): StateFlow<List<TimecodeTag>> {
        ensureLoaded(songId)
        return _tagsBySong
            .map { it[songId] ?: seed }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), _tagsBySong.value[songId] ?: seed)
    }

    fun cached(songId: PlatformUUID): List<TimecodeTag>? = _tagsBySong.value[songId]

    fun ensureLoaded(songId: PlatformUUID) {
        if (_tagsBySong.value.containsKey(songId)) return
        if (!markLoading(songId)) return
        scope.launch {
            try {
                load(songId)
            } finally {
                loading.update { it - songId }
            }
        }
    }

    fun refresh(songId: PlatformUUID) {
        scope.launch { load(songId) }
    }

    fun refreshIfCached(songId: PlatformUUID) {
        if (_tagsBySong.value.containsKey(songId)) refresh(songId)
    }

    suspend fun create(
        songId: PlatformUUID,
        type: TimecodeTagType,
        text: String,
        timestampMs: Long,
        endMs: Long?,
        action: TimecodeTagAction,
        fade: Boolean
    ): TimecodeTag {
        val tag = service.createTag(songId, type, text, timestampMs, endMs, action, fade)
        load(songId)
        return tag
    }

    suspend fun update(
        tag: TimecodeTag,
        type: TimecodeTagType,
        text: String,
        timestampMs: Long,
        endMs: Long?,
        action: TimecodeTagAction,
        fade: Boolean
    ): TimecodeTag {
        val updated = service.updateTag(tag.id, type, text, timestampMs, endMs, action, fade)
        load(tag.songId)
        return updated
    }

    suspend fun delete(tag: TimecodeTag): Boolean {
        val deleted = service.deleteTag(tag.id)
        load(tag.songId)
        return deleted
    }

    private fun markLoading(songId: PlatformUUID): Boolean {
        while (true) {
            val current = loading.value
            if (songId in current) return false
            if (loading.compareAndSet(current, current + songId)) return true
        }
    }

    private suspend fun load(songId: PlatformUUID) {
        val tags = try {
            rpcServiceManager.awaitAuthentication()
            service.getTags(songId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(LogTag.RPC, "Failed to load timecode tags of $songId", e)
            _tagsBySong.value[songId] ?: emptyList()
        }
        _tagsBySong.update { it + (songId to tags.sortedBy { tag -> tag.timestampMs }) }
    }
}
