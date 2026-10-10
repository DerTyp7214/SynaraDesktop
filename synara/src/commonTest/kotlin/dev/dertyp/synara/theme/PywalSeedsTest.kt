package dev.dertyp.synara.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class PywalSeedsTest {
    private fun colors(color10: String, color12: String, color14: String) = PywalColors(
        special = PywalSpecial(background = "#000000", foreground = "#ffffff", cursor = "#ffffff"),
        colors = mapOf("color10" to color10, "color12" to color12, "color14" to color14)
    )

    @Test
    fun sixDigitValuesBecomeOpaqueSeedsInTheOrderPrimarySecondaryTertiary() {
        val seeds = pywalSeeds(colors(color10 = "#112233", color12 = "#445566", color14 = "#778899"))

        assertEquals(Triple<Int?, Int?, Int?>(0xFF445566.toInt(), 0xFF112233.toInt(), 0xFF778899.toInt()), seeds)
    }

    @Test
    fun eightDigitValuesKeepTheirAlpha() {
        val seeds = pywalSeeds(colors(color10 = "80112233", color12 = "#FF445566", color14 = "#00778899"))

        assertEquals(Triple<Int?, Int?, Int?>(0xFF445566.toInt(), 0x80112233.toInt(), 0x00778899), seeds)
    }

    @Test
    fun aValueThatIsNotHexIsSkippedAndTheOthersAreKept() {
        val seeds = pywalSeeds(colors(color10 = "#GGGGGG", color12 = "#445566", color14 = "#12345Z78"))

        assertEquals(Triple<Int?, Int?, Int?>(0xFF445566.toInt(), null, null), seeds)
    }

    @Test
    fun aValueOfAnotherLengthIsSkipped() {
        val seeds = pywalSeeds(colors(color10 = "red", color12 = "#fff", color14 = ""))

        assertEquals(Triple<Int?, Int?, Int?>(null, null, null), seeds)
    }
}
