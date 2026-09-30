package dev.dertyp.synara.ui.components.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.ui.LocalWindowActions
import dev.dertyp.synara.ui.SynaraIcons
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.fullscreen_hud_auto_hide_disable
import synara.synara.generated.resources.fullscreen_hud_auto_hide_enable
import kotlin.math.roundToInt

const val HUD_HIDE_MILLIS = 450
const val HUD_SHOW_MILLIS = 250

val ExpandedPlayerHorizontalMinWidth = 800.dp

val LocalPlayerHudHidden = compositionLocalOf { false }

fun playerHudHidden(
    autoHide: Boolean,
    isFullscreen: Boolean,
    isExpanded: Boolean,
    idle: Boolean,
    overlayOpen: Boolean
): Boolean = autoHide && isFullscreen && isExpanded && idle && !overlayOpen

fun sidePanelShowing(queue: Boolean, lyrics: Boolean, tags: Boolean, isPodcast: Boolean): Boolean =
    queue || lyrics || (tags && !isPodcast)

data class HudShift(val cover: Offset, val line: Offset)

fun hudShiftOffsets(
    strip: Boolean,
    isRemote: Boolean,
    height: Float,
    topBar: Float,
    panelWidth: Float
): HudShift {
    val dx = panelWidth / 2f
    return if (strip && !isRemote) {
        HudShift(cover = Offset(dx, 0f), line = Offset(dx, height / 2f))
    } else {
        HudShift(cover = Offset(dx, (height - topBar) / 2f), line = Offset(dx, 0f))
    }
}

@Stable
class PlayerHud(val hidden: State<Boolean>, val progress: State<Float>) {
    val topBarHeight = mutableIntStateOf(0)
    val panelWidth = mutableIntStateOf(0)
    val contentWidth = mutableIntStateOf(0)
}

@Composable
fun rememberPlayerHud(hidden: Boolean): PlayerHud {
    val hiddenState = rememberUpdatedState(hidden)
    val progress = animateFloatAsState(
        targetValue = if (hidden) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (hidden) HUD_HIDE_MILLIS else HUD_SHOW_MILLIS,
            easing = FastOutSlowInEasing
        ),
        label = "playerHudProgress"
    )
    return remember(hiddenState, progress) { PlayerHud(hiddenState, progress) }
}

fun Modifier.hudShift(progress: State<Float>, offset: () -> Offset): Modifier = graphicsLayer {
    val p = progress.value
    if (p != 0f) {
        val shift = offset()
        translationX = shift.x * p
        translationY = shift.y * p
    }
}

fun hudLineWidth(current: Float, full: Float, progress: Float): Float =
    if (full <= 0f) current else current + (full - current) * progress

fun centeredWidth(reported: Int, width: Float): Int =
    (reported + 2 * ((width - reported) / 2f).roundToInt()).coerceAtLeast(0)

fun Modifier.hudLineWidth(progress: State<Float>, fraction: Float, fullWidth: () -> Float): Modifier =
    layout { measurable, constraints ->
        val current = if (constraints.hasBoundedWidth) constraints.maxWidth * fraction else constraints.minWidth.toFloat()
        val reported = current.roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val p = progress.value
        val width = if (p == 0f) reported else centeredWidth(reported, hudLineWidth(current, fullWidth(), p))
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(reported, placeable.height) {
            placeable.place((reported - placeable.width) / 2, 0)
        }
    }

fun Modifier.hudChrome(hud: PlayerHud, alpha: () -> Float = { 1f }): Modifier = this
    .pointerInput(hud) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && hud.hidden.value) {
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
    .graphicsLayer { this.alpha = alpha() * (1f - hud.progress.value) }

@Composable
fun rememberHudShiftedCenter(
    center: State<Offset>,
    progress: State<Float>,
    offset: () -> Offset
): State<Offset> {
    val currentOffset = rememberUpdatedState(offset)
    return remember(center, progress) {
        derivedStateOf {
            val base = center.value
            val p = progress.value
            if (!base.isSpecified || p == 0f) base else base + currentOffset.value() * p
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullscreenHudToggle(autoHide: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    if (!LocalWindowActions.current.isFullscreen) return
    val label = stringResource(
        if (autoHide) Res.string.fullscreen_hud_auto_hide_disable else Res.string.fullscreen_hud_auto_hide_enable
    )
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier
    ) {
        IconButton(onClick = onToggle) {
            Icon(
                if (autoHide) SynaraIcons.HudAutoHideOn.get() else SynaraIcons.HudAutoHideOff.get(),
                contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = if (autoHide) MaterialTheme.colorScheme.primary else LocalContentColor.current
            )
        }
    }
}
