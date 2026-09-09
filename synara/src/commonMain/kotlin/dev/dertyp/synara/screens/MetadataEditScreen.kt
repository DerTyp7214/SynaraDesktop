package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import dev.dertyp.PlatformUUID
import dev.dertyp.core.joinArtists
import dev.dertyp.data.Artist
import dev.dertyp.data.MusicBrainzRecording
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.data.UserSong
import dev.dertyp.data.effectiveAudio
import dev.dertyp.services.ISongService
import dev.dertyp.synara.core.displayTags
import dev.dertyp.synara.core.localizedName
import dev.dertyp.synara.core.toSong
import dev.dertyp.synara.db.LibraryRepository
import dev.dertyp.synara.scrobble.MusicBrainzService
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.TitleTagChipStyle
import dev.dertyp.synara.ui.components.TitleTagChips
import dev.dertyp.synara.ui.components.dialogs.EditSongArtistsDialog
import dev.dertyp.synara.ui.components.dialogs.LyricsEditDialog
import dev.dertyp.synara.ui.components.dialogs.MusicBrainzSearchDialog
import dev.dertyp.synara.ui.components.formatDuration
import dev.dertyp.synara.ui.components.formatFileSize
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

class MetadataEditScreen(private val songId: PlatformUUID) : Screen {

    override val key: ScreenKey = "MetadataEditScreen_$songId"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val navigator = LocalNavigator.currentOrThrow
        val songService: ISongService = koinInject()
        val mbService: MusicBrainzService = koinInject()
        val libraryRepository: LibraryRepository = koinInject()

        var song by remember { mutableStateOf<UserSong?>(null) }
        var title by remember { mutableStateOf("") }
        var tags by remember { mutableStateOf<List<TitleTag>>(emptyList()) }
        var artists by remember { mutableStateOf<List<Artist>>(emptyList()) }
        var lyrics by remember { mutableStateOf<List<String>>(emptyList()) }
        var musicBrainzId by remember { mutableStateOf<PlatformUUID?>(null) }
        var mbRecording by remember { mutableStateOf<MusicBrainzRecording?>(null) }
        
        var showArtistsDialog by remember { mutableStateOf(false) }
        var showLyricsDialog by remember { mutableStateOf(false) }
        var showMusicBrainzDialog by remember { mutableStateOf(false) }
        
        var isLoading by remember { mutableStateOf(true) }
        var isSaving by remember { mutableStateOf(false) }

        LaunchedEffect(songId) {
            song = songService.byId(songId)
            title = song?.title ?: ""
            tags = song?.tags ?: emptyList()
            artists = song?.artists ?: emptyList()
            lyrics = song?.lyrics?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            musicBrainzId = song?.musicBrainzId
            if (musicBrainzId != null) {
                mbRecording = mbService.getRecording(musicBrainzId!!)
            }
            isLoading = false
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(Res.string.edit_metadata)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    ),
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                SynaraIcons.Back.get(),
                                contentDescription = stringResource(Res.string.back)
                            )
                        }
                    },
                    actions = {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        isSaving = true
                                        try {
                                            val current = song ?: return@launch
                                            val payload = current.toSong().copy(
                                                title = title.trim(),
                                                tags = tags
                                                    .map { it.copy(label = it.label.trim()) }
                                                    .filter { it.label.isNotBlank() },
                                                artists = artists,
                                                lyrics = lyrics.joinToString("\n"),
                                                musicBrainzId = musicBrainzId,
                                            )
                                            val updated = songService.updateSong(payload)
                                            if (updated != null &&
                                                libraryRepository.isSongSaved(songId, explicitlySavedOnly = false)
                                            ) {
                                                libraryRepository.saveSongMetadata(
                                                    updated,
                                                    explicitlySaved = libraryRepository.isSongSaved(
                                                        songId,
                                                        explicitlySavedOnly = true
                                                    )
                                                )
                                            }
                                            navigator.pop()
                                        } catch (_: Exception) {
                                        } finally {
                                            isSaving = false
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    SynaraIcons.Confirm.get(),
                                    contentDescription = stringResource(Res.string.save)
                                )
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text(stringResource(Res.string.metadata_title)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = artists.joinArtists(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (tags.displayTags.isNotEmpty()) {
                                TitleTagChips(
                                    tags = tags,
                                    style = TitleTagChipStyle.Detailed
                                )
                            }
                        }
                    }

                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { showArtistsDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(SynaraIcons.Artists.get(), contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(Res.string.edit_song_artists_title))
                            }

                            Button(
                                onClick = { showLyricsDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(SynaraIcons.Lyrics.get(), contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(Res.string.edit_lyrics))
                            }

                            Button(
                                onClick = { showMusicBrainzDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(
                                    SynaraIcons.MusicBrainz.get(),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(Res.string.match_musicbrainz))
                            }
                        }
                    }

                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.edit_title_tags),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )

                                tags.forEachIndexed { index, tag ->
                                    var kindExpanded by remember { mutableStateOf(false) }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ExposedDropdownMenuBox(
                                            expanded = kindExpanded,
                                            onExpandedChange = { kindExpanded = it },
                                            modifier = Modifier.width(180.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = tag.kind.localizedName(),
                                                onValueChange = {},
                                                readOnly = true,
                                                singleLine = true,
                                                label = { Text(stringResource(Res.string.title_tag_kind)) },
                                                trailingIcon = {
                                                    ExposedDropdownMenuDefaults.TrailingIcon(
                                                        expanded = kindExpanded
                                                    )
                                                },
                                                modifier = Modifier
                                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                                    .fillMaxWidth()
                                            )
                                            ExposedDropdownMenu(
                                                expanded = kindExpanded,
                                                onDismissRequest = { kindExpanded = false }
                                            ) {
                                                TitleTagKind.entries.forEach { kind ->
                                                    DropdownMenuItem(
                                                        text = { Text(kind.localizedName()) },
                                                        onClick = {
                                                            tags = tags.toMutableList().also {
                                                                it[index] = it[index].copy(kind = kind)
                                                            }
                                                            kindExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }

                                        OutlinedTextField(
                                            value = tag.label,
                                            onValueChange = { value ->
                                                tags = tags.toMutableList().also {
                                                    it[index] = it[index].copy(label = value)
                                                }
                                            },
                                            label = { Text(stringResource(Res.string.title_tag_label)) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )

                                        IconButton(onClick = {
                                            tags = tags.toMutableList().also { it.removeAt(index) }
                                        }) {
                                            Icon(
                                                SynaraIcons.Delete.get(),
                                                contentDescription = null
                                            )
                                        }
                                    }
                                }

                                TextButton(
                                    onClick = { tags = tags + TitleTag(TitleTagKind.VERSION, "") }
                                ) {
                                    Icon(SynaraIcons.Add.get(), contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(Res.string.add_title_tag))
                                }
                            }
                        }
                    }

                    if (musicBrainzId != null) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (mbRecording != null) {
                                        val releaseId = mbRecording?.releases?.firstOrNull()?.id
                                        if (releaseId != null) {
                                            AsyncImage(
                                                model = "https://coverartarchive.org/release/$releaseId/front-250",
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(64.dp)
                                                    .clip(MaterialTheme.shapes.small),
                                                contentScale = ContentScale.Crop
                                            )
                                            Spacer(modifier = Modifier.width(16.dp))
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(Res.string.musicbrainz_id),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (mbRecording != null) {
                                            Text(
                                                text = mbRecording?.title ?: "",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = mbRecording?.artistCredit?.joinToString("") {
                                                    (it.name ?: it.artist?.name ?: "") + (it.joinphrase ?: "")
                                                } ?: "",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            mbRecording?.releases?.firstOrNull()?.title?.let {
                                                Text(
                                                    text = it,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = musicBrainzId?.toString() ?: "",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                    IconButton(onClick = {
                                        musicBrainzId = null
                                        mbRecording = null
                                    }) {
                                        Icon(SynaraIcons.Close.get(), contentDescription = null)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            text = stringResource(Res.string.song_info_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                InfoItem(
                                    stringResource(Res.string.metadata_title),
                                    title.ifBlank { "-" }
                                )
                                InfoItem(
                                    stringResource(Res.string.metadata_tags),
                                    tags.displayTags.joinToString { it.label }.ifBlank { "-" }
                                )
                                InfoItem(
                                    stringResource(Res.string.metadata_artist),
                                    artists.joinArtists()
                                )
                                InfoItem(
                                    stringResource(Res.string.metadata_album),
                                    song?.album?.name ?: "-"
                                )
                                InfoItem(
                                    stringResource(Res.string.metadata_genres),
                                    song?.genres?.joinToString(", ") { it.name } ?: "-"
                                )
                                InfoItem(
                                    stringResource(Res.string.metadata_duration),
                                    song?.duration?.let(::formatDuration) ?: "-"
                                )
                                InfoItem(
                                    stringResource(Res.string.metadata_release_date),
                                    song?.releaseDate?.toString() ?: "-"
                                )
                                InfoItem(
                                    stringResource(Res.string.metadata_track_disc),
                                    "${song?.trackNumber ?: 0} / ${song?.discNumber ?: 0}"
                                )
                                val audio = song?.effectiveAudio
                                InfoItem(
                                    stringResource(Res.string.metadata_quality),
                                    "${(audio?.sampleRate ?: 0) / 1000}kHz / ${audio?.bitsPerSample ?: 0}bit / ${audio?.bitRate ?: 0}kbps"
                                )
                                InfoItem(
                                    stringResource(Res.string.metadata_file_size),
                                    formatFileSize(audio?.fileSize ?: 0L)
                                )
                                InfoItem(
                                    stringResource(Res.string.metadata_path),
                                    song?.path ?: "-"
                                )
                                InfoItem(
                                    stringResource(Res.string.metadata_url),
                                    song?.originalUrl ?: "-"
                                )
                                InfoItem(
                                    stringResource(Res.string.metadata_copyright),
                                    song?.copyright?.ifBlank { "-" } ?: "-"
                                )
                            }
                        }
                    }

                    if (lyrics.isNotEmpty()) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = lyrics.joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (song != null) {
            EditSongArtistsDialog(
                isOpen = showArtistsDialog,
                song = song!!,
                onDismissRequest = { showArtistsDialog = false },
                onSave = { artists = it }
            )

            LyricsEditDialog(
                isOpen = showLyricsDialog,
                onDismissRequest = { showLyricsDialog = false },
                initialLyrics = lyrics,
                onSave = { lyrics = it },
                artist = song?.artists?.joinArtists() ?: "",
                title = song?.title ?: ""
            )

            MusicBrainzSearchDialog(
                isOpen = showMusicBrainzDialog,
                onDismissRequest = { showMusicBrainzDialog = false },
                initialQuery = "${song?.artists?.joinArtists()} - ${song?.title}",
                onSelect = { recording ->
                    musicBrainzId = recording.id
                    mbRecording = recording
                }
            )
        }
    }

    @Composable
    private fun InfoItem(label: String, value: String) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
