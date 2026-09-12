package dev.dertyp.synara.rpc.services

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
import dev.dertyp.services.IQueueService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class QueueServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IQueueService {
    override suspend fun getQueueInfo(): QueueInfo {
        return manager.getService<IQueueService>().getQueueInfo()
    }

    override suspend fun getQueue(
        page: Int,
        pageSize: Int,
        includeSongs: Boolean
    ): PaginatedResponse<QueueItem> {
        return manager.getService<IQueueService>().getQueue(page, pageSize, includeSongs)
    }

    override fun observeQueue(): Flow<QueueInfo> {
        return manager.getService<IQueueService>().observeQueue()
    }

    override suspend fun beginUpload(baseVersion: Long, force: Boolean): QueueUploadStart {
        return manager.getService<IQueueService>().beginUpload(baseVersion, force)
    }

    override suspend fun uploadPage(uploadId: PlatformUUID, items: List<QueueItem>): Int {
        return manager.getService<IQueueService>().uploadPage(uploadId, items)
    }

    override suspend fun commitUpload(
        uploadId: PlatformUUID,
        meta: QueueMeta,
        requestId: PlatformUUID?
    ): QueueWriteResult {
        return manager.getService<IQueueService>().commitUpload(uploadId, meta, requestId)
    }

    override suspend fun cancelUpload(uploadId: PlatformUUID) {
        manager.getService<IQueueService>().cancelUpload(uploadId)
    }

    override suspend fun insert(
        baseVersion: Long,
        position: Int,
        items: List<QueueItem>,
        force: Boolean
    ): QueueWriteResult {
        return manager.getService<IQueueService>().insert(baseVersion, position, items, force)
    }

    override suspend fun remove(
        baseVersion: Long,
        queueIds: List<Long>,
        force: Boolean
    ): QueueWriteResult {
        return manager.getService<IQueueService>().remove(baseVersion, queueIds, force)
    }

    override suspend fun move(
        baseVersion: Long,
        queueId: Long,
        toPosition: Int,
        force: Boolean
    ): QueueWriteResult {
        return manager.getService<IQueueService>().move(baseVersion, queueId, toPosition, force)
    }

    override suspend fun setCurrentIndex(
        baseVersion: Long,
        currentIndex: Int,
        force: Boolean
    ): QueueWriteResult {
        return manager.getService<IQueueService>().setCurrentIndex(baseVersion, currentIndex, force)
    }

    override suspend fun setModes(
        baseVersion: Long,
        shuffleMode: Boolean,
        repeatMode: RepeatMode,
        force: Boolean
    ): QueueWriteResult {
        return manager.getService<IQueueService>().setModes(baseVersion, shuffleMode, repeatMode, force)
    }

    override suspend fun setSyncEnabled(enabled: Boolean, deviceName: String) {
        manager.getService<IQueueService>().setSyncEnabled(enabled, deviceName)
    }

    override suspend fun ackSynced(version: Long) {
        manager.getService<IQueueService>().ackSynced(version)
    }

    override suspend fun getSyncDevices(): List<QueueSyncDevice> {
        return manager.getService<IQueueService>().getSyncDevices()
    }

    override suspend fun requestUploadFrom(sessionId: PlatformUUID): ClientRequestStatus {
        return manager.getService<IQueueService>().requestUploadFrom(sessionId)
    }
}
