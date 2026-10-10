package dev.dertyp.synara.scrobble

import dev.dertyp.data.User
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.db.RawScrobbleQueueEntry
import dev.dertyp.synara.db.ScrobbleQueueEntry
import dev.dertyp.synara.db.ScrobbleQueueRepository
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.synara.viewmodels.GlobalStateModel
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrobbleQueueTest {
    private val testDispatcher = StandardTestDispatcher(TestCoroutineScheduler())

    private val userId = randomPlatformUUID()
    private val userFlow = MutableStateFlow<User?>(null)
    private val globalState = mockk<GlobalStateModel>()
    private val scrobblerService = mockk<ScrobblerService>(relaxed = true)
    private val repository = mockk<ScrobbleQueueRepository>(relaxed = true)
    private val logger = mockk<Logger>(relaxed = true)
    private val song = testSong(0)

    private lateinit var queue: ScrobbleQueue

    @BeforeTest
    fun setUp() {
        every { globalState.user } returns userFlow
        startTestKoin(
            module {
                single { globalState }
                single<SynaraDispatchers> { TestDispatchers(testDispatcher) }
                single { scrobblerService }
                single<Logger> { this@ScrobbleQueueTest.logger }
            }
        )
        queue = ScrobbleQueue(repository)
    }

    @AfterTest
    fun tearDown() {
        stopTestKoin()
    }

    private fun signIn() {
        userFlow.value = User(id = userId, username = "listener")
    }

    @Test
    fun aListenWithoutAnyUserIsDroppedAndLoggedOnce() = runTest(testDispatcher) {
        queue.push(song, 1_700_000_000L, "lastfm")
        queue.pushRaw(song.id.toString(), "payload", 1_700_000_000_000L, "server")
        queue.push(song, 1_700_000_100L, "lastfm")

        verify { repository wasNot Called }
        verify(exactly = 1) { logger.warning(any(), any(), any()) }
    }

    @Test
    fun aDropIsLoggedAgainOnlyAfterAUserWasPresentInBetween() = runTest(testDispatcher) {
        queue.push(song, 1_700_000_000L, "lastfm")
        signIn()
        queue.push(song, 1_700_000_100L, "lastfm")
        verify(exactly = 1) { logger.warning(any(), any(), any()) }

        userFlow.value = null
        queue.push(song, 1_700_000_200L, "lastfm")
        queue.pushRaw(song.id.toString(), "payload", 1_700_000_300_000L, "server")

        verify(exactly = 2) { logger.warning(any(), any(), any()) }
        coVerify(exactly = 1) { repository.insert(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.insertRaw(any(), any(), any(), any(), any()) }
    }

    @Test
    fun withoutAUserTheQueueReadsAsEmpty() = runTest(testDispatcher) {
        assertTrue(queue.isEmpty("lastfm"))
        assertNull(queue.peek("lastfm"))
        assertEquals(emptyList(), queue.getAllRaw("server"))
        verify { repository wasNot Called }
    }

    @Test
    fun readingWithoutAUserLogsNothing() = runTest(testDispatcher) {
        queue.isEmpty("lastfm")
        queue.peek("lastfm")
        queue.getAllRaw("server")

        verify(exactly = 0) { logger.warning(any(), any(), any()) }
    }

    @Test
    fun pushInsertsTheSongForTheSignedInUser() = runTest(testDispatcher) {
        signIn()

        queue.push(song, 1_700_000_000L, "lastfm")

        coVerify(exactly = 1) {
            repository.insert(userId = userId, song = song, timestamp = 1_700_000_000L, target = "lastfm")
        }
    }

    @Test
    fun pushRawInsertsThePayloadForTheSignedInUser() = runTest(testDispatcher) {
        signIn()

        queue.pushRaw(song.id.toString(), "payload", 1_700_000_000_000L, "server")

        coVerify(exactly = 1) {
            repository.insertRaw(
                userId = userId,
                songId = song.id.toString(),
                payload = "payload",
                timestamp = 1_700_000_000_000L,
                target = "server"
            )
        }
    }

    @Test
    fun rawEntriesOfTheSignedInUserAreReturned() = runTest(testDispatcher) {
        signIn()
        val entries = listOf(RawScrobbleQueueEntry(3L, userId, "payload", 1_700_000_000_000L, "server"))
        coEvery { repository.getAllRaw(userId, "server") } returns entries

        assertEquals(entries, queue.getAllRaw("server"))
    }

    @Test
    fun queueWithoutRowsIsEmpty() = runTest(testDispatcher) {
        signIn()
        coEvery { repository.getCount(userId, "lastfm") } returns 0L

        assertTrue(queue.isEmpty("lastfm"))
    }

    @Test
    fun queueWithOneRowIsNotEmpty() = runTest(testDispatcher) {
        signIn()
        coEvery { repository.getCount(userId, "lastfm") } returns 1L

        assertFalse(queue.isEmpty("lastfm"))
    }

    @Test
    fun peekMapsEveryFieldOfTheOldestRow() = runTest(testDispatcher) {
        signIn()
        coEvery { repository.peek(userId, "lastfm") } returns
            ScrobbleQueueEntry(id = 7L, userId = userId, song = song, timestamp = 1_700_000_000L, target = "lastfm")

        assertEquals(
            QueuedScrobble(id = 7L, userId = userId, song = song, timestamp = 1_700_000_000L, target = "lastfm"),
            queue.peek("lastfm")
        )
    }

    @Test
    fun popDeletesTheRowEvenWithoutAUser() = runTest(testDispatcher) {
        queue.pop(5L)

        coVerify(exactly = 1) { repository.delete(5L) }
    }
}
