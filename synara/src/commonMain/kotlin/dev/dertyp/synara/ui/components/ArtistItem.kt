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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dertyp.data.Artist
import dev.dertyp.synara.ui.components.menus.ArtistContextMenu

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistItem(
    artist: Artist,
    modifier: Modifier = Modifier,
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
            .pointerInput(artist.id) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { showContextMenu = true },
                )
            },
        color = backgroundColor,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Box {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SynaraImage(
                    imageId = artist.imageId,
                    size = 120.dp,
                    shape = CircleShape,
                    fallbackIcon = Icons.Rounded.Person
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            ArtistContextMenu(
                artist = artist,
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false }
            )
        }
    }
}
