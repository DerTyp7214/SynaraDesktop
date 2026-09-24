package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dertyp.PlatformUUID
import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TimecodeTagAction
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.player.TimecodeTagRules
import dev.dertyp.synara.player.TimecodeTagStore
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.player.formatTimecode
import dev.dertyp.synara.ui.components.player.icon
import dev.dertyp.synara.ui.components.player.label
import dev.dertyp.synara.ui.components.player.parseTimecode
import dev.dertyp.synara.ui.verticalScrollScrim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimecodeTagEditDialog(
    isOpen: Boolean,
    songId: PlatformUUID,
    tag: TimecodeTag?,
    initialType: TimecodeTagType,
    initialStartMs: Long,
    initialEndMs: Long?,
    durationMs: Long,
    position: StateFlow<Long>,
    onDismissRequest: () -> Unit,
    tagStore: TimecodeTagStore = koinInject()
) {
    val scope = rememberCoroutineScope()

    var type by remember(tag) { mutableStateOf(initialType) }
    var text by remember(tag) { mutableStateOf(tag?.text ?: "") }
    var startText by remember(tag) { mutableStateOf(formatTimecode(initialStartMs)) }
    var endText by remember(tag) { mutableStateOf(initialEndMs?.let { formatTimecode(it) } ?: "") }
    var selectedAction by remember(tag) { mutableStateOf(tag?.action ?: TimecodeTagAction.NONE) }
    var selectedFade by remember(tag) { mutableStateOf(tag?.fade ?: false) }
    var actionExpanded by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val allowedActions = TimecodeTagRules.allowedActions(type)
    val action = if (selectedAction in allowedActions) selectedAction else TimecodeTagAction.NONE
    val fade = selectedFade && action != TimecodeTagAction.NONE

    val startMs = parseTimecode(startText)
    val hasEnd = type == TimecodeTagType.CHAPTER
    val endMs = if (hasEnd) parseTimecode(endText) else null
    val needsEnd = action == TimecodeTagAction.PLAY_ONLY || action == TimecodeTagAction.SKIP
    val startInvalid = startMs == null || (durationMs > 0 && startMs > durationMs)
    val endInvalid = hasEnd && endText.isNotBlank() && endMs == null
    val endBeforeStart = startMs != null && endMs != null && endMs <= startMs
    val endMissing = needsEnd && endMs == null
    val canSave = !isSaving && !startInvalid && !endInvalid && !endBeforeStart && !endMissing

    SynaraAlertDialog(
        isOpen = isOpen,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(if (tag == null) Res.string.timecode_tag_new else Res.string.timecode_tag_edit),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScrollScrim(scrollState, applyScroll = true),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimecodeTagType.entries.forEach { entry ->
                        FilterChip(
                            selected = type == entry,
                            onClick = {
                                type = entry
                                if (entry == TimecodeTagType.CHAPTER && endText.isBlank()) {
                                    val start = parseTimecode(startText) ?: 0L
                                    val end = (start + 30_000L).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
                                    if (end > start) endText = formatTimecode(end)
                                }
                            },
                            label = { Text(stringResource(entry.label())) },
                            leadingIcon = {
                                Icon(entry.icon().get(), contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }

                InternalTextField(
                    value = text,
                    onValueChange = { text = it.take(1000) },
                    label = { Text(stringResource(Res.string.timecode_text)) },
                    modifier = Modifier.fillMaxWidth()
                )

                TimeField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = stringResource(Res.string.timecode_start),
                    isError = startInvalid,
                    errorText = stringResource(Res.string.timecode_time_invalid),
                    onUseCurrent = { startText = formatTimecode(position.value) }
                )

                if (hasEnd) {
                    TimeField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = stringResource(Res.string.timecode_end),
                        isError = endInvalid || endBeforeStart || endMissing,
                        errorText = if (endBeforeStart) stringResource(Res.string.timecode_end_before_start)
                        else stringResource(Res.string.timecode_time_invalid),
                        onUseCurrent = { endText = formatTimecode(position.value) }
                    )
                }

                ExposedDropdownMenuBox(
                    expanded = actionExpanded,
                    onExpandedChange = { if (allowedActions.size > 1) actionExpanded = it }
                ) {
                    OutlinedTextField(
                        value = stringResource(action.label()),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        enabled = allowedActions.size > 1,
                        label = { Text(stringResource(Res.string.timecode_action)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = actionExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = actionExpanded,
                        onDismissRequest = { actionExpanded = false }
                    ) {
                        allowedActions.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text(stringResource(entry.label())) },
                                onClick = {
                                    selectedAction = entry
                                    actionExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = fade,
                        onCheckedChange = { selectedFade = it },
                        enabled = action != TimecodeTagAction.NONE
                    )
                    Text(
                        text = stringResource(Res.string.timecode_fade),
                        color = if (action != TimecodeTagAction.NONE) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }

                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val start = startMs ?: return@Button
                    val end = if (hasEnd) endMs else null
                    isSaving = true
                    error = null
                    scope.launch {
                        try {
                            if (tag == null) {
                                tagStore.create(songId, type, text, start, end, action, fade)
                            } else {
                                tagStore.update(tag, type, text, start, end, action, fade)
                            }
                            onDismissRequest()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = getString(Res.string.timecode_save_failed, e.message ?: e::class.simpleName ?: "")
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = canSave
            ) {
                Text(stringResource(Res.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}

@Composable
private fun TimeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    errorText: String,
    onUseCurrent: () -> Unit
) {
    InternalTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = if (isError) {
            { Text(errorText) }
        } else null,
        trailingIcon = {
            IconButton(onClick = onUseCurrent) {
                Icon(
                    SynaraIcons.TimecodeMarker.get(),
                    contentDescription = stringResource(Res.string.timecode_use_current_position),
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
