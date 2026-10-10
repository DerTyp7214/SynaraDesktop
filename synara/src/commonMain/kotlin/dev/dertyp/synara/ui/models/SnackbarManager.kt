package dev.dertyp.synara.ui.models

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

class SnackbarManager(dispatchers: SynaraDispatchers) {
    val snackbarHostState = SnackbarHostState()
    private val scope = CoroutineScope(dispatchers.main + SupervisorJob())
    private val pending = Channel<SnackbarVisuals>(capacity = PENDING_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            for (visuals in pending) {
                val lifetime = when (visuals.duration) {
                    SnackbarDuration.Short -> SHORT_LIFETIME_MS
                    SnackbarDuration.Long -> LONG_LIFETIME_MS
                    SnackbarDuration.Indefinite -> null
                }
                if (lifetime == null) {
                    snackbarHostState.showSnackbar(visuals)
                } else {
                    withTimeoutOrNull(lifetime) { snackbarHostState.showSnackbar(visuals) }
                }
            }
        }
    }

    fun showSnackbar(
        message: String,
        actionLabel: String? = null,
        withDismissAction: Boolean = false,
        duration: SnackbarDuration = SnackbarDuration.Short
    ) {
        enqueue(
            AnnotatedSnackbarVisuals(
                message = parseMarkdown(message),
                actionLabel = actionLabel,
                withDismissAction = withDismissAction,
                duration = duration
            )
        )
    }

    fun showSnackbar(visuals: SnackbarVisuals) {
        enqueue(visuals)
    }

    private fun enqueue(visuals: SnackbarVisuals) {
        scope.launch {
            val current = snackbarHostState.currentSnackbarData
            if (current?.visuals?.duration == SnackbarDuration.Indefinite) current.dismiss()
            pending.trySend(visuals)
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

private const val PENDING_CAPACITY = 16
private const val SHORT_LIFETIME_MS = 4_500L
private const val LONG_LIFETIME_MS = 10_500L
