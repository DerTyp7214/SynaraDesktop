package dev.dertyp.synara.scrobble

import dev.dertyp.data.User
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
import io.mockk.Called
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrobblerGateTest {
    private val testDispatcher = StandardTestDispatcher(TestCoroutineScheduler())

    private val userFlow = MutableStateFlow<User?>(User(id = randomPlatformUUID(), username = "listener"))
    private val globalState = mockk<GlobalStateModel>()
    private val scrobblerService = mockk<ScrobblerService>(relaxed = true)
    private val settings = InMemorySettings()
    private val queue = mockk<ScrobbleQueue>()
    private val musicBrainzService = mockk<MusicBrainzService>()
    private val songService = mockk<ISongService>()
    private val song = testSong(0)

    private lateinit var listenBrainz: ListenBrainzScrobbler
    private lateinit var lastFm: LastFmScrobbler

    @BeforeTest
    fun setUp() {
        every { globalState.user } returns userFlow
        coEvery { queue.isEmpty(any()) } returns false
        coEvery { queue.push(any(), any(), any()) } just Runs
        startTestKoin(
            module {
                single { globalState }
                single<SynaraDispatchers> { TestDispatchers(testDispatcher) }
                single { scrobblerService }
            }
        )
        listenBrainz = ListenBrainzScrobbler(settings, musicBrainzService, queue, songService, AppJson)
        lastFm = LastFmScrobbler(settings, queue, AppJson)
    }

    @AfterTest
    fun tearDown() {
        stopTestKoin()
    }

    private fun enableListenBrainz(token: String?) {
        settings.putBoolean(SettingKey.IsListenBrainzEnabled.name, true)
        token?.let { settings.putString(SettingKey.ListenBrainzToken.name, it) }
    }

    private fun configureLastFm(enabled: Boolean, apiKey: String?, sharedSecret: String?, sessionKey: String?) {
        settings.putBoolean(SettingKey.IsLastFmEnabled.name, enabled)
        apiKey?.let { settings.putString(SettingKey.LastFmApiKey.name, it) }
        sharedSecret?.let { settings.putString(SettingKey.LastFmSharedSecret.name, it) }
        sessionKey?.let { settings.putString(SettingKey.LastFmSessionKey.name, it) }
    }

    @Test
    fun disabledListenBrainzTouchesNeitherQueueNorServices() = runTest(testDispatcher) {
        settings.putBoolean(SettingKey.IsListenBrainzEnabled.name, false)
        settings.putString(SettingKey.ListenBrainzToken.name, "token")

        listenBrainz.triggered(song, 1_700_000_000_123L)

        verify { listOf(queue, musicBrainzService, songService) wasNot Called }
    }

    @Test
    fun listenBrainzWithoutATokenTouchesNeitherQueueNorServices() = runTest(testDispatcher) {
        enableListenBrainz(token = null)

        listenBrainz.triggered(song, 1_700_000_000_123L)

        verify { listOf(queue, musicBrainzService, songService) wasNot Called }
    }

    @Test
    fun listenBrainzQueuesBehindPendingListensWithoutALookup() = runTest(testDispatcher) {
        enableListenBrainz(token = "token")

        listenBrainz.triggered(song, 1_700_000_000_123L)

        assertEquals(BaseScrobbler.ScrobbleStatus.QUEUED, listenBrainz.status.value)
        coVerify(exactly = 1) { queue.isEmpty("listenbrainz") }
        coVerify(exactly = 1) { queue.push(song, 1_700_000_000L, "listenbrainz") }
        verify { listOf(musicBrainzService, songService) wasNot Called }
    }

    @Test
    fun disabledLastFmDoesNotTouchTheQueue() = runTest(testDispatcher) {
        configureLastFm(enabled = false, apiKey = "key", sharedSecret = "secret", sessionKey = "session")

        lastFm.triggered(song, 1_700_000_000_123L)

        verify { queue wasNot Called }
    }

    @Test
    fun lastFmWithoutAnApiKeyDoesNotTouchTheQueue() = runTest(testDispatcher) {
        configureLastFm(enabled = true, apiKey = null, sharedSecret = "secret", sessionKey = "session")

        lastFm.triggered(song, 1_700_000_000_123L)

        verify { queue wasNot Called }
    }

    @Test
    fun lastFmWithoutASharedSecretDoesNotTouchTheQueue() = runTest(testDispatcher) {
        configureLastFm(enabled = true, apiKey = "key", sharedSecret = null, sessionKey = "session")

        lastFm.triggered(song, 1_700_000_000_123L)

        verify { queue wasNot Called }
    }

    @Test
    fun lastFmWithoutASessionKeyDoesNotTouchTheQueue() = runTest(testDispatcher) {
        configureLastFm(enabled = true, apiKey = "key", sharedSecret = "secret", sessionKey = null)

        lastFm.triggered(song, 1_700_000_000_123L)

        verify { queue wasNot Called }
    }

    @Test
    fun lastFmQueuesBehindPendingListensWithASecondsTimestamp() = runTest(testDispatcher) {
        configureLastFm(enabled = true, apiKey = "key", sharedSecret = "secret", sessionKey = "session")

        lastFm.triggered(song, 1_700_000_000_123L)

        assertEquals(BaseScrobbler.ScrobbleStatus.QUEUED, lastFm.status.value)
        coVerify(exactly = 1) { queue.isEmpty("lastfm") }
        coVerify(exactly = 1) { queue.push(song, 1_700_000_000L, "lastfm") }
    }

    @Test
    fun mobileSessionWithoutAnApiKeyIsNull() = runTest(testDispatcher) {
        configureLastFm(enabled = true, apiKey = null, sharedSecret = "secret", sessionKey = null)

        assertNull(lastFm.getMobileSession("listener", "password"))
    }

    @Test
    fun mobileSessionWithoutASharedSecretIsNull() = runTest(testDispatcher) {
        configureLastFm(enabled = true, apiKey = "key", sharedSecret = null, sessionKey = null)

        assertNull(lastFm.getMobileSession("listener", "password"))
    }

    @Test
    fun playingNowPayloadOmitsTheListenTimeAndUsesSnakeCaseKeys() {
        val json = Json(AppJson) { encodeDefaults = false }
        val payload = ListenBrainzScrobbler.ListenPayload(
            listenType = ListenBrainzScrobbler.ListenType.PLAYING_NOW,
            payload = listOf(
                ListenBrainzScrobbler.ListenData(
                    listenedAt = null,
                    trackMetadata = ListenBrainzScrobbler.TrackMetadata(
                        artistName = "Artist",
                        trackName = "Track",
                        additionalInfo = ListenBrainzScrobbler.AdditionalInfo(
                            mediaPlayer = "Synara",
                            submissionClient = "Synara Desktop",
                            submissionClientVersion = "1",
                            durationMs = 240_000L
                        )
                    )
                )
            )
        )

        val encoded = json.parseToJsonElement(json.encodeToString(payload)).jsonObject

        assertEquals(setOf("listen_type", "payload"), encoded.keys)
        assertEquals(JsonPrimitive("playing_now"), encoded["listen_type"])
        val data = encoded.getValue("payload").jsonArray.single().jsonObject
        assertEquals(setOf("track_metadata"), data.keys)
        val metadata = data.getValue("track_metadata").jsonObject
        assertEquals(setOf("artist_name", "track_name", "additional_info"), metadata.keys)
        assertEquals(
            setOf("media_player", "submission_client", "submission_client_version", "duration_ms"),
            metadata.getValue("additional_info").jsonObject.keys
        )
    }
}
