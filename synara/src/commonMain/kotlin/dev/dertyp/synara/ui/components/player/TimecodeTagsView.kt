package dev.dertyp.synara.ui.components.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TimecodeTagAction
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.data.UserSong
import dev.dertyp.synara.player.TimecodeTagStore
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.dialogs.TimecodeTagEditDialog
import dev.dertyp.synara.ui.components.formatDuration
import dev.dertyp.synara.ui.models.SnackbarManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

private const val DEFAULT_CHAPTER_MS = 30_000L

fun TimecodeTagType.icon(): SynaraIcons = when (this) {
    TimecodeTagType.CHAPTER -> SynaraIcons.TimecodeChapter
    TimecodeTagType.MARKER -> SynaraIcons.TimecodeMarker
    TimecodeTagType.NOTE -> SynaraIcons.TimecodeNote
}

fun TimecodeTagType.label(): StringResource = when (this) {
    TimecodeTagType.CHAPTER -> Res.string.timecode_type_chapter
    TimecodeTagType.MARKER -> Res.string.timecode_type_marker
    TimecodeTagType.NOTE -> Res.string.timecode_type_note
}

fun TimecodeTagAction.label(): StringResource = when (this) {
    TimecodeTagAction.NONE -> Res.string.timecode_action_none
    TimecodeTagAction.PLAY_ONLY -> Res.string.timecode_action_play_only
    TimecodeTagAction.SKIP -> Res.string.timecode_action_skip
    TimecodeTagAction.SKIP_TO -> Res.string.timecode_action_skip_to
    TimecodeTagAction.PLAY_UNTIL -> Res.string.timecode_action_play_until
}

@Composable
fun TimecodeTagAction.color(): Color = when (this) {
    TimecodeTagAction.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    TimecodeTagAction.PLAY_ONLY, TimecodeTagAction.SKIP_TO -> MaterialTheme.colorScheme.primary
    TimecodeTagAction.SKIP, TimecodeTagAction.PLAY_UNTIL -> MaterialTheme.colorScheme.error
}

fun TimecodeTag.formatRange(): String {
    val end = endMs
    return if (end != null) "${formatDuration(timestampMs)} – ${formatDuration(end)}" else formatDuration(timestampMs)
}

fun formatTimecode(ms: Long): String {
    val base = formatDuration(ms)
    val millis = ms % 1000
    return if (millis == 0L) base else "$base.${millis.toString().padStart(3, '0')}"
}

fun parseTimecode(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val secondsPart = trimmed.substringAfterLast(':')
    val prefix = trimmed.substringBeforeLast(':', "")
    val units = if (prefix.isEmpty()) emptyList() else prefix.split(':')
    if (units.size > 2) return null
    val seconds = secondsPart.toDoubleOrNull() ?: return null
    if (seconds < 0 || (units.isNotEmpty() && seconds >= 60)) return null
    var total = 0L
    for (unit in units) {
        val value = unit.toLongOrNull() ?: return null
        if (value < 0) return null
        total = total * 60 + value
    }
    return total * 60_000 + (seconds * 1000).toLong()
}

@Composable
fun rememberTimecodeTags(song: UserSong?, tagStore: TimecodeTagStore = koinInject()): State<List<TimecodeTag>> {
    val songId = song?.id
    val flow = remember(songId) {
        if (song != null) tagStore.observe(song.id, song.playbackTags) else MutableStateFlow(emptyList<TimecodeTag>())
    }
    return flow.collectAsState()
}

private data class TagEditRequest(
    val tag: TimecodeTag?,
    val type: TimecodeTagType,
    val startMs: Long,
    val endMs: Long?
)

@Composable
fun TimecodeTagsView(
    song: UserSong?,
    position: StateFlow<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    tagStore: TimecodeTagStore = koinInject(),
    snackbarManager: SnackbarManager = koinInject()
) {
    val songId = song?.id
    val tags by rememberTimecodeTags(song, tagStore)
    val currentPosition by position.collectAsState()
    val scope = rememberCoroutineScope()
    var editRequest by remember(songId) { mutableStateOf<TagEditRequest?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(vertical = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(Res.string.timecode_tags),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    val pos = position.value
                    editRequest = TagEditRequest(null, TimecodeTagType.MARKER, pos, null)
                },
                enabled = songId != null
            ) {
                Icon(SynaraIcons.TimecodeMarker.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.timecode_add_marker_here))
            }
            FilledTonalButton(
                onClick = {
                    val pos = position.value
                    val end = (pos + DEFAULT_CHAPTER_MS).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
                    editRequest = TagEditRequest(null, TimecodeTagType.CHAPTER, pos, end.takeIf { it > pos })
                },
                enabled = songId != null
            ) {
                Icon(SynaraIcons.TimecodeChapter.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.timecode_add_chapter_here))
            }
        }

        if (tags.isEmpty()) {
            Text(
                text = stringResource(Res.string.timecode_tags_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(tags, key = { it.id.toString() }) { tag ->
                    val end = tag.endMs
                    val isCurrent = if (end != null) currentPosition in tag.timestampMs until end else false
                    TimecodeTagRow(
                        tag = tag,
                        isCurrent = isCurrent,
                        onClick = { onSeek(tag.timestampMs) },
                        onEdit = { editRequest = TagEditRequest(tag, tag.type, tag.timestampMs, tag.endMs) },
                        onDelete = {
                            scope.launch {
                                val deleted = try {
                                    tagStore.delete(tag)
                                } catch (_: Exception) {
                                    false
                                }
                                if (!deleted) snackbarManager.showSnackbar(getString(Res.string.timecode_delete_failed))
                            }
                        }
                    )
                }
            }
        }
    }

    val request = editRequest
    if (songId != null && request != null) {
        TimecodeTagEditDialog(
            isOpen = true,
            songId = songId,
            tag = request.tag,
            initialType = request.type,
            initialStartMs = request.startMs,
            initialEndMs = request.endMs,
            durationMs = durationMs,
            position = position,
            onDismissRequest = { editRequest = null }
        )
    }
}

@Composable
private fun TimecodeTagRow(
    tag: TimecodeTag,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val contentColor = if (isCurrent) MaterialTheme.colorScheme.primary else LocalContentColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            tag.type.icon().get(),
            contentDescription = stringResource(tag.type.label()),
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tag.text.ifBlank { stringResource(tag.type.label()) },
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = tag.formatRange(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (tag.action != TimecodeTagAction.NONE) {
            val actionColor = tag.action.color()
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = actionColor.copy(alpha = 0.15f),
                contentColor = actionColor
            ) {
                Text(
                    text = stringResource(tag.action.label()) + if (tag.fade) " · " + stringResource(Res.string.timecode_fade_short) else "",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(SynaraIcons.Edit.get(), contentDescription = stringResource(Res.string.timecode_tag_edit), modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(SynaraIcons.Delete.get(), contentDescription = stringResource(Res.string.delete), modifier = Modifier.size(20.dp))
        }
    }
}
