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
import dev.dertyp.data.UserPlaylist
import dev.dertyp.synara.ui.components.menus.PlaylistContextMenu

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistItem(
    playlist: UserPlaylist,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
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
            .pointerInput(playlist.id) {
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
                    AsyncImage(
                        model = rememberImageRequest(playlist.imageId, size = 64.dp),
                        contentDescription = null,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    AsyncImage(
                        model = rememberImageRequest(playlist.imageId, size = 136.dp),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            PlaylistContextMenu(
                playlist = playlist,
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false }
            )
        }
    }
}
