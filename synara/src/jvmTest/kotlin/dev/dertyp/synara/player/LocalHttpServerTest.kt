package dev.dertyp.synara.player

import dev.dertyp.randomPlatformUUID
import io.ktor.http.HttpStatusCode
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalHttpServerTest {
    private companion object {
        const val PORT = 10767
    }

    private val api = mockk<ISynaraApi>(relaxed = true)
    private val server = LocalHttpServer(api)
    private val id = randomPlatformUUID().toString()

    @Test
    fun hostHeaderNamingTheLoopbackAddressOrLocalhostWithThePortIsLocal() {
        assertTrue(server.isLocalHost("127.0.0.1:$PORT", PORT))
        assertTrue(server.isLocalHost("localhost:$PORT", PORT))
        assertTrue(server.isLocalHost("LocalHost:$PORT", PORT))
    }

    @Test
    fun hostHeaderOfAnotherNameOrPortIsNotLocal() {
        assertFalse(server.isLocalHost(null, PORT))
        assertFalse(server.isLocalHost("", PORT))
        assertFalse(server.isLocalHost("rebound.example:$PORT", PORT))
        assertFalse(server.isLocalHost("localhost.example:$PORT", PORT))
        assertFalse(server.isLocalHost("127.0.0.1", PORT))
        assertFalse(server.isLocalHost("localhost", PORT))
        assertFalse(server.isLocalHost("127.0.0.1:${PORT + 1}", PORT))
        assertFalse(server.isLocalHost("localhost:${PORT}0", PORT))
        assertFalse(server.isLocalHost("127.0.0.2:$PORT", PORT))
    }

    @Test
    fun playCallsTheApiOfTheItemType() {
        assertEquals(HttpStatusCode.OK, server.performAction("play", id, "song", null))
        assertEquals(HttpStatusCode.OK, server.performAction("play", id, "album", null))
        assertEquals(HttpStatusCode.OK, server.performAction("play", id, "artist", null))
        assertEquals(HttpStatusCode.OK, server.performAction("play", id, "playlist", null))

        verify(exactly = 1) { api.PlaySong(id) }
        verify(exactly = 1) { api.PlayAlbum(id) }
        verify(exactly = 1) { api.PlayArtist(id) }
        verify(exactly = 1) { api.PlayPlaylist(id) }
        confirmVerified(api)
    }

    @Test
    fun playNextAndAddToQueuePassTheIdAndTheItemType() {
        assertEquals(HttpStatusCode.OK, server.performAction("play-next", id, "album", null))
        assertEquals(HttpStatusCode.OK, server.performAction("add-to-queue", id, "song", null))

        verify(exactly = 1) { api.PlayNext(id, "album") }
        verify(exactly = 1) { api.AddToQueue(id, "song") }
        confirmVerified(api)
    }

    @Test
    fun anUnknownItemTypeIsABadRequestAndReachesNoApiCall() {
        for (action in listOf("play", "play-next", "add-to-queue")) {
            assertEquals(HttpStatusCode.BadRequest, server.performAction(action, id, "podcast", null), action)
            assertEquals(HttpStatusCode.BadRequest, server.performAction(action, id, "", null), action)
        }

        confirmVerified(api)
    }

    @Test
    fun aMissingOrInvalidIdIsABadRequestAndReachesNoApiCall() {
        for (action in listOf("play", "play-next", "add-to-queue")) {
            assertEquals(HttpStatusCode.BadRequest, server.performAction(action, null, "song", null), action)
            assertEquals(HttpStatusCode.BadRequest, server.performAction(action, "", "song", null), action)
            assertEquals(HttpStatusCode.BadRequest, server.performAction(action, "not-an-id", "song", null), action)
            assertEquals(HttpStatusCode.BadRequest, server.performAction(action, "$id-x", "song", null), action)
        }

        confirmVerified(api)
    }

    @Test
    fun playQueueItemNeedsANumericQueueId() {
        assertEquals(HttpStatusCode.OK, server.performAction("play-queue-item", null, "song", "42"))
        assertEquals(HttpStatusCode.BadRequest, server.performAction("play-queue-item", null, "song", null))
        assertEquals(HttpStatusCode.BadRequest, server.performAction("play-queue-item", null, "song", "first"))
        assertEquals(HttpStatusCode.BadRequest, server.performAction("play-queue-item", id, "song", id))

        verify(exactly = 1) { api.PlayQueueItem("42") }
        confirmVerified(api)
    }

    @Test
    fun anUnknownActionIsNotFound() {
        assertEquals(HttpStatusCode.NotFound, server.performAction("shuffle", id, "song", null))
        assertEquals(HttpStatusCode.NotFound, server.performAction(null, id, "song", null))

        confirmVerified(api)
    }
}
