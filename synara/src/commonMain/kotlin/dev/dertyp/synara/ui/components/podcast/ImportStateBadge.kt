package dev.dertyp.synara.ui.components.podcast

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dertyp.data.PodcastImportState
import dev.dertyp.synara.ui.SynaraIcons
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

@Composable
fun ImportStateBadge(state: PodcastImportState, modifier: Modifier = Modifier) {
    val (label, icon) = when (state) {
        PodcastImportState.NONE -> return
        PodcastImportState.QUEUED -> Res.string.podcast_import_state_queued to SynaraIcons.Pending
        PodcastImportState.IMPORTING -> Res.string.podcast_import_state_importing to SynaraIcons.SyncCircle
        PodcastImportState.IMPORTED -> Res.string.podcast_import_state_imported to SynaraIcons.ServerStorage
        PodcastImportState.FAILED -> Res.string.podcast_import_state_failed to SynaraIcons.ErrorCircle
    }
    val (container, content) = when (state) {
        PodcastImportState.FAILED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        PodcastImportState.IMPORTED -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = container,
        contentColor = content
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (state == PodcastImportState.IMPORTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = content
                )
            } else {
                Icon(icon.get(), contentDescription = null, modifier = Modifier.size(12.dp))
            }
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}
