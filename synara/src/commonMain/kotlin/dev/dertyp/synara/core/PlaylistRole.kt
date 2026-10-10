package dev.dertyp.synara.core

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PlaylistAccess
import dev.dertyp.data.UserPlaylist
import dev.dertyp.dbus.annotations.DbusFieldDoc
import kotlinx.serialization.Serializable

@Serializable
enum class PlaylistRole(val canWrite: Boolean) {
    @DbusFieldDoc("The user owns the playlist and can change and share it.")
    OWNER(true),

    @DbusFieldDoc("The playlist is shared with the user with write access. The user can add and remove songs.")
    EDITOR(true),

    @DbusFieldDoc("The playlist is shared with the user with read access.")
    VIEWER(false),

    @DbusFieldDoc("The playlist belongs to someone else and is not shared with the user. It is visible because it is public.")
    VISITOR(false);

    val isOwner: Boolean get() = this == OWNER
    val isShared: Boolean get() = this == EDITOR || this == VIEWER
}

fun UserPlaylist.roleOf(userId: PlatformUUID?): PlaylistRole {
    if (userId == null) return PlaylistRole.VISITOR
    if (creator == userId) return PlaylistRole.OWNER
    return when (shares.find { it.userId == userId }?.access) {
        PlaylistAccess.WRITE -> PlaylistRole.EDITOR
        PlaylistAccess.READ -> PlaylistRole.VIEWER
        null -> PlaylistRole.VISITOR
    }
}
