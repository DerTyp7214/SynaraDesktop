package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.onSurfaceVariantDistinct
import dev.dertyp.synara.scrobble.BaseScrobbler
import dev.dertyp.synara.scrobble.ScrobblerService
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.formatDuration
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.scrobble_providers
import kotlin.time.Duration

@Composable
fun ScrobbleInfoDialog(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    durationLeft: Duration,
    isScrobbled: Boolean,
    scrobblerService: ScrobblerService = koinInject()
) {
    SynaraDialog(
        isOpen = isOpen,
        onDismissRequest = onDismissRequest
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 400.dp)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(bottom = 16.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = stringResource(Res.string.scrobble_providers),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    AnimatedVisibility(
                        visible = !isScrobbled,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Text(
                            text = formatDuration(durationLeft.inWholeMilliseconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                scrobblerService
                    .registeredScrobblers()
                    .filter { it.showInDialog }
                    .sortedBy { it.sortOrder }
                    .forEach { scrobbler ->
                        ScrobblerStatusRow(
                            scrobbler = scrobbler,
                            isScrobbled = isScrobbled
                        )
                    }
            }
        }
    }
}

@Composable
fun ScrobblerStatusRow(
    scrobbler: BaseScrobbler,
    isScrobbled: Boolean
) {
    val status by scrobbler.status.collectAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = scrobbler.icon.get(),
            contentDescription = null,
            tint = if (scrobbler.tintIcon) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else Color.Unspecified,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = stringResource(scrobbler.name),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        AnimatedContent(targetState = status, label = "scrobbleStatus") { currentStatus ->
            val icon = when {
                !scrobbler.isRunning -> SynaraIcons.Circle.get()
                currentStatus == BaseScrobbler.ScrobbleStatus.SCROBBLED -> SynaraIcons.CheckCircle.get()
                currentStatus == BaseScrobbler.ScrobbleStatus.QUEUED -> SynaraIcons.Expiration.get()
                currentStatus == BaseScrobbler.ScrobbleStatus.FAILED -> SynaraIcons.ErrorCircle.get()
                isScrobbled -> SynaraIcons.SyncCircle.get()
                else -> SynaraIcons.Circle.get()
            }
            val iconTint = when {
                currentStatus == BaseScrobbler.ScrobbleStatus.SCROBBLED && scrobbler.isRunning -> MaterialTheme.colorScheme.onSurfaceVariantDistinct()
                currentStatus == BaseScrobbler.ScrobbleStatus.QUEUED -> MaterialTheme.colorScheme.secondary
                currentStatus == BaseScrobbler.ScrobbleStatus.FAILED -> MaterialTheme.colorScheme.error
                isScrobbled && scrobbler.isRunning -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            }
            Icon(
                imageVector = icon,
                tint = iconTint,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
