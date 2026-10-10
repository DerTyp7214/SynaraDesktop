package dev.dertyp.synara.logging

import dev.dertyp.logging.LogPersistence

expect fun platformLogPersistence(dataDir: () -> String): LogPersistence
