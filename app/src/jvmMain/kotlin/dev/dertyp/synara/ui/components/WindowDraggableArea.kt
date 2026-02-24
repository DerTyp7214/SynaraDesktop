package dev.dertyp.synara.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun WindowDraggableArea(
    modifier: Modifier = Modifier,
    onDrag: (Float, Float) -> Unit,
    content: @Composable () -> Unit = {}
) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                onDrag(dragAmount.x, dragAmount.y)
            }
        }
    ) {
        content()
    }
}
