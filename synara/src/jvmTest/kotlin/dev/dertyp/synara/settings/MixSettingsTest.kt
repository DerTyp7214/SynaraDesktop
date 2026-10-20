package dev.dertyp.synara.settings

import dev.dertyp.data.RecommendationWindow
import dev.dertyp.data.StatsRange
import dev.dertyp.data.TopOrder
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.Config
import dev.dertyp.synara.mixes.DEFAULT_MIX_OPTIONS
import dev.dertyp.synara.mixes.MixSource
import dev.dertyp.synara.mixes.MixSpec
import dev.dertyp.synara.mixes.MixType
import dev.dertyp.synara.sync.SecretsCipher
import dev.dertyp.synara.sync.SyncedSettingsRegistry
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.InMemorySettings
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MixSettingsTest {

    private val weekMix = MixSpec.YourMix(MixSource.Recent(RecommendationWindow.WEEK), 50)
    private val dayMix = MixSpec.YourMix(MixSource.Recent(RecommendationWindow.DAY), 25)
    private val chill = MixSpec.YourMix(MixSource.Mood("chill"), 10)
    private val topMonth = MixSpec.TopSongs(StatsRange.MONTH, TopOrder.LISTEN_COUNT, 100)

    @BeforeTest
    fun setUp() {
        startTestKoin()
        ConfigFixture.resetToDefaults()
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    @Test
    fun mixOptionsStartFromTheDefaults() {
        assertEquals(DEFAULT_MIX_OPTIONS, Config.mixOptions.value)
    }

    @Test
    fun setMixOptionReplacesOnlyTheSpecOfItsType() {
        Config.setMixOption(topMonth)

        assertEquals(DEFAULT_MIX_OPTIONS + (MixType.TOP_SONGS to topMonth), Config.mixOptions.value)

        Config.setMixOption(chill)

        assertEquals(mapOf(MixType.YOUR_MIX to chill, MixType.TOP_SONGS to topMonth), Config.mixOptions.value)
    }

    @Test
    fun setMixOptionsFillsMissingTypesAndDropsSpecsUnderTheWrongType() {
        Config.setMixOptions(mapOf(MixType.YOUR_MIX to topMonth))

        assertEquals(DEFAULT_MIX_OPTIONS, Config.mixOptions.value)

        Config.setMixOptions(mapOf(MixType.YOUR_MIX to dayMix))

        assertEquals(DEFAULT_MIX_OPTIONS + (MixType.YOUR_MIX to dayMix), Config.mixOptions.value)
    }

    @Test
    fun mixOptionsAreDefaultUntilASpecDiffersAndARemoteValueIsNormalised() = runTest {
        val setting = assertNotNull(SyncedSettingsRegistry(SecretsCipher(InMemorySettings())).byKey["mix_options"])

        Config.setMixOptions(emptyMap())
        assertTrue(setting.isDefault())

        Config.setMixOption(dayMix)
        assertFalse(setting.isDefault())

        Config.setMixOption(weekMix)
        assertTrue(setting.isDefault())

        val remote = """{"YOUR_MIX":{"type":"top_songs","range":"MONTH","order":"LISTEN_COUNT","size":100},""" +
            """"TOP_SONGS":{"type":"top_songs","range":"MONTH","order":"LISTEN_COUNT","size":100}}"""
        assertTrue(setting.applyRemote(remote))
        assertEquals(DEFAULT_MIX_OPTIONS + (MixType.TOP_SONGS to topMonth), Config.mixOptions.value)
    }

    @Test
    fun pinnedMixesAreDistinctByKey() {
        Config.setPinnedMixes(listOf(weekMix, chill, weekMix.copy(size = 10), topMonth, topMonth.copy(size = 25)))

        assertEquals(listOf(weekMix, chill, topMonth), Config.pinnedMixes.value)
    }

    @Test
    fun unpinningDropsTheDownloadedMix() {
        Config.setPinnedMixes(listOf(weekMix, chill, topMonth))
        Config.setDownloadedMixes(listOf(weekMix, topMonth))

        Config.setPinnedMixes(listOf(chill, topMonth.copy(size = 10)))

        assertEquals(listOf(chill, topMonth.copy(size = 10)), Config.pinnedMixes.value)
        assertEquals(listOf(topMonth), Config.downloadedMixes.value)
    }

    @Test
    fun downloadedMixesAreDistinctByKey() {
        Config.setDownloadedMixes(listOf(chill, chill.copy(size = 100), dayMix))

        assertEquals(listOf(chill, dayMix), Config.downloadedMixes.value)
    }

    @Test
    fun pinnedMixesAreDefaultOnlyWhileEmpty() {
        val setting = assertNotNull(SyncedSettingsRegistry(SecretsCipher(InMemorySettings())).byKey["pinned_mixes"])

        assertTrue(setting.isDefault())

        Config.setPinnedMixes(listOf(weekMix))

        assertFalse(setting.isDefault())
        assertEquals("""[{"type":"your_mix","source":{"type":"recent","window":"WEEK"},"size":50}]""", setting.encodeLocal())
    }

    @Test
    fun downloadedMixSongIdsAreSetAndClearedPerKey() {
        val first = listOf(randomPlatformUUID(), randomPlatformUUID())
        val second = listOf(randomPlatformUUID())

        Config.setDownloadedMixSongIds(weekMix.key, first)
        Config.setDownloadedMixSongIds(chill.key, second)

        assertEquals(mapOf(weekMix.key to first, chill.key to second), Config.downloadedMixSongIds.value)

        Config.setDownloadedMixSongIds(weekMix.key, null)

        assertEquals(mapOf(chill.key to second), Config.downloadedMixSongIds.value)
    }
}
