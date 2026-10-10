package dev.dertyp.synara.material

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.dertyp.synara.material.ColorSchemeBuilder.Key
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ColorSchemeBuilderTest {
    private val seeds = listOf(
        0xFF3366CC.toInt(),
        0xFF808080.toInt(),
        0xFFB03060.toInt(),
        0xFF22AA88.toInt(),
    )

    private fun contrast(a: Int, b: Int): Float {
        val first = Color(a).luminance()
        val second = Color(b).luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    private fun channels(argb: Int) = listOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    @Test
    fun everyKeyIsPresentInDarkAndLight() {
        for (seed in seeds) {
            for (isDark in listOf(true, false)) {
                val colors = ColorSchemeBuilder(seed, isDark).build()
                assertEquals(Key.entries.toSet(), colors.keys, "seed=$seed dark=$isDark")
            }
        }
    }

    @Test
    fun everyValueIsOpaque() {
        for (seed in seeds) {
            for (isDark in listOf(true, false)) {
                for ((key, value) in ColorSchemeBuilder(seed, isDark).build()) {
                    assertEquals(0xFF, (value ushr 24) and 0xFF, "$key seed=$seed dark=$isDark")
                }
            }
        }
    }

    @Test
    fun darkSurfaceIsDarkerThanLightSurface() {
        for (seed in seeds) {
            val dark = ColorSchemeBuilder(seed, true).build().getValue(Key.Surface)
            val light = ColorSchemeBuilder(seed, false).build().getValue(Key.Surface)
            assertTrue(Color(dark).luminance() < Color(light).luminance(), "seed=$seed")
        }
    }

    @Test
    fun darkOnBackgroundIsLighterThanDarkBackground() {
        for (seed in seeds) {
            val colors = ColorSchemeBuilder(seed, true).build()
            assertTrue(
                Color(colors.getValue(Key.OnBackground)).luminance() > Color(colors.getValue(Key.Background)).luminance(),
                "seed=$seed"
            )
        }
    }

    @Test
    fun lightOnBackgroundIsDarkerThanLightBackground() {
        for (seed in seeds) {
            val colors = ColorSchemeBuilder(seed, false).build()
            assertTrue(
                Color(colors.getValue(Key.OnBackground)).luminance() < Color(colors.getValue(Key.Background)).luminance(),
                "seed=$seed"
            )
        }
    }

    @Test
    fun explicitSecondarySeedChangesSecondaryButNotPrimaryOrTertiary() {
        val seed = 0xFF3366CC.toInt()
        val secondarySeed = 0xFFFF8800.toInt()
        for (isDark in listOf(true, false)) {
            val plain = ColorSchemeBuilder(seed, isDark).build()
            val seeded = ColorSchemeBuilder(seed, isDark, secondarySeedColor = secondarySeed).build()
            assertNotEquals(plain.getValue(Key.Secondary), seeded.getValue(Key.Secondary), "dark=$isDark")
            assertEquals(plain.getValue(Key.Primary), seeded.getValue(Key.Primary), "dark=$isDark")
            assertEquals(plain.getValue(Key.Tertiary), seeded.getValue(Key.Tertiary), "dark=$isDark")
        }
    }

    @Test
    fun explicitTertiarySeedChangesTertiaryButNotPrimaryOrSecondary() {
        val seed = 0xFF3366CC.toInt()
        val tertiarySeed = 0xFF22AA88.toInt()
        for (isDark in listOf(true, false)) {
            val plain = ColorSchemeBuilder(seed, isDark).build()
            val seeded = ColorSchemeBuilder(seed, isDark, tertiarySeedColor = tertiarySeed).build()
            assertNotEquals(plain.getValue(Key.Tertiary), seeded.getValue(Key.Tertiary), "dark=$isDark")
            assertEquals(plain.getValue(Key.Primary), seeded.getValue(Key.Primary), "dark=$isDark")
            assertEquals(plain.getValue(Key.Secondary), seeded.getValue(Key.Secondary), "dark=$isDark")
        }
    }

    @Test
    fun primaryPairsMeetTextContrastInBothModes() {
        for (seed in seeds) {
            for (isDark in listOf(true, false)) {
                val colors = ColorSchemeBuilder(seed, isDark).build()
                val pairs = listOf(
                    Key.Primary to Key.OnPrimary,
                    Key.PrimaryContainer to Key.OnPrimaryContainer,
                    Key.Surface to Key.OnSurface,
                )
                for ((background, foreground) in pairs) {
                    val ratio = contrast(colors.getValue(background), colors.getValue(foreground))
                    assertTrue(ratio >= 4.5f, "$background/$foreground seed=$seed dark=$isDark ratio=$ratio")
                }
            }
        }
    }

    @Test
    fun hctRoundTripsPrimaryColorsWithinOnePerChannel() {
        val colors = listOf(
            0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF808080, 0xFFFFFFFF, 0xFF000000,
            0xFF3366CC, 0xFFB03060, 0xFF22AA88,
        ).map { it.toInt() }
        for (argb in colors) {
            val hct = Hct.fromInt(argb)
            val back = Hct.from(hct.hue, hct.chroma, hct.tone).toInt()
            channels(argb).zip(channels(back)).forEach { (expected, actual) ->
                assertTrue(abs(expected - actual) <= 1, "argb=${argb.toUInt().toString(16)} expected=$expected actual=$actual")
            }
        }
    }

    @Test
    fun hctHueStaysWithinZeroAndThreeHundredSixty() {
        val colors = listOf(
            0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF808080, 0xFFFFFFFF, 0xFF000000,
            0xFF3366CC, 0xFFB03060, 0xFF22AA88, 0xFFFF00FF, 0xFFFFFF00, 0xFF00FFFF,
        ).map { it.toInt() }
        for (argb in colors) {
            val hue = Hct.fromInt(argb).hue
            assertTrue(hue >= 0.0 && hue < 360.0, "argb=${argb.toUInt().toString(16)} hue=$hue")
        }
    }

    @Test
    fun hctToneOfBlackIsZeroAndOfWhiteIsOneHundred() {
        assertEquals(0.0, Hct.fromInt(0xFF000000.toInt()).tone, 0.01)
        assertEquals(100.0, Hct.fromInt(0xFFFFFFFF.toInt()).tone, 0.01)
    }

    @Test
    fun zeroChromaPaletteProducesGreys() {
        val palette = TonalPalette.fromHueAndChroma(123.0, 0.0)
        for (tone in listOf(10, 25, 50, 75, 90)) {
            val (r, g, b) = channels(palette.tone(tone))
            assertTrue(r == g && g == b, "tone=$tone r=$r g=$g b=$b")
        }
    }

    @Test
    fun toneZeroIsBlackAndToneOneHundredIsWhite() {
        val palette = TonalPalette.fromHueAndChroma(200.0, 0.0)
        assertEquals(0xFF000000.toInt(), palette.tone(0))
        assertEquals(0xFFFFFFFF.toInt(), palette.tone(100))
    }

    @Test
    fun tonalPaletteFromColorKeepsTheColorsHue() {
        val argb = 0xFF3366CC.toInt()
        val hct = Hct.fromInt(argb)
        val tone = Hct.fromInt(TonalPalette.fromInt(argb).tone(50))
        val difference = abs(hct.hue - tone.hue)
        assertTrue(minOf(difference, 360.0 - difference) < 5.0, "hue=${hct.hue} toneHue=${tone.hue}")
    }
}
