package dev.dertyp.synara.ui.components

import org.jetbrains.skia.*
import kotlin.math.abs
import kotlin.math.max

data class GoldenComparison(val interiorDifference: Int, val edgeDifference: Int) {
    val worst: Int get() = max(interiorDifference, edgeDifference)

    val matches: Boolean get() = interiorDifference <= MAX_INTERIOR_DIFFERENCE && edgeDifference <= MAX_EDGE_DIFFERENCE

    fun describe(): String =
        "interior difference $interiorDifference (max $MAX_INTERIOR_DIFFERENCE), edge difference $edgeDifference (max $MAX_EDGE_DIFFERENCE)"

    companion object {
        const val MAX_INTERIOR_DIFFERENCE = 1
        const val MAX_EDGE_DIFFERENCE = 32
        const val EDGE_CONTRAST = 48
        private const val CHANNELS = 4

        fun pixels(image: Image): ByteArray {
            val bitmap = Bitmap()
            bitmap.allocPixels(ImageInfo(image.width, image.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
            image.readPixels(bitmap, 0, 0)
            return bitmap.readPixels() ?: error("no pixels")
        }

        fun compare(expected: Image, actual: Image): GoldenComparison {
            require(expected.width == actual.width && expected.height == actual.height) { "image sizes differ" }
            return compare(pixels(expected), pixels(actual), expected.width)
        }

        fun compare(expected: ByteArray, actual: ByteArray, width: Int): GoldenComparison {
            require(expected.size == actual.size) { "pixel buffers differ in size" }
            val height = expected.size / CHANNELS / width
            var interior = 0
            var edge = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val offset = (y * width + x) * CHANNELS
                    var difference = 0
                    for (c in 0 until CHANNELS) {
                        difference = max(difference, abs(channel(expected, offset + c) - channel(actual, offset + c)))
                    }
                    if (difference == 0) continue
                    if (isEdge(expected, width, height, x, y)) edge = max(edge, difference)
                    else interior = max(interior, difference)
                }
            }
            return GoldenComparison(interior, edge)
        }

        private fun channel(pixels: ByteArray, index: Int): Int = pixels[index].toInt() and 0xFF

        private fun isEdge(pixels: ByteArray, width: Int, height: Int, x: Int, y: Int): Boolean {
            val offset = (y * width + x) * CHANNELS
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val neighbour = (ny * width + nx) * CHANNELS
                    for (c in 0 until CHANNELS) {
                        if (abs(channel(pixels, offset + c) - channel(pixels, neighbour + c)) > EDGE_CONTRAST) return true
                    }
                }
            }
            return false
        }
    }
}
