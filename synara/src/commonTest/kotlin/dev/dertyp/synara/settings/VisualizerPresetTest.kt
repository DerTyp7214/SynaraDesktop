package dev.dertyp.synara.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualizerPresetTest {
    private val custom = VisualizerPreset(
        id = "user.one",
        name = "Mine",
        reaction = VisualizerStyle.Monstercat,
        shape = VisualizerShape.Radial,
        renderMode = VisualizerRenderMode.Wave,
        anchor = VisualizerAnchor.Bottom,
        colorMode = VisualizerColorMode.Custom,
        fillMode = VisualizerFillMode.Vertical,
        customBaseColor = 0xFF112233.toInt(),
        frequencyScale = FrequencyScale.Linear,
        sensitivity = VisualizerSensitivity.Fixed,
        barWidth = 7f,
        glowEnabled = false,
    )

    @Test
    fun encodeDecodeRoundTrip() {
        val presets = listOf(custom, VisualizerPreset(id = "user.two", name = "Other"))
        assertEquals(presets, VisualizerPresets.decode(VisualizerPresets.encode(presets)))
    }

    @Test
    fun oldJsonWithOnlyIdAndNameGetsDefaults() {
        val decoded = VisualizerPresets.decode("""[{"id":"user.old","name":"Old","someRemovedField":3}]""")
        assertEquals(listOf(VisualizerPreset(id = "user.old", name = "Old")), decoded)
    }

    @Test
    fun oldJsonWithoutStereoDecodesToTrue() {
        val decoded = VisualizerPresets.decode("""[{"id":"user.old","name":"Old"}]""")
        assertTrue(decoded.single().stereo)
    }

    @Test
    fun garbageDecodesToEmptyList() {
        assertEquals(emptyList<VisualizerPreset>(), VisualizerPresets.decode("not json at all"))
        assertEquals(emptyList<VisualizerPreset>(), VisualizerPresets.decode("""{"id":"x","name":"y"}"""))
        assertEquals(emptyList<VisualizerPreset>(), VisualizerPresets.decode(""))
        assertEquals(emptyList<VisualizerPreset>(), VisualizerPresets.decode(null))
    }

    @Test
    fun invalidEntriesAreSkipped() {
        val decoded = VisualizerPresets.decode("""[{"id":"user.a","name":"A"},{"name":"missing id"},{"id":"user.b","name":"B","shape":"Nope"}]""")
        assertEquals(listOf("user.a"), decoded.map { it.id })
    }

    @Test
    fun decodeDropsBuiltInEntries() {
        val entries = listOf(
            VisualizerPresets.encode(listOf(custom)).removeSurrounding("[", "]"),
            """{"id":"user.fake","name":"Fake","builtIn":true}""",
            """{"id":"${VisualizerPresets.SYNARA_ID}","name":"Clone"}"""
        )
        val json = entries.joinToString(",", "[", "]")
        assertEquals(listOf(custom), VisualizerPresets.decode(json))
    }

    @Test
    fun resolveActiveIdKeepsKnownStoredId() {
        assertEquals(
            VisualizerPresets.NEON_ID,
            VisualizerPresets.resolveActiveId(VisualizerPresets.NEON_ID, "Monstercat", emptyList())
        )
        assertEquals(custom.id, VisualizerPresets.resolveActiveId(custom.id, null, listOf(custom)))
    }

    @Test
    fun resolveActiveIdFallsBackToLegacyStyleForUnknownId() {
        assertEquals(
            VisualizerPresets.MONSTERCAT_ID,
            VisualizerPresets.resolveActiveId("user.gone", "Monstercat", listOf(custom))
        )
        assertEquals(
            VisualizerPresets.SYNARA_ID,
            VisualizerPresets.resolveActiveId("user.gone", "Synara", listOf(custom))
        )
    }

    @Test
    fun resolveActiveIdMigratesLegacyStyle() {
        assertEquals(VisualizerPresets.MONSTERCAT_ID, VisualizerPresets.resolveActiveId(null, "Monstercat", emptyList()))
        assertEquals(VisualizerPresets.SYNARA_ID, VisualizerPresets.resolveActiveId(null, "Synara", emptyList()))
        assertEquals(VisualizerPresets.SYNARA_ID, VisualizerPresets.resolveActiveId(null, "Unknown", emptyList()))
        assertEquals(VisualizerPresets.SYNARA_ID, VisualizerPresets.resolveActiveId(null, null, emptyList()))
    }

    @Test
    fun builtInsHaveUniqueIdsAndAreBuiltIn() {
        val ids = VisualizerPresets.builtIns.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(VisualizerPresets.builtIns.all { it.builtIn })
        assertTrue(
            ids.containsAll(
                listOf(
                    VisualizerPresets.SYNARA_ID,
                    VisualizerPresets.MONSTERCAT_ID,
                    VisualizerPresets.MINIMAL_ID,
                    VisualizerPresets.NEON_ID,
                    VisualizerPresets.FLOW_ID,
                    VisualizerPresets.HALO_ID
                )
            )
        )
    }

    @Test
    fun builtInsAreStereo() {
        assertTrue(VisualizerPresets.builtIns.all { it.stereo })
    }

    @Test
    fun encodeSkipsBuiltIns() {
        assertEquals(emptyList<VisualizerPreset>(), VisualizerPresets.decode(VisualizerPresets.encode(VisualizerPresets.builtIns)))
    }
}
