package dev.dertyp.synara.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.data.MoodSummary
import dev.dertyp.data.RecommendationWindow
import dev.dertyp.data.TopOrder
import dev.dertyp.synara.mixes.MIX_SIZE_OPTIONS
import dev.dertyp.synara.mixes.MixSource
import dev.dertyp.synara.mixes.MixSpec
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.*
import dev.dertyp.synara.ui.components.dialogs.CreatePlaylistDialog
import dev.dertyp.synara.viewmodels.MixScreenModel
import org.jetbrains.compose.resources.stringResource
import org.koin.core.parameter.parametersOf
import synara.synara.generated.resources.*

data class MixScreen(val spec: MixSpec) : Screen {

    override val key: ScreenKey = uniqueScreenKey

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = getScreenModel<MixScreenModel> { parametersOf(spec) }
        RegisterRefreshTarget(screenModel)
        val state by screenModel.state.collectAsState()
        val currentSong by screenModel.playerModel.currentSong.collectAsState()
        val lazyListState = rememberLazyListState()

        var showSaveDialog by remember { mutableStateOf(false) }
        var showMenu by remember { mutableStateOf(false) }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(SynaraIcons.Back.get(), contentDescription = stringResource(Res.string.back))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { screenModel.togglePin() },
                            modifier = Modifier.testTag("mix_pin")
                        ) {
                            Icon(
                                SynaraIcons.Pin.get(filled = state.isPinned),
                                contentDescription = stringResource(
                                    if (state.isPinned) Res.string.unpin_from_home else Res.string.pin_to_home
                                ),
                                tint = if (state.isPinned) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.isPinned) {
                            IconButton(
                                onClick = { screenModel.toggleDownload() },
                                modifier = Modifier.testTag("mix_download")
                            ) {
                                Icon(
                                    SynaraIcons.Download.get(filled = state.isDownloaded),
                                    contentDescription = stringResource(
                                        if (state.isDownloaded) Res.string.remove_download else Res.string.menu_download
                                    ),
                                    tint = if (state.isDownloaded) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.testTag("mix_more")
                            ) {
                                Icon(
                                    SynaraIcons.MoreOptions.get(),
                                    contentDescription = stringResource(Res.string.more_options)
                                )
                            }
                            SynaraMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.save_as_playlist)) },
                                    enabled = state.songs.isNotEmpty(),
                                    onClick = {
                                        showMenu = false
                                        showSaveDialog = true
                                    },
                                    leadingIcon = { Icon(SynaraIcons.AddToPlaylist.get(), contentDescription = null) },
                                    modifier = Modifier.testTag("mix_save_as_playlist")
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item(key = "header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            MixIconTile(spec = state.spec, size = 128.dp)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = state.spec.title(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${state.spec.optionsLabel()} · ${state.songs.size} ${stringResource(Res.string.songs)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = { screenModel.play() },
                                        enabled = state.songs.isNotEmpty(),
                                        modifier = Modifier.testTag("mix_play")
                                    ) {
                                        Icon(SynaraIcons.Play.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(Res.string.play))
                                    }
                                    OutlinedButton(
                                        onClick = { screenModel.shuffle() },
                                        enabled = state.songs.isNotEmpty(),
                                        modifier = Modifier.testTag("mix_shuffle")
                                    ) {
                                        Icon(SynaraIcons.Shuffle.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(Res.string.shuffle))
                                    }
                                }
                            }
                        }
                    }

                    item(key = "options") {
                        MixOptions(
                            spec = state.spec,
                            moods = state.moods,
                            onSpecChange = screenModel::setSpec,
                            onSizeChange = screenModel::setSize
                        )
                    }

                    when {
                        state.isLoading && state.songs.isEmpty() -> item(key = "loading") {
                            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        state.error != null -> item(key = "error") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(text = state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { screenModel.refresh() }) {
                                    Text(stringResource(Res.string.retry))
                                }
                            }
                        }
                        state.songs.isEmpty() -> item(key = "empty") {
                            Text(
                                text = stringResource(state.spec.emptyMessage()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(24.dp)
                            )
                        }
                        else -> itemsIndexed(state.songs, key = { _, song -> song.id }) { index, song ->
                            SongItem(
                                song = song,
                                isCurrent = currentSong?.id == song.id,
                                showCover = true,
                                onClick = { screenModel.play(index) },
                                onPlayNext = { screenModel.playerModel.playNext(song) }
                            )
                        }
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(lazyListState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }

        CreatePlaylistDialog(
            isOpen = showSaveDialog,
            onConfirm = { name -> screenModel.saveAsPlaylist(name) },
            onDismissRequest = { showSaveDialog = false },
            initialName = state.spec.cardTitle()
        )
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun MixOptions(
        spec: MixSpec,
        moods: List<MoodSummary>,
        onSpecChange: (MixSpec) -> Unit,
        onSizeChange: (Int) -> Unit
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (spec) {
                is MixSpec.YourMix -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RecommendationWindow.entries.forEach { window ->
                        val source = MixSource.Recent(window)
                        FilterChip(
                            selected = spec.source == source,
                            onClick = { onSpecChange(spec.copy(source = source)) },
                            label = { Text(window.label()) },
                            modifier = Modifier.testTag("mix_window_${window.name}")
                        )
                    }
                    moods.forEach { mood ->
                        val source = MixSource.Mood(mood.mood)
                        FilterChip(
                            selected = spec.source == source,
                            onClick = { onSpecChange(spec.copy(source = source)) },
                            label = { Text("${mood.mood} (${mood.count})") },
                            modifier = Modifier.testTag("mix_mood_${mood.mood}")
                        )
                    }
                }
                is MixSpec.TopSongs -> SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    MIX_STATS_RANGES.forEachIndexed { index, range ->
                        SegmentedButton(
                            selected = spec.range == range,
                            onClick = { onSpecChange(spec.copy(range = range)) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = MIX_STATS_RANGES.size),
                            modifier = Modifier.testTag("mix_range_${range.name}")
                        ) {
                            Text(range.label())
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (spec is MixSpec.TopSongs) {
                    OptionGroup(label = stringResource(Res.string.stats_rank_by)) {
                        SingleChoiceSegmentedButtonRow {
                            TopOrder.entries.forEachIndexed { index, order ->
                                SegmentedButton(
                                    selected = spec.order == order,
                                    onClick = { onSpecChange(spec.copy(order = order)) },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = TopOrder.entries.size),
                                    modifier = Modifier.testTag("mix_order_${order.name}")
                                ) {
                                    Text(order.label())
                                }
                            }
                        }
                    }
                }
                OptionGroup(label = stringResource(Res.string.mix_size)) {
                    SingleChoiceSegmentedButtonRow {
                        MIX_SIZE_OPTIONS.forEachIndexed { index, size ->
                            SegmentedButton(
                                selected = spec.size == size,
                                onClick = { onSizeChange(size) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = MIX_SIZE_OPTIONS.size),
                                modifier = Modifier.testTag("mix_size_$size")
                            ) {
                                Text(size.toString())
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun OptionGroup(label: String, content: @Composable () -> Unit) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

private fun MixSpec.emptyMessage() = when (this) {
    is MixSpec.YourMix -> when (source) {
        is MixSource.Recent -> Res.string.recommendations_mix_empty
        is MixSource.Mood -> Res.string.mood_playlist_empty
    }
    is MixSpec.TopSongs -> Res.string.stats_empty
}
