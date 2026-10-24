package dev.dertyp.synara.ui.server

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiImageRef
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign

@Composable
fun UiLeadingVisual(
    icon: UiIcon?,
    image: UiImageRef?,
    size: Dp,
    imageSize: Dp = size,
    tint: Color = LocalContentColor.current,
) {
    when {
        image != null -> UiImageRefView(image, imageSize)
        icon != null -> UiIconView(icon, Modifier.testTag(UiTestTags.ICON), size, tint)
    }
}

@Composable
fun UiImageRefView(image: UiImageRef, size: Dp, modifier: Modifier = Modifier) {
    val shape = if (image.rounded) CircleShape else MaterialTheme.shapes.extraSmall
    val placeholder = remember(image.blurHash) { image.blurHash?.let { BlurHash.decodeBitmap(it) } }
    Box(modifier = modifier.testTag(UiTestTags.image(image)).size(size).clip(shape)) {
        placeholder?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Low,
            )
        }
        SynaraImage(
            imageId = image.imageId,
            size = size,
            shape = shape,
            backgroundColor = if (placeholder != null) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

internal object BlurHash {
    private const val CHARACTERS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"
    private const val BITMAP_SIZE = 32

    fun decodeBitmap(hash: String): ImageBitmap? {
        val pixels = decode(hash, BITMAP_SIZE, BITMAP_SIZE) ?: return null
        val bitmap = ImageBitmap(BITMAP_SIZE, BITMAP_SIZE)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (y in 0 until BITMAP_SIZE) {
            for (x in 0 until BITMAP_SIZE) {
                paint.color = pixels[y * BITMAP_SIZE + x]
                canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
            }
        }
        return bitmap
    }

    fun decode(hash: String, width: Int, height: Int): List<Color>? {
        if (hash.length < 6) return null
        val sizeFlag = decode83(hash, 0, 1) ?: return null
        val componentsY = sizeFlag / 9 + 1
        val componentsX = sizeFlag % 9 + 1
        if (hash.length != 4 + 2 * componentsX * componentsY) return null
        val quantisedMaximum = decode83(hash, 1, 2) ?: return null
        val maximum = (quantisedMaximum + 1) / 166f
        val factors = (0 until componentsX * componentsY).map { index ->
            if (index == 0) {
                decodeDc(decode83(hash, 2, 6) ?: return null)
            } else {
                decodeAc(decode83(hash, 4 + index * 2, 6 + index * 2) ?: return null, maximum)
            }
        }
        return (0 until width * height).map { pixel ->
            val x = pixel % width
            val y = pixel / width
            var r = 0f
            var g = 0f
            var b = 0f
            for (j in 0 until componentsY) {
                for (i in 0 until componentsX) {
                    val basis = (cos(PI * x * i / width) * cos(PI * y * j / height)).toFloat()
                    val factor = factors[i + j * componentsX]
                    r += factor[0] * basis
                    g += factor[1] * basis
                    b += factor[2] * basis
                }
            }
            Color(linearToSrgb(r), linearToSrgb(g), linearToSrgb(b))
        }
    }

    private fun decode83(hash: String, from: Int, to: Int): Int? {
        var value = 0
        for (index in from until to) {
            val digit = CHARACTERS.indexOf(hash[index])
            if (digit < 0) return null
            value = value * 83 + digit
        }
        return value
    }

    private fun decodeDc(value: Int) = floatArrayOf(
        srgbToLinear(value shr 16),
        srgbToLinear((value shr 8) and 255),
        srgbToLinear(value and 255),
    )

    private fun decodeAc(value: Int, maximum: Float) = floatArrayOf(
        signPow(((value / (19 * 19)) - 9) / 9f) * maximum,
        signPow(((value / 19) % 19 - 9) / 9f) * maximum,
        signPow((value % 19 - 9) / 9f) * maximum,
    )

    private fun signPow(value: Float) = sign(value) * abs(value).pow(2f)

    private fun srgbToLinear(value: Int): Float {
        val v = value / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }
}
