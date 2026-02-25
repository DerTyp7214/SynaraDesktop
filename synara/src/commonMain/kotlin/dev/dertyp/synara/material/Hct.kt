package dev.dertyp.synara.material

import kotlin.math.*

/**
 * HCT (Hue, Chroma, Tone) color system implementation.
 * Pure Kotlin implementation for color scheme generation.
 */
class Hct private constructor(private val argb: Int) {
    val hue: Double
    val chroma: Double
    val tone: Double

    init {
        val lab = argbToLab(argb)
        val lch = labToLch(lab)
        hue = lch[2]
        chroma = lch[1]
        tone = lab[0]
    }

    fun toInt(): Int = argb

    companion object {
        fun fromInt(argb: Int): Hct = Hct(argb)

        fun from(h: Double, c: Double, t: Double): Hct {
            val lab = lchToLab(doubleArrayOf(t, c, h))
            val argb = labToArgb(lab)
            return Hct(argb)
        }

        private fun argbToLab(argb: Int): DoubleArray {
            val r = (argb shr 16 and 0xFF) / 255.0
            val g = (argb shr 8 and 0xFF) / 255.0
            val b = (argb and 0xFF) / 255.0

            val linearR = pivotRgb(r)
            val linearG = pivotRgb(g)
            val linearB = pivotRgb(b)

            val x = (linearR * 0.4124564 + linearG * 0.3575761 + linearB * 0.1804375) / 0.95047
            val y = (linearR * 0.2126729 + linearG * 0.7151522 + linearB * 0.0721750)
            val z = (linearR * 0.0193339 + linearG * 0.1191920 + linearB * 0.9503041) / 1.08883

            return doubleArrayOf(
                116.0 * pivotXyz(y) - 16.0,
                500.0 * (pivotXyz(x) - pivotXyz(y)),
                200.0 * (pivotXyz(y) - pivotXyz(z))
            )
        }

        private fun labToArgb(lab: DoubleArray): Int {
            var y = (lab[0] + 16.0) / 116.0
            var x = lab[1] / 500.0 + y
            var z = y - lab[2] / 200.0

            x = (if (x.pow(3.0) > 0.008856) x.pow(3.0) else (x - 16.0 / 116.0) / 7.787) * 0.95047
            y = (if (y.pow(3.0) > 0.008856) y.pow(3.0) else (y - 16.0 / 116.0) / 7.787)
            z = (if (z.pow(3.0) > 0.008856) z.pow(3.0) else (z - 16.0 / 116.0) / 7.787) * 1.08883

            var r = x * 3.2404542 + y * -1.5371385 + z * -0.4985314
            var g = x * -0.9692660 + y * 1.8760108 + z * 0.0415560
            var b = x * 0.0556434 + y * -0.2040259 + z * 1.0572252

            r = if (r > 0.0031308) 1.055 * r.pow(1.0 / 2.4) - 0.055 else 12.92 * r
            g = if (g > 0.0031308) 1.055 * g.pow(1.0 / 2.4) - 0.055 else 12.92 * g
            b = if (b > 0.0031308) 1.055 * b.pow(1.0 / 2.4) - 0.055 else 12.92 * b

            val rInt = (r * 255).roundToInt().coerceIn(0, 255)
            val gInt = (g * 255).roundToInt().coerceIn(0, 255)
            val bInt = (b * 255).roundToInt().coerceIn(0, 255)

            return (0xFF shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
        }

        private fun lchToLab(lch: DoubleArray): DoubleArray {
            val l = lch[0]
            val c = lch[1]
            val h = lch[2] * PI / 180.0
            return doubleArrayOf(l, cos(h) * c, sin(h) * c)
        }

        private fun labToLch(lab: DoubleArray): DoubleArray {
            val l = lab[0]
            val a = lab[1]
            val b = lab[2]
            val c = sqrt(a * a + b * b)
            var h = atan2(b, a) * 180.0 / PI
            if (h < 0) h += 360.0
            return doubleArrayOf(l, c, h)
        }

        private fun pivotRgb(n: Double): Double {
            return if (n > 0.04045) ((n + 0.055) / 1.055).pow(2.4) else n / 12.92
        }

        private fun pivotXyz(n: Double): Double {
            return if (n > 0.008856) n.pow(1.0 / 3.0) else (7.787 * n) + (16.0 / 116.0)
        }
    }
}
