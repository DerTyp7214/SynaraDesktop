package dev.dertyp.synara.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.data.ReleaseType
import dev.dertyp.formatDate
import dev.dertyp.services.IReleaseService
import dev.dertyp.services.models.RecentRelease
import dev.dertyp.services.tdn.IDownloadService
import dev.dertyp.services.tdn.Type
import dev.dertyp.synara.Config
import dev.dertyp.synara.screens.ArtistScreen
import dev.dertyp.synara.screens.TidalDownloadScreen
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.dialogs.SynaraDialog
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

@Composable
fun RecentReleasesView(
    modifier: Modifier = Modifier,
    releaseService: IReleaseService = koinInject(),
    downloadService: IDownloadService = koinInject()
) {
    var releases by remember { mutableStateOf(emptyList<RecentRelease>()) }
    var selectedRelease by remember { mutableStateOf<RecentRelease?>(null) }
    var isExpanded by remember { mutableStateOf(false) }

    val lastSeenRecentReleaseId by Config.lastSeenRecentReleaseId.collectAsState()

    LaunchedEffect(Unit) {
        releases = releaseService.getRecentReleases(0, 150).data
    }

    LaunchedEffect(isExpanded, releases) {
        if (isExpanded && releases.isNotEmpty() && releases.first().releaseId != lastSeenRecentReleaseId) {
            Config.setLastSeenRecentReleaseId(releases.first().releaseId)
        }
    }

    val hasNewReleases = remember(releases, lastSeenRecentReleaseId) {
        releases.isNotEmpty() && releases.first().releaseId != lastSeenRecentReleaseId
    }

    val groupedReleases = remember(releases) {
        releases.groupBy { it.type }.toSortedMap(compareBy { it.ordinal })
    }

    if (releases.isNotEmpty()) {
        Column(modifier = modifier) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.recent_releases),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )

                if (hasNewReleases && !isExpanded) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                val rotation by animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    label = "rotation"
                )

                Icon(
                    imageVector = SynaraIcons.ChevronDown.get(),
                    contentDescription = if (isExpanded) stringResource(Res.string.show_less) else stringResource(Res.string.show_more),
                    modifier = Modifier.rotate(rotation)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    groupedReleases.forEach { (type, typeReleases) ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(type.toResResource()),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(typeReleases, key = { it.releaseId }) { release ->
                                RecentReleaseCard(
                                    release = release,
                                    onClick = { selectedRelease = release },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = tween(durationMillis = 500),
                                        placementSpec = spring(stiffness = Spring.StiffnessLow),
                                        fadeOutSpec = tween(durationMillis = 300)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selectedRelease?.let { release ->
        RecentReleaseDialog(
            release = release,
            downloadService = downloadService,
            onDismissRequest = { selectedRelease = null }
        )
    }
}

@Composable
fun RecentReleaseCard(
    release: RecentRelease,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .width(140.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            )
            .padding(12.dp)
    ) {
        SynaraImage(
            imageId = release.imageId,
            size = 116.dp,
            shape = MaterialTheme.shapes.small,
            fallbackIcon = SynaraIcons.Albums
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = release.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = release.artistName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        release.releaseDate?.let { date ->
            Text(
                text = date.formatDate(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun RecentReleaseDialog(
    release: RecentRelease,
    downloadService: IDownloadService,
    onDismissRequest: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.current

    SynaraDialog(
        isOpen = true,
        onDismissRequest = onDismissRequest
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.width(320.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SynaraImage(
                    imageId = release.imageId,
                    size = 160.dp,
                    shape = MaterialTheme.shapes.medium,
                    fallbackIcon = SynaraIcons.Albums
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = release.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = release.artistName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable {
                        navigator?.push(ArtistScreen(release.artistId))
                        onDismissRequest()
                    }
                )
                Text(
                    text = stringResource(release.type.toResResource()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                release.releaseDate?.let { date ->
                    Text(
                        text = date.formatDate(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                val tidalLink = remember(release.links) {
                    release.links.find { it.contains("tidal.com") }
                }

                val otherLinks = remember(release.links) {
                    release.links.filter {
                        !it.contains("musicbrainz.org", ignoreCase = true) &&
                                !it.contains("tidal.com", ignoreCase = true)
                    }
                }

                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tidalLink?.let { link ->
                        item {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val urlParts = link.split("/")
                                        val typeIndex =
                                            urlParts.indexOfFirst { it == "track" || it == "album" || it == "artist" || it == "playlist" }
                                        if (typeIndex != -1 && typeIndex + 1 < urlParts.size) {
                                            val typeStr = urlParts[typeIndex]
                                            val id = urlParts[typeIndex + 1]
                                            val type = Type.fromValue(typeStr)
                                            if (type != null) {
                                                downloadService.downloadTidalIds(listOf(id), type)
                                                onDismissRequest()
                                                navigator?.push(TidalDownloadScreen())
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(Res.string.menu_download_tidal))
                            }
                        }
                    }

                    items(otherLinks) { link ->
                        val domain = remember(link) {
                            link.split("//").last().split("/").first().removePrefix("www.")
                        }
                        Button(
                            onClick = { uriHandler.openUri(link) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Text(domain)
                        }
                    }

                    item {
                        Button(
                            onClick = {
                                val url =
                                    "https://musicbrainz.org/release-group/${release.releaseId}"
                                uriHandler.openUri(url)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(
                                SynaraIcons.MusicBrainz.get(),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.tag_has_musicbrainz_id))
                        }
                    }
                }
            }
        }
    }
}

private fun ReleaseType.toResResource() = when (this) {
    ReleaseType.Album -> Res.string.release_type_album
    ReleaseType.Single -> Res.string.release_type_single
    ReleaseType.EP -> Res.string.release_type_ep
    ReleaseType.Broadcast -> Res.string.release_type_broadcast
    ReleaseType.Other -> Res.string.release_type_other
    ReleaseType.Unknown -> Res.string.release_type_unknown
}
