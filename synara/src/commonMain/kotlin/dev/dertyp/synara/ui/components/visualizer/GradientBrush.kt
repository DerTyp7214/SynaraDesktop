package dev.dertyp.synara.ui.components.visualizer

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.GradientStyle
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Shader as SkShader

private const val LINEAR = 0
private const val SWEEP = 1
private const val RADIAL = 2

private val CLAMP_PREMUL = GradientStyle(FilterTileMode.CLAMP, true, Matrix33.IDENTITY)

internal class GradientBrush(size: Int) {
    val colors = IntArray(size)
    val positions = FloatArray(size)
    private val lastColors = IntArray(size)
    private val lastPositions = FloatArray(size)
    private var kind = -1
    private var withPositions = false
    private var k0 = 0f
    private var k1 = 0f
    private var k2 = 0f
    private var k3 = 0f
    private var brush: Brush? = null

    private fun cached(kind: Int, withPositions: Boolean, a: Float, b: Float, c: Float, d: Float): Brush? {
        val current = brush
        if (current != null &&
            this.kind == kind &&
            this.withPositions == withPositions &&
            k0 == a && k1 == b && k2 == c && k3 == d &&
            colors.contentEquals(lastColors) &&
            (!withPositions || positions.contentEquals(lastPositions))
        ) {
            return current
        }
        this.kind = kind
        this.withPositions = withPositions
        k0 = a
        k1 = b
        k2 = c
        k3 = d
        colors.copyInto(lastColors)
        positions.copyInto(lastPositions)
        return null
    }

    private fun store(shader: SkShader): Brush = ShaderBrush(shader.asComposeShader()).also { brush = it }

    fun linear(x0: Float, y0: Float, x1: Float, y1: Float, withPositions: Boolean = true): Brush =
        cached(LINEAR, withPositions, x0, y0, x1, y1) ?: store(
            SkShader.makeLinearGradient(x0, y0, x1, y1, colors, if (withPositions) positions else null, CLAMP_PREMUL)
        )

    fun sweep(centerX: Float, centerY: Float): Brush =
        cached(SWEEP, true, centerX, centerY, 0f, 0f) ?: store(
            SkShader.makeSweepGradient(centerX, centerY, colors, positions)
        )

    fun radial(centerX: Float, centerY: Float, radius: Float): Brush =
        cached(RADIAL, true, centerX, centerY, radius, 0f) ?: store(
            SkShader.makeRadialGradient(centerX, centerY, radius, colors, positions, CLAMP_PREMUL)
        )
}
