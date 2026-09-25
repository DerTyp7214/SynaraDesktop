package dev.dertyp.synara.ui.components.podcast

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dertyp.data.PodcastDeliveryMode
import dev.dertyp.data.PodcastRetention
import dev.dertyp.data.PodcastShow
import dev.dertyp.data.PodcastShowSettings
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.ui.components.dialogs.SynaraDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

@Composable
fun ShowStorageDialog(
    show: PodcastShow,
    onDismissRequest: () -> Unit,
    onSave: suspend (PodcastShowSettings) -> Unit,
    onSaved: () -> Unit,
    onFailed: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var deliveryMode by remember(show.id) { mutableStateOf(show.deliveryMode) }
    var keepText by remember(show.id) { mutableStateOf(show.keepEpisodes?.toString() ?: "") }
    var retention by remember(show.id) { mutableStateOf(show.retention) }
    var isSaving by remember { mutableStateOf(false) }

    val keepValue = keepText.trim().toIntOrNull()
    val keepInvalid = keepText.isNotBlank() && (keepValue == null || keepValue < 1)
    val isImport = deliveryMode == PodcastDeliveryMode.IMPORT

    SynaraDialog(isOpen = true, onDismissRequest = { if (!isSaving) onDismissRequest() }) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.width(480.dp).heightIn(max = 680.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(Res.string.podcast_storage_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = show.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(Res.string.podcast_storage_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column {
                    StorageOption(
                        title = Res.string.podcast_storage_stream,
                        summary = Res.string.podcast_storage_stream_summary,
                        selected = deliveryMode == PodcastDeliveryMode.STREAM,
                        enabled = !isSaving,
                        onClick = {
                            deliveryMode = PodcastDeliveryMode.STREAM
                            retention = PodcastRetention.NEWEST
                        }
                    )
                    StorageOption(
                        title = Res.string.podcast_storage_import,
                        summary = Res.string.podcast_storage_import_summary,
                        selected = isImport,
                        enabled = !isSaving,
                        onClick = { deliveryMode = PodcastDeliveryMode.IMPORT }
                    )
                }

                InternalTextField(
                    value = keepText,
                    onValueChange = { value -> keepText = value.filter { it.isDigit() }.take(6) },
                    label = { Text(stringResource(Res.string.podcast_storage_keep_episodes)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (keepInvalid) Res.string.podcast_storage_keep_invalid
                                else Res.string.podcast_storage_keep_all_hint
                            )
                        )
                    },
                    isError = keepInvalid,
                    enabled = !isSaving,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Column(modifier = Modifier.alpha(if (isImport) 1f else 0.5f)) {
                    Text(
                        text = stringResource(Res.string.podcast_storage_retention),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    StorageOption(
                        title = Res.string.podcast_storage_retention_newest,
                        summary = Res.string.podcast_storage_retention_newest_summary,
                        selected = retention == PodcastRetention.NEWEST,
                        enabled = isImport && !isSaving,
                        onClick = { retention = PodcastRetention.NEWEST }
                    )
                    StorageOption(
                        title = Res.string.podcast_storage_retention_unlistened,
                        summary = Res.string.podcast_storage_retention_unlistened_summary,
                        selected = retention == PodcastRetention.UNLISTENED,
                        enabled = isImport && !isSaving,
                        onClick = { retention = PodcastRetention.UNLISTENED }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismissRequest, enabled = !isSaving) {
                        Text(stringResource(Res.string.cancel))
                    }
                    Button(
                        onClick = {
                            isSaving = true
                            scope.launch {
                                try {
                                    onSave(
                                        PodcastShowSettings(
                                            deliveryMode = deliveryMode,
                                            keepEpisodes = keepValue,
                                            retention = if (isImport) retention else PodcastRetention.NEWEST
                                        )
                                    )
                                    onSaved()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    isSaving = false
                                    onFailed(e.message ?: "")
                                }
                            }
                        },
                        enabled = !isSaving && !keepInvalid
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(Res.string.podcast_storage_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageOption(
    title: StringResource,
    summary: StringResource,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
