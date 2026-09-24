package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.*
import dev.dertyp.services.ITimecodeTagService
import dev.dertyp.synara.rpc.RpcServiceManager

class TimecodeTagServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), ITimecodeTagService {
    override suspend fun createTag(
        songId: PlatformUUID,
        tagType: TimecodeTagType,
        text: String,
        timestampMs: Long,
        endMs: Long?,
        action: TimecodeTagAction,
        fade: Boolean
    ): TimecodeTag {
        return manager.getService<ITimecodeTagService>().createTag(songId, tagType, text, timestampMs, endMs, action, fade)
    }

    override suspend fun getTags(songId: PlatformUUID): List<TimecodeTag> {
        return manager.getService<ITimecodeTagService>().getTags(songId)
    }

    override suspend fun updateTag(
        tagId: PlatformUUID,
        tagType: TimecodeTagType,
        text: String,
        timestampMs: Long,
        endMs: Long?,
        action: TimecodeTagAction?,
        fade: Boolean?
    ): TimecodeTag {
        return manager.getService<ITimecodeTagService>().updateTag(tagId, tagType, text, timestampMs, endMs, action, fade)
    }

    override suspend fun deleteTag(tagId: PlatformUUID): Boolean {
        return manager.getService<ITimecodeTagService>().deleteTag(tagId)
    }

    override suspend fun replaceTags(songId: PlatformUUID, tags: List<TimecodeTagInput>): List<TimecodeTag> {
        return manager.getService<ITimecodeTagService>().replaceTags(songId, tags)
    }

    override suspend fun listTags(tagType: TimecodeTagType?, page: Int, pageSize: Int): PaginatedResponse<TimecodeTag> {
        return manager.getService<ITimecodeTagService>().listTags(tagType, page, pageSize)
    }
}
