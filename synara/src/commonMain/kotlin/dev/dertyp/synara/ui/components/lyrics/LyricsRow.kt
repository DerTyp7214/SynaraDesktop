package dev.dertyp.synara.ui.components.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val sungColor = Color.White
private val dimColor = Color.White.copy(alpha = 0.4f)
private val backingSungColor = Color.White.copy(alpha = 0.75f)
private val backingDimColor = Color.White.copy(alpha = 0.25f)
private const val BACKING_SCALE = 0.65f
private const val ACTIVE_SCALE = 1.05f

@Composable
internal fun LyricsRow(
    line: DisplayLine,
    position: LongState,
    active: Boolean,
    style: TextStyle,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val textAlign = when (line.voiceIndex) {
        0 -> TextAlign.Start
        1 -> TextAlign.End
        else -> style.textAlign
    }
    val originX = when (textAlign) {
        TextAlign.Start, TextAlign.Left -> 0f
        TextAlign.End, TextAlign.Right -> 1f
        else -> 0.5f
    }
    val alignment = when (originX) {
        0f -> Alignment.Start
        1f -> Alignment.End
        else -> Alignment.CenterHorizontally
    }
    val leadStyle = style.copy(textAlign = textAlign)
    val scale by animateFloatAsState(targetValue = if (active) ACTIVE_SCALE else 1f, label = "lyricScale")
    val color by animateColorAsState(
        targetValue = if (active && line.words == null) sungColor else dimColor,
        label = "lyricColor"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(originX, 0.5f)
            }
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSeek(line.start) }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalAlignment = alignment
    ) {
        val label = line.label
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = color.copy(alpha = color.alpha * 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SweepText(
            line = line,
            position = position,
            active = active,
            style = leadStyle,
            baseColor = color,
            sungColor = sungColor
        )
        if (line.backing.isNotEmpty()) {
            val backingStyle = leadStyle.copy(
                fontSize = leadStyle.fontSize * BACKING_SCALE,
                lineHeight = leadStyle.lineHeight * BACKING_SCALE
            )
            line.backing.forEach { backing ->
                val backingColor by animateColorAsState(
                    targetValue = if (active && backing.words == null) backingSungColor else backingDimColor,
                    label = "backingColor"
                )
                SweepText(
                    line = backing,
                    position = position,
                    active = active,
                    style = backingStyle,
                    baseColor = backingColor,
                    sungColor = backingSungColor,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private class SweepCache {
    var layout: TextLayoutResult? = null
    private var builtFor: TextLayoutResult? = null
    private var prefixes: List<Path> = emptyList()
    private var wordPaths: List<Path> = emptyList()
    private var wordBounds: List<Rect> = emptyList()

    fun draw(scope: ContentDrawScope, words: List<DisplayWord>, time: Long, color: Color) {
        val layout = layout ?: return
        val current = words.indexOfLast { it.start <= time }
        if (current < 0) return
        build(layout, words)
        val word = words[current]
        val span = (word.end - word.start).coerceAtLeast(1L)
        val progress = ((time - word.start).toFloat() / span).coerceIn(0f, 1f)
        with(scope) {
            if (progress >= 1f) {
                clipPath(prefixes[current]) { drawText(layout, color = color) }
                return
            }
            if (current > 0) {
                clipPath(prefixes[current - 1]) { drawText(layout, color = color) }
            }
            if (progress > 0f) {
                val bounds = wordBounds[current]
                clipPath(wordPaths[current]) {
                    clipRect(left = bounds.left, top = bounds.top, right = bounds.left + bounds.width * progress, bottom = bounds.bottom) {
                        drawText(layout, color = color)
                    }
                }
            }
        }
    }

    private fun build(layout: TextLayoutResult, words: List<DisplayWord>) {
        if (builtFor === layout) return
        prefixes = words.map { layout.getPathForRange(0, it.endIndex) }
        wordPaths = words.map { layout.getPathForRange(it.startIndex, it.endIndex) }
        wordBounds = wordPaths.map { it.getBounds() }
        builtFor = layout
    }
}

@Composable
private fun SweepText(
    line: DisplayLine,
    position: LongState,
    active: Boolean,
    style: TextStyle,
    baseColor: Color,
    sungColor: Color,
    modifier: Modifier = Modifier
) {
    val words = line.words
    val cache = remember(line) { SweepCache() }
    Text(
        text = line.text,
        style = style,
        color = baseColor,
        onTextLayout = { cache.layout = it },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (words != null && active) {
                    Modifier.drawWithContent {
                        drawContent()
                        cache.draw(this, words, position.longValue, sungColor)
                    }
                } else Modifier
            )
    )
}
