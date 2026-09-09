package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import dev.dertyp.data.InsertableRadioChannel
import dev.dertyp.data.RadioChannel
import dev.dertyp.data.RadioChannelItemType
import dev.dertyp.data.RadioType
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.core.displayTitle
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SettingsCard
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.components.dialogs.SynaraDialog
import dev.dertyp.synara.utils.pickImageBytes
import dev.dertyp.synara.viewmodels.GlobalStateModel
import dev.dertyp.synara.viewmodels.RadioScreenModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

class RadioScreen : Screen {

    private data class Station(
        val type: RadioType,
        val title: StringResource,
        val subtitle: StringResource
    )

    private val stations = listOf(
        Station(RadioType.RANDOM, Res.string.radio_random, Res.string.radio_random_summary),
        Station(RadioType.LAST_WEEK, Res.string.radio_last_week, Res.string.radio_last_week_summary),
        Station(RadioType.LAST_MONTH, Res.string.radio_last_month, Res.string.radio_last_month_summary),
        Station(RadioType.LAST_YEAR, Res.string.radio_last_year, Res.string.radio_last_year_summary),
    )

    @Composable
    override fun Content() {
        val screenModel = getScreenModel<RadioScreenModel>()
        val globalState = koinInject<GlobalStateModel>()
        val state by screenModel.state.collectAsState()
        val user by globalState.user.collectAsState()
        val isAdmin = user?.isAdmin == true

        var showCreateDialog by remember { mutableStateOf(false) }
        var editChannel by remember { mutableStateOf<RadioChannel?>(null) }
        var deleteChannel by remember { mutableStateOf<RadioChannel?>(null) }

        val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()

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
                            text = stringResource(Res.string.radio),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    item {
                        Text(
                            text = stringResource(Res.string.radio_stations),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    item {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val isNarrow = maxWidth < 700.dp
                            if (isNarrow) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    stations.chunked(2).forEach { pair ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            pair.forEach { station ->
                                                StationCard(
                                                    station = station,
                                                    modifier = Modifier.weight(1f),
                                                    onClick = { name ->
                                                        screenModel.startStation(station.type, name)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    stations.forEach { station ->
                                        StationCard(
                                            station = station,
                                            modifier = Modifier.weight(1f),
                                            onClick = { name ->
                                                screenModel.startStation(station.type, name)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(Res.string.radio_channels_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (isAdmin) {
                                Button(onClick = { showCreateDialog = true }) {
                                    Icon(
                                        SynaraIcons.Add.get(),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(Res.string.radio_channel_create))
                                }
                            }
                        }
                    }

                    if (state.isLoading && state.channels.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (state.channels.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(Res.string.radio_channel_items_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(state.channels.size, key = { state.channels[it].id.toString() }) { index ->
                            val channel = state.channels[index]
                            ChannelCard(
                                channel = channel,
                                isAdmin = isAdmin,
                                onPlay = { screenModel.startChannel(channel) },
                                onEdit = { editChannel = channel },
                                onDelete = { deleteChannel = channel }
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

        if (showCreateDialog) {
            ChannelEditorDialog(
                screenModel = screenModel,
                channel = null,
                onDismissRequest = { showCreateDialog = false }
            )
        }

        editChannel?.let { channel ->
            ChannelEditorDialog(
                screenModel = screenModel,
                channel = channel,
                onDismissRequest = {
                    editChannel = null
                    screenModel.clearChannelContent()
                }
            )
        }

        deleteChannel?.let { channel ->
            AlertDialog(
                onDismissRequest = { deleteChannel = null },
                title = { Text(channel.name) },
                text = { Text(stringResource(Res.string.radio_channel_delete_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        screenModel.deleteChannel(channel.id)
                        deleteChannel = null
                    }) {
                        Text(
                            stringResource(Res.string.delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteChannel = null }) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            )
        }
    }

    @Composable
    private fun StationCard(
        station: Station,
        modifier: Modifier = Modifier,
        onClick: (String) -> Unit
    ) {
        val title = stringResource(station.title)
        SettingsCard(modifier = modifier, onClick = { onClick(title) }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        SynaraIcons.Radio.get(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = stringResource(station.subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun ChannelCard(
        channel: RadioChannel,
        isAdmin: Boolean,
        onPlay: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        SettingsCard(onClick = onPlay) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SynaraImage(
                    imageId = channel.imageId,
                    size = 56.dp,
                    shape = MaterialTheme.shapes.small,
                    fallbackIcon = SynaraIcons.Radio
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!channel.enabled) {
                            Text(
                                text = stringResource(Res.string.radio_channel_draft),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        MaterialTheme.shapes.extraSmall
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    channel.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = stringResource(
                            Res.string.radio_channel_counts,
                            channel.songCount,
                            channel.albumCount,
                            channel.artistCount
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isAdmin) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            SynaraIcons.Edit.get(),
                            contentDescription = stringResource(Res.string.radio_channel_edit)
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            SynaraIcons.Delete.get(),
                            contentDescription = stringResource(Res.string.delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                IconButton(onClick = onPlay) {
                    Icon(
                        SynaraIcons.Play.get(),
                        contentDescription = stringResource(Res.string.radio),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    @Composable
    private fun ChannelEditorDialog(
        screenModel: RadioScreenModel,
        channel: RadioChannel?,
        onDismissRequest: () -> Unit
    ) {
        val scope = rememberCoroutineScope()
        val state by screenModel.state.collectAsState()

        var name by remember { mutableStateOf(channel?.name ?: "") }
        var description by remember { mutableStateOf(channel?.description ?: "") }
        var enabled by remember { mutableStateOf(channel?.enabled ?: false) }
        var position by remember { mutableStateOf((channel?.position ?: 0).toString()) }
        var discovery by remember { mutableStateOf(channel?.discovery ?: false) }

        var contentQuery by remember { mutableStateOf("") }
        var addQuery by remember { mutableStateOf("") }

        LaunchedEffect(channel?.id) {
            channel?.let { screenModel.loadChannelContent(it.id) }
        }

        SynaraDialog(
            isOpen = true,
            onDismissRequest = onDismissRequest
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
                modifier = Modifier.width(560.dp).heightIn(max = 720.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (channel == null) Res.string.radio_channel_create
                            else Res.string.radio_channel_edit
                        ),
                        style = MaterialTheme.typography.headlineSmall
                    )

                    if (channel != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            SynaraImage(
                                imageId = channel.imageId,
                                size = 72.dp,
                                shape = MaterialTheme.shapes.small,
                                fallbackIcon = SynaraIcons.Radio
                            )
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val bytes = pickImageBytes() ?: return@launch
                                    screenModel.setChannelImage(channel.id, bytes)
                                }
                            }) {
                                Text(stringResource(Res.string.radio_channel_choose_image))
                            }
                        }
                    }

                    InternalTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(Res.string.radio_channel_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    InternalTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(Res.string.radio_channel_description)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    InternalTextField(
                        value = position,
                        onValueChange = { position = it },
                        label = { Text(stringResource(Res.string.radio_channel_position)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    EditorSwitchRow(
                        title = stringResource(Res.string.radio_channel_enabled),
                        subtitle = stringResource(Res.string.radio_channel_enabled_summary),
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                    EditorSwitchRow(
                        title = stringResource(Res.string.radio_channel_discovery),
                        subtitle = stringResource(Res.string.radio_channel_discovery_summary),
                        checked = discovery,
                        onCheckedChange = { discovery = it }
                    )

                    if (channel != null) {
                        HorizontalDivider()

                        Text(
                            text = stringResource(Res.string.radio_channel_items),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        InternalTextField(
                            value = contentQuery,
                            onValueChange = {
                                contentQuery = it
                                screenModel.loadChannelContent(channel.id, it, debounce = true)
                            },
                            label = { Text(stringResource(Res.string.search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        val content = state.channelContent
                        if (state.channelContentLoading && content == null) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (content != null) {
                            val explicitSongs = content.songs.data.filter { it.explicitMember }
                            if (explicitSongs.isEmpty() && content.artists.data.isEmpty() && content.albums.data.isEmpty()) {
                                Text(
                                    text = stringResource(Res.string.radio_channel_items_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            explicitSongs.forEach { match ->
                                EditorItemRow(
                                    imageId = match.song.coverId,
                                    fallbackIcon = SynaraIcons.Songs,
                                    title = match.song.displayTitle,
                                    subtitle = match.song.artists.joinToString(", ") { it.name },
                                    actionIcon = SynaraIcons.Delete,
                                    onAction = {
                                        screenModel.removeChannelItem(
                                            channel.id, RadioChannelItemType.SONG, match.song.id, contentQuery
                                        )
                                    }
                                )
                            }
                            content.artists.data.forEach { artist ->
                                EditorItemRow(
                                    imageId = artist.imageId,
                                    fallbackIcon = SynaraIcons.Artists,
                                    title = artist.name,
                                    subtitle = stringResource(Res.string.artists),
                                    actionIcon = SynaraIcons.Delete,
                                    onAction = {
                                        screenModel.removeChannelItem(
                                            channel.id, RadioChannelItemType.ARTIST, artist.id, contentQuery
                                        )
                                    }
                                )
                            }
                            content.albums.data.forEach { album ->
                                EditorItemRow(
                                    imageId = album.coverId,
                                    fallbackIcon = SynaraIcons.Albums,
                                    title = album.name,
                                    subtitle = stringResource(Res.string.albums),
                                    actionIcon = SynaraIcons.Delete,
                                    onAction = {
                                        screenModel.removeChannelItem(
                                            channel.id, RadioChannelItemType.ALBUM, album.id, contentQuery
                                        )
                                    }
                                )
                            }
                        }

                        HorizontalDivider()

                        Text(
                            text = stringResource(Res.string.radio_channel_items_add),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        InternalTextField(
                            value = addQuery,
                            onValueChange = {
                                addQuery = it
                                screenModel.searchLibrary(it)
                            },
                            label = { Text(stringResource(Res.string.search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        state.librarySongs.forEach { song ->
                            EditorItemRow(
                                imageId = song.coverId,
                                fallbackIcon = SynaraIcons.Songs,
                                title = song.displayTitle,
                                subtitle = song.artists.joinToString(", ") { it.name },
                                actionIcon = SynaraIcons.Add,
                                onAction = {
                                    screenModel.addChannelItem(
                                        channel.id, RadioChannelItemType.SONG, song.id, contentQuery
                                    )
                                }
                            )
                        }
                        state.libraryArtists.forEach { artist ->
                            EditorItemRow(
                                imageId = artist.imageId,
                                fallbackIcon = SynaraIcons.Artists,
                                title = artist.name,
                                subtitle = stringResource(Res.string.artists),
                                actionIcon = SynaraIcons.Add,
                                onAction = {
                                    screenModel.addChannelItem(
                                        channel.id, RadioChannelItemType.ARTIST, artist.id, contentQuery
                                    )
                                }
                            )
                        }
                        state.libraryAlbums.forEach { album ->
                            EditorItemRow(
                                imageId = album.coverId,
                                fallbackIcon = SynaraIcons.Albums,
                                title = album.name,
                                subtitle = stringResource(Res.string.albums),
                                actionIcon = SynaraIcons.Add,
                                onAction = {
                                    screenModel.addChannelItem(
                                        channel.id, RadioChannelItemType.ALBUM, album.id, contentQuery
                                    )
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(onClick = onDismissRequest) {
                            Text(stringResource(Res.string.cancel))
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    val insertable = InsertableRadioChannel(
                                        name = name,
                                        description = description.takeIf { it.isNotBlank() },
                                        enabled = enabled,
                                        position = position.toIntOrNull() ?: 0,
                                        discovery = discovery
                                    )
                                    if (channel == null) {
                                        screenModel.createChannel(insertable)
                                    } else {
                                        screenModel.updateChannel(channel.id, insertable)
                                    }
                                    onDismissRequest()
                                }
                            },
                            enabled = name.isNotBlank()
                        ) {
                            Text(stringResource(Res.string.save))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EditorSwitchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    @Composable
    private fun EditorItemRow(
        imageId: dev.dertyp.PlatformUUID?,
        fallbackIcon: SynaraIcons,
        title: String,
        subtitle: String,
        actionIcon: SynaraIcons,
        onAction: () -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SynaraImage(
                imageId = imageId,
                size = 40.dp,
                shape = MaterialTheme.shapes.extraSmall,
                fallbackIcon = fallbackIcon
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onAction) {
                Icon(actionIcon.get(), contentDescription = null)
            }
        }
    }
}
