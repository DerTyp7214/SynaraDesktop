package dev.dertyp.synara.utils

expect fun currentTimezoneId(): String

expect fun defaultDeviceName(): String

expect suspend fun pickImageBytes(): ByteArray?
