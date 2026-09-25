package dev.dertyp.synara.ui.components.podcast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dertyp.data.PodcastShow
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.ui.components.dialogs.SynaraAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

private val feedUrlRegex = Regex("^https?://[^\\s/?#]+[^\\s]*$", RegexOption.IGNORE_CASE)

fun isValidFeedUrl(url: String): Boolean = url.length <= 2048 && feedUrlRegex.matches(url)

@Composable
fun AddFeedDialog(
    onDismissRequest: () -> Unit,
    onSubscribe: suspend (String) -> PodcastShow,
    onSubscribed: (PodcastShow) -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var url by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    var isSubscribing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val trimmed = url.trim()
    val valid = isValidFeedUrl(trimmed)
    val showInvalid = touched && trimmed.isNotEmpty() && !valid

    fun submit() {
        touched = true
        if (!valid || isSubscribing) return
        isSubscribing = true
        error = null
        scope.launch {
            try {
                onSubscribed(onSubscribe(trimmed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: ""
                isSubscribing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    SynaraAlertDialog(
        isOpen = true,
        onDismissRequest = { if (!isSubscribing) onDismissRequest() },
        title = { Text(stringResource(Res.string.podcast_add_feed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.podcast_add_feed_description))
                InternalTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text(stringResource(Res.string.podcast_add_feed_url_label)) },
                    placeholder = { Text(stringResource(Res.string.podcast_add_feed_url_placeholder)) },
                    isError = showInvalid || error != null,
                    supportingText = when {
                        showInvalid -> {
                            { Text(stringResource(Res.string.podcast_add_feed_invalid_url)) }
                        }
                        error != null -> {
                            { Text(stringResource(Res.string.podcast_add_feed_failed, error ?: "")) }
                        }
                        else -> null
                    },
                    enabled = !isSubscribing,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().widthIn(min = 360.dp).focusRequester(focusRequester)
                )
            }
        },
        confirmButton = {
            Button(onClick = { submit() }, enabled = !isSubscribing && trimmed.isNotEmpty()) {
                if (isSubscribing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.podcast_subscribing))
                } else {
                    Text(stringResource(Res.string.podcast_subscribe))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !isSubscribing) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}
