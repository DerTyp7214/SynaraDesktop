package dev.dertyp.synara.mixes

import dev.dertyp.data.RecommendationWindow
import dev.dertyp.data.StatsRange
import dev.dertyp.data.TopOrder
import dev.dertyp.serializers.AppJson
import kotlin.test.Test
import kotlin.test.assertEquals

class MixSpecTest {

    @Test
    fun yourMixKeysMatchTheRecommendationKeys() {
        RecommendationWindow.entries.forEach { window ->
            assertEquals("mix:${window.name}", MixSpec.YourMix(MixSource.Recent(window), 10).key)
        }
        assertEquals("mix:WEEK", MixSpec.YourMix(MixSource.Recent(RecommendationWindow.WEEK), 50).key)
        assertEquals("mood:chill", MixSpec.YourMix(MixSource.Mood("chill"), 100).key)
    }

    @Test
    fun topSongsKeyEncodesRangeAndOrderButNotSize() {
        assertEquals("top:WEEK:LISTENED_MS", MixSpec.TopSongs(StatsRange.WEEK, TopOrder.LISTENED_MS, 50).key)
        assertEquals(
            MixSpec.TopSongs(StatsRange.ALL_TIME, TopOrder.LISTEN_COUNT, 10).key,
            MixSpec.TopSongs(StatsRange.ALL_TIME, TopOrder.LISTEN_COUNT, 100).key
        )
    }

    @Test
    fun typesAndDefaults() {
        assertEquals(MixType.YOUR_MIX, MixSpec.YourMix(MixSource.Mood("x"), 10).type)
        assertEquals(MixType.TOP_SONGS, MixSpec.TopSongs(StatsRange.DAY, TopOrder.LISTEN_COUNT, 10).type)
        assertEquals(
            MixSpec.YourMix(MixSource.Recent(RecommendationWindow.WEEK), 50),
            DEFAULT_MIX_OPTIONS[MixType.YOUR_MIX]
        )
        assertEquals(MixSpec.TopSongs(StatsRange.WEEK, TopOrder.LISTENED_MS, 50), DEFAULT_MIX_OPTIONS[MixType.TOP_SONGS])
        assertEquals(MixType.entries.toSet(), DEFAULT_MIX_OPTIONS.keys)
        DEFAULT_MIX_OPTIONS.forEach { (type, spec) -> assertEquals(type, spec.type) }
        MixType.entries.forEach { assertEquals(it, it.defaultSpec.type) }
        assertEquals(listOf(10, 25, 50, 100), MIX_SIZE_OPTIONS)
        assertEquals(50, DEFAULT_MIX_SIZE)
    }

    @Test
    fun specListsEncodeToTheMobileWireFormat() {
        val specs = listOf(
            MixSpec.YourMix(MixSource.Recent(RecommendationWindow.MONTH), 25),
            MixSpec.YourMix(MixSource.Mood("focus"), 10),
            MixSpec.TopSongs(StatsRange.LAST_YEAR, TopOrder.LISTEN_COUNT, 100)
        )
        val wire = """[{"type":"your_mix","source":{"type":"recent","window":"MONTH"},"size":25},""" +
            """{"type":"your_mix","source":{"type":"mood","mood":"focus"},"size":10},""" +
            """{"type":"top_songs","range":"LAST_YEAR","order":"LISTEN_COUNT","size":100}]"""

        assertEquals(wire, AppJson.encodeToString<List<MixSpec>>(specs))
        assertEquals(specs, AppJson.decodeFromString<List<MixSpec>>(wire))
    }

    @Test
    fun decodesMobilePinnedMixes() {
        val wire = """[{"type":"your_mix","source":{"type":"recent","window":"WEEK"},"size":50}]"""

        assertEquals(
            listOf(MixSpec.YourMix(MixSource.Recent(RecommendationWindow.WEEK), 50)),
            AppJson.decodeFromString<List<MixSpec>>(wire)
        )
    }

    @Test
    fun mixOptionsEncodeToTheMobileWireFormat() {
        val wire = """{"YOUR_MIX":{"type":"your_mix","source":{"type":"recent","window":"WEEK"},"size":50},""" +
            """"TOP_SONGS":{"type":"top_songs","range":"WEEK","order":"LISTENED_MS","size":50}}"""

        assertEquals(wire, AppJson.encodeToString<Map<MixType, MixSpec>>(DEFAULT_MIX_OPTIONS))
        assertEquals(DEFAULT_MIX_OPTIONS, AppJson.decodeFromString<Map<MixType, MixSpec>>(wire))
    }

    @Test
    fun decodesMobileMixOptionsWithAMoodSource() {
        val wire = """{"YOUR_MIX":{"type":"your_mix","source":{"type":"mood","mood":"chill"},"size":25},""" +
            """"TOP_SONGS":{"type":"top_songs","range":"ALL_TIME","order":"LISTEN_COUNT","size":100}}"""

        assertEquals(
            mapOf(
                MixType.YOUR_MIX to MixSpec.YourMix(MixSource.Mood("chill"), 25),
                MixType.TOP_SONGS to MixSpec.TopSongs(StatsRange.ALL_TIME, TopOrder.LISTEN_COUNT, 100)
            ),
            AppJson.decodeFromString<Map<MixType, MixSpec>>(wire)
        )
    }
}
