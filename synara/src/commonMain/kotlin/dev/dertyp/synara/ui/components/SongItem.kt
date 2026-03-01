package dev.dertyp.synara.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.onClick
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.dertyp.core.joinArtists
import dev.dertyp.data.UserSong
import dev.dertyp.synara.onSurfaceVariantDistinct
import dev.dertyp.synara.player.CacheUpdate
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.SongCache
import kotlinx.coroutines.flow.filterIsInstance
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.favorite
import synara.synara.generated.resources.more_options

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItem(
    song: UserSong,
    modifier: Modifier = Modifier,
    index: Int? = null,
    isCurrent: Boolean = false,
    showCover: Boolean = false,
    showLike: Boolean = true,
    onClick: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onMoreOptions: (() -> Unit)? = null,
    playerModel: PlayerModel = koinInject(),
    songCache: SongCache = koinInject(),
    onToggleLike: () -> Unit = { playerModel.toggleLike(song) },
    isInQueue: Boolean = false,
    isInPlaylist: Boolean = false,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    var currentSongState by remember(song.id) { mutableStateOf(song) }
    var showContextMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    LaunchedEffect(song.id) {
        songCache.updates
            .filterIsInstance<CacheUpdate.SongUpdated>()
            .collect { update ->
                if (update.song.id == song.id) {
                    currentSongState = update.song
                }
            }
    }

    val baseBgColor = if (isCurrent) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
    } else {
        Color.Transparent
    }

    val backgroundColor by animateColorAsState(
        if (isHovered) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        } else {
            baseBgColor
        }
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .hoverable(interactionSource)
            .onClick(
                matcher = PointerMatcher.mouse(PointerButton.Secondary),
                onClick = { showContextMenu = true }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(song.id) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { showContextMenu = true },
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
                if (index != null) {
                    Text(
                        text = (index + 1).toString(),
                        modifier = Modifier.width(32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (showCover) {
                    AsyncImage(
                        model = rememberImageRequest(currentSongState.coverId, size = 40.dp),
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(MaterialTheme.shapes.extraSmall),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentSongState.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) MaterialTheme.colorScheme.onSurfaceVariantDistinct()
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentSongState.artists.joinArtists(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = formatDuration(currentSongState.duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                if (trailingContent != null) {
                    trailingContent()
                } else {
                    if (showLike) {
                        IconButton(onClick = onToggleLike) {
                            Icon(
                                if (currentSongState.isFavourite == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = stringResource(Res.string.favorite),
                                modifier = Modifier.size(20.dp),
                                tint = if (currentSongState.isFavourite == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (onPlayNext != null) {
                        IconButton(onClick = onPlayNext) {
                            Icon(
                                Icons.AutoMirrored.Rounded.PlaylistPlay,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (onMoreOptions != null) {
                        IconButton(onClick = { showContextMenu = true }) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = stringResource(Res.string.more_options),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            SongContextMenu(
                song = currentSongState,
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false },
                playerModel = playerModel,
                isInQueue = isInQueue,
                isInPlaylist = isInPlaylist,
                onRemoveFromPlaylist = onRemoveFromPlaylist,
                onRemoveFromQueue = onRemoveFromQueue
            )
        }
    }
}

fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    return if (durationMs >= 3600000) {
        val hours = durationMs / 3600000
        "${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes}:${seconds.toString().padStart(2, '0')}"
    }
}
