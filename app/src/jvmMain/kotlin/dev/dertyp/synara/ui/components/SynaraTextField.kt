package dev.dertyp.synara.ui.components

import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

@Composable
fun SynaraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
) {
    var textFieldValueState by remember { mutableStateOf(TextFieldValue(text = value)) }

    LaunchedEffect(value) {
        if (value != textFieldValueState.text) {
            textFieldValueState = textFieldValueState.copy(text = value)
        }
    }

    TextField(
        value = textFieldValueState,
        onValueChange = {
            textFieldValueState = it
            if (value != it.text) {
                onValueChange(it.text)
            }
        },
        label = label,
        isError = isError,
        singleLine = singleLine,
        modifier = modifier.onKeyEvent { keyEvent ->
            if (keyEvent.type != KeyEventType.KeyDown) {
                return@onKeyEvent false
            }

            val currentText = textFieldValueState.text
            val selection = textFieldValueState.selection

            if (keyEvent.key == Key.Backspace) {
                val newText: String
                val newSelection: TextRange
                if (selection.collapsed && selection.start > 0) {
                    newText = currentText.substring(0, selection.start - 1) + currentText.substring(selection.start)
                    newSelection = TextRange(selection.start - 1)
                } else if (!selection.collapsed) {
                    newText = currentText.replaceRange(selection.min, selection.max, "")
                    newSelection = TextRange(selection.min)
                } else {
                    return@onKeyEvent false
                }

                textFieldValueState = TextFieldValue(newText, newSelection)
                onValueChange(newText)
                return@onKeyEvent true
            }

            val charCode = keyEvent.utf16CodePoint
            if (charCode != 0) {
                val char = charCode.toChar()
                if (!char.isISOControl()) {
                    val newText = currentText.replaceRange(selection.min, selection.max, char.toString())
                    val newSelection = TextRange(selection.min + 1)
                    textFieldValueState = TextFieldValue(newText, newSelection)
                    onValueChange(newText)
                    return@onKeyEvent true
                }
            }

            false
        }
    )
}
