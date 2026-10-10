package dev.dertyp.synara.logging

import dev.dertyp.logging.LogPersistence

actual fun platformLogPersistence(dataDir: () -> String): LogPersistence = FileLogPersistence(dataDir)
