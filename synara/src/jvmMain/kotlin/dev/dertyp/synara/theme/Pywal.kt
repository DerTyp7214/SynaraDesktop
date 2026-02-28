package dev.dertyp.synara.theme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.*

actual object PywalLoader {
    private val json = Json { ignoreUnknownKeys = true }

    private fun loadColors(): PywalColors? {
        if (!isSupported()) return null

        val home = System.getProperty("user.home")
        val file = File(home, ".cache/wal/colors.json")
        if (!file.exists()) return null

        return try {
            json.decodeFromString<PywalColors>(file.readText())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    actual fun isSupported(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        return os.contains("linux")
    }

    actual fun getColorsFlow(): Flow<PywalColors?> = callbackFlow {
        if (!isSupported()) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val home = System.getProperty("user.home")
        val walDir = Paths.get(home, ".cache/wal")
        val fileName = "colors.json"

        trySend(loadColors())

        if (!Files.exists(walDir)) {
            close()
            return@callbackFlow
        }

        val watchService = FileSystems.getDefault().newWatchService()
        val watchKey = walDir.register(
            watchService,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_CREATE
        )

        val job = launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val key = watchService.take()
                    for (event in key.pollEvents()) {
                        val context = event.context() as? Path
                        if (context?.toString() == fileName) {
                            trySend(loadColors())
                        }
                    }
                    if (!key.reset()) break
                }
            } catch (_: Exception) {
            }
        }

        awaitClose {
            job.cancel()
            watchKey.cancel()
            watchService.close()
        }
    }.flowOn(Dispatchers.IO)
}
