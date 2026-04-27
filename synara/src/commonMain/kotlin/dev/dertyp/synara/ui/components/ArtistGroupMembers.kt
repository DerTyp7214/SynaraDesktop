package dev.dertyp.synara.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import dev.dertyp.data.Artist
import dev.dertyp.synara.screens.ArtistScreen
import dev.dertyp.synara.ui.SynaraIcons
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.artist_group_members

@Composable
fun ArtistGroupMembers(
    artist: Artist,
    navigator: Navigator?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "rotation")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = expanded,
                    label = "headerContent",
                    transitionSpec = {
                        fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                    }
                ) { isExpanded ->
                    if (!isExpanded) {
                        Row(
                            modifier = Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
                            horizontalArrangement = Arrangement.spacedBy((-12).dp)
                        ) {
                            artist.artists.take(5).forEach { member ->
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .drawWithContent {
                                            drawCircle(
                                                color = Color.Black,
                                                radius = size.minDimension / 2,
                                                blendMode = BlendMode.Clear
                                            )
                                            drawContent()
                                        }
                                        .padding(2.dp)
                                ) {
                                    SynaraImage(
                                        imageId = member.imageId,
                                        size = 32.dp,
                                        shape = CircleShape,
                                        fallbackIcon = SynaraIcons.Artists
                                    )
                                }
                            }
                            if (artist.artists.size > 5) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .drawWithContent {
                                            drawCircle(
                                                color = Color.Black,
                                                radius = size.minDimension / 2,
                                                blendMode = BlendMode.Clear
                                            )
                                            drawContent()
                                        }
                                        .padding(2.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+${artist.artists.size - 5}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(Res.string.artist_group_members),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Icon(
                SynaraIcons.ChevronRight.get(),
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = rotation },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                artist.artists.forEach { member ->
                    ArtistListItem(
                        artist = member,
                        onClick = {
                            navigator?.push(ArtistScreen(member.id))
                        }
                    )
                }
            }
        }
    }
}
