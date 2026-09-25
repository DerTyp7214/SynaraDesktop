package dev.dertyp.synara.screens.podcasts

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.PlatformUUID
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastImportState
import dev.dertyp.data.PodcastIndexResult
import dev.dertyp.data.UserCapability
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.RegisterRefreshTarget
import dev.dertyp.synara.ui.components.SynaraMenu
import dev.dertyp.synara.ui.components.podcast.*
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.viewmodels.GlobalStateModel
import dev.dertyp.synara.viewmodels.podcasts.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

class PodcastsScreen : Screen {

    override val key: ScreenKey = "PodcastsScreen"

    @Composable
    override fun Content() {
        val screenModel = getScreenModel<PodcastsScreenModel>()
        RegisterRefreshTarget(screenModel)
        val navigator = LocalNavigator.currentOrThrow
        val globalState = koinInject<GlobalStateModel>()
        val snackbarManager = koinInject<SnackbarManager>()
        val scope = rememberCoroutineScope()

        val tab by screenModel.tab.collectAsState()
        val user by globalState.user.collectAsState()
        val canEdit = user?.hasCapability(UserCapability.PODCAST_EDIT) == true

        var showAddFeed by remember { mutableStateOf(false) }
        var directoryResult by remember { mutableStateOf<PodcastIndexResult?>(null) }
        var detailEpisode by remember { mutableStateOf<PodcastEpisode?>(null) }

        LaunchedEffect(Unit) {
            screenModel.onShown()
        }

        fun scanLocal() {
            scope.launch {
                snackbarManager.showSnackbar(getString(Res.string.podcast_scan_local_running), duration = SnackbarDuration.Indefinite)
                try {
                    val result = screenModel.scanLocal()
                    snackbarManager.showSnackbar(
                        getString(
                            Res.string.podcast_scan_local_result,
                            result.shows,
                            result.episodesAdded,
                            result.episodesUpdated,
                            result.episodesRemoved
                        ),
                        duration = SnackbarDuration.Long
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    snackbarManager.showSnackbar(getString(Res.string.podcast_scan_local_failed, e.message ?: ""))
                }
            }
        }

        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(Res.string.podcasts),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(SynaraIcons.MoreOptions.get(), contentDescription = stringResource(Res.string.more_options))
                            }
                            SynaraMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.podcast_add_feed)) },
                                    onClick = {
                                        menuOpen = false
                                        showAddFeed = true
                                    },
                                    leadingIcon = { Icon(SynaraIcons.AddFeed.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                                )
                                if (canEdit) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.podcast_scan_local)) },
                                        onClick = {
                                            menuOpen = false
                                            scanLocal()
                                        },
                                        leadingIcon = { Icon(SynaraIcons.Sync.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                                    )
                                }
                            }
                        }
                    }

                    SingleChoiceSegmentedButtonRow {
                        PodcastsTab.entries.forEachIndexed { index, entry ->
                            SegmentedButton(
                                selected = tab == entry,
                                onClick = { screenModel.selectTab(entry) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = PodcastsTab.entries.size)
                            ) {
                                Text(stringResource(entry.label()))
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (tab) {
                        PodcastsTab.SUBSCRIPTIONS -> SubscriptionsTab(
                            screenModel = screenModel,
                            onOpenShow = { navigator.push(PodcastShowScreen(it)) },
                            onAddFeed = { showAddFeed = true }
                        )
                        PodcastsTab.LATEST -> EpisodesTab(
                            pager = screenModel.latest,
                            screenModel = screenModel,
                            canEdit = canEdit,
                            emptyText = Res.string.podcast_latest_empty,
                            onOpenEpisode = { detailEpisode = it }
                        )
                        PodcastsTab.IN_PROGRESS -> EpisodesTab(
                            pager = screenModel.inProgress,
                            screenModel = screenModel,
                            canEdit = canEdit,
                            emptyText = Res.string.podcast_in_progress_empty,
                            onOpenEpisode = { detailEpisode = it }
                        )
                        PodcastsTab.BROWSE -> BrowseTab(
                            screenModel = screenModel,
                            onOpenShow = { navigator.push(PodcastShowScreen(it)) },
                            onOpenDirectoryResult = { directoryResult = it }
                        )
                    }
                }
            }
        }

        if (showAddFeed) {
            AddFeedDialog(
                onDismissRequest = { showAddFeed = false },
                onSubscribe = { screenModel.subscribe(it) },
                onSubscribed = { show ->
                    showAddFeed = false
                    navigator.push(PodcastShowScreen(show.id))
                }
            )
        }

        directoryResult?.let { result ->
            DirectoryResultDialog(
                result = result,
                onDismissRequest = { directoryResult = null },
                onSubscribe = { screenModel.subscribe(it) },
                onSubscribed = { show ->
                    directoryResult = null
                    navigator.push(PodcastShowScreen(show.id))
                }
            )
        }

        detailEpisode?.let { episode ->
            EpisodeDetailDialog(
                episode = episode,
                onDismissRequest = { detailEpisode = null }
            )
        }
    }

    @Composable
    private fun SubscriptionsTab(
        screenModel: PodcastsScreenModel,
        onOpenShow: (PlatformUUID) -> Unit,
        onAddFeed: () -> Unit
    ) {
        val state by screenModel.subscriptions.collectAsState()
        when {
            state.isLoading && !state.loaded -> LoadingState()
            state.failed -> FailedState(onRetry = { screenModel.retry() })
            state.loaded && state.shows.isEmpty() -> EmptyState(
                title = stringResource(Res.string.podcast_subscriptions_empty_title),
                message = stringResource(Res.string.podcast_subscriptions_empty_message)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { screenModel.selectTab(PodcastsTab.BROWSE) }) {
                        Icon(SynaraIcons.Search.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.podcast_tab_browse))
                    }
                    OutlinedButton(onClick = onAddFeed) {
                        Icon(SynaraIcons.AddFeed.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.podcast_add_feed))
                    }
                }
            }
            else -> {
                val gridState = rememberLazyGridState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(180.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        items(state.shows, key = { it.id.toString() }) { show ->
                            PodcastShowCard(
                                show = show,
                                onClick = { onOpenShow(show.id) },
                                onShowChanged = { screenModel.onShown() },
                                onDeleted = { screenModel.onShown() }
                            )
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(gridState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
                }
            }
        }
    }

    @Composable
    private fun EpisodesTab(
        pager: EpisodeListPager,
        screenModel: PodcastsScreenModel,
        canEdit: Boolean,
        emptyText: StringResource,
        onOpenEpisode: (PodcastEpisode) -> Unit
    ) {
        val state by pager.state.collectAsState()
        val currentEpisode by screenModel.podcastPlayer.currentEpisode.collectAsState()
        val isPlaying by screenModel.podcastPlayer.isPlaying.collectAsState()

        when {
            state.isLoading && !state.loaded -> LoadingState()
            state.failed -> FailedState(onRetry = { screenModel.retry() })
            state.loaded && state.episodes.isEmpty() -> EmptyState(
                title = null,
                message = stringResource(emptyText)
            )
            else -> {
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        itemsIndexed(state.episodes, key = { _, episode -> episode.id.toString() }) { index, episode ->
                            val isCurrent = currentEpisode?.id == episode.id
                            EpisodeItem(
                                episode = episode,
                                isCurrent = isCurrent,
                                isPlaying = isCurrent && isPlaying,
                                showShowTitle = true,
                                onClick = { onOpenEpisode(episode) },
                                onPlay = { screenModel.playEpisodes(state.episodes, index) },
                                onEpisodeChanged = { screenModel.onEpisodeChanged(it) },
                                importBadge = importBadgeFor(episode, canEdit)
                            )
                        }
                        if (state.hasMore) {
                            item(key = "load_more") {
                                LaunchedEffect(state.episodes.size) { pager.loadMore() }
                                LoadMoreIndicator()
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
                }
            }
        }
    }

    @Composable
    private fun BrowseTab(
        screenModel: PodcastsScreenModel,
        onOpenShow: (PlatformUUID) -> Unit,
        onOpenDirectoryResult: (PodcastIndexResult) -> Unit
    ) {
        val query by screenModel.query.collectAsState()
        val browse by screenModel.browse.collectAsState()
        val directory by screenModel.directory.collectAsState()
        val gridState = rememberLazyGridState()
        val showSections = directory !is DirectoryState.Idle

        Column(modifier = Modifier.fillMaxSize()) {
            InternalTextField(
                value = query,
                onValueChange = { screenModel.setQuery(it) },
                placeholder = { Text(stringResource(Res.string.podcast_browse_search_placeholder)) },
                leadingIcon = { Icon(SynaraIcons.Search.get(), contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { screenModel.setQuery("") }) {
                            Icon(SynaraIcons.Clear.get(), contentDescription = null)
                        }
                    }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(180.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    if (showSections) {
                        fullLineItem("server_header") {
                            SectionHeader(stringResource(Res.string.podcast_browse_section_server))
                        }
                    }

                    when {
                        browse.isLoading && browse.shows.isEmpty() -> fullLineItem("server_loading") { LoadMoreIndicator() }
                        browse.failed -> fullLineItem("server_failed") {
                            FailedState(onRetry = { screenModel.retry() }, fill = false)
                        }
                        browse.loaded && browse.shows.isEmpty() -> fullLineItem("server_empty") {
                            InlineMessage(
                                stringResource(
                                    if (browse.query.isBlank()) Res.string.podcast_browse_empty
                                    else Res.string.podcast_browse_no_results
                                )
                            )
                        }
                        else -> {
                            items(browse.shows, key = { "show_${it.id}" }) { show ->
                                PodcastShowCard(
                                    show = show,
                                    onClick = { onOpenShow(show.id) }
                                )
                            }
                            if (browse.hasMore) {
                                fullLineItem("server_more") {
                                    LaunchedEffect(browse.shows.size) { screenModel.loadMoreShows() }
                                    LoadMoreIndicator()
                                }
                            }
                        }
                    }

                    if (showSections) {
                        fullLineItem("directory_header") {
                            SectionHeader(stringResource(Res.string.podcast_browse_section_directory))
                        }
                        when (val current = directory) {
                            DirectoryState.Idle -> Unit
                            DirectoryState.Loading -> fullLineItem("directory_loading") {
                                InlineMessage(stringResource(Res.string.podcast_browse_searching_directory), loading = true)
                            }
                            DirectoryState.Unavailable -> fullLineItem("directory_unavailable") {
                                InlineMessage(stringResource(Res.string.podcast_browse_directory_unavailable))
                            }
                            DirectoryState.Failed -> fullLineItem("directory_failed") {
                                InlineMessage(stringResource(Res.string.podcast_browse_directory_failed), error = true)
                            }
                            is DirectoryState.Results -> {
                                if (current.results.isEmpty()) {
                                    fullLineItem("directory_empty") {
                                        InlineMessage(stringResource(Res.string.podcast_browse_no_results))
                                    }
                                } else {
                                    items(current.results, key = { "directory_${it.indexId}_${it.feedUrl}" }) { result ->
                                        DirectoryResultCard(
                                            result = result,
                                            onClick = { onOpenDirectoryResult(result) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(gridState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }
    }

    private fun LazyGridScope.fullLineItem(key: String, content: @Composable () -> Unit) {
        item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
    }
}

private fun PodcastsTab.label(): StringResource = when (this) {
    PodcastsTab.SUBSCRIPTIONS -> Res.string.podcast_tab_subscriptions
    PodcastsTab.LATEST -> Res.string.podcast_tab_latest
    PodcastsTab.IN_PROGRESS -> Res.string.podcast_tab_in_progress
    PodcastsTab.BROWSE -> Res.string.podcast_tab_browse
}

internal fun importBadgeFor(episode: PodcastEpisode, canEdit: Boolean): (@Composable () -> Unit)? {
    if (!canEdit || episode.importState == PodcastImportState.NONE) return null
    return { ImportStateBadge(episode.importState) }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
    )
}

@Composable
internal fun LoadMoreIndicator() {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}

@Composable
internal fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun InlineMessage(text: String, loading: Boolean = false, error: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun FailedState(onRetry: () -> Unit, fill: Boolean = true, text: StringResource = Res.string.podcast_load_failed) {
    Box(
        modifier = if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                SynaraIcons.ErrorCircle.get(),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            )
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onRetry) {
                Icon(SynaraIcons.Refresh.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.podcast_refresh))
            }
        }
    }
}

@Composable
internal fun EmptyState(
    title: String?,
    message: String,
    actions: (@Composable () -> Unit)? = null
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.widthIn(max = 420.dp)
        ) {
            Icon(
                SynaraIcons.Podcast.get(),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            actions?.invoke()
        }
    }
}
