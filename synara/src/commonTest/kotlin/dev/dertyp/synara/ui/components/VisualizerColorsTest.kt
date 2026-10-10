package dev.dertyp.synara.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class VisualizerColorsTest {
    private val light = Color(0xFFE0E0E0)
    private val dark = Color(0xFF202020)

    @Test
    fun lighterColorComesFirstWhenGivenLighterFirst() {
        assertEquals(light to dark, sortByLuminance(light, dark))
    }

    @Test
    fun lighterColorComesFirstWhenGivenDarkerFirst() {
        assertEquals(light to dark, sortByLuminance(dark, light))
    }

    @Test
    fun equalLuminanceKeepsTheSecondArgumentFirst() {
        val opaque = Color(red = 1f, green = 0f, blue = 0f, alpha = 1f)
        val translucent = Color(red = 1f, green = 0f, blue = 0f, alpha = 0.5f)
        assertEquals(translucent to opaque, sortByLuminance(opaque, translucent))
        assertEquals(opaque to translucent, sortByLuminance(translucent, opaque))
    }
}
