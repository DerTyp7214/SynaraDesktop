package dev.dertyp.synara.scrobble

import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.io.RandomAccessFile
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ByteChannel
import java.nio.channels.SocketChannel
import java.util.UUID

@Suppress("PropertyName")
class DiscordIpcClient(
    private val applicationId: String,
    private val logger: Logger
) {
    private var channel: ByteChannel? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Handshake(
        val v: Int = 1,
        val client_id: String
    )

    @Serializable
    private data class Frame(
        val cmd: String,
        val args: JsonElement,
        val nonce: String = UUID.randomUUID().toString()
    )

    @Serializable
    private data class ActivityArgs(
        val pid: Int,
        val activity: JsonElement?
    )

    fun connect(): Boolean {
        try {
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("win")) {
                for (i in 0..9) {
                    val path = "\\\\.\\pipe\\discord-ipc-$i"
                    try {
                        val raf = RandomAccessFile(path, "rw")
                        channel = raf.channel
                        break
                    } catch (_: Exception) {
                        continue
                    }
                }
            } else {
                val path = findUnixSocketPath() ?: return false
                channel = SocketChannel.open(UnixDomainSocketAddress.of(path))
            }

            val currentChannel = channel ?: return false

            send(0, json.encodeToString(Handshake(client_id = applicationId)))

            scope.launch {
                val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                while (isActive && currentChannel.isOpen) {
                    header.clear()
                    try {
                        val read = currentChannel.read(header)
                        if (read == -1) break
                        if (read == 0) {
                            delay(100)
                            continue
                        }
                    } catch (_: AsynchronousCloseException) {
                        break
                    }
                    header.flip()
                    header.int
                    val len = header.int
                    val body = ByteBuffer.allocate(len)
                    while (body.hasRemaining()) {
                        if (currentChannel.read(body) == -1) break
                    }
                }
            }

            return true
        } catch (e: Exception) {
            logger.error(LogTag.SCROBBLER, "Failed to connect to Discord IPC: ${e.message}")
            return false
        }
    }

    fun setActivity(activity: JsonObject?) {
        val args = json.encodeToJsonElement(
            ActivityArgs(
                pid = ProcessHandle.current().pid().toInt(),
                activity = activity
            )
        )
        send(1, json.encodeToString(Frame(cmd = "SET_ACTIVITY", args = args)))
    }

    fun close() {
        scope.cancel()
        channel?.close()
        channel = null
    }

    private fun send(op: Int, payload: String) {
        val data = payload.toByteArray()
        val buffer = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(op)
        buffer.putInt(data.size)
        buffer.put(data)
        buffer.flip()
        channel?.write(buffer)
    }

    private fun findUnixSocketPath(): String? {
        val envs = listOf("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP")
        val paths = envs.mapNotNull { System.getenv(it) }.toMutableList()
        paths.add("/tmp")

        for (base in paths) {
            for (i in 0..9) {
                val path = "$base/discord-ipc-$i"
                if (File(path).exists()) return path
            }
        }
        return null
    }
}
