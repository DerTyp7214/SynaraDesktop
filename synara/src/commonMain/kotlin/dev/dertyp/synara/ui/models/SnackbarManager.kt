package dev.dertyp.synara.ui.models

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SnackbarManager {
    val snackbarHostState = SnackbarHostState()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun showSnackbar(
        message: String,
        actionLabel: String? = null,
        withDismissAction: Boolean = false,
        duration: SnackbarDuration = SnackbarDuration.Short
    ) {
        scope.launch {
            val annotatedMessage = parseMarkdown(message)
            snackbarHostState.showSnackbar(
                visuals = AnnotatedSnackbarVisuals(
                    message = annotatedMessage,
                    actionLabel = actionLabel,
                    withDismissAction = withDismissAction,
                    duration = duration
                )
            )
        }
    }

    fun showSnackbar(visuals: SnackbarVisuals) {
        scope.launch {
            snackbarHostState.showSnackbar(visuals)
        }
    }

    private fun parseMarkdown(text: String): AnnotatedString {
        val parts = text.split("**")
        return buildAnnotatedString {
            parts.forEachIndexed { index, part ->
                if (index % 2 == 1) {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(part)
                    }
                } else {
                    append(part)
                }
            }
        }
    }
}

class AnnotatedSnackbarVisuals(
    override val message: String,
    val annotatedMessage: AnnotatedString,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short
) : SnackbarVisuals {
    constructor(
        message: AnnotatedString,
        actionLabel: String? = null,
        withDismissAction: Boolean = false,
        duration: SnackbarDuration = SnackbarDuration.Short
    ) : this(message.text, message, actionLabel, withDismissAction, duration)
}
