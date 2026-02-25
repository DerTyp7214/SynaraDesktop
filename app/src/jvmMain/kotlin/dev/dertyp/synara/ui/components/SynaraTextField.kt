package dev.dertyp.synara.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.intl.LocaleList


@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SynaraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = TextFieldDefaults.shape,
    colors: TextFieldColors = TextFieldDefaults.colors(),
) {
    val editProcessor = remember { EditProcessor() }
    var textFieldValueState by remember { mutableStateOf(TextFieldValue(text = value)) }
    var textInputSession by remember { mutableStateOf<TextInputSession?>(null) }
    var focused by remember { mutableStateOf(false) }

    val inputService = LocalTextInputService.current
    LaunchedEffect(inputService, focused) {
        if (focused) {
            textInputSession = inputService?.startInput(
                value = textFieldValueState,
                imeOptions = keyboardOptions.toImeOptions(singleLine),
                onEditCommand = { commands ->
                    editProcessor.reset(textFieldValueState, textInputSession)
                    editProcessor.apply(commands)
                    textFieldValueState = editProcessor.toTextFieldValue()

                    if (value != textFieldValueState.text) {
                        onValueChange(textFieldValueState.text)
                    }
                },
                onImeActionPerformed = {}
            )
        } else textInputSession?.let { inputService?.stopInput(it) }
    }

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
        modifier = modifier.onFocusChanged {
            focused = it.isFocused
        },
        label = label,
        isError = isError,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        visualTransformation = visualTransformation,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors
    )
}

internal fun KeyboardOptions.toImeOptions(singleLine: Boolean): ImeOptions {
    return ImeOptions(
        singleLine = singleLine,
        capitalization = capitalization,
        autoCorrect = autoCorrectEnabled == true,
        keyboardType = keyboardType,
        imeAction = imeAction,
        platformImeOptions = platformImeOptions,
        hintLocales = hintLocales ?: LocaleList.Empty
    )
}