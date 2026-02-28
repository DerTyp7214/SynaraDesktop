package dev.dertyp.synara.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
fun PlayPauseIcon(
    isPlaying: Boolean,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val progress by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(300),
        label = "playPauseMorph"
    )

    Canvas(modifier = modifier.size(32.dp)) {
        val w = size.width
        val h = size.height
        val r = 4.dp.toPx()

        fun lerp(s: Float, e: Float) = s + progress * (e - s)
        fun p(x: Float, y: Float) = Offset(x * w, y * h)

        val l1 = p(lerp(0.20f, 0.25f), 0.15f) // Top-Left (Outer)
        val l2 = p(lerp(0.50f, 0.43f), lerp(0.325f, 0.15f)) // Top-Right (Inner)
        val l3 = p(lerp(0.50f, 0.43f), lerp(0.675f, 0.85f)) // Bottom-Right (Inner)
        val l4 = p(lerp(0.20f, 0.25f), 0.85f) // Bottom-Left (Outer)

        val r1 = p(lerp(0.50f, 0.57f), lerp(0.325f, 0.15f)) // Top-Left (Inner)
        val r2 = p(lerp(0.80f, 0.75f), lerp(0.5f - 0.02f, 0.15f)) // Top-Right (Outer/Tip)
        val r3 = p(lerp(0.80f, 0.75f), lerp(0.5f + 0.02f, 0.85f)) // Bottom-Right (Outer/Tip)
        val r4 = p(lerp(0.50f, 0.57f), lerp(0.675f, 0.85f)) // Bottom-Left (Inner)

        fun drawMorph(points: List<Offset>, radii: List<Float>) {
            val path = Path()
            val n = points.size
            
            fun getDir(from: Offset, to: Offset): Offset {
                val d = to - from
                val m = d.getDistance()
                return if (m > 1e-4f) d / m else Offset(0f, 0f)
            }

            for (i in 0 until n) {
                val curr = points[i]
                val prev = points[(i + n - 1) % n]
                val next = points[(i + 1) % n]
                val cornerR = radii[i]
                
                if (cornerR > 0.1f) {
                    val d1 = getDir(prev, curr)
                    val d2 = getDir(curr, next)
                    val dist1 = (curr - prev).getDistance()
                    val dist2 = (next - curr).getDistance()
                    
                    val actualR = min(cornerR, min(dist1 / 2.1f, dist2 / 2.1f))
                    
                    val pStart = curr - d1 * actualR
                    val pEnd = curr + d2 * actualR
                    
                    if (i == 0) path.moveTo(pStart.x, pStart.y)
                    else path.lineTo(pStart.x, pStart.y)
                    path.quadraticTo(curr.x, curr.y, pEnd.x, pEnd.y)
                } else {
                    if (i == 0) path.moveTo(curr.x, curr.y)
                    else path.lineTo(curr.x, curr.y)
                }
            }
            path.close()
            drawPath(path, color = contentColor)
        }

        drawMorph(listOf(l1, l2, l3, l4), listOf(r, r * progress, r * progress, r))
        drawMorph(listOf(r1, r2, r3, r4), listOf(r * progress, r, r, r * progress))
    }
}
