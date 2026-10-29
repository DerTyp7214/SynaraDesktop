package dev.dertyp.synara.utils

expect fun currentTimezoneId(): String

expect fun defaultDeviceName(): String

enum class InstallChannel { PACMAN, DEB, TARBALL, MSI, DMG }

fun installChannelOf(name: String?): InstallChannel? =
    InstallChannel.entries.firstOrNull { it.name.lowercase() == name }

expect fun currentInstallChannel(): InstallChannel?

expect suspend fun pickImageBytes(): ByteArray?

class PickedFile(val name: String, val bytes: ByteArray)

expect suspend fun pickFile(title: String, extensions: Set<String> = emptySet()): PickedFile?
