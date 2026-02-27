package dev.dertyp.synara.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.dertyp.data.RepeatMode
import dev.dertyp.synara.player.PlayerModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

@Composable
fun PlayerBar(
    modifier: Modifier = Modifier,
    playerModel: PlayerModel = koinInject()
) {
    val isPlaying by playerModel.isPlaying.collectAsState()
    val currentSong by playerModel.currentSong.collectAsState()
    val volume by playerModel.volume.collectAsState()
    val currentPosition by playerModel.currentPosition.collectAsState()
    val duration by playerModel.duration.collectAsState()
    val shuffleMode by playerModel.shuffleMode.collectAsState()
    val repeatMode by playerModel.repeatMode.collectAsState()

    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableLongStateOf(0L) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column {
            val position = if (isSeeking) seekPosition else currentPosition

            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = {
                    isSeeking = true
                    seekPosition = (it * duration).toLong()
                },
                onValueChangeFinished = {
                    playerModel.seekTo(seekPosition)
                    isSeeking = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .padding(horizontal = 0.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                enabled = currentSong != null
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Song Info
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        currentSong?.let { song ->
                            val imageRequest = rememberImageRequest(song.coverId, size = 56.dp)
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } ?: Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.Center),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = currentSong?.title ?: stringResource(Res.string.not_playing),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = currentSong?.artists?.joinToString(", ") { it.name } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (currentSong != null) {
                        IconButton(onClick = { /* TODO: Like song */ }) {
                            Icon(
                                if (currentSong?.isFavourite == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = stringResource(Res.string.favorite),
                                tint = if (currentSong?.isFavourite == true) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                    }
                }

                // Controls
                Column(
                    modifier = Modifier.weight(1.2f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(
                            onClick = { playerModel.skipPrevious() },
                            enabled = currentSong != null
                        ) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(Res.string.previous))
                        }

                        LargeFloatingActionButton(
                            onClick = { playerModel.togglePlayPause() },
                            modifier = Modifier.size(56.dp),
                            shape = MaterialTheme.shapes.medium,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) stringResource(Res.string.pause) else stringResource(Res.string.play),
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        IconButton(
                            onClick = { playerModel.skipNext() },
                            enabled = currentSong != null
                        ) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(Res.string.next_song))
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatDuration(position),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            formatDuration(duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Volume and other actions
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { playerModel.toggleShuffle() },
                        enabled = currentSong != null
                    ) {
                        Icon(
                            Icons.Rounded.Shuffle,
                            contentDescription = stringResource(Res.string.shuffle),
                            tint = if (shuffleMode) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(
                        onClick = { playerModel.toggleRepeat() },
                        enabled = currentSong != null
                    ) {
                        Icon(
                            when (repeatMode) {
                                RepeatMode.ONE -> Icons.Rounded.RepeatOne
                                else -> Icons.Rounded.Repeat
                            },
                            contentDescription = stringResource(Res.string.repeat),
                            tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Icon(
                        Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = stringResource(Res.string.volume),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Slider(
                        value = volume,
                        onValueChange = { playerModel.setVolume(it) },
                        modifier = Modifier.width(100.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }
    }
}