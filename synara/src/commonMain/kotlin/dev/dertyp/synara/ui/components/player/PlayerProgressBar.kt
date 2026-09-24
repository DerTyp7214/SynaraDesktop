package dev.dertyp.synara.ui.components.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TimecodeTagAction
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.synara.Config
import dev.dertyp.synara.ui.components.formatDuration
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerProgressBar(
    currentPosition: Long,
    duration: Long,
    currentSongExists: Boolean,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
    tags: List<TimecodeTag> = emptyList()
) {
    val showRemainingTime by Config.showRemainingTime.collectAsState()
    var hoverX by remember { mutableStateOf<Float?>(null) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            formatDuration(currentPosition.coerceAtMost(duration)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 40.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) {
                    hoverX = it.changes.firstOrNull()?.position?.x
                }
                .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) {
                    hoverX = it.changes.firstOrNull()?.position?.x
                }
                .onPointerEvent(PointerEventType.Exit, PointerEventPass.Initial) {
                    hoverX = null
                }
        ) {
            Slider(
                value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                onValueChange = onSeek,
                onValueChangeFinished = onSeekFinished,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                enabled = currentSongExists
            )

            if (tags.isNotEmpty() && duration > 0) {
                TimecodeTagOverlay(
                    tags = tags,
                    duration = duration,
                    hoverX = hoverX,
                    modifier = Modifier.matchParentSize()
                )
            }
        }

        Text(
            if (showRemainingTime) "-${formatDuration((duration - currentPosition).coerceAtLeast(0))}"
            else formatDuration(duration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(min = 40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { Config.setShowRemainingTime(!showRemainingTime) },
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun TimecodeTagOverlay(
    tags: List<TimecodeTag>,
    duration: Long,
    hoverX: Float?,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }

    val neutral = MaterialTheme.colorScheme.onSurface
    val playColor = MaterialTheme.colorScheme.primary
    val skipColor = MaterialTheme.colorScheme.error
    val noteColor = MaterialTheme.colorScheme.tertiary

    fun colorOf(tag: TimecodeTag): Color = when (tag.action) {
        TimecodeTagAction.PLAY_ONLY, TimecodeTagAction.SKIP_TO -> playColor
        TimecodeTagAction.SKIP, TimecodeTagAction.PLAY_UNTIL -> skipColor
        TimecodeTagAction.NONE -> if (tag.type == TimecodeTagType.NOTE) noteColor else neutral
    }

    val markerHitPx = with(density) { 5.dp.toPx() }
    val hovered = hoverX?.let { x ->
        if (size.width <= 0) return@let null
        val ms = (x / size.width * duration).toLong()
        tags.filter { it.endMs == null }
            .minByOrNull { abs(it.timestampMs.toFloat() / duration * size.width - x) }
            ?.takeIf { abs(it.timestampMs.toFloat() / duration * size.width - x) <= markerHitPx }
            ?: tags.filter { tag -> tag.endMs?.let { ms in tag.timestampMs until it } == true }
                .minByOrNull { (it.endMs ?: it.timestampMs) - it.timestampMs }
    }

    Box(modifier = modifier.onSizeChanged { size = it }) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = this.size.width
            val height = this.size.height
            val bandHeight = height * 0.6f
            val bandTop = (height - bandHeight) / 2f
            tags.forEach { tag ->
                val end = tag.endMs ?: return@forEach
                val startX = (tag.timestampMs.toFloat() / duration).coerceIn(0f, 1f) * width
                val endX = (end.toFloat() / duration).coerceIn(0f, 1f) * width
                val alpha = if (tag.action == TimecodeTagAction.NONE) 0.18f else 0.4f
                drawRoundRect(
                    color = colorOf(tag).copy(alpha = if (tag == hovered) alpha + 0.2f else alpha),
                    topLeft = Offset(startX, bandTop),
                    size = Size((endX - startX).coerceAtLeast(2f), bandHeight),
                    cornerRadius = CornerRadius(bandHeight / 2f, bandHeight / 2f)
                )
            }
            val tickWidth = 2.dp.toPx()
            tags.forEach { tag ->
                if (tag.endMs != null) return@forEach
                val x = (tag.timestampMs.toFloat() / duration).coerceIn(0f, 1f) * width
                drawRect(
                    color = colorOf(tag).copy(alpha = if (tag == hovered) 1f else 0.8f),
                    topLeft = Offset(x - tickWidth / 2f, 0f),
                    size = Size(tickWidth, height)
                )
            }
        }

        val tag = hovered
        val x = hoverX
        if (tag != null) {
            val tooltipOffset = with(density) { IntOffset(x.toInt(), -32.dp.roundToPx()) }
            Popup(offset = tooltipOffset) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface
                ) {
                    val title = tag.text.ifBlank { stringResource(tag.type.label()) }
                    val action = if (tag.action != TimecodeTagAction.NONE) " · " + stringResource(tag.action.label()) else ""
                    Text(
                        text = "$title · ${tag.formatRange()}$action",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
