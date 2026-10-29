package dev.dertyp.synara.ui.components

import org.jetbrains.skia.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoldenComparisonTest {
    private val size = 64

    private fun circle(centerX: Float, centerY: Float, radius: Float = 20f): ByteArray {
        val surface = Surface.makeRasterN32Premul(size, size)
        try {
            surface.canvas.clear(0xFF000000.toInt())
            surface.canvas.drawCircle(centerX, centerY, radius, Paint().apply {
                color = 0xFFFFFFFF.toInt()
                isAntiAlias = true
            })
            return GoldenComparison.pixels(surface.makeImageSnapshot())
        } finally {
            surface.close()
        }
    }

    private fun value(pixels: ByteArray, index: Int): Int = pixels[index].toInt() and 0xFF

    private fun pixelIndex(x: Int, y: Int): Int = (y * size + x) * 4

    private fun antialiasedPixels(pixels: ByteArray): List<Int> =
        (0 until size * size).map { it * 4 }.filter { value(pixels, it) in 40..215 }

    private fun shifted(pixels: ByteArray, index: Int, delta: Int): ByteArray = pixels.copyOf().also {
        for (c in 0..2) it[index + c] = (value(pixels, index + c) + delta).coerceIn(0, 255).toByte()
    }

    @Test
    fun identicalImagesMatch() {
        val expected = circle(32f, 32f)
        val comparison = GoldenComparison.compare(expected, expected.copyOf(), size)
        assertEquals(GoldenComparison(0, 0), comparison)
        assertTrue(comparison.matches)
    }

    @Test
    fun smallAntialiasWobbleOnEdgesMatches() {
        val expected = circle(32f, 32f)
        val edges = antialiasedPixels(expected)
        assertTrue(edges.size >= 8)
        var actual = expected
        edges.take(8).forEachIndexed { i, index -> actual = shifted(actual, index, if (i % 2 == 0) 11 else -11) }
        val comparison = GoldenComparison.compare(expected, actual, size)
        assertEquals(0, comparison.interiorDifference)
        assertEquals(11, comparison.edgeDifference)
        assertTrue(comparison.matches)
    }

    @Test
    fun interiorChangeOfTwoFails() {
        val expected = circle(32f, 32f)
        val actual = shifted(expected, pixelIndex(32, 32), -2)
        val comparison = GoldenComparison.compare(expected, actual, size)
        assertEquals(2, comparison.interiorDifference)
        assertFalse(comparison.matches)
    }

    @Test
    fun backgroundChangeOfTwoFails() {
        val expected = circle(32f, 32f)
        val actual = shifted(expected, pixelIndex(2, 2), 2)
        val comparison = GoldenComparison.compare(expected, actual, size)
        assertEquals(2, comparison.interiorDifference)
        assertFalse(comparison.matches)
    }

    @Test
    fun circleShiftedByOnePixelFails() {
        val comparison = GoldenComparison.compare(circle(32f, 32f), circle(33f, 32f), size)
        assertTrue(comparison.edgeDifference > GoldenComparison.MAX_EDGE_DIFFERENCE)
        assertFalse(comparison.matches)
    }

    @Test
    fun circleGrownByOnePixelFails() {
        val comparison = GoldenComparison.compare(circle(32f, 32f), circle(32f, 32f, radius = 21f), size)
        assertFalse(comparison.matches)
    }

    @Test
    fun edgeChangeAboveToleranceFails() {
        val expected = circle(32f, 32f)
        val index = antialiasedPixels(expected).first { value(expected, it) < 128 }
        val actual = shifted(expected, index, GoldenComparison.MAX_EDGE_DIFFERENCE + 1)
        val comparison = GoldenComparison.compare(expected, actual, size)
        assertEquals(0, comparison.interiorDifference)
        assertEquals(GoldenComparison.MAX_EDGE_DIFFERENCE + 1, comparison.edgeDifference)
        assertFalse(comparison.matches)
    }
}
