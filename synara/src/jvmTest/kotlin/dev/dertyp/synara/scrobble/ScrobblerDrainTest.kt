package dev.dertyp.synara.scrobble

import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.ISongService
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.testing.InMemorySettings
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.synara.viewmodels.GlobalStateModel
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.content.TextContent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScrobblerDrainTest {
    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(scheduler)

    private val userId = randomPlatformUUID()
    private val userFlow = MutableStateFlow<User?>(User(id = userId, username = "listener"))
    private val globalState = mockk<GlobalStateModel>()
    private val scrobblerService = mockk<ScrobblerService>(relaxed = true)
    private val settings = InMemorySettings()
    private val repository = InMemoryScrobbleQueueRepository()
    private val musicBrainzService = mockk<MusicBrainzService>()
    private val songService = mockk<ISongService>()
    private val queued = testSong(0)
    private val song = testSong(1)

    private val posts = mutableListOf<HttpRequestData>()
    private var answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respondOk() }
    private val httpEngine = MockEngine.create {
        dispatcher = testDispatcher
        addHandler { request ->
            posts += request
            answer(this, request)
        }
    }

    private lateinit var listenBrainz: ListenBrainzScrobbler
    private lateinit var lastFm: LastFmScrobbler

    @BeforeTest
    fun setUp() {
        every { globalState.user } returns userFlow
        every { scrobblerService.triggeredSong } returns MutableStateFlow<TriggeredListen?>(null)
        every { scrobblerService.newSong } returns MutableStateFlow<UserSong?>(null)
        every { scrobblerService.resumed } returns MutableSharedFlow<UserSong>()
        every { scrobblerService.restarted } returns MutableSharedFlow<UserSong>()
        every { scrobblerService.resetTrigger } returns MutableSharedFlow<Unit>()
        every { scrobblerService.listenEnded } returns MutableSharedFlow<ListenEnded>()
        coEvery { songService.fetchMusicBrainzId(any()) } returns null
        coEvery { musicBrainzService.searchMb(any()) } returns null
        settings.putBoolean(SettingKey.IsListenBrainzEnabled.name, true)
        settings.putString(SettingKey.ListenBrainzToken.name, "token")
        settings.putBoolean(SettingKey.IsLastFmEnabled.name, true)
        settings.putString(SettingKey.LastFmApiKey.name, "key")
        settings.putString(SettingKey.LastFmSharedSecret.name, "secret")
        settings.putString(SettingKey.LastFmSessionKey.name, "session")
        startTestKoin(
            module {
                single { globalState }
                single<SynaraDispatchers> { TestDispatchers(testDispatcher) }
                single { scrobblerService }
                single<HttpClientEngine>(named(THIRD_PARTY_HTTP_ENGINE)) { httpEngine }
            }
        )
        val queue = ScrobbleQueue(repository)
        listenBrainz = ListenBrainzScrobbler(settings, musicBrainzService, queue, songService, AppJson)
        lastFm = LastFmScrobbler(settings, queue, AppJson)
    }

    @AfterTest
    fun tearDown() {
        stopTestKoin()
    }

    private fun drainTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            body()
        } finally {
            listenBrainz.stopScrobbler()
            lastFm.stopScrobbler()
        }
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun HttpRequestData.listenBrainzTrack(): String =
        AppJson.decodeFromString<ListenBrainzScrobbler.ListenPayload>((body as TextContent).text)
            .payload.single().trackMetadata.trackName

    private fun HttpRequestData.lastFmTrack(): String = assertNotNull((body as FormDataContent).formData["track"])

    @Test
    fun listenQueuedBehindAListenBrainzDrainDoesNotInterruptThePostInFlight() = drainTest {
        answer = { awaitCancellation() }
        repository.insert(userId, queued, 1_700_000_000L, "listenbrainz")
        listenBrainz.startScrobbler()
        advance(1_000)
        assertEquals(listOf(queued.title), posts.map { it.listenBrainzTrack() })

        listenBrainz.triggered(song, 1_700_000_100_000L)
        advance(1_000)

        assertEquals(listOf(queued.title), posts.map { it.listenBrainzTrack() })
        assertEquals(listOf(queued, song), repository.songRows.map { it.song })
        assertEquals(BaseScrobbler.ScrobbleStatus.QUEUED, listenBrainz.status.value)
    }

    @Test
    fun listenQueuedBehindALastFmDrainDoesNotInterruptThePostInFlight() = drainTest {
        answer = { awaitCancellation() }
        repository.insert(userId, queued, 1_700_000_000L, "lastfm")
        lastFm.startScrobbler()
        advance(1_000)
        assertEquals(listOf(queued.title), posts.map { it.lastFmTrack() })

        lastFm.triggered(song, 1_700_000_100_000L)
        advance(1_000)

        assertEquals(listOf(queued.title), posts.map { it.lastFmTrack() })
        assertEquals(listOf(queued, song), repository.songRows.map { it.song })
        assertEquals(BaseScrobbler.ScrobbleStatus.QUEUED, lastFm.status.value)
    }

    @Test
    fun listenBrainzPostThatArrivedBeforeAnotherListenWasQueuedIsAnsweredAndSentOnlyOnce() = drainTest {
        val response = CompletableDeferred<Unit>()
        answer = {
            response.await()
            respondOk()
        }
        repository.insert(userId, queued, 1_700_000_000L, "listenbrainz")
        listenBrainz.startScrobbler()
        advance(1_000)
        listenBrainz.triggered(song, 1_700_000_100_000L)
        advance(1_000)
        assertEquals(listOf(queued.title), posts.map { it.listenBrainzTrack() })

        response.complete(Unit)
        advance(1_000)

        assertEquals(listOf(queued.title, song.title), posts.map { it.listenBrainzTrack() })
        assertEquals(emptyList(), repository.songRows)
        assertEquals(BaseScrobbler.ScrobbleStatus.SCROBBLED, listenBrainz.status.value)
    }

    @Test
    fun lastFmPostThatArrivedBeforeAnotherListenWasQueuedIsAnsweredAndSentOnlyOnce() = drainTest {
        val response = CompletableDeferred<Unit>()
        answer = {
            response.await()
            respondOk()
        }
        repository.insert(userId, queued, 1_700_000_000L, "lastfm")
        lastFm.startScrobbler()
        advance(1_000)
        lastFm.triggered(song, 1_700_000_100_000L)
        advance(1_000)
        assertEquals(listOf(queued.title), posts.map { it.lastFmTrack() })

        response.complete(Unit)
        advance(1_000)

        assertEquals(listOf(queued.title, song.title), posts.map { it.lastFmTrack() })
        assertEquals(emptyList(), repository.songRows)
        assertEquals(BaseScrobbler.ScrobbleStatus.SCROBBLED, lastFm.status.value)
    }

    @Test
    fun listenBrainzListenCancelledDuringThePostIsQueuedForTheNextDrain() = drainTest {
        answer = { awaitCancellation() }
        val submission = launch { listenBrainz.triggered(song, 1_700_000_000_123L) }
        advance(1_000)
        assertEquals(listOf(song.title), posts.map { it.listenBrainzTrack() })
        assertEquals(emptyList(), repository.songRows)

        submission.cancelAndJoin()

        assertTrue(submission.isCancelled)
        val row = repository.songRows.single()
        assertEquals(song, row.song)
        assertEquals(1_700_000_000L, row.timestamp)
        assertEquals("listenbrainz", row.target)
        assertEquals(listOf(song.title), posts.map { it.listenBrainzTrack() })
    }

    @Test
    fun lastFmListenCancelledDuringThePostIsQueuedForTheNextDrain() = drainTest {
        answer = { awaitCancellation() }
        val submission = launch { lastFm.triggered(song, 1_700_000_000_123L) }
        advance(1_000)
        assertEquals(listOf(song.title), posts.map { it.lastFmTrack() })
        assertEquals(emptyList(), repository.songRows)

        submission.cancelAndJoin()

        assertTrue(submission.isCancelled)
        val row = repository.songRows.single()
        assertEquals(song, row.song)
        assertEquals(1_700_000_000L, row.timestamp)
        assertEquals("lastfm", row.target)
        assertEquals(listOf(song.title), posts.map { it.lastFmTrack() })
    }
}
