package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.PlatformUUID
import dev.dertyp.data.StatsRange
import dev.dertyp.data.TopSongEntry
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SettingsCard
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.components.formatListenedTime
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.viewmodels.StatsScreenModel
import dev.dertyp.services.ISongService
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

class StatsScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<StatsScreenModel>()
        val playerModel = koinInject<PlayerModel>()
        val songService = koinInject<ISongService>()
        val snackbarManager = koinInject<SnackbarManager>()
        val navigator = LocalNavigator.currentOrThrow
        val state by screenModel.state.collectAsState()
        val scope = rememberCoroutineScope()

        var linkTarget by remember { mutableStateOf<TopSongEntry?>(null) }

        val lazyListState = rememberLazyListState()

        Scaffold(containerColor = Color.Transparent) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            text = stringResource(Res.string.stats),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    item {
                        SingleChoiceSegmentedButtonRow {
                            StatsRange.entries.forEachIndexed { index, range ->
                                SegmentedButton(
                                    selected = state.selectedRange == range,
                                    onClick = { screenModel.load(range) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = StatsRange.entries.size
                                    )
                                ) {
                                    Text(
                                        when (range) {
                                            StatsRange.DAY -> stringResource(Res.string.stats_range_day)
                                            StatsRange.WEEK -> stringResource(Res.string.stats_range_week)
                                            StatsRange.MONTH -> stringResource(Res.string.stats_range_month)
                                            StatsRange.YEAR -> stringResource(Res.string.stats_range_year)
                                            StatsRange.ALL_TIME -> stringResource(Res.string.stats_range_all_time)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (state.isLoading) {
                        item {
                            if (state.stats == null) {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }

                    state.stats?.let { stats ->
                        item {
                            Row(
                                modifier = Modifier.height(IntrinsicSize.Max),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                StatCard(
                                    modifier = Modifier.weight(1f),
                                    title = stringResource(Res.string.stats_listens),
                                    value = stats.listenCount.toString(),
                                    subtitle = stats.comparison?.percentChange?.let { change ->
                                        comparisonText(change)
                                    }
                                )
                                StatCard(
                                    modifier = Modifier.weight(1f),
                                    title = stringResource(Res.string.stats_listened_time),
                                    value = formatListenedTime(stats.listenedMs),
                                    subtitle = stats.comparison
                                        ?.takeIf { it.previousListenedMs > 0 }
                                        ?.let { comparison ->
                                            val change = (stats.listenedMs - comparison.previousListenedMs) *
                                                    100.0 / comparison.previousListenedMs
                                            comparisonText(change)
                                        }
                                )
                                StatCard(
                                    modifier = Modifier.weight(1f),
                                    title = stringResource(Res.string.stats_unique_songs),
                                    value = stats.uniqueSongs.toString()
                                )
                                StatCard(
                                    modifier = Modifier.weight(1f),
                                    title = stringResource(Res.string.stats_unique_artists),
                                    value = stats.uniqueArtists.toString()
                                )
                                StatCard(
                                    modifier = Modifier.weight(1f),
                                    title = stringResource(Res.string.stats_unique_albums),
                                    value = stats.uniqueAlbums.toString()
                                )
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.height(IntrinsicSize.Max),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                StatCard(
                                    modifier = Modifier.weight(1f),
                                    title = stringResource(Res.string.stats_current_streak),
                                    value = stringResource(Res.string.stats_days, stats.streaks.currentStreakDays)
                                )
                                StatCard(
                                    modifier = Modifier.weight(1f),
                                    title = stringResource(Res.string.stats_longest_streak),
                                    value = stringResource(Res.string.stats_days, stats.streaks.longestStreakDays)
                                )
                            }
                        }

                        if (stats.listenCount == 0L) {
                            item {
                                Text(
                                    text = stringResource(Res.string.stats_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (stats.listenClock.hourOfDay.any { it > 0 }) {
                            item {
                                Text(
                                    text = stringResource(Res.string.stats_listen_clock),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            item {
                                SettingsCard {
                                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text(
                                            text = stringResource(Res.string.stats_by_hour),
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        BarRow(
                                            values = stats.listenClock.hourOfDay,
                                            labels = (0..23).map { if (it % 6 == 0) it.toString() else "" }
                                        )
                                        Text(
                                            text = stringResource(Res.string.stats_by_weekday),
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        BarRow(
                                            values = stats.listenClock.dayOfWeek,
                                            labels = stringResource(Res.string.stats_weekday_labels).split(",")
                                        )
                                    }
                                }
                            }
                        }

                        if (stats.topSongs.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(Res.string.stats_top_songs),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            items(stats.topSongs.size) { index ->
                                val entry = stats.topSongs[index]
                                TopEntryRow(
                                    rank = index + 1,
                                    imageId = entry.coverId,
                                    fallbackIcon = SynaraIcons.Songs,
                                    title = entry.title,
                                    subtitle = listOfNotNull(entry.artistName, entry.albumName)
                                        .joinToString(" · "),
                                    listenCount = entry.listenCount,
                                    listenedMs = entry.listenedMs,
                                    onClick = entry.songId?.let { songId ->
                                        {
                                            scope.launch {
                                                songService.byId(songId)?.let { playerModel.playSong(it) }
                                            }
                                        }
                                    },
                                    trailing = if (entry.songId == null &&
                                        (entry.recordingMbid != null || entry.recordingMsid != null)
                                    ) {
                                        {
                                            IconButton(onClick = { linkTarget = entry }) {
                                                Icon(
                                                    SynaraIcons.Link.get(),
                                                    contentDescription = stringResource(Res.string.stats_link_track)
                                                )
                                            }
                                        }
                                    } else null
                                )
                            }
                        }

                        if (stats.topArtists.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(Res.string.stats_top_artists),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            items(stats.topArtists.size) { index ->
                                val entry = stats.topArtists[index]
                                TopEntryRow(
                                    rank = index + 1,
                                    imageId = entry.imageId,
                                    fallbackIcon = SynaraIcons.Artists,
                                    imageShape = CircleShape,
                                    title = entry.name,
                                    subtitle = null,
                                    listenCount = entry.listenCount,
                                    listenedMs = entry.listenedMs,
                                    onClick = entry.artistId?.let { artistId ->
                                        { navigator.push(ArtistScreen(artistId)) }
                                    }
                                )
                            }
                        }

                        if (stats.topAlbums.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(Res.string.stats_top_albums),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            items(stats.topAlbums.size) { index ->
                                val entry = stats.topAlbums[index]
                                TopEntryRow(
                                    rank = index + 1,
                                    imageId = entry.coverId,
                                    fallbackIcon = SynaraIcons.Albums,
                                    title = entry.name,
                                    subtitle = null,
                                    listenCount = entry.listenCount,
                                    listenedMs = entry.listenedMs,
                                    onClick = entry.albumId?.let { albumId ->
                                        { navigator.push(AlbumScreen(albumId)) }
                                    }
                                )
                            }
                        }

                        if (stats.discoveries.songs.isNotEmpty() || stats.discoveries.artists.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(Res.string.stats_discoveries),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            items(stats.discoveries.songs.size) { index ->
                                val entry = stats.discoveries.songs[index]
                                TopEntryRow(
                                    rank = index + 1,
                                    imageId = entry.coverId,
                                    fallbackIcon = SynaraIcons.Songs,
                                    title = entry.title,
                                    subtitle = entry.artistName,
                                    listenCount = entry.listenCount,
                                    listenedMs = entry.listenedMs,
                                    onClick = entry.songId?.let { songId ->
                                        {
                                            scope.launch {
                                                songService.byId(songId)?.let { playerModel.playSong(it) }
                                            }
                                        }
                                    }
                                )
                            }
                            items(stats.discoveries.artists.size) { index ->
                                val entry = stats.discoveries.artists[index]
                                TopEntryRow(
                                    rank = index + 1,
                                    imageId = entry.imageId,
                                    fallbackIcon = SynaraIcons.Artists,
                                    imageShape = CircleShape,
                                    title = entry.name,
                                    subtitle = null,
                                    listenCount = entry.listenCount,
                                    listenedMs = entry.listenedMs,
                                    onClick = entry.artistId?.let { artistId ->
                                        { navigator.push(ArtistScreen(artistId)) }
                                    }
                                )
                            }
                        }
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(lazyListState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }

        linkTarget?.let { entry ->
            LinkTrackDialog(
                entry = entry,
                screenModel = screenModel,
                onLinked = { result ->
                    scope.launch {
                        snackbarManager.showSnackbar(
                            getString(Res.string.stats_linked_listens, result.linkedListens)
                        )
                    }
                },
                onDismissRequest = {
                    linkTarget = null
                    screenModel.clearLinkSearch()
                }
            )
        }
    }

    @Composable
    private fun comparisonText(change: Double): String {
        val arrow = if (change >= 0) "▲" else "▼"
        val formatted = (if (change >= 0) "+" else "") + "${(change * 10).toInt() / 10.0}%"
        return "$arrow $formatted ${stringResource(Res.string.stats_vs_previous)}"
    }

    @Composable
    private fun StatCard(
        title: String,
        value: String,
        modifier: Modifier = Modifier,
        subtitle: String? = null
    ) {
        SettingsCard(modifier = modifier.fillMaxHeight()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    private fun BarRow(values: List<Long>, labels: List<String>) {
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
        Row(
            modifier = Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            values.forEachIndexed { index, value ->
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    val fraction = (value.toFloat() / max).coerceIn(0.02f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(fraction * 0.8f)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(
                                if (value > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    )
                    Text(
                        text = labels.getOrElse(index) { "" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    @Composable
    private fun TopEntryRow(
        rank: Int,
        imageId: PlatformUUID?,
        fallbackIcon: SynaraIcons,
        title: String,
        subtitle: String?,
        listenCount: Long,
        listenedMs: Long = 0,
        onClick: (() -> Unit)? = null,
        trailing: (@Composable () -> Unit)? = null,
        imageShape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.extraSmall
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
                textAlign = TextAlign.Center
            )
            SynaraImage(
                imageId = imageId,
                size = 44.dp,
                shape = imageShape,
                fallbackIcon = fallbackIcon
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(Res.string.stats_listen_count, listenCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (listenedMs > 0) {
                    Text(
                        text = formatListenedTime(listenedMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            trailing?.invoke()
        }
    }

    @Composable
    private fun LinkTrackDialog(
        entry: TopSongEntry,
        screenModel: StatsScreenModel,
        onLinked: (dev.dertyp.data.LinkUnmatchedTrackResult) -> Unit,
        onDismissRequest: () -> Unit
    ) {
        val state by screenModel.state.collectAsState()
        val scope = rememberCoroutineScope()
        var query by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(Res.string.stats_link_track)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "${entry.title}${entry.artistName?.let { " · $it" } ?: ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    InternalTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            screenModel.searchLinkTarget(it)
                        },
                        label = { Text(stringResource(Res.string.search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Column(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        state.linkSearchResults.forEach { song ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        scope.launch {
                                            val result = screenModel.linkUnmatchedTrack(
                                                songId = song.id,
                                                recordingMsid = entry.recordingMsid,
                                                recordingMbid = entry.recordingMbid
                                            )
                                            if (result != null) onLinked(result)
                                            onDismissRequest()
                                        }
                                    }
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SynaraImage(
                                    imageId = song.coverId,
                                    size = 36.dp,
                                    shape = MaterialTheme.shapes.extraSmall,
                                    fallbackIcon = SynaraIcons.Songs
                                )
                                Column {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = song.artists.joinToString(", ") { it.name },
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
            },
            confirmButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }
}
