package dev.dertyp.synara.ui.components.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import dev.dertyp.synara.ui.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val GAP_HALF_DEGREES = 30f
private const val TOP_ANGLE_DEGREES = -90f
private const val ARC_SWEEP_DEGREES = 300f
private const val ARC_SHORTEN_DEGREES = 4f
private const val PHOSPHOR_GRID = 256f
private const val BASE_ICON_DP = 24f
private const val TRIANGLE_LENGTH_FRACTION = 0.24f
private const val TRIANGLE_HALF_WIDTH_FRACTION = 0.17f
private const val TRIANGLE_TEXT_SIZE_FRACTION = 0.39f

private class RotateGlyph(
    val grid: Float,
    val circleRadius: Float,
    backPathData: List<String>,
    forwardPathData: List<String>
) {
    val center = Offset(grid / 2f, grid / 2f)
    val backPaths: List<Path> = backPathData.map { PathParser().parsePathString(it).toPath() }
    val forwardPaths: List<Path> = forwardPathData.map { PathParser().parsePathString(it).toPath() }
}

private val LucideRotateGlyph = RotateGlyph(
    grid = BASE_ICON_DP,
    circleRadius = 9f,
    backPathData = listOf(
        "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
        "M3 3v5h5"
    ),
    forwardPathData = listOf(
        "M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8",
        "M21 3v5h-5"
    )
)

private val PhosphorRotateGlyph = RotateGlyph(
    grid = PHOSPHOR_GRID,
    circleRadius = 88f,
    backPathData = listOf(
        "M24 56V104H72",
        "M67.59 192A88 88 0 1 0 65.77 65.77L24 104"
    ),
    forwardPathData = listOf(
        "M232 56V104H184",
        "M188.41 192A88 88 0 1 1 190.23 65.77L232 104"
    )
)

private class SkipIntervalStyleSpec(
    val strokeWidthDpAt24: Float,
    val strokeCap: StrokeCap,
    val arrowheadCornerRadiusFraction: Float,
    val fontWeight: FontWeight,
    val textSizeFraction: Float,
    val glyph: RotateGlyph? = null,
    val duotoneAlpha: Float = 0f
)

private fun resolveStyleSpec(iconPackType: IconPackType, iconStyle: SynaraIconStyle): SkipIntervalStyleSpec {
    return when (iconPackType) {
        IconPackType.MaterialSymbols -> {
            val style = iconStyle as? MaterialSymbolStyle ?: MaterialSymbolStyle.Rounded
            when (style) {
                MaterialSymbolStyle.Rounded -> SkipIntervalStyleSpec(
                    strokeWidthDpAt24 = 2f,
                    strokeCap = StrokeCap.Round,
                    arrowheadCornerRadiusFraction = 0.3f,
                    fontWeight = FontWeight.SemiBold,
                    textSizeFraction = TRIANGLE_TEXT_SIZE_FRACTION
                )
                MaterialSymbolStyle.Outlined -> SkipIntervalStyleSpec(
                    strokeWidthDpAt24 = 2f,
                    strokeCap = StrokeCap.Butt,
                    arrowheadCornerRadiusFraction = 0f,
                    fontWeight = FontWeight.SemiBold,
                    textSizeFraction = TRIANGLE_TEXT_SIZE_FRACTION
                )
                MaterialSymbolStyle.Sharp -> SkipIntervalStyleSpec(
                    strokeWidthDpAt24 = 2f,
                    strokeCap = StrokeCap.Square,
                    arrowheadCornerRadiusFraction = 0f,
                    fontWeight = FontWeight.SemiBold,
                    textSizeFraction = TRIANGLE_TEXT_SIZE_FRACTION
                )
            }
        }
        IconPackType.Phosphor -> {
            val style = iconStyle as? PhosphorIconStyle ?: PhosphorIconStyle.Regular
            when (style) {
                PhosphorIconStyle.Thin -> SkipIntervalStyleSpec(
                    strokeWidthDpAt24 = 8f / PHOSPHOR_GRID * BASE_ICON_DP,
                    strokeCap = StrokeCap.Round,
                    arrowheadCornerRadiusFraction = 0f,
                    fontWeight = FontWeight.Light,
                    textSizeFraction = 0.34f,
                    glyph = PhosphorRotateGlyph
                )
                PhosphorIconStyle.Light -> SkipIntervalStyleSpec(
                    strokeWidthDpAt24 = 12f / PHOSPHOR_GRID * BASE_ICON_DP,
                    strokeCap = StrokeCap.Round,
                    arrowheadCornerRadiusFraction = 0f,
                    fontWeight = FontWeight.Normal,
                    textSizeFraction = 0.33f,
                    glyph = PhosphorRotateGlyph
                )
                PhosphorIconStyle.Regular -> SkipIntervalStyleSpec(
                    strokeWidthDpAt24 = 16f / PHOSPHOR_GRID * BASE_ICON_DP,
                    strokeCap = StrokeCap.Round,
                    arrowheadCornerRadiusFraction = 0f,
                    fontWeight = FontWeight.SemiBold,
                    textSizeFraction = 0.29f,
                    glyph = PhosphorRotateGlyph
                )
                PhosphorIconStyle.Bold -> SkipIntervalStyleSpec(
                    strokeWidthDpAt24 = 24f / PHOSPHOR_GRID * BASE_ICON_DP,
                    strokeCap = StrokeCap.Round,
                    arrowheadCornerRadiusFraction = 0f,
                    fontWeight = FontWeight.Bold,
                    textSizeFraction = 0.26f,
                    glyph = PhosphorRotateGlyph
                )
                PhosphorIconStyle.Filled -> SkipIntervalStyleSpec(
                    strokeWidthDpAt24 = 28f / PHOSPHOR_GRID * BASE_ICON_DP,
                    strokeCap = StrokeCap.Round,
                    arrowheadCornerRadiusFraction = 0.2f,
                    fontWeight = FontWeight.Bold,
                    textSizeFraction = TRIANGLE_TEXT_SIZE_FRACTION
                )
                PhosphorIconStyle.Duotone -> SkipIntervalStyleSpec(
                    strokeWidthDpAt24 = 16f / PHOSPHOR_GRID * BASE_ICON_DP,
                    strokeCap = StrokeCap.Round,
                    arrowheadCornerRadiusFraction = 0f,
                    fontWeight = FontWeight.SemiBold,
                    textSizeFraction = 0.29f,
                    glyph = PhosphorRotateGlyph,
                    duotoneAlpha = 0.2f
                )
            }
        }
        IconPackType.Lucide -> SkipIntervalStyleSpec(
            strokeWidthDpAt24 = 2f,
            strokeCap = StrokeCap.Round,
            arrowheadCornerRadiusFraction = 0f,
            fontWeight = FontWeight.SemiBold,
            textSizeFraction = 0.29f,
            glyph = LucideRotateGlyph
        )
    }
}

private fun roundedTrianglePath(tip: Offset, wing1: Offset, wing2: Offset, cornerRadius: Float): Path {
    if (cornerRadius <= 0.05f) {
        return Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(wing1.x, wing1.y)
            lineTo(wing2.x, wing2.y)
            close()
        }
    }
    val points = listOf(tip, wing1, wing2)
    val path = Path()
    val n = points.size
    for (i in 0 until n) {
        val curr = points[i]
        val prev = points[(i + n - 1) % n]
        val next = points[(i + 1) % n]
        val toPrev = prev - curr
        val toNext = next - curr
        val distPrev = toPrev.getDistance()
        val distNext = toNext.getDistance()
        val dirPrev = if (distPrev > 1e-4f) toPrev / distPrev else Offset.Zero
        val dirNext = if (distNext > 1e-4f) toNext / distNext else Offset.Zero
        val actualRadius = min(cornerRadius, min(distPrev, distNext) / 2.1f)
        val start = curr + dirPrev * actualRadius
        val end = curr + dirNext * actualRadius
        if (i == 0) path.moveTo(start.x, start.y) else path.lineTo(start.x, start.y)
        path.quadraticTo(curr.x, curr.y, end.x, end.y)
    }
    path.close()
    return path
}

@Composable
fun SkipIntervalIcon(
    seconds: Int,
    forward: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val contentColor = LocalContentColor.current
    val iconPackType = LocalIconPack.current.type
    val iconStyle = LocalIconStyle.current
    val spec = resolveStyleSpec(iconPackType, iconStyle)

    BoxWithConstraints(
        modifier = modifier.then(
            if (contentDescription != null) {
                Modifier.semantics {
                    this.contentDescription = contentDescription
                    this.role = Role.Image
                }
            } else {
                Modifier.clearAndSetSemantics {}
            }
        ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val diameter = min(size.width, size.height)
            val glyph = spec.glyph
            if (glyph != null) {
                val strokeWidth = spec.strokeWidthDpAt24 / BASE_ICON_DP * glyph.grid
                translate((size.width - diameter) / 2f, (size.height - diameter) / 2f) {
                    scale(diameter / glyph.grid, pivot = Offset.Zero) {
                        if (spec.duotoneAlpha > 0f) {
                            drawCircle(
                                color = contentColor.copy(alpha = contentColor.alpha * spec.duotoneAlpha),
                                radius = glyph.circleRadius - strokeWidth / 2f,
                                center = glyph.center
                            )
                        }
                        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        (if (forward) glyph.forwardPaths else glyph.backPaths).forEach { path ->
                            drawPath(path, color = contentColor, style = stroke)
                        }
                    }
                }
                return@Canvas
            }

            val radius = diameter * 0.43f
            val center = Offset(size.width / 2f, size.height / 2f)
            val strokeWidthPx = spec.strokeWidthDpAt24 / BASE_ICON_DP * diameter

            val rightEnd = TOP_ANGLE_DEGREES + GAP_HALF_DEGREES
            val leftEnd = TOP_ANGLE_DEGREES - GAP_HALF_DEGREES

            val arcStartAngle = if (forward) rightEnd else rightEnd + ARC_SHORTEN_DEGREES
            val arcSweepAngle = ARC_SWEEP_DEGREES - ARC_SHORTEN_DEGREES

            drawArc(
                color = contentColor,
                startAngle = arcStartAngle,
                sweepAngle = arcSweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = strokeWidthPx, cap = spec.strokeCap)
            )

            fun pointOnArc(angleDegrees: Float): Offset {
                val angleRad = angleDegrees * (PI / 180.0).toFloat()
                return Offset(center.x + radius * cos(angleRad), center.y + radius * sin(angleRad))
            }

            fun tangentAt(angleDegrees: Float, clockwise: Boolean): Offset {
                val angleRad = angleDegrees * (PI / 180.0).toFloat()
                val c = cos(angleRad)
                val s = sin(angleRad)
                return if (clockwise) Offset(-s, c) else Offset(s, -c)
            }

            val arrowAngle = if (forward) leftEnd else rightEnd
            val direction = tangentAt(arrowAngle, clockwise = forward)
            val arcEndPoint = pointOnArc(arrowAngle)
            val perpendicular = Offset(-direction.y, direction.x)
            val length = diameter * TRIANGLE_LENGTH_FRACTION
            val halfWidth = diameter * TRIANGLE_HALF_WIDTH_FRACTION
            val tip = arcEndPoint + direction * length
            val wing1 = arcEndPoint + perpendicular * halfWidth
            val wing2 = arcEndPoint - perpendicular * halfWidth
            val cornerRadiusPx = halfWidth * spec.arrowheadCornerRadiusFraction
            drawPath(roundedTrianglePath(tip, wing1, wing2, cornerRadiusPx), color = contentColor)
        }

        val fontSizeSp = (min(maxWidth.value, maxHeight.value) * spec.textSizeFraction).sp
        Text(
            text = seconds.toString(),
            maxLines = 1,
            style = TextStyle(
                color = contentColor,
                fontWeight = spec.fontWeight,
                fontSize = fontSizeSp,
                lineHeight = fontSizeSp,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both
                ),
                textAlign = TextAlign.Center
            )
        )
    }
}
