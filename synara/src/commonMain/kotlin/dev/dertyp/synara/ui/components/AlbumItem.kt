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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dertyp.data.Album
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.menus.AlbumContextMenu

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumItem(
    album: Album,
    modifier: Modifier = Modifier,
    subText: String? = null,
    horizontal: Boolean = true,
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
            if (horizontal) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SynaraImage(
                        imageId = album.coverId,
                        size = 64.dp,
                        fallbackIcon = SynaraIcons.Albums
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

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!subText.isNullOrEmpty()) {
                                Text(
                                    text = "$subText • ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            ArtistsText(
                                artists = album.artists,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    SynaraImage(
                        imageId = album.coverId,
                        size = 180.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        fallbackIcon = SynaraIcons.Albums
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    ArtistsText(
                        artists = album.artists,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
