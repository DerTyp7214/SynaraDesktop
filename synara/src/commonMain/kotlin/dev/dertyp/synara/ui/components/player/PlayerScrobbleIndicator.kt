package dev.dertyp.synara.ui.components.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dertyp.data.UserSong
import dev.dertyp.synara.scrobble.BaseScrobbler
import dev.dertyp.synara.scrobble.ScrobblerService
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.dialogs.ScrobbleInfoDialog
import dev.dertyp.synara.ui.components.formatDuration
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.pending_scrobble
import synara.synara.generated.resources.song_scrobbled
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private enum class ScrobbleOverallState {
    PENDING,
    FAILED,
    QUEUED,
    SCROBBLED,
    SYNCING
}

@Composable
fun PlayerScrobbleIndicator(
    currentSong: UserSong,
    scrobbledFor: Int,
    triggeredSong: UserSong?,
    scrobblerService: ScrobblerService,
    modifier: Modifier = Modifier
) {
    var showScrobbleInfo by remember { mutableStateOf(false) }
    
    val durationLeft = BaseScrobbler.requiredDuration(
        scrobbledFor.seconds,
        currentSong.duration.milliseconds
    )
    val isTimeReached = durationLeft.inWholeSeconds <= 0
    val scrobbled = isTimeReached || triggeredSong?.id == currentSong.id

    val scrobblers = scrobblerService.registeredScrobblers().filter { it.showInDialog }
    val statuses = scrobblers.map { it.status.collectAsState().value }

    val overallState = remember(scrobbled, statuses) {
        when {
            !scrobbled -> ScrobbleOverallState.PENDING
            statuses.any { it == BaseScrobbler.ScrobbleStatus.FAILED } -> ScrobbleOverallState.FAILED
            statuses.any { it == BaseScrobbler.ScrobbleStatus.QUEUED } -> ScrobbleOverallState.QUEUED
            statuses.all { it == BaseScrobbler.ScrobbleStatus.SCROBBLED } -> ScrobbleOverallState.SCROBBLED
            else -> ScrobbleOverallState.SYNCING
        }
    }

    Box(
        modifier = modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { showScrobbleInfo = true }
    ) {
        AnimatedContent(
            targetState = overallState,
            label = "scrobbleIndicator"
        ) { state ->
            when (state) {
                ScrobbleOverallState.SCROBBLED -> {
                    Icon(
                        SynaraIcons.CheckCircle.get(),
                        contentDescription = stringResource(Res.string.song_scrobbled),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                ScrobbleOverallState.FAILED -> {
                    Icon(
                        SynaraIcons.ErrorCircle.get(),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                ScrobbleOverallState.QUEUED -> {
                    Icon(
                        SynaraIcons.Expiration.get(),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }

                ScrobbleOverallState.SYNCING -> {
                    Icon(
                        SynaraIcons.SyncCircle.get(),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(
                            alpha = 0.7f
                        )
                    )
                }

                ScrobbleOverallState.PENDING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            modifier = Modifier.padding(top = 2.dp),
                            text = formatDuration(durationLeft.inWholeMilliseconds),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.6f
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            SynaraIcons.Circle.get(),
                            contentDescription = stringResource(
                                Res.string.pending_scrobble
                            ),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.6f
                            )
                        )
                    }
                }
            }
        }
    }

    ScrobbleInfoDialog(
        isOpen = showScrobbleInfo,
        onDismissRequest = { showScrobbleInfo = false },
        durationLeft = durationLeft,
        isScrobbled = scrobbled
    )
}
