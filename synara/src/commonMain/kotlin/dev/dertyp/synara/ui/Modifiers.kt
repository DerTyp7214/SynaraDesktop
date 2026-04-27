@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.dertyp.synara.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.CompositingStrategy.Companion.Offscreen
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.fadingEdge(
    orientation: Orientation,
    fadeLength: Dp = 16.dp
) = graphicsLayer(compositingStrategy = Offscreen)
    .drawWithContent {
        drawContent()
        val fadeLengthPx = fadeLength.toPx()
        
        val brush = when (orientation) {
            Orientation.Horizontal -> {
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    fadeLengthPx / size.width to Color.Black,
                    (size.width - fadeLengthPx) / size.width to Color.Black,
                    1f to Color.Transparent
                )
            }
            Orientation.Vertical -> {
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    fadeLengthPx / size.height to Color.Black,
                    (size.height - fadeLengthPx) / size.height to Color.Black,
                    1f to Color.Transparent
                )
            }
        }

        drawRect(
            brush = brush,
            blendMode = BlendMode.DstIn
        )
    }

@Composable
fun Modifier.verticalScrollScrim(
    edgeHeight: Float = 0.05f,
    topEnabled: Boolean = true,
    bottomEnabled: Boolean = true
): Modifier {
    val animatedTopHeight by animateFloatAsState(
        targetValue = if (topEnabled) edgeHeight else 0f,
        label = "topScrimHeight"
    )
    val animatedBottomHeight by animateFloatAsState(
        targetValue = if (bottomEnabled) edgeHeight else 0f,
        label = "bottomScrimHeight"
    )

    val fadeBrush = remember(animatedTopHeight, animatedBottomHeight) {
        Brush.verticalGradient(
            0f to Color.Transparent,
            animatedTopHeight to Color.Black,
            (1f - animatedBottomHeight) to Color.Black,
            1f to Color.Transparent
        )
    }

    return this
        .graphicsLayer {
            compositingStrategy = if (animatedTopHeight > 0f || animatedBottomHeight > 0f) Offscreen else CompositingStrategy.Auto
        }
        .drawWithContent {
            drawContent()
            if (animatedTopHeight > 0f || animatedBottomHeight > 0f) {
                drawRect(brush = fadeBrush, blendMode = BlendMode.DstIn)
            }
        }
}

@Composable
fun Modifier.verticalScrollScrim(
    state: ScrollState,
    edgeHeight: Float = 0.05f,
    applyScroll: Boolean = false
): Modifier = this
    .verticalScrollScrim(
        edgeHeight = edgeHeight,
        topEnabled = state.canScrollBackward,
        bottomEnabled = state.canScrollForward
    )
    .then(if (applyScroll) Modifier.verticalScroll(state) else Modifier)

@Composable
fun Modifier.verticalScrollScrim(
    state: LazyListState,
    edgeHeight: Float = 0.05f,
): Modifier = verticalScrollScrim(
    edgeHeight = edgeHeight,
    topEnabled = state.canScrollBackward,
    bottomEnabled = state.canScrollForward
)
