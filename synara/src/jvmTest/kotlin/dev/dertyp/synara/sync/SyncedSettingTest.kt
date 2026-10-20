package dev.dertyp.synara.sync

import androidx.compose.ui.graphics.Color
import dev.dertyp.serializers.AppJson
import dev.dertyp.synara.Config
import dev.dertyp.synara.settings.VisualizerPreset
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.InMemorySettings
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.ui.IconPackType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncedSettingTest {

    private class Fixture(
        default: String = "default",
        decoder: (String) -> String? = { AppJson.decodeFromString<String>(it) }
    ) {
        val flow = MutableStateFlow(default)
        val applied = mutableListOf<String>()
        val setting = SyncedSetting(
            key = "key",
            group = SyncGroup.GENERAL,
            flow = flow,
            default = default,
            encoder = { AppJson.encodeToString(it) },
            decoder = decoder
        ) {
            applied += it
            flow.value = it
        }
    }

    private val derivedKey = DerivedKey(
        encKey = ByteArray(32) { it.toByte() },
        macKey = ByteArray(32) { (it + 100).toByte() },
        saltFingerprint = "fingerprint"
    )

    private fun json(value: String): String = AppJson.encodeToString(value)

    @BeforeTest
    fun setUp() {
        startTestKoin()
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    @Test
    fun decodingNullGivesTheDefault() {
        val fixture = Fixture()
        fixture.flow.value = "changed"

        assertEquals("default", fixture.setting.decode(null))
    }

    @Test
    fun throwingDecoderGivesNullAndTheRemoteValueIsNotApplied() = runTest {
        val fixture = Fixture(decoder = { throw IllegalStateException("undecodable") })

        assertNull(fixture.setting.decode(json("remote")))
        assertFalse(fixture.setting.applyRemote(json("remote")))
        assertTrue(fixture.applied.isEmpty())
        assertEquals("default", fixture.flow.value)
    }

    @Test
    fun decoderReturningNullGivesNullAndTheRemoteValueIsNotApplied() = runTest {
        val fixture = Fixture(decoder = { null })

        assertNull(fixture.setting.decode(json("remote")))
        assertFalse(fixture.setting.applyRemote(json("remote")))
        assertTrue(fixture.applied.isEmpty())
        assertEquals("default", fixture.flow.value)
    }

    @Test
    fun applyingNullResetsAChangedValueToTheDefault() = runTest {
        val fixture = Fixture()
        fixture.flow.value = "changed"

        assertTrue(fixture.setting.applyRemote(null))

        assertEquals(listOf("default"), fixture.applied)
        assertEquals("default", fixture.flow.value)
    }

    @Test
    fun applyingNullToADefaultValueChangesNothing() = runTest {
        val fixture = Fixture()

        assertFalse(fixture.setting.applyRemote(null))

        assertTrue(fixture.applied.isEmpty())
    }

    @Test
    fun applyingADifferentValueCallsTheSetterOnce() = runTest {
        val fixture = Fixture()

        assertTrue(fixture.setting.applyRemote(json("remote")))

        assertEquals(listOf("remote"), fixture.applied)
    }

    @Test
    fun applyingTheCurrentValueDoesNotCallTheSetter() = runTest {
        val fixture = Fixture()
        fixture.flow.value = "same"

        assertFalse(fixture.setting.applyRemote(json("same")))

        assertTrue(fixture.applied.isEmpty())
    }

    @Test
    fun registryChangesEmitNothingOnSubscribeAndOncePerChange() = runTest {
        val registry = SyncedSettingsRegistry(SecretsCipher(InMemorySettings()))
        val initial = Config.darkTheme.value
        var emissions = 0
        try {
            backgroundScope.launch { registry.changes().collect { emissions++ } }
            runCurrent()
            assertEquals(0, emissions)

            Config.setDarkTheme(!initial)
            runCurrent()
            assertEquals(1, emissions)

            Config.setDarkTheme(initial)
            runCurrent()
            assertEquals(2, emissions)
        } finally {
            Config.setDarkTheme(initial)
        }
    }

    @Test
    fun themeTravelsAsDarkOrLightAndAnyOtherValueDecodesToNull() {
        val registry = SyncedSettingsRegistry(SecretsCipher(InMemorySettings()))
        val theme = assertNotNull(registry.byKey["theme_mode"])
        val initial = Config.darkTheme.value
        try {
            Config.setDarkTheme(true)
            assertEquals(json("DARK"), theme.encodeLocal())
            Config.setDarkTheme(false)
            assertEquals(json("LIGHT"), theme.encodeLocal())

            assertEquals(true, theme.decode(json("DARK")))
            assertEquals(false, theme.decode(json("LIGHT")))
            assertNull(theme.decode(json("SYSTEM")))
        } finally {
            Config.setDarkTheme(initial)
        }
    }

    @Test
    fun nullableStringEncodesNullAsNull() {
        val registry = SyncedSettingsRegistry(SecretsCipher(InMemorySettings()))
        val language = assertNotNull(registry.byKey["desktop.language"])
        val initial = Config.language.value
        try {
            Config.setLanguage("de")
            assertEquals(json("de"), language.encodeLocal())
            Config.setLanguage(null)
            assertNull(language.encodeLocal())
        } finally {
            Config.setLanguage(initial)
        }
    }

    @Test
    fun registryKeysAndGroupsAreTheWireContract() {
        val registry = SyncedSettingsRegistry(SecretsCipher(InMemorySettings()))
        val expected = listOf(
            "theme_mode" to SyncGroup.GENERAL,
            "show_remaining_time" to SyncGroup.GENERAL,
            "show_title_tags_in_text" to SyncGroup.GENERAL,
            "particle_multiplier" to SyncGroup.GENERAL,
            "is_server_scrobbling_enabled" to SyncGroup.GENERAL,
            "is_podcasts_enabled" to SyncGroup.GENERAL,
            "mix_options" to SyncGroup.GENERAL,
            "pinned_mixes" to SyncGroup.GENERAL,
            "desktop.light_theme_color" to SyncGroup.GENERAL,
            "desktop.dark_theme_color" to SyncGroup.GENERAL,
            "desktop.use_song_color" to SyncGroup.GENERAL,
            "desktop.icon_style" to SyncGroup.GENERAL,
            "desktop.icon_filled" to SyncGroup.GENERAL,
            "desktop.icon_pack" to SyncGroup.GENERAL,
            "desktop.visualizer_presets" to SyncGroup.GENERAL,
            "desktop.visualizer_active_preset" to SyncGroup.GENERAL,
            "desktop.streaming_quality" to SyncGroup.GENERAL,
            "desktop.fullscreen_hud_auto_hide" to SyncGroup.GENERAL,
            "desktop.fullscreen_hud_auto_hide_delay" to SyncGroup.GENERAL,
            "desktop.language" to SyncGroup.GENERAL,
            "is_listenbrainz_enabled" to SyncGroup.SECRETS,
            "is_lastfm_enabled" to SyncGroup.SECRETS,
            "desktop.lastfm_username" to SyncGroup.SECRETS,
            "secret.listenbrainz_token" to SyncGroup.SECRETS,
            "secret.lastfm_session_key" to SyncGroup.SECRETS,
            "secret.lastfm_api_key" to SyncGroup.SECRETS,
            "secret.lastfm_shared_secret" to SyncGroup.SECRETS
        )

        assertEquals(expected, registry.all.map { it.key to it.group })
        assertEquals(expected.filter { it.second == SyncGroup.GENERAL }.map { it.first }, registry.general.map { it.key })
        assertEquals(expected.filter { it.second == SyncGroup.SECRETS }.map { it.first }, registry.secrets.map { it.key })
        assertEquals(expected.map { it.first }, registry.byKey.keys.toList())
        assertFalse(registry.byKey.containsKey(SecretsCipher.META_KEY))
    }

    @Test
    fun colourTravelsAsItsArgbIntAndNullResetsToTheDefault() = runTest {
        val registry = SyncedSettingsRegistry(SecretsCipher(InMemorySettings()))
        val colour = assertNotNull(registry.byKey["desktop.dark_theme_color"])
        val initial = Config.darkThemeColor.value
        val remote = 0xFF445566.toInt().toString()
        try {
            Config.setDarkThemeColor(Color(0xFF112233.toInt()))
            assertEquals("-15654349", colour.encodeLocal())
            assertEquals<Any?>(Color(0xFF112233.toInt()), colour.decode("-15654349"))
            assertTrue(colour.matchesLocal("-15654349"))
            assertFalse(colour.matchesLocal(remote))

            assertTrue(colour.applyRemote(remote))
            assertEquals(Color(0xFF445566.toInt()), Config.darkThemeColor.value)
            assertEquals(remote, colour.encodeLocal())
            assertFalse(colour.applyRemote(remote))

            assertNull(colour.decode(json("red")))
            assertFalse(colour.applyRemote(json("red")))
            assertEquals(Color(0xFF445566.toInt()), Config.darkThemeColor.value)

            assertTrue(colour.applyRemote(null))
            assertEquals(Color(0xFFFF0000.toInt()), Config.darkThemeColor.value)
            assertTrue(colour.isDefault())
        } finally {
            Config.setDarkThemeColor(initial)
        }
    }

    @Test
    fun enumTravelsAsItsNameAndAnUnknownNameIsNotApplied() = runTest {
        val registry = SyncedSettingsRegistry(SecretsCipher(InMemorySettings()))
        val pack = assertNotNull(registry.byKey["desktop.icon_pack"])
        val initial = Config.iconPack.value
        try {
            Config.setIconPack(IconPackType.Lucide)
            assertEquals(json("Lucide"), pack.encodeLocal())
            assertEquals<Any?>(IconPackType.Phosphor, pack.decode(json("Phosphor")))
            assertFalse(pack.matchesLocal(json("Phosphor")))

            assertTrue(pack.applyRemote(json("Phosphor")))
            assertEquals(IconPackType.Phosphor, Config.iconPack.value)
            assertEquals(json("Phosphor"), pack.encodeLocal())

            assertNull(pack.decode(json("NoSuchPack")))
            assertFalse(pack.applyRemote(json("NoSuchPack")))
            assertEquals(IconPackType.Phosphor, Config.iconPack.value)

            assertTrue(pack.applyRemote(null))
            assertEquals(IconPackType.MaterialSymbols, Config.iconPack.value)
            assertTrue(pack.isDefault())
        } finally {
            Config.setIconPack(initial)
        }
    }

    @Test
    fun floatTravelsAsAJsonNumberAndNullResetsToTheDefault() = runTest {
        val registry = SyncedSettingsRegistry(SecretsCipher(InMemorySettings()))
        val multiplier = assertNotNull(registry.byKey["particle_multiplier"])
        val initial = Config.particleMultiplier.value
        try {
            Config.setParticleMultiplier(1.5f)
            assertEquals("1.5", multiplier.encodeLocal())
            assertEquals<Any?>(0.25f, multiplier.decode("0.25"))
            assertEquals<Any?>(3f, multiplier.decode("3"))

            assertTrue(multiplier.applyRemote("0.25"))
            assertEquals(0.25f, Config.particleMultiplier.value)
            assertEquals("0.25", multiplier.encodeLocal())

            assertNull(multiplier.decode(json("fast")))
            assertFalse(multiplier.applyRemote(json("fast")))
            assertEquals(0.25f, Config.particleMultiplier.value)

            assertTrue(multiplier.applyRemote(null))
            assertEquals(2.5f, Config.particleMultiplier.value)
            assertTrue(multiplier.isDefault())
        } finally {
            Config.setParticleMultiplier(initial)
        }
    }

    @Test
    fun visualizerPresetsRoundTripAndBuiltInEntriesOfARemoteValueAreDropped() = runTest {
        val registry = SyncedSettingsRegistry(SecretsCipher(InMemorySettings()))
        val presets = assertNotNull(registry.byKey["desktop.visualizer_presets"])
        val initial = Config.userVisualizerPresets.value
        val user = VisualizerPreset(id = "user-preset", name = "Mine", barWidth = 7f, mirrored = false)
        val flaggedBuiltIn = VisualizerPreset(id = "flagged-preset", name = "Flagged", builtIn = true)
        try {
            Config.setUserVisualizerPresets(listOf(user))
            val encoded = assertNotNull(presets.encodeLocal())
            assertEquals<Any?>(listOf(user), presets.decode(encoded))
            assertTrue(presets.matchesLocal(encoded))

            Config.setUserVisualizerPresets(emptyList())
            assertTrue(presets.isDefault())
            assertFalse(presets.matchesLocal(encoded))

            val remote = AppJson.encodeToString(listOf(flaggedBuiltIn, user))
            assertEquals<Any?>(listOf(user), presets.decode(remote))
            assertTrue(presets.applyRemote(remote))
            assertEquals(listOf(user), Config.userVisualizerPresets.value)
            assertTrue(presets.matchesLocal(remote))

            assertNull(presets.decode("{not json"))
            assertFalse(presets.applyRemote("{not json"))
            assertEquals(listOf(user), Config.userVisualizerPresets.value)

            assertTrue(presets.applyRemote(null))
            assertEquals(emptyList(), Config.userVisualizerPresets.value)
        } finally {
            Config.setUserVisualizerPresets(initial)
        }
    }

    @Test
    fun secretEncodesToNullWhileLockedOrEmptyAndToAnEnvelopeBoundToItsKeyWhileUnlocked() {
        val cipher = SecretsCipher(InMemorySettings())
        val registry = SyncedSettingsRegistry(cipher)
        val token = assertNotNull(registry.byKey["secret.listenbrainz_token"])
        val other = assertNotNull(registry.byKey["secret.lastfm_api_key"])
        val initial = Config.listenBrainzToken.value
        try {
            Config.setListenBrainzToken("listenbrainz-secret-value")
            assertNull(token.encodeLocal())

            cipher.remember(derivedKey)
            val encoded = assertNotNull(token.encodeLocal())
            assertFalse(encoded.contains("listenbrainz-secret-value"))
            assertEquals("listenbrainz-secret-value", cipher.decryptFromJson("secret.listenbrainz_token", encoded))
            assertEquals<Any?>("listenbrainz-secret-value", token.decode(encoded))
            assertTrue(token.matchesLocal(encoded))
            assertNull(other.decode(encoded))

            Config.setListenBrainzToken("")
            assertNull(token.encodeLocal())
            assertTrue(token.isDefault())
        } finally {
            Config.setListenBrainzToken(initial)
        }
    }

    @Test
    fun secretDecodesToNullAndIsNotAppliedWhileLockedAndIsAppliedOnceUnlocked() = runTest {
        val cipher = SecretsCipher(InMemorySettings())
        val registry = SyncedSettingsRegistry(cipher)
        val token = assertNotNull(registry.byKey["secret.listenbrainz_token"])
        val initial = Config.listenBrainzToken.value
        try {
            Config.setListenBrainzToken("")
            cipher.remember(derivedKey)
            val remote = assertNotNull(cipher.encryptToJson("secret.listenbrainz_token", "remote-secret-value"))
            cipher.forget()

            assertNull(token.decode(remote))
            assertFalse(token.applyRemote(remote))
            assertEquals("", Config.listenBrainzToken.value)

            cipher.remember(derivedKey)
            assertEquals<Any?>("remote-secret-value", token.decode(remote))
            assertFalse(token.matchesLocal(remote))
            assertTrue(token.applyRemote(remote))
            assertEquals("remote-secret-value", Config.listenBrainzToken.value)
            assertTrue(token.matchesLocal(remote))

            assertTrue(token.applyRemote(null))
            assertEquals("", Config.listenBrainzToken.value)
        } finally {
            Config.setListenBrainzToken(initial)
        }
    }
}
