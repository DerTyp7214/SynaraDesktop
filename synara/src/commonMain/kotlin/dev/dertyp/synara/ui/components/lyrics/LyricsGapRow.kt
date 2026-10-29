package dev.dertyp.synara.ui.components.lyrics

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

private const val DOTS = 3
private const val DIM_ALPHA = 0.4f
private const val PULSE_SCALE = 0.08f
private const val PULSE_MS = 1200
private val dotRadius = 6.dp
private val dotSpacing = 20.dp
private val dotOutline = 1.5.dp

@Composable
internal fun LyricsGapRow(
    gap: LyricsItem.Gap,
    position: LongState,
    active: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val activity = animateFloatAsState(targetValue = if (active) 1f else 0f, label = "gapActivity")
    val pulse = if (active) {
        rememberInfiniteTransition(label = "gapPulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "gapPulseScale"
        )
    } else null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSeek(gap.start) }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = dotSpacing * (DOTS - 1) + dotRadius * 2, height = 40.dp)
                .graphicsLayer {
                    val scale = 1f + PULSE_SCALE * activity.value * (pulse?.value ?: 0f)
                    scaleX = scale
                    scaleY = scale
                }
                .drawBehind {
                    val span = (gap.end - gap.start).coerceAtLeast(1L)
                    val progress = ((position.longValue - gap.start).toFloat() / span).coerceIn(0f, 1f)
                    val radius = dotRadius.toPx()
                    val spacing = dotSpacing.toPx()
                    val left = (size.width - spacing * (DOTS - 1)) / 2f
                    val amount = activity.value
                    val outline = dotOutline.toPx()
                    val ring = Stroke(width = outline)
                    val ringColor = Color.White.copy(alpha = DIM_ALPHA + (1f - DIM_ALPHA) * amount)
                    for (dot in 0 until DOTS) {
                        val fill = (progress * DOTS - dot).coerceIn(0f, 1f) * amount
                        val center = Offset(left + spacing * dot, size.height / 2f)
                        drawCircle(color = ringColor, radius = radius - outline / 2f, center = center, style = ring)
                        if (fill > 0f) {
                            drawArc(
                                color = Color.White,
                                startAngle = -90f,
                                sweepAngle = 360f * fill,
                                useCenter = true,
                                topLeft = Offset(center.x - radius, center.y - radius),
                                size = Size(radius * 2f, radius * 2f)
                            )
                        }
                    }
                }
        )
    }
}
