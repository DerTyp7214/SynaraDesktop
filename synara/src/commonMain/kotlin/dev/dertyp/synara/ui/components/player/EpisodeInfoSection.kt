package dev.dertyp.synara.ui.components.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.synara.podcast.artworkId
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraImage
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.not_playing

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EpisodeInfoSection(
    episode: PodcastEpisode?,
    onToggleExpanded: () -> Unit,
    onShowClick: (PodcastEpisode) -> Unit,
    onSecondaryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedContent(
            targetState = episode?.artworkId,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            label = "smallEpisodeArtworkTransition"
        ) { artworkId ->
            SynaraImage(
                imageId = artworkId,
                size = 56.dp,
                onClick = onToggleExpanded,
                fallbackIcon = SynaraIcons.Podcast
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        AnimatedContent(
            targetState = episode,
            contentKey = { it?.id },
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            label = "smallEpisodeInfoTransition",
            modifier = Modifier.weight(1f, fill = false)
        ) { current ->
            Column {
                Text(
                    text = current?.title
                        ?: stringResource(Res.string.not_playing),
                    modifier = Modifier
                        .pointerInput(current?.id) {
                            detectTapGestures(
                                onLongPress = { if (current != null) onSecondaryClick() },
                            )
                        }
                        .onPointerEvent(PointerEventType.Release) {
                            if (it.button == PointerButton.Secondary && current != null) {
                                onSecondaryClick()
                            }
                        }
                        .pointerHoverIcon(if (current != null) PointerIcon.Hand else PointerIcon.Default),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (current != null) {
                    Text(
                        text = current.showTitle,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onShowClick(current) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
