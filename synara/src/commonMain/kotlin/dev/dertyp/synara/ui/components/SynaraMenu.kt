package dev.dertyp.synara.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.viewmodels.GlobalStateModel
import org.koin.compose.koinInject

@Composable
fun SynaraMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val globalState = koinInject<GlobalStateModel>()

    DisposableEffect(expanded) {
        if (expanded) {
            globalState.incrementMenuCount()
        }
        onDispose {
            if (expanded) {
                globalState.decrementMenuCount()
            }
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = shape,
        content = content
    )
}
