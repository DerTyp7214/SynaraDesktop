package dev.dertyp.synara.ui.components.visualizer

import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RadialOutlineTest {
    private val coverSize = 300f
    private val coverCorner = 16f
    private val gap = 12f
    private val count = 180
    private val eps = 0.01f

    @Test
    fun fullRoundnessIsACircle() {
        val outline = radialOutline(coverSize, coverCorner, 1f, gap, count)
        val radius = radialCircleRadius(coverSize, coverCorner) + gap
        for (k in 0 until count) {
            assertEquals(radius, hypot(outline.xs[k], outline.ys[k]), eps)
        }
        assertEquals(2f * PI.toFloat() * radius, outline.perimeter, 0.1f)
    }

    @Test
    fun zeroRoundnessHugsTheCoverEdges() {
        val outline = radialOutline(coverSize, coverCorner, 0f, gap, count)
        val expected = coverSize / 2f + gap
        assertEquals(0f, outline.xs[0], eps)
        assertEquals(-expected, outline.ys[0], eps)
        var edgePoints = 0
        for (k in 0 until count) {
            val nx = outline.normalXs[k]
            val ny = outline.normalYs[k]
            if (abs(abs(nx) - 1f) < 1e-4f || abs(abs(ny) - 1f) < 1e-4f) {
                edgePoints++
                assertEquals(expected, outline.xs[k] * nx + outline.ys[k] * ny, eps)
            }
        }
        assertTrue(edgePoints > count / 2)
    }

    @Test
    fun everyPointLiesOutsideTheCover() {
        for (roundness in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            for (padding in listOf(0f, gap)) {
                val outline = radialOutline(coverSize, coverCorner, roundness, padding, count, offset = 7.3f)
                for (k in 0 until count) {
                    val distance = coverDistance(outline.xs[k], outline.ys[k])
                    assertTrue(distance >= padding - eps, "roundness $roundness point $k at $distance")
                }
            }
        }
    }

    @Test
    fun pointsAreEvenlySpacedAlongThePerimeter() {
        for (roundness in listOf(0f, 0.5f, 1f)) {
            val outline = radialOutline(coverSize, coverCorner, roundness, gap, count)
            val shape = outline.shape
            val expectedPerimeter = 8f * (shape.halfSize - shape.corner) + 2f * PI.toFloat() * shape.corner
            assertEquals(expectedPerimeter, outline.perimeter, 0.01f)
            assertEquals(outline.perimeter / count, outline.spacing, 1e-4f)
            val shortest = 2f * shape.corner * sin(outline.spacing / (2f * shape.corner))
            for (k in 0 until count) {
                val next = (k + 1) % count
                val chord = hypot(outline.xs[next] - outline.xs[k], outline.ys[next] - outline.ys[k])
                assertTrue(chord <= outline.spacing + eps, "roundness $roundness chord $k is $chord")
                assertTrue(chord >= shortest - eps, "roundness $roundness chord $k is $chord")
            }
        }
    }

    @Test
    fun offsetShiftsPointsAlongThePerimeter() {
        val base = radialOutline(coverSize, coverCorner, 0.3f, gap, count)
        val shifted = radialOutline(coverSize, coverCorner, 0.3f, gap, count, offset = base.spacing)
        for (k in 0 until count - 1) {
            assertEquals(base.xs[k + 1], shifted.xs[k], eps)
            assertEquals(base.ys[k + 1], shifted.ys[k], eps)
        }
    }

    private fun coverDistance(x: Float, y: Float): Float {
        val inner = coverSize / 2f - coverCorner
        val qx = abs(x) - inner
        val qy = abs(y) - inner
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - coverCorner
    }
}
