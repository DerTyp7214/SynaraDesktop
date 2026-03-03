@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.dertyp.synara.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.fadingEdge(
    orientation: Orientation,
    fadeLength: Dp = 16.dp
) = graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
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
