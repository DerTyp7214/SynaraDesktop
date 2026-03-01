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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import dev.dertyp.data.Album
import dev.dertyp.synara.ui.components.menus.AlbumContextMenu

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumItem(
    album: Album,
    modifier: Modifier = Modifier,
    subText: String? = null,
    onClick: () -> Unit,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor by animateColorAsState(
        if (isHovered) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        } else {
            Color.Transparent
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
            .pointerInput(album.id) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { showContextMenu = true },
                )
            },
        color = backgroundColor,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Box {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = rememberImageRequest(album.coverId, size = 64.dp),
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val artists = album.artists.joinArtists()
                    val secondaryText = buildString {
                        append(artists)
                        if (artists.isNotEmpty() && !subText.isNullOrEmpty()) {
                            append(" • ")
                        }
                        if (!subText.isNullOrEmpty()) {
                            append(subText)
                        }
                    }

                    if (secondaryText.isNotEmpty()) {
                        Text(
                            text = secondaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            AlbumContextMenu(
                album = album,
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false }
            )
        }
    }
}
