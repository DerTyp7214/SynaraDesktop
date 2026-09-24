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
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.data.ReleaseSource
import dev.dertyp.data.ReleaseType
import dev.dertyp.formatDate
import dev.dertyp.services.IReleaseService
import dev.dertyp.services.IUiService
import dev.dertyp.data.UserCapability
import dev.dertyp.services.models.RecentRelease
import dev.dertyp.synara.Config
import dev.dertyp.synara.viewmodels.GlobalStateModel
import dev.dertyp.synara.screens.ArtistScreen
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.dialogs.SynaraDialog
import dev.dertyp.synara.ui.server.UiHost
import dev.dertyp.synara.ui.server.UiHostOverlays
import dev.dertyp.synara.ui.server.rememberUiHost
import dev.dertyp.ui.IntakeItem
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiHookHandler
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

@Composable
fun RecentReleasesView(
    modifier: Modifier = Modifier,
    releaseService: IReleaseService = koinInject(),
    uiService: IUiService = koinInject()
) {
    var releases by remember { mutableStateOf(emptyList<RecentRelease>()) }
    var selectedRelease by remember { mutableStateOf<RecentRelease?>(null) }
    var isExpanded by remember { mutableStateOf(false) }

    val host = rememberUiHost("recentReleases")
    val lastSeenRecentReleaseId by Config.lastSeenRecentReleaseId.collectAsState()

    LaunchedEffect(Unit) {
        releases = releaseService.getRecentReleases().data
    }

    LaunchedEffect(isExpanded, releases) {
        if (isExpanded && releases.isNotEmpty() && releases.first().releaseId.toString() != lastSeenRecentReleaseId) {
            Config.setLastSeenRecentReleaseId(releases.first().releaseId.toString())
        }
    }

    val hasNewReleases = remember(releases, lastSeenRecentReleaseId) {
        releases.isNotEmpty() && releases.first().releaseId.toString() != lastSeenRecentReleaseId
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
            uiService = uiService,
            host = host,
            onDismissRequest = { selectedRelease = null }
        )
    }

    UiHostOverlays(host)
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
        Box {
            SynaraImage(
                imageId = release.imageId,
                size = 116.dp,
                shape = MaterialTheme.shapes.small,
                fallbackIcon = SynaraIcons.Albums
            )
            if (release.versions.isNotEmpty()) {
                val versionCount = release.versions.size + 1
                val badgeDescription = stringResource(Res.string.versions_count, versionCount)
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-4).dp, y = 4.dp)
                        .semantics { contentDescription = badgeDescription }
                ) {
                    Text(
                        text = "$versionCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
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
    uiService: IUiService,
    host: UiHost,
    onDismissRequest: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
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

                val globalState = koinInject<GlobalStateModel>()
                val user by globalState.user.collectAsState()
                val canImport = user?.hasCapability(UserCapability.IMPORT) == true

                val handlerLinks by rememberReleaseLinkHandlers(release.links, uiService, canImport)

                val otherLinks = remember(release.links, handlerLinks) {
                    val handledUrls = handlerLinks.map { it.first }.toSet()
                    release.links.filter {
                        !it.contains("musicbrainz.org", ignoreCase = true) &&
                                it !in handledUrls
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
                    items(handlerLinks) { (link, handler) ->
                        Button(
                            onClick = {
                                host.dispatch(UiAction.Intake(listOf(IntakeItem.Url(link)), resolverId = handler.id))
                                onDismissRequest()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(handler.title)
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

                    if (release.versions.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(Res.string.other_versions, release.versions.size),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(release.versions, key = { it.releaseId }) { version ->
                            ReleaseVersionItem(
                                version = version,
                                uiService = uiService,
                                host = host,
                                canImport = canImport,
                                uriHandler = uriHandler,
                                onDismissRequest = onDismissRequest
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseVersionItem(
    version: RecentRelease,
    uiService: IUiService,
    host: UiHost,
    canImport: Boolean,
    uriHandler: UriHandler,
    onDismissRequest: () -> Unit
) {
    val handlerLinks by rememberReleaseLinkHandlers(version.links, uiService, canImport)
    val otherLinks = remember(version.links, handlerLinks) {
        val handledUrls = handlerLinks.map { it.first }.toSet()
        version.links.filter {
            !it.contains("musicbrainz.org", ignoreCase = true) &&
                    it !in handledUrls
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = version.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        version.releaseDate?.formatDate()?.let { date ->
            Text(
                text = date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        handlerLinks.forEach { (link, handler) ->
            FilledTonalButton(
                onClick = {
                    host.dispatch(UiAction.Intake(listOf(IntakeItem.Url(link)), resolverId = handler.id))
                    onDismissRequest()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(handler.title)
            }
        }
        otherLinks.forEach { link ->
            val domain = remember(link) {
                link.split("//").last().split("/").first().removePrefix("www.")
            }
            OutlinedButton(
                onClick = { uriHandler.openUri(link) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(SynaraIcons.OpenInNew.get(), null, Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(domain)
            }
        }
        if (version.source == ReleaseSource.MusicBrainz) {
            OutlinedButton(
                onClick = { uriHandler.openUri("https://musicbrainz.org/release-group/${version.releaseId}") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(SynaraIcons.MusicBrainz.get(), null, Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.tag_has_musicbrainz_id))
            }
        }
    }
}

@Composable
private fun rememberReleaseLinkHandlers(
    links: List<String>,
    uiService: IUiService,
    canImport: Boolean
): State<List<Pair<String, UiHookHandler>>> =
    produceState(emptyList(), links, canImport) {
        if (!canImport) {
            value = emptyList()
            return@produceState
        }
        val results = mutableListOf<Pair<String, UiHookHandler>>()
        links.forEach { link ->
            if (!link.contains("musicbrainz.org", ignoreCase = true)) {
                try {
                    val handler = uiService.resolveIntake(listOf(IntakeItem.Url(link))).firstOrNull()
                    if (handler != null) {
                        results.add(link to handler)
                    }
                } catch (_: Exception) {
                }
            }
        }
        value = results
    }

private fun ReleaseType.toResResource() = when (this) {
    ReleaseType.Album -> Res.string.release_type_album
    ReleaseType.Single -> Res.string.release_type_single
    ReleaseType.EP -> Res.string.release_type_ep
    ReleaseType.Broadcast -> Res.string.release_type_broadcast
    ReleaseType.Other -> Res.string.release_type_other
    ReleaseType.Unknown -> Res.string.release_type_unknown
}
