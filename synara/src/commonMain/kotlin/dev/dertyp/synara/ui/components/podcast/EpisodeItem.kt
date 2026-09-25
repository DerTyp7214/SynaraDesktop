package dev.dertyp.synara.ui.components.podcast

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.onClick
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeType
import dev.dertyp.formatDate
import dev.dertyp.platformDateFromEpochMilliseconds
import dev.dertyp.synara.formatCompactDuration
import dev.dertyp.synara.onSurfaceVariantDistinct
import dev.dertyp.synara.podcast.*
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.components.menus.EpisodeContextMenu
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeItem(
    episode: PodcastEpisode,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    showShowTitle: Boolean = false,
    showArtwork: Boolean = true,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
    onGoToShow: (() -> Unit)? = null,
    showGoToShow: Boolean = true,
    onEpisodeChanged: (PodcastEpisode) -> Unit = {},
    showContextMenu: Boolean = true,
    importBadge: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val baseBgColor = if (isCurrent) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
    } else {
        Color.Transparent
    }

    val backgroundColor by animateColorAsState(
        if (isHovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f) else baseBgColor
    )

    val played = episode.isPlayed
    val remaining = episode.remainingMs
    val date = remember(episode.publishedAt) { platformDateFromEpochMilliseconds(episode.publishedAt).formatDate() }
    val durationText = when {
        episode.isInProgress && remaining != null ->
            stringResource(Res.string.podcast_time_left, remaining.formatCompactDuration(includeSeconds = false))
        else -> episode.knownDurationMs?.formatCompactDuration(includeSeconds = false)
    }
    val subtitle = if (durationText != null) stringResource(Res.string.podcast_date_duration, date, durationText) else date

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .hoverable(interactionSource)
            .onClick(
                enabled = showContextMenu,
                matcher = PointerMatcher.mouse(PointerButton.Secondary),
                onClick = { menuOpen = true }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(episode.id, showContextMenu) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { if (showContextMenu) menuOpen = true },
                )
            },
        color = backgroundColor,
        contentColor = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    ) {
        Box {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showArtwork) {
                    SynaraImage(
                        imageId = episode.artworkId,
                        size = 48.dp,
                        shape = MaterialTheme.shapes.extraSmall,
                        fallbackIcon = SynaraIcons.Podcast
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    if (showShowTitle) {
                        Text(
                            text = episode.showTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isCurrent && isPlaying) {
                            Icon(
                                SynaraIcons.VolumeHigh.get(),
                                contentDescription = stringResource(Res.string.podcast_now_playing),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = episode.title,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                isCurrent -> MaterialTheme.colorScheme.onSurfaceVariantDistinct()
                                played -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        EpisodeTypeChip(episode.episodeType)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (played) {
                            Icon(
                                SynaraIcons.MarkPlayed.get(),
                                contentDescription = stringResource(Res.string.podcast_played),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        importBadge?.invoke()
                    }

                    if (episode.isInProgress) {
                        LinearProgressIndicator(
                            progress = { episode.progressFraction },
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .widthIn(max = 160.dp)
                                .fillMaxWidth()
                                .height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {}
                        )
                    }
                }

                if (trailingContent != null) {
                    trailingContent()
                }

                if (onPlay != null) {
                    IconButton(onClick = onPlay) {
                        Icon(
                            if (isCurrent && isPlaying) SynaraIcons.Pause.get() else SynaraIcons.Play.get(),
                            contentDescription = stringResource(
                                if (episode.isInProgress) Res.string.podcast_resume else Res.string.podcast_play
                            ),
                            modifier = Modifier.size(22.dp),
                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (showContextMenu) {
                EpisodeContextMenu(
                    episode = episode,
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    onGoToShow = onGoToShow,
                    showGoToShow = showGoToShow,
                    onEpisodeChanged = onEpisodeChanged
                )
            }
        }
    }
}

@Composable
private fun EpisodeTypeChip(type: PodcastEpisodeType) {
    val label = when (type) {
        PodcastEpisodeType.TRAILER -> Res.string.podcast_episode_trailer
        PodcastEpisodeType.BONUS -> Res.string.podcast_episode_bonus
        PodcastEpisodeType.FULL -> return
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            maxLines = 1
        )
    }
}
