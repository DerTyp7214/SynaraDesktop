package dev.dertyp.synara.theme

import androidx.compose.ui.graphics.Color
import dev.dertyp.synara.material.ColorSchemeBuilder
import dev.dertyp.synara.material.ColorSchemeBuilder.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ColorSchemeFromSeedsTest {
    @Test
    fun schemeWithoutAnySeedIsBuilt() {
        createColorSchemeFromSeeds(Triple(null, null, null), isDark = true)
        createColorSchemeFromSeeds(Triple(null, null, null), isDark = false)
    }

    @Test
    fun schemeWithoutSeedsMatchesTheBuilderForSeedZero() {
        for (isDark in listOf(true, false)) {
            val scheme = createColorSchemeFromSeeds(Triple(null, null, null), isDark)
            val colors = ColorSchemeBuilder(0, isDark).build()
            assertEquals(Color(colors.getValue(Key.Primary)), scheme.primary, "dark=$isDark")
            assertEquals(Color(colors.getValue(Key.Surface)), scheme.surface, "dark=$isDark")
            assertEquals(Color(colors.getValue(Key.Scrim)), scheme.scrim, "dark=$isDark")
        }
    }

    @Test
    fun seedsAreForwardedToTheBuilder() {
        val primary = 0xFF3366CC.toInt()
        val secondary = 0xFFFF8800.toInt()
        val tertiary = 0xFF22AA88.toInt()
        for (isDark in listOf(true, false)) {
            val scheme = createColorSchemeFromSeeds(Triple(primary, secondary, tertiary), isDark)
            val colors = ColorSchemeBuilder(primary, isDark, secondary, tertiary).build()
            assertEquals(Color(colors.getValue(Key.Primary)), scheme.primary, "dark=$isDark")
            assertEquals(Color(colors.getValue(Key.Secondary)), scheme.secondary, "dark=$isDark")
            assertEquals(Color(colors.getValue(Key.Tertiary)), scheme.tertiary, "dark=$isDark")
            assertEquals(Color(colors.getValue(Key.OnTertiaryFixedVariant)), scheme.onTertiaryFixedVariant, "dark=$isDark")
        }
    }

    @Test
    fun darkAndLightSchemesDiffer() {
        val seeds = Triple<Int?, Int?, Int?>(0xFF3366CC.toInt(), null, null)
        assertNotEquals(
            createColorSchemeFromSeeds(seeds, isDark = true).background,
            createColorSchemeFromSeeds(seeds, isDark = false).background
        )
    }
}
