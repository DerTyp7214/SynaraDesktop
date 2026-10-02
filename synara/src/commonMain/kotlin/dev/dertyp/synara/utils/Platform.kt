package dev.dertyp.synara.utils

expect fun currentTimezoneId(): String

expect fun defaultDeviceName(): String

expect suspend fun pickImageBytes(): ByteArray?

class PickedFile(val name: String, val bytes: ByteArray)

expect suspend fun pickFile(title: String, extensions: Set<String> = emptySet()): PickedFile?
