package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.dertyp.synara.viewmodels.GlobalStateModel
import org.koin.compose.koinInject

@Composable
fun SynaraDialog(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    globalStateModel: GlobalStateModel = koinInject(),
    content: @Composable () -> Unit
) {
    if (!isOpen) return

    DisposableEffect(Unit) {
        globalStateModel.incrementDialogCount()
        onDispose {
            globalStateModel.decrementDialogCount()
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
        content = content
    )
}

@Composable
fun SynaraAlertDialog(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
    globalStateModel: GlobalStateModel = koinInject()
) {
    if (!isOpen) return

    DisposableEffect(Unit) {
        globalStateModel.incrementDialogCount()
        onDispose {
            globalStateModel.decrementDialogCount()
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties
    )
}
