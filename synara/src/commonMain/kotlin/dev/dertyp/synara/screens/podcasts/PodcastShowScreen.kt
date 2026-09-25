@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dertyp.synara.screens.podcasts

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.PlatformUUID
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastShow
import dev.dertyp.data.PodcastSource
import dev.dertyp.data.UserCapability
import dev.dertyp.synara.podcast.PodcastContinuation
import dev.dertyp.synara.podcast.htmlToPlainText
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.RegisterRefreshTarget
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.components.dialogs.FullscreenImageDialog
import dev.dertyp.synara.ui.components.menus.ShowContextMenu
import dev.dertyp.synara.ui.components.menus.UnsubscribeConfirmDialog
import dev.dertyp.synara.ui.components.podcast.EpisodeDetailDialog
import dev.dertyp.synara.ui.components.podcast.EpisodeItem
import dev.dertyp.synara.ui.components.podcast.PodcastBadge
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.viewmodels.GlobalStateModel
import dev.dertyp.synara.viewmodels.podcasts.PodcastShowScreenModel
import dev.dertyp.synara.viewmodels.podcasts.PodcastShowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import synara.synara.generated.resources.*

class PodcastShowScreen(val showId: PlatformUUID) : Screen {

    override val key: ScreenKey = "PodcastShowScreen_$showId"

    @Composable
    override fun Content() {
        val screenModel = getScreenModel<PodcastShowScreenModel> { parametersOf(showId) }
        RegisterRefreshTarget(screenModel)
        val navigator = LocalNavigator.current
        val globalState = koinInject<GlobalStateModel>()
        val snackbarManager = koinInject<SnackbarManager>()
        val scope = rememberCoroutineScope()

        val state by screenModel.state.collectAsState()
        val episodesState by screenModel.episodes.state.collectAsState()
        val currentEpisode by screenModel.podcastPlayer.currentEpisode.collectAsState()
        val isPlaying by screenModel.podcastPlayer.isPlaying.collectAsState()
        val user by globalState.user.collectAsState()
        val canEdit = user?.hasCapability(UserCapability.PODCAST_EDIT) == true

        var detailEpisode by remember { mutableStateOf<PodcastEpisode?>(null) }
        var confirmUnsubscribe by remember { mutableStateOf(false) }
        var showFullscreenImage by remember { mutableStateOf(false) }
        val lazyListState = rememberLazyListState()
        val description = remember(state.show?.description) { htmlToPlainText(state.show?.description ?: "") }

        LaunchedEffect(Unit) {
            screenModel.refreshFailed.collect {
                snackbarManager.showSnackbar(getString(Res.string.podcast_refresh_failed))
            }
        }

        LaunchedEffect(Unit) {
            screenModel.pollImports()
        }

        fun toggleSubscription(show: PodcastShow) {
            if (show.subscribed) {
                confirmUnsubscribe = true
                return
            }
            scope.launch {
                try {
                    screenModel.subscribe()
                    snackbarManager.showSnackbar(getString(Res.string.podcast_subscribed))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    snackbarManager.showSnackbar(getString(Res.string.podcast_subscribe_failed, e.message ?: ""))
                }
            }
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = {
                        Text(
                            text = state.show?.title ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(SynaraIcons.Back.get(), contentDescription = stringResource(Res.string.back))
                        }
                    },
                    actions = {
                        state.show?.let { show ->
                            Box {
                                var menuOpen by remember { mutableStateOf(false) }
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(SynaraIcons.MoreOptions.get(), contentDescription = stringResource(Res.string.more_options))
                                }
                                ShowContextMenu(
                                    show = show,
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                    showOpen = false,
                                    onShowChanged = { screenModel.onShowChanged(it) },
                                    onDeleted = { navigator?.pop() },
                                    onRefresh = { screenModel.refresh() },
                                    onPlay = { screenModel.playContinuation() }
                                )
                            }
                        }
                    }
                )
            }
        ) { padding ->
            val show = state.show
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when {
                    show == null && state.notFound -> EmptyState(
                        title = null,
                        message = stringResource(Res.string.podcast_show_not_found)
                    )
                    show == null && state.failed -> FailedState(onRetry = { screenModel.retry() })
                    show == null -> LoadingState()
                    else -> {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            item(key = "header") {
                                ShowHeader(
                                    show = show,
                                    state = state,
                                    onImageClick = { showFullscreenImage = true },
                                    onPlay = { screenModel.playContinuation() },
                                    onToggleSubscription = { toggleSubscription(show) }
                                )
                            }

                            if (description.isNotBlank()) {
                                item(key = "description") {
                                    ShowDescription(description)
                                }
                            }

                            item(key = "episodes_header") {
                                EpisodesHeader(
                                    show = show,
                                    newestFirst = state.newestFirst,
                                    onSortChange = { screenModel.setNewestFirst(it) }
                                )
                            }

                            when {
                                episodesState.isLoading && !episodesState.loaded -> item(key = "episodes_loading") {
                                    LoadMoreIndicator()
                                }
                                episodesState.failed -> item(key = "episodes_failed") {
                                    FailedState(onRetry = { screenModel.retry() }, fill = false)
                                }
                                episodesState.loaded && episodesState.episodes.isEmpty() -> item(key = "episodes_empty") {
                                    InlineMessage(stringResource(Res.string.podcast_show_episodes_empty))
                                }
                                else -> {
                                    itemsIndexed(
                                        episodesState.episodes,
                                        key = { _, episode -> episode.id.toString() }
                                    ) { index, episode ->
                                        val isCurrent = currentEpisode?.id == episode.id
                                        EpisodeItem(
                                            episode = episode,
                                            isCurrent = isCurrent,
                                            isPlaying = isCurrent && isPlaying,
                                            onClick = { detailEpisode = episode },
                                            onPlay = { screenModel.playFromList(index) },
                                            showGoToShow = false,
                                            onEpisodeChanged = { screenModel.onEpisodeChanged(it) },
                                            importBadge = importBadgeFor(episode, canEdit)
                                        )
                                    }
                                    if (episodesState.hasMore) {
                                        item(key = "episodes_more") {
                                            LaunchedEffect(episodesState.episodes.size) { screenModel.episodes.loadMore() }
                                            LoadMoreIndicator()
                                        }
                                    }
                                }
                            }
                        }

                        VerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(lazyListState)
                        )
                    }
                }
            }
        }

        state.show?.let { show ->
            UnsubscribeConfirmDialog(
                isOpen = confirmUnsubscribe,
                show = show,
                onDismissRequest = { confirmUnsubscribe = false },
                onConfirm = {
                    confirmUnsubscribe = false
                    scope.launch {
                        try {
                            screenModel.unsubscribe()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            snackbarManager.showSnackbar(getString(Res.string.podcast_action_failed, e.message ?: ""))
                        }
                    }
                }
            )

            FullscreenImageDialog(
                isOpen = showFullscreenImage,
                imageId = show.imageId,
                onDismissRequest = { showFullscreenImage = false }
            )
        }

        detailEpisode?.let { episode ->
            EpisodeDetailDialog(
                episode = episode,
                onDismissRequest = { detailEpisode = null },
                showGoToShow = false
            )
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ShowHeader(
        show: PodcastShow,
        state: PodcastShowState,
        onImageClick: () -> Unit,
        onPlay: () -> Unit,
        onToggleSubscription: () -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SynaraImage(
                imageId = show.imageId,
                size = 200.dp,
                shape = MaterialTheme.shapes.medium,
                onClick = if (show.imageId != null) onImageClick else null,
                fallbackIcon = SynaraIcons.Podcast
            )

            Spacer(modifier = Modifier.width(24.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = show.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                show.author?.takeIf { it.isNotBlank() }?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = listOf(
                        pluralStringResource(Res.plurals.podcast_episode_count, show.episodeCount, show.episodeCount),
                        pluralStringResource(Res.plurals.podcast_subscriber_count, show.subscriberCount, show.subscriberCount)
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (show.explicit || show.source == PodcastSource.LOCAL) {
                    FlowRow(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (show.explicit) PodcastBadge(stringResource(Res.string.podcast_explicit), error = true)
                        if (show.source == PodcastSource.LOCAL) PodcastBadge(stringResource(Res.string.podcast_local_show))
                    }
                }

                show.lastFetchError?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(
                        text = stringResource(Res.string.podcast_last_fetch_error, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val continuation = state.continuation
                    Button(
                        onClick = onPlay,
                        enabled = state.continuationLoaded && show.episodeCount > 0,
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Icon(SynaraIcons.Play.get(), contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(
                                when (continuation) {
                                    is PodcastContinuation.Continue -> Res.string.podcast_continue
                                    is PodcastContinuation.Next -> Res.string.podcast_play_next_episode
                                    else -> Res.string.podcast_play
                                }
                            )
                        )
                    }

                    if (show.subscribed) {
                        OutlinedButton(onClick = onToggleSubscription, enabled = !state.isUpdatingSubscription) {
                            Icon(SynaraIcons.CheckCircle.get(), contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.podcast_subscribed))
                        }
                    } else {
                        FilledTonalButton(onClick = onToggleSubscription, enabled = !state.isUpdatingSubscription) {
                            Icon(SynaraIcons.Add.get(), contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.podcast_subscribe))
                        }
                    }
                }

                val target = state.continuation
                if (target is PodcastContinuation.Continue || target is PodcastContinuation.Next) {
                    Text(
                        text = target.episode.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun ShowDescription(description: String) {
        var expanded by remember { mutableStateOf(false) }
        var overflowing by remember { mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).animateContentSize()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result -> if (!expanded) overflowing = result.hasVisualOverflow }
            )
            if (overflowing || expanded) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(if (expanded) Res.string.podcast_show_less else Res.string.podcast_show_more))
                }
            }
        }
    }

    @Composable
    private fun EpisodesHeader(
        show: PodcastShow,
        newestFirst: Boolean,
        onSortChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = pluralStringResource(Res.plurals.podcast_episode_count, show.episodeCount, show.episodeCount),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = newestFirst,
                    onClick = { onSortChange(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {},
                    modifier = Modifier.widthIn(min = 128.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.podcast_sort_newest),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SegmentedButton(
                    selected = !newestFirst,
                    onClick = { onSortChange(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {},
                    modifier = Modifier.widthIn(min = 128.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.podcast_sort_oldest),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
