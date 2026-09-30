package dev.dertyp.synara.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import dev.dertyp.synara.settings.FullscreenHudLimits
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

val LocalPlayerHudIdle = compositionLocalOf { false }

val CursorIdleTimeout = 3.seconds

@Composable
fun rememberIdleAfter(activity: Any?, active: Boolean, timeout: Duration): Boolean {
    val session = remember(activity, active, timeout) { Any() }
    var idleSession by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(session) {
        if (active) {
            delay(timeout)
            idleSession = session
        }
    }
    return active && idleSession === session
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FullscreenIdleBox(
    windowActions: WindowActions,
    overlayOpen: Boolean,
    hudAutoHide: Boolean,
    hudDelaySeconds: Int,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var pointerActivity by remember { mutableLongStateOf(0L) }
    val watching = windowActions.isFullscreen && !overlayOpen

    val cursorIdle = rememberIdleAfter(pointerActivity, watching, CursorIdleTimeout)
    val hudIdle = rememberIdleAfter(
        pointerActivity,
        watching && hudAutoHide,
        FullscreenHudLimits.clampDelay(hudDelaySeconds).seconds
    )

    LaunchedEffect(cursorIdle) {
        windowActions.setCursorVisible(!cursorIdle)
    }

    CompositionLocalProvider(LocalPlayerHudIdle provides hudIdle) {
        Box(
            modifier = modifier
                .onPointerEvent(PointerEventType.Move) {
                    if (windowActions.isFullscreen) pointerActivity++
                }
                .onPointerEvent(PointerEventType.Press) {
                    if (windowActions.isFullscreen) pointerActivity++
                }
                .onPointerEvent(PointerEventType.Enter) {
                    if (windowActions.isFullscreen) pointerActivity++
                },
            content = content
        )
    }
}
