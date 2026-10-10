package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.QueueInfo
import dev.dertyp.data.QueueItem
import dev.dertyp.data.QueueMeta
import dev.dertyp.data.QueueSyncDevice
import dev.dertyp.data.QueueUploadStart
import dev.dertyp.data.QueueWriteResult
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IQueueService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeQueueService(
    private val sessionId: PlatformUUID = randomPlatformUUID(),
    private val deviceName: String = "local"
) : IQueueService {

    sealed interface Call {
        sealed interface Write : Call

        data object GetQueueInfo : Call
        data class GetQueue(val page: Int, val pageSize: Int, val includeSongs: Boolean) : Call
        data object ObserveQueue : Call
        data class BeginUpload(val baseVersion: Long, val force: Boolean) : Write
        data class UploadPage(val uploadId: PlatformUUID, val items: List<QueueItem>) : Write
        data class CommitUpload(val uploadId: PlatformUUID, val meta: QueueMeta, val requestId: PlatformUUID?) : Write
        data class CancelUpload(val uploadId: PlatformUUID) : Write
        data class Insert(val baseVersion: Long, val position: Int, val items: List<QueueItem>, val force: Boolean) : Write
        data class Remove(val baseVersion: Long, val queueIds: List<Long>, val force: Boolean) : Write
        data class Move(val baseVersion: Long, val queueId: Long, val toPosition: Int, val force: Boolean) : Write
        data class SetCurrentIndex(val baseVersion: Long, val currentIndex: Int, val force: Boolean) : Write
        data class SetModes(val baseVersion: Long, val shuffleMode: Boolean, val repeatMode: RepeatMode, val force: Boolean) : Write
        data class SetSyncEnabled(val enabled: Boolean, val deviceName: String) : Call
        data class AckSynced(val version: Long) : Call
        data object GetSyncDevices : Call
        data class RequestUploadFrom(val sessionId: PlatformUUID) : Call
    }

    val calls = mutableListOf<Call>()
    val updates = MutableSharedFlow<QueueInfo>(extraBufferCapacity = 64)
    val observeFlows = ArrayDeque<Flow<QueueInfo>>()
    var onCall: suspend (Call) -> Unit = {}
    var requestStatus = ClientRequestStatus.COMPLETED
    var devices: List<QueueSyncDevice> = emptyList()
    var lastUploadId: PlatformUUID? = null
        private set

    var info = QueueInfo(
        version = 0L,
        modifiedAt = 0L,
        currentIndex = 0,
        isShuffled = false,
        repeatMode = RepeatMode.OFF,
        total = 0
    )

    private var original: List<QueueItem> = emptyList()
    private var shuffledOrder: List<Long>? = null
    private val songs = mutableMapOf<PlatformUUID, UserSong>()
    private var staged: MutableList<QueueItem>? = null
    private var stagedBaseVersion = 0L
    private var stagedForce = false

    val items: List<QueueItem>
        get() {
            val shuffled = shuffledOrder?.withIndex()?.associate { (index, queueId) -> queueId to index }
            return original.mapIndexed { index, item ->
                item.copy(position = index, shuffledPosition = shuffled?.get(item.queueId), song = null)
            }
        }

    val activeQueueIds: List<Long>
        get() = shuffledOrder ?: original.map { it.queueId }

    inline fun <reified T : Call> callsOf(): List<T> = calls.filterIsInstance<T>()

    fun register(known: List<UserSong>) {
        known.forEach { songs[it.id] = it }
    }

    fun store(
        items: List<QueueItem>,
        version: Long = info.version + 1,
        sessionId: PlatformUUID? = this.sessionId,
        deviceName: String? = this.deviceName,
        currentIndex: Int = 0,
        shuffled: Boolean = false,
        repeatMode: RepeatMode = RepeatMode.OFF,
        sourceId: String? = null,
        notify: Boolean = false
    ): QueueInfo {
        replace(items, shuffled)
        info = QueueInfo(
            version = version,
            modifiedAt = version,
            modifiedBySessionId = sessionId,
            modifiedByDeviceName = deviceName,
            currentIndex = currentIndex,
            isShuffled = shuffled,
            repeatMode = repeatMode,
            sourceId = sourceId,
            total = original.size
        )
        if (notify) updates.tryEmit(info)
        return info
    }

    override suspend fun getQueueInfo(): QueueInfo {
        record(Call.GetQueueInfo)
        return info
    }

    override suspend fun getQueue(page: Int, pageSize: Int, includeSongs: Boolean): PaginatedResponse<QueueItem> {
        record(Call.GetQueue(page, pageSize, includeSongs))
        val all = items
        val data = all.drop(page * pageSize).take(pageSize).map { item ->
            if (includeSongs) item.copy(song = songs[item.songId]) else item
        }
        return PaginatedResponse(
            data = data,
            page = page,
            total = all.size,
            pageSize = pageSize,
            hasNextPage = page * pageSize + data.size < all.size
        )
    }

    override fun observeQueue(): Flow<QueueInfo> {
        calls += Call.ObserveQueue
        return observeFlows.removeFirstOrNull() ?: updates
    }

    override suspend fun beginUpload(baseVersion: Long, force: Boolean): QueueUploadStart {
        record(Call.BeginUpload(baseVersion, force))
        if (!force && baseVersion != info.version) return QueueUploadStart.Conflict(info)
        val uploadId = randomPlatformUUID()
        lastUploadId = uploadId
        staged = mutableListOf()
        stagedBaseVersion = baseVersion
        stagedForce = force
        return QueueUploadStart.Started(uploadId, 0L)
    }

    override suspend fun uploadPage(uploadId: PlatformUUID, items: List<QueueItem>): Int {
        record(Call.UploadPage(uploadId, items))
        val target = stagedFor(uploadId)
        target.addAll(items)
        return target.size
    }

    override suspend fun commitUpload(uploadId: PlatformUUID, meta: QueueMeta, requestId: PlatformUUID?): QueueWriteResult {
        record(Call.CommitUpload(uploadId, meta, requestId))
        val uploaded = stagedFor(uploadId)
        staged = null
        if (!stagedForce && stagedBaseVersion != info.version) return QueueWriteResult.Conflict(info)
        replace(uploaded, meta.isShuffled)
        info = info.copy(
            currentIndex = meta.currentIndex,
            isShuffled = meta.isShuffled,
            repeatMode = meta.repeatMode,
            sourceId = meta.sourceId
        )
        return QueueWriteResult.Ok(accept())
    }

    override suspend fun cancelUpload(uploadId: PlatformUUID) {
        record(Call.CancelUpload(uploadId))
        staged = null
    }

    override suspend fun insert(baseVersion: Long, position: Int, items: List<QueueItem>, force: Boolean): QueueWriteResult =
        write(Call.Insert(baseVersion, position, items, force), baseVersion, force) {
            val added = items.map { it.copy(song = null) }
            register(items.mapNotNull { it.song })
            val order = shuffledOrder
            if (order == null) {
                original = original.toMutableList().apply { addAll(position.coerceIn(0, size), added) }
            } else {
                shuffledOrder = order.toMutableList().apply { addAll(position.coerceIn(0, size), added.map { it.queueId }) }
                original = original + added
            }
        }

    override suspend fun remove(baseVersion: Long, queueIds: List<Long>, force: Boolean): QueueWriteResult =
        write(Call.Remove(baseVersion, queueIds, force), baseVersion, force) {
            val dropped = queueIds.toSet()
            original = original.filter { it.queueId !in dropped }
            shuffledOrder = shuffledOrder?.filter { it !in dropped }
        }

    override suspend fun move(baseVersion: Long, queueId: Long, toPosition: Int, force: Boolean): QueueWriteResult =
        write(Call.Move(baseVersion, queueId, toPosition, force), baseVersion, force) {
            val order = shuffledOrder
            if (order == null) {
                val item = original.firstOrNull { it.queueId == queueId } ?: return@write
                val rest = original.filter { it.queueId != queueId }.toMutableList()
                rest.add(toPosition.coerceIn(0, rest.size), item)
                original = rest
            } else if (queueId in order) {
                val rest = order.filter { it != queueId }.toMutableList()
                rest.add(toPosition.coerceIn(0, rest.size), queueId)
                shuffledOrder = rest
            }
        }

    override suspend fun setCurrentIndex(baseVersion: Long, currentIndex: Int, force: Boolean): QueueWriteResult =
        write(Call.SetCurrentIndex(baseVersion, currentIndex, force), baseVersion, force) {
            info = info.copy(currentIndex = currentIndex)
        }

    override suspend fun setModes(baseVersion: Long, shuffleMode: Boolean, repeatMode: RepeatMode, force: Boolean): QueueWriteResult =
        write(Call.SetModes(baseVersion, shuffleMode, repeatMode, force), baseVersion, force) {
            val currentId = activeQueueIds.getOrNull(info.currentIndex)
            var index = info.currentIndex
            if (shuffleMode && shuffledOrder == null) {
                val ids = original.map { it.queueId }
                shuffledOrder = listOfNotNull(currentId) + ids.filter { it != currentId }
                index = 0
            } else if (!shuffleMode && shuffledOrder != null) {
                shuffledOrder = null
                index = original.indexOfFirst { it.queueId == currentId }.coerceAtLeast(0)
            }
            info = info.copy(currentIndex = index, isShuffled = shuffleMode, repeatMode = repeatMode)
        }

    override suspend fun setSyncEnabled(enabled: Boolean, deviceName: String) {
        record(Call.SetSyncEnabled(enabled, deviceName))
    }

    override suspend fun ackSynced(version: Long) {
        record(Call.AckSynced(version))
    }

    override suspend fun getSyncDevices(): List<QueueSyncDevice> {
        record(Call.GetSyncDevices)
        return devices
    }

    override suspend fun requestUploadFrom(sessionId: PlatformUUID): ClientRequestStatus {
        record(Call.RequestUploadFrom(sessionId))
        return requestStatus
    }

    private suspend fun record(call: Call) {
        calls += call
        onCall(call)
    }

    private fun stagedFor(uploadId: PlatformUUID): MutableList<QueueItem> {
        check(uploadId == lastUploadId) { "Unknown upload $uploadId" }
        return checkNotNull(staged) { "Upload $uploadId is not open" }
    }

    private fun replace(items: List<QueueItem>, shuffled: Boolean) {
        register(items.mapNotNull { it.song })
        original = items.sortedBy { it.position }.map { it.copy(song = null) }
        shuffledOrder = if (shuffled) {
            items.sortedBy { it.shuffledPosition ?: Int.MAX_VALUE }.map { it.queueId }
        } else {
            null
        }
    }

    private suspend fun write(call: Call, baseVersion: Long, force: Boolean, change: () -> Unit): QueueWriteResult {
        record(call)
        if (!force && baseVersion != info.version) return QueueWriteResult.Conflict(info)
        change()
        return QueueWriteResult.Ok(accept())
    }

    private fun accept(): QueueInfo {
        val version = info.version + 1
        info = info.copy(
            version = version,
            modifiedAt = version,
            modifiedBySessionId = sessionId,
            modifiedByDeviceName = deviceName,
            total = original.size
        )
        updates.tryEmit(info)
        return info
    }
}
