package dev.dertyp.synara.ui.components.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dertyp.core.cleanTitle
import dev.dertyp.data.UserSong
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.ArtistsText
import dev.dertyp.synara.ui.components.SynaraImage
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SongInfoSection(
    currentSong: UserSong?,
    liveBitRate: Long,
    liveSampleRate: Int,
    liveBitsPerSample: Int,
    onToggleExpanded: () -> Unit,
    onArtistClick: () -> Unit,
    onLikeClick: () -> Unit,
    onSecondaryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedContent(
            targetState = currentSong?.coverId,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            label = "smallCoverTransition"
        ) { coverId ->
            SynaraImage(
                imageId = coverId,
                size = 56.dp,
                onClick = onToggleExpanded,
                fallbackIcon = SynaraIcons.Songs
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        AnimatedContent(
            targetState = currentSong,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            label = "smallSongInfoTransition",
            modifier = Modifier.weight(1f, fill = false)
        ) { song ->
            Column {
                Text(
                    text = song?.title?.cleanTitle()
                        ?: stringResource(Res.string.not_playing),
                    modifier = Modifier
                        .pointerInput(song?.id) {
                            detectTapGestures(
                                onLongPress = { if (song != null) onSecondaryClick() },
                            )
                        }
                        .onPointerEvent(PointerEventType.Release) {
                            if (it.button == PointerButton.Secondary && song != null) {
                                onSecondaryClick()
                            }
                        }
                        .pointerHoverIcon(if (song != null) PointerIcon.Hand else PointerIcon.Default),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (song != null) {
                    ArtistsText(
                        artists = song.artists,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onArtistClick = onArtistClick
                    )
                }

                song?.let { s ->
                    val bitRate = if (liveBitRate > 0) liveBitRate else s.bitRate
                    val sampleRate = if (liveSampleRate > 0) liveSampleRate.toLong() else s.sampleRate.toLong()
                    val bits = if (liveBitsPerSample > 0) liveBitsPerSample else s.bitsPerSample

                    if (bitRate > 0 || sampleRate > 0 || s.musicBrainzId != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.musicBrainzId != null) {
                                Icon(
                                    painter = painterResource(Res.drawable.musicbrainz),
                                    contentDescription = stringResource(Res.string.tag_has_musicbrainz_id),
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .size(10.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                )
                            }

                            if (bitRate > 0 || sampleRate > 0) {
                                Text(
                                    text = buildString {
                                        if (bitRate > 0) append("$bitRate kbps")
                                        if (bitRate > 0 && (bits > 0 || sampleRate > 0)) append(" • ")
                                        if (bits > 0) append("$bits bit")
                                        if (bits > 0 && sampleRate > 0) append(" • ")
                                        if (sampleRate > 0) {
                                            if (sampleRate > 1000) append("${sampleRate / 1000.0} kHz")
                                            else append("$sampleRate kHz")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        if (currentSong != null) {
            IconButton(
                onClick = onLikeClick,
                modifier = Modifier.offset(y = (-8).dp)
            ) {
                Icon(
                    if (currentSong.isFavourite == true) SynaraIcons.IsFavorite.get() else SynaraIcons.IsNotFavorite.get(),
                    contentDescription = stringResource(Res.string.favorite),
                    tint = if (currentSong.isFavourite == true) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
        }
    }
}
