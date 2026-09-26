package dev.dertyp.synara.ui.components.visualizer

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private val SQRT2 = sqrt(2f)
private val TWO_PI = (2.0 * PI).toFloat()
private val RAD_TO_DEG = (180.0 / PI).toFloat()

internal class RadialOutlineShape(val halfSize: Float, val corner: Float) {
    val straight = halfSize - corner
    val perimeter = 8f * straight + TWO_PI * corner
    val outerReach = max(halfSize, straight * SQRT2 + corner)
}

internal fun radialCircleRadius(coverSize: Float, coverCorner: Float): Float {
    val corner = coverCorner.coerceIn(0f, coverSize / 2f)
    return (coverSize / 2f - corner) * SQRT2 + corner
}

internal fun radialOutlineShape(coverSize: Float, coverCorner: Float, roundness: Float, gap: Float): RadialOutlineShape {
    val t = roundness.coerceIn(0f, 1f)
    val half = coverSize.coerceAtLeast(0f) / 2f
    val rc = coverCorner.coerceIn(0f, half)
    val circle = radialCircleRadius(half * 2f, rc)
    val halfSize = half + (circle - half) * t + gap
    val corner = rc + (circle - rc) * t + gap
    return RadialOutlineShape(halfSize, corner.coerceAtMost(halfSize))
}

internal fun fitRadialCoverSize(reach: Float, coverCorner: Float, roundness: Float, gap: Float): Float {
    if (radialOutlineShape(0f, coverCorner, roundness, gap).outerReach >= reach) return 0f
    var low = 0f
    var high = reach * 2f
    repeat(32) {
        val mid = (low + high) / 2f
        if (radialOutlineShape(mid, coverCorner, roundness, gap).outerReach <= reach) low = mid else high = mid
    }
    return low
}

internal class RadialOutline(val shape: RadialOutlineShape, val count: Int) {
    val xs = FloatArray(count)
    val ys = FloatArray(count)
    val normalXs = FloatArray(count)
    val normalYs = FloatArray(count)
    val normalDegrees = FloatArray(count)
    val sweep = FloatArray(count)
    val perimeter get() = shape.perimeter
    val spacing = if (count > 0) shape.perimeter / count else 0f
    private var sampledOffset = Float.NaN

    fun sample(offset: Float) {
        if (offset == sampledOffset) return
        sampledOffset = offset
        for (k in 0 until count) place(k, offset + k * spacing)
    }

    private fun place(k: Int, distance: Float) {
        val perimeter = shape.perimeter
        val a = shape.straight
        val c = shape.corner
        val h = shape.halfSize
        val arc = c * PI.toFloat() / 2f
        val quarter = 2f * a + arc
        val d = if (perimeter > 0f) ((distance % perimeter) + perimeter) % perimeter else 0f
        val quadrant = if (quarter > 0f) (d / quarter).toInt().coerceIn(0, 3) else 0
        val u = d - quadrant * quarter
        var x: Float
        var y: Float
        var nx: Float
        var ny: Float
        when {
            u < a -> {
                x = u
                y = -h
                nx = 0f
                ny = -1f
            }
            u < a + arc && c > 0f -> {
                val phi = -PI.toFloat() / 2f + (u - a) / c
                nx = cos(phi)
                ny = sin(phi)
                x = a + c * nx
                y = -a + c * ny
            }
            else -> {
                x = h
                y = -a + (u - a - arc)
                nx = 1f
                ny = 0f
            }
        }
        repeat(quadrant) {
            val px = x
            x = -y
            y = px
            val pnx = nx
            nx = -ny
            ny = pnx
        }
        xs[k] = x
        ys[k] = y
        normalXs[k] = nx
        normalYs[k] = ny
        normalDegrees[k] = atan2(nx, -ny) * RAD_TO_DEG
        val degrees = atan2(y, x) * RAD_TO_DEG
        sweep[k] = (((degrees % 360f) + 360f) % 360f) / 360f
    }
}

internal fun radialOutline(
    coverSize: Float,
    coverCorner: Float,
    roundness: Float,
    gap: Float,
    count: Int,
    offset: Float = 0f
): RadialOutline =
    RadialOutline(radialOutlineShape(coverSize, coverCorner, roundness, gap), count).also { it.sample(offset) }
